package com.myapp.controlapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.database.*
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.concurrent.TimeUnit

/**
 * 基于Firebase实时数据库的信令客户端实现
 * 负责在WebRTC客户端之间传递信令信息
 */
class SignalingClient(private val context: Context) {
    
    private val TAG = "SignalingClient"
    
    // Firebase 数据库引用
    private lateinit var database: DatabaseReference
    private lateinit var roomRef: DatabaseReference
    
    // 监听器
    private var listener: SignalingClientListener? = null
    
    // 房间ID和角色
    private lateinit var roomId: String
    private var isInitiator: Boolean = false
    
    // 连接监控
    private var connectionMonitor: ValueEventListener? = null
    private var disconnectDetector: ValueEventListener? = null
    private var lastActiveTimestamp = 0L
    private var connectionTimeout = 30000L // 30秒超时
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private var isReconnecting = false
    
    // 处理重连逻辑的Handler
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    
    // ICE服务器配置
    private val defaultIceServers = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:stun2.l.google.com:19302",
        "stun:stun3.l.google.com:19302",
        "stun:stun4.l.google.com:19302"
    )
    
    // 初始化信令
    fun initialize(roomId: String, isInitiator: Boolean) {
        Log.d(TAG, "初始化信令: 房间ID=$roomId, 是否为发起者=$isInitiator")
        this.roomId = roomId
        this.isInitiator = isInitiator
        
        try {
            // 初始化Firebase
            database = FirebaseDatabase.getInstance().reference
            
            // 获取房间引用
            roomRef = database.child("rooms").child(roomId)
            
            // 创建或更新房间状态
            setupRoom()
            
            // 监听连接状态
            setupConnectionMonitoring()
            
            // 设置断开连接检测
            startConnectionMonitoring()
            
        } catch (e: Exception) {
            Log.e(TAG, "信令初始化失败", e)
            listener?.onConnectionFailed("信令初始化失败: ${e.message}")
        }
    }
    
    // 设置房间初始状态
    private fun setupRoom() {
        if (isInitiator) {
            // 发起者创建房间
            val roomData = HashMap<String, Any>()
            roomData["created_at"] = ServerValue.TIMESTAMP
            roomData["status"] = "available"
            roomData["initiator_connected"] = true
            
            roomRef.updateChildren(roomData).addOnSuccessListener {
                Log.d(TAG, "房间创建成功")
                // 设置房间过期（1小时后自动删除）
                setupRoomExpiration()
            }.addOnFailureListener { e ->
                Log.e(TAG, "房间创建失败", e)
                listener?.onConnectionFailed("房间创建失败: ${e.message}")
            }
        } else {
            // 接收者更新房间状态
            roomRef.child("receiver_connected").setValue(true).addOnSuccessListener {
                Log.d(TAG, "接收者连接状态更新成功")
            }.addOnFailureListener { e ->
                Log.e(TAG, "接收者连接状态更新失败", e)
            }
        }
    }
    
    // 设置房间过期逻辑
    private fun setupRoomExpiration() {
        // 设置房间1小时后过期
        val onDisconnectRef = database.child("rooms").child(roomId)
        onDisconnectRef.onDisconnect().removeValue().addOnSuccessListener {
            Log.d(TAG, "设置房间断开连接后自动删除成功")
        }.addOnFailureListener { e ->
            Log.e(TAG, "设置房间断开连接后自动删除失败", e)
        }
    }
    
    // 设置连接监控
    private fun setupConnectionMonitoring() {
        val connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected")
        connectionMonitor = connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                Log.d(TAG, "Firebase连接状态: ${if (connected) "已连接" else "已断开"}")
                
                if (connected) {
                    isReconnecting = false
                    reconnectAttempts = 0
                    setupRoomListeners()
                    listener?.onConnectionEstablished()
                } else {
                    Log.e(TAG, "Firebase连接丢失")
                    attemptReconnect()
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase连接监听被取消: ${error.message}")
                listener?.onConnectionFailed("Firebase连接错误: ${error.message}")
                attemptReconnect()
            }
        })
    }
    
    // 尝试重新连接
    private fun attemptReconnect() {
        if (isReconnecting) return
        
        isReconnecting = true
        reconnectRunnable = Runnable {
            if (reconnectAttempts < maxReconnectAttempts) {
                reconnectAttempts++
                val delay = (2.0.pow(reconnectAttempts.toDouble()) * 1000).toLong()
                Log.d(TAG, "尝试重新连接 (${reconnectAttempts}/${maxReconnectAttempts})，延迟 ${delay}ms")
                
                // 通知UI线程
                listener?.onReconnecting(reconnectAttempts, maxReconnectAttempts)
                
                try {
                    // 重新初始化Firebase连接
                    val newDatabase = FirebaseDatabase.getInstance()
                    database = newDatabase.reference
                    roomRef = database.child("rooms").child(roomId)
                    
                    // 检查房间是否仍然存在
                    roomRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            if (snapshot.exists()) {
                                Log.d(TAG, "房间仍然存在，重新设置监听器")
                                setupRoomListeners()
                                isReconnecting = false
                                listener?.onReconnected()
                            } else {
                                Log.e(TAG, "房间不存在，连接失败")
                                if (reconnectAttempts < maxReconnectAttempts) {
                                    reconnectHandler.postDelayed(reconnectRunnable!!, delay)
                                } else {
                                    isReconnecting = false
                                    listener?.onConnectionFailed("重连尝试次数已达上限")
                                }
                            }
                        }
                        
                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "检查房间存在性时取消", error.toException())
                            if (reconnectAttempts < maxReconnectAttempts) {
                                reconnectHandler.postDelayed(reconnectRunnable!!, delay)
                            } else {
                                isReconnecting = false
                                listener?.onConnectionFailed("重连尝试次数已达上限")
                            }
                        }
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "重连过程中发生异常", e)
                    if (reconnectAttempts < maxReconnectAttempts) {
                        reconnectHandler.postDelayed(reconnectRunnable!!, delay)
                    } else {
                        isReconnecting = false
                        listener?.onConnectionFailed("重连尝试次数已达上限")
                    }
                }
            } else {
                isReconnecting = false
                listener?.onConnectionFailed("重连尝试次数已达上限")
            }
        }
        
        // 开始第一次重连尝试
        reconnectHandler.post(reconnectRunnable!!)
    }
    
    // 计算2的幂
    private fun Double.pow(exponent: Double): Double = Math.pow(this, exponent)
    
    // 监控连接活动
    private fun startConnectionMonitoring() {
        // 更新当前时间戳以标记活动
        updateActivityTimestamp()
        
        // 每10秒检查一次连接状态
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                checkConnectionStatus()
                handler.postDelayed(this, 10000) // 10秒检查一次
            }
        }, 10000)
    }
    
    // 更新活动时间戳
    private fun updateActivityTimestamp() {
        lastActiveTimestamp = System.currentTimeMillis()
        
        // 同时更新房间的活动时间戳
        roomRef.child("last_activity").setValue(ServerValue.TIMESTAMP).addOnFailureListener { e ->
            Log.w(TAG, "更新房间活动时间戳失败", e)
        }
    }
    
    // 检查连接状态
    private fun checkConnectionStatus() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActiveTimestamp > connectionTimeout) {
            Log.w(TAG, "检测到可能的连接断开 - 无活动超过 ${connectionTimeout/1000} 秒")
            
            // 检查房间是否仍然存在
            roomRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        val status = snapshot.child("status").getValue(String::class.java)
                        if (status == "disconnected") {
                            Log.d(TAG, "房间已标记为断开连接")
                            listener?.onRemoteHangup()
                        } else {
                            // 房间存在但可能处于不活动状态
                            pingRoom()
                        }
                    } else {
                        Log.e(TAG, "房间不存在")
                        listener?.onConnectionFailed("房间不存在，可能已被清理")
                    }
                }
                
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "检查房间状态失败", error.toException())
                }
            })
        }
    }
    
    // 发送ping来检查连接
    private fun pingRoom() {
        roomRef.child("ping").setValue(ServerValue.TIMESTAMP).addOnSuccessListener {
            Log.d(TAG, "发送ping成功")
            updateActivityTimestamp()
        }.addOnFailureListener { e ->
            Log.e(TAG, "发送ping失败", e)
            // 可能需要重连
            if (!isReconnecting) {
                attemptReconnect()
            }
        }
    }

    // 设置房间监听器
    private fun setupRoomListeners() {
        Log.d(TAG, "设置房间监听器")
        
        try {
            // 监听offer
            roomRef.child("offer").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isInitiator && snapshot.exists()) {
                        updateActivityTimestamp()
                        val sdpString = snapshot.getValue(String::class.java)
                        sdpString?.let {
                            Log.d(TAG, "收到offer SDP")
                            try {
                                val sessionDescription = SessionDescription(
                                    SessionDescription.Type.OFFER,
                                    it
                                )
                                listener?.onOfferReceived(sessionDescription)
                            } catch (e: Exception) {
                                Log.e(TAG, "解析offer SDP失败", e)
                                listener?.onConnectionFailed("解析offer失败: ${e.message}")
                            }
                        }
                    }
                }
                
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "offer监听被取消: ${error.message}")
                }
            })
            
            // 监听answer
            roomRef.child("answer").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (isInitiator && snapshot.exists()) {
                        updateActivityTimestamp()
                        val sdpString = snapshot.getValue(String::class.java)
                        sdpString?.let {
                            Log.d(TAG, "收到answer SDP")
                            try {
                                val sessionDescription = SessionDescription(
                                    SessionDescription.Type.ANSWER,
                                    it
                                )
                                listener?.onAnswerReceived(sessionDescription)
                            } catch (e: Exception) {
                                Log.e(TAG, "解析answer SDP失败", e)
                                listener?.onConnectionFailed("解析answer失败: ${e.message}")
                            }
                        }
                    }
                }
                
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "answer监听被取消: ${error.message}")
                }
            })
            
            // 监听ICE候选者
            roomRef.child("candidates").addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    updateActivityTimestamp()
                    val candidateMap = snapshot.getValue(Map::class.java)
                    candidateMap?.let {
                        try {
                            Log.d(TAG, "收到ICE候选者")
                            val sdpMid = it["sdpMid"] as String
                            val sdpMLineIndex = (it["sdpMLineIndex"] as Long).toInt()
                            val sdp = it["sdp"] as String
                            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                            listener?.onIceCandidateReceived(iceCandidate)
                        } catch (e: Exception) {
                            Log.e(TAG, "解析ICE候选者失败", e)
                        }
                    }
                }
                
                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "ICE候选者监听被取消: ${error.message}")
                }
            })
            
            // 监听房间状态，检测断开连接
            disconnectDetector = roomRef.child("status").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    updateActivityTimestamp()
                    val status = snapshot.getValue(String::class.java)
                    Log.d(TAG, "房间状态变更: $status")
                    
                    if (status == "disconnected") {
                        Log.d(TAG, "检测到远程端断开连接")
                        listener?.onRemoteHangup()
                    }
                }
                
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "状态监听被取消: ${error.message}")
                }
            })
            
            // 监听远端连接状态
            val remoteConnectionKey = if (isInitiator) "receiver_connected" else "initiator_connected"
            roomRef.child(remoteConnectionKey).addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val isRemoteConnected = snapshot.getValue(Boolean::class.java) ?: false
                    Log.d(TAG, "远端连接状态: ${if (isRemoteConnected) "已连接" else "未连接"}")
                    
                    if (isRemoteConnected) {
                        listener?.onRemoteConnected()
                    }
                }
                
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "远端连接状态监听被取消: ${error.message}")
                }
            })
            
        } catch (e: Exception) {
            Log.e(TAG, "设置房间监听器失败", e)
            listener?.onConnectionFailed("设置房间监听器失败: ${e.message}")
        }
    }
    
    // 发送offer
    fun sendOffer(description: SessionDescription) {
        Log.d(TAG, "发送offer SDP")
        try {
            updateActivityTimestamp()
            val values = HashMap<String, Any>()
            values["offer"] = description.description
            values["offer_timestamp"] = ServerValue.TIMESTAMP
            roomRef.updateChildren(values).addOnSuccessListener {
                Log.d(TAG, "offer发送成功")
            }.addOnFailureListener { e ->
                Log.e(TAG, "offer发送失败", e)
                listener?.onConnectionFailed("发送offer失败: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送offer异常", e)
            listener?.onConnectionFailed("发送offer异常: ${e.message}")
        }
    }
    
    // 发送answer
    fun sendAnswer(description: SessionDescription) {
        Log.d(TAG, "发送answer SDP")
        try {
            updateActivityTimestamp()
            val values = HashMap<String, Any>()
            values["answer"] = description.description
            values["answer_timestamp"] = ServerValue.TIMESTAMP
            roomRef.updateChildren(values).addOnSuccessListener {
                Log.d(TAG, "answer发送成功")
            }.addOnFailureListener { e ->
                Log.e(TAG, "answer发送失败", e)
                listener?.onConnectionFailed("发送answer失败: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送answer异常", e)
            listener?.onConnectionFailed("发送answer异常: ${e.message}")
        }
    }
    
    // 发送ICE候选者
    fun sendIceCandidate(iceCandidate: IceCandidate) {
        Log.d(TAG, "发送ICE候选者")
        try {
            updateActivityTimestamp()
            val candidateMap = HashMap<String, Any>()
            candidateMap["sdpMid"] = iceCandidate.sdpMid
            candidateMap["sdpMLineIndex"] = iceCandidate.sdpMLineIndex
            candidateMap["sdp"] = iceCandidate.sdp
            candidateMap["timestamp"] = ServerValue.TIMESTAMP
            
            val childRef = roomRef.child("candidates").push()
            childRef.setValue(candidateMap).addOnSuccessListener {
                Log.d(TAG, "ICE候选者发送成功")
            }.addOnFailureListener { e ->
                Log.e(TAG, "ICE候选者发送失败", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送ICE候选者异常", e)
        }
    }
    
    // 获取ICE服务器
    fun getIceServers(): List<String> {
        return defaultIceServers
    }
    
    // 检查是否已连接
    fun isConnected(): Boolean {
        // 检查Firebase连接状态
        return ::roomRef.isInitialized && roomId.isNotEmpty()
    }
    
    // 设置状态（用于挂断）
    fun setRoomStatus(status: String) {
        try {
            updateActivityTimestamp()
            roomRef.child("status").setValue(status).addOnSuccessListener {
                Log.d(TAG, "房间状态更新为: $status")
            }.addOnFailureListener { e ->
                Log.e(TAG, "房间状态更新失败", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置房间状态异常", e)
        }
    }
    
    // 断开连接
    fun disconnect() {
        Log.d(TAG, "断开信令连接")
        try {
            // 取消所有挂起的重连尝试
            reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
            
            // 标记房间状态为断开
            setRoomStatus("disconnected")
            
            // 移除连接监视器
            if (connectionMonitor != null) {
                val connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected")
                connectedRef.removeEventListener(connectionMonitor!!)
                connectionMonitor = null
            }
            
            // 移除断开检测器
            if (disconnectDetector != null) {
                roomRef.child("status").removeEventListener(disconnectDetector!!)
                disconnectDetector = null
            }
            
            // 如果是发起者并且没有接收者连接，可以选择删除房间
            if (isInitiator) {
                roomRef.child("receiver_connected").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val receiverConnected = snapshot.getValue(Boolean::class.java) ?: false
                        if (!receiverConnected) {
                            // 没有接收者连接，可以安全删除房间
                            roomRef.removeValue().addOnSuccessListener {
                                Log.d(TAG, "房间已删除")
                            }
                        }
                    }
                    
                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "检查接收者连接状态失败", error.toException())
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "断开连接异常", e)
        }
    }
    
    // 设置监听器
    fun setListener(listener: SignalingClientListener) {
        this.listener = listener
    }
    
    // 信令客户端监听器接口
    interface SignalingClientListener {
        fun onConnectionEstablished()
        fun onOfferReceived(description: SessionDescription)
        fun onAnswerReceived(description: SessionDescription)
        fun onIceCandidateReceived(iceCandidate: IceCandidate)
        fun onRemoteHangup()
        fun onRemoteConnected()
        fun onReconnecting(attempt: Int, maxAttempts: Int)
        fun onReconnected()
        fun onConnectionFailed(errorMessage: String)
    }
} 