package com.myapp.controlapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.webrtc.*
import java.util.*

class MonitoringActivity : AppCompatActivity() {
    companion object {
        private const val CAPTURE_PERMISSION_REQUEST_CODE = 1234
        private const val TAG = "MonitoringActivity"
    }

    private lateinit var remoteVideoView: SurfaceViewRenderer
    private lateinit var statusTextView: TextView
    private lateinit var disconnectButton: Button
    
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null
    private var peerConnectionHelper: PeerConnectionHelper? = null
    private var signalingClient: SignalingClient = SignalingClient(this)
    
    private var isInitiator = false
    private var roomId = ""
    
    // 前台服务相关
    private var screenCaptureService: ScreenCaptureService? = null
    private var isServiceBound = false
    private var pendingMediaProjectionData: Pair<Int, Intent>? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "服务已连接")
            val binder = service as ScreenCaptureService.ScreenCaptureBinder
            screenCaptureService = binder.getService()
            isServiceBound = true
            
            // 如果有待处理的媒体投影数据，立即处理
            pendingMediaProjectionData?.let { (resultCode, data) ->
                startScreenCaptureWithPermission(resultCode, data)
                pendingMediaProjectionData = null
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "服务已断开")
            screenCaptureService = null
            isServiceBound = false
        }
    }
    
    private val debugMode = true // 设置为true启用调试日志
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitoring)
        
        roomId = intent.getStringExtra("roomId") ?: UUID.randomUUID().toString()
        isInitiator = intent.getBooleanExtra("isInitiator", false)
        
        // 调试模式下打印更多日志
        if (debugMode) {
            Log.d(TAG, "调试模式开启")
            Log.d(TAG, "房间ID: $roomId, 角色: ${if (isInitiator) "发起者" else "接收者"}")
        }
        
        initViews()
        initWebRTC()
        
        // 初始化信令客户端
        initializeSignalingClient()
        
        disconnectButton.setOnClickListener {
            disconnect()
            finish()
        }
        
        if (isInitiator) {
            statusTextView.text = "正在等待连接... (房间ID: $roomId)"
        } else {
            statusTextView.text = "正在连接到房间: $roomId"
        }
    }
    
    private fun initViews() {
        remoteVideoView = findViewById(R.id.remote_video_view)
        statusTextView = findViewById(R.id.status_text)
        disconnectButton = findViewById(R.id.disconnect_button)
    }
    
    private fun initWebRTC() {
        try {
            // 创建EGL上下文
            eglBase = EglBase.create()
            
            // 配置远程视频视图
            remoteVideoView.init(eglBase?.eglBaseContext, null)
            remoteVideoView.setEnableHardwareScaler(true)
            remoteVideoView.setMirror(false)
            
            // 创建PeerConnection观察者
            val peerConnectionObserver = object : PeerConnection.Observer {
                override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
                    Log.d(TAG, "onSignalingChange: $signalingState")
                }
                
                override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "onIceConnectionChange: $iceConnectionState")
                    if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                        runOnUiThread {
                            statusTextView.text = "连接已建立"
                        }
                    } else if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED || 
                               iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
                        runOnUiThread {
                            statusTextView.text = "连接断开"
                        }
                    }
                }
                
                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(TAG, "onIceConnectionReceivingChange: $receiving")
                }
                
                override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {
                    Log.d(TAG, "onIceGatheringChange: $iceGatheringState")
                }
                
                override fun onIceCandidate(iceCandidate: IceCandidate) {
                    Log.d(TAG, "onIceCandidate: $iceCandidate")
                    // 将ICE候选发送到远程端
                    signalingClient.sendIceCandidate(iceCandidate)
                }
                
                override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {
                    Log.d(TAG, "onIceCandidatesRemoved: ${iceCandidates.contentToString()}")
                }
                
                override fun onAddStream(mediaStream: MediaStream) {
                    Log.d(TAG, "onAddStream: ${mediaStream.videoTracks.size}")
                    // 接收到远程媒体流时，将其显示在界面上
                    val remoteVideoTrack = mediaStream.videoTracks.firstOrNull()
                    remoteVideoTrack?.setEnabled(true)
                    
                    runOnUiThread {
                        try {
                            remoteVideoTrack?.addSink(remoteVideoView)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                
                override fun onRemoveStream(mediaStream: MediaStream) {
                    Log.d(TAG, "onRemoveStream")
                }
                
                override fun onDataChannel(dataChannel: DataChannel) {
                    Log.d(TAG, "onDataChannel")
                }
                
                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "onRenegotiationNeeded")
                }
                
                override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
                    Log.d(TAG, "onAddTrack")
                }
            }
            
            // 创建PeerConnection辅助类
            peerConnectionHelper = PeerConnectionHelper(
                context = this,
                eglBaseContext = eglBase?.eglBaseContext!!,
                peerConnectionObserver = peerConnectionObserver
            )
            
            // 创建PeerConnection
            peerConnection = peerConnectionHelper?.createPeerConnection()
            
            if (peerConnection == null) {
                Log.e(TAG, "PeerConnection创建失败")
                showErrorAndFinish("无法创建WebRTC连接")
            } else {
                Log.d(TAG, "PeerConnection创建成功")
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC初始化失败: ${e.message}")
            e.printStackTrace()
            showErrorAndFinish("WebRTC初始化失败: ${e.message}")
        }
    }
    
    // 初始化信令客户端
    private fun initializeSignalingClient() {
        statusTextView.text = "正在连接到信令服务器..."
        
        if (debugMode) {
            Log.d(TAG, "初始化信令客户端: 房间ID=$roomId, 角色=${if (isInitiator) "发起者" else "接收者"}")
        }
        
        // 使用后台线程初始化，避免阻塞UI线程
        Thread {
            try {
                // 创建SignalingClient实例
                signalingClient = SignalingClient(this)
                
                // 设置监听器
                signalingClient.setListener(object : SignalingClient.SignalingClientListener {
                    override fun onConnectionEstablished() {
                        runOnUiThread {
                            showMessage("已连接到信令服务器", true)
                            
                            if (isInitiator) {
                                // Do not pass parameters here
                                startScreenCaptureWithPermission()
                            } else {
                                statusTextView.text = "等待屏幕共享连接..."
                            }
                        }
                    }
                    
                    override fun onOfferReceived(description: SessionDescription) {
                        peerConnection?.setRemoteDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                Log.d(TAG, "远程描述设置成功")
                                
                                // 创建应答
                                peerConnection?.createAnswer(object : SdpObserver {
                                    override fun onCreateSuccess(sdp: SessionDescription?) {
                                        sdp?.let {
                                            Log.d(TAG, "Answer创建成功")
                                            peerConnection?.setLocalDescription(object : SdpObserver {
                                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                                override fun onSetSuccess() {
                                                    Log.d(TAG, "本地描述设置成功")
                                                    runOnUiThread {
                                                        showMessage("正在建立连接...", true)
                                                    }
                                                    signalingClient.sendAnswer(it)
                                                }
                                                
                                                override fun onCreateFailure(p0: String?) {
                                                    showMessage("创建本地描述失败: $p0", false)
                                                }
                                                
                                                override fun onSetFailure(p0: String?) {
                                                    showMessage("设置本地描述失败: $p0", false)
                                                }
                                            }, it)
                                        }
                                    }
                                    
                                    override fun onCreateFailure(p0: String?) {
                                        showMessage("创建Answer失败: $p0", false)
                                    }
                                    
                                    override fun onSetSuccess() {}
                                    override fun onSetFailure(p0: String?) {}
                                }, MediaConstraints())
                            }
                            
                            override fun onCreateFailure(p0: String?) {}
                            
                            override fun onSetFailure(p0: String?) {
                                showMessage("设置远程描述失败: $p0", false)
                            }
                        }, description)
                    }
                    
                    override fun onAnswerReceived(description: SessionDescription) {
                        peerConnection?.setRemoteDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                Log.d(TAG, "远程描述(Answer)设置成功")
                                runOnUiThread {
                                    showMessage("连接已建立", true)
                                }
                            }
                            
                            override fun onCreateFailure(p0: String?) {}
                            
                            override fun onSetFailure(p0: String?) {
                                showMessage("设置远程描述(Answer)失败: $p0", false)
                            }
                        }, description)
                    }
                    
                    override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
                        Log.d(TAG, "收到ICE候选者")
                        peerConnection?.addIceCandidate(iceCandidate)
                    }
                    
                    override fun onRemoteHangup() {
                        Log.d(TAG, "远程端已断开连接")
                        runOnUiThread {
                            showMessage("远程连接已断开", false)
                            cleanupResources()
                            Handler(Looper.getMainLooper()).postDelayed({
                                finish()
                            }, 3000)
                        }
                    }
                    
                    override fun onRemoteConnected() {
                        Log.d(TAG, "远程端已连接")
                        runOnUiThread {
                            showMessage("远程端已连接", true)
                        }
                    }
                    
                    override fun onReconnecting(attempt: Int, maxAttempts: Int) {
                        Log.d(TAG, "正在重连 ($attempt/$maxAttempts)")
                        runOnUiThread {
                            showMessage("正在重连 ($attempt/$maxAttempts)", false)
                        }
                    }
                    
                    override fun onReconnected() {
                        Log.d(TAG, "重连成功")
                        runOnUiThread {
                            showMessage("重连成功", true)
                        }
                    }
                    
                    override fun onConnectionFailed(errorMessage: String) {
                        showMessage("连接失败: $errorMessage", false)
                    }
                })
                
                // 初始化连接
                signalingClient.initialize(roomId, isInitiator)
                
            } catch (e: Exception) {
                Log.e(TAG, "初始化信令客户端失败", e)
                runOnUiThread {
                    showMessage("无法连接到信令服务器: ${e.message}", false)
                }
            }
        }.start()
    }
    
    /**
     * 请求屏幕捕获权限
     */
    private fun requestScreenCapturePermission() {
        try {
            Log.d(TAG, "请求屏幕捕获权限")
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            
            // 确保UI显示正在请求权限
            runOnUiThread {
                statusTextView.text = "正在请求屏幕共享权限..."
            }
            
            // 检查系统是否支持屏幕捕获
            if (captureIntent.resolveActivity(packageManager) != null) {
                try {
                    Log.d(TAG, "发起屏幕捕获权限请求")
                    startActivityForResult(
                        captureIntent,
                        CAPTURE_PERMISSION_REQUEST_CODE
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "启动屏幕捕获权限请求失败", e)
                    showErrorAndFinish("启动屏幕捕获权限请求失败: ${e.message}")
                }
            } else {
                Log.e(TAG, "系统不支持屏幕捕获")
                showErrorAndFinish("您的设备不支持屏幕捕获功能")
            }
        } catch (e: Exception) {
            Log.e(TAG, "请求屏幕捕获权限失败: ${e.message}", e)
            e.printStackTrace()
            showErrorAndFinish("无法获取屏幕捕获权限: ${e.message}")
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode, data=$data")
        
        if (requestCode == CAPTURE_PERMISSION_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(TAG, "屏幕捕获权限已获取，开始捕获")
                
                // 更新UI
                runOnUiThread {
                    statusTextView.text = "正在准备屏幕共享..."
                }
                
                // 确保在主线程外启动服务，避免ANR
                Thread {
                    try {
                        // 启动前台服务
                        startAndBindCaptureService(resultCode, data)
                    } catch (e: Exception) {
                        Log.e(TAG, "后台线程启动服务失败", e)
                        runOnUiThread {
                            showErrorAndFinish("启动屏幕捕获服务失败: ${e.message}")
                        }
                    }
                }.start()
            } else {
                Log.e(TAG, "屏幕捕获权限被拒绝: resultCode=$resultCode")
                // 更详细的日志，帮助诊断问题
                if (resultCode == 0) {
                    Log.e(TAG, "用户拒绝了权限请求")
                } else if (data == null) {
                    Log.e(TAG, "权限请求返回的Intent为null")
                }
                
                // 尝试重新请求权限
                if (isInitiator) {
                    Toast.makeText(this, "屏幕共享需要捕获权限，请允许权限请求", Toast.LENGTH_LONG).show()
                    statusTextView.text = "正在重新请求权限..."
                    
                    // 2秒后重新请求权限
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            requestScreenCapturePermission()
                        } catch (e: Exception) {
                            Log.e(TAG, "重新请求权限失败", e)
                            Toast.makeText(this, "屏幕捕获权限被拒绝，无法继续", Toast.LENGTH_SHORT).show()
                            statusTextView.text = "屏幕共享已取消"
                        }
                    }, 2000)
                } else {
                    Toast.makeText(this, "屏幕捕获权限被拒绝，无法继续", Toast.LENGTH_SHORT).show()
                    statusTextView.text = "屏幕共享已取消"
                }
            }
        }
    }
    
    /**
     * 启动并绑定屏幕捕获前台服务
     */
    private fun startAndBindCaptureService(resultCode: Int, data: Intent) {
        try {
            Log.d(TAG, "启动屏幕捕获服务")
            
            // 先启动服务
            val serviceIntent = Intent(this, ScreenCaptureService::class.java)
            
            // 华为设备兼容性处理 - 确保intent不会被系统过滤
            serviceIntent.setPackage(packageName)
            
            // 添加额外数据，标记这是屏幕捕获
            serviceIntent.putExtra("isScreenCapture", true)
            
            try {
                // 尝试使用新API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.d(TAG, "使用startForegroundService启动服务")
                    startForegroundService(serviceIntent)
                } else {
                    Log.d(TAG, "使用startService启动服务")
                    startService(serviceIntent)
                }
            } catch (e: Exception) {
                // 如果启动服务失败，记录错误
                Log.e(TAG, "启动服务失败: ${e.message}", e)
                // 尝试使用旧API
                startService(serviceIntent)
            }
            
            // 添加短暂延迟，确保服务有时间启动
            try {
                Thread.sleep(300)
            } catch (e: InterruptedException) {
                Log.e(TAG, "线程睡眠中断", e)
            }
            
            // 绑定服务
            try {
                val bound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
                Log.d(TAG, "服务绑定结果: $bound")
                if (!bound) {
                    Log.e(TAG, "服务绑定失败")
                    showError("服务绑定失败，请重试")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "绑定服务异常: ${e.message}", e)
                showError("绑定服务异常: ${e.message}")
                return
            }
            
            // 保存媒体投影数据以在服务连接后使用
            pendingMediaProjectionData = Pair(resultCode, data)
            
        } catch (e: Exception) {
            Log.e(TAG, "启动服务失败: ${e.message}", e)
            showErrorAndFinish("启动前台服务失败: ${e.message}")
        }
    }
    
    /**
     * 请求屏幕捕获权限
     */
    private fun startScreenCaptureWithPermission() {
        try {
            requestScreenCapturePermission()
        } catch (e: Exception) {
            Log.e(TAG, "开始屏幕捕获失败", e)
            showErrorAndFinish("无法启动屏幕捕获: ${e.message}")
        }
    }
    
    /**
     * 在前台服务启动后进行屏幕捕获（带权限结果）
     */
    private fun startScreenCaptureWithPermission(resultCode: Int, data: Intent) {
        try {
            // 检查是否为华为设备
            val isHuawei = MyApplication.isHuaweiDevice()
            if (isHuawei) {
                Log.d(TAG, "华为设备，使用特殊处理流程")
            }
            
            // 华为设备尝试在后台线程处理
            if (isHuawei) {
                Thread {
                    try {
                        Log.d(TAG, "在后台线程中启动屏幕捕获")
                        startScreenCapture(resultCode, data)
                    } catch (e: Exception) {
                        Log.e(TAG, "后台线程屏幕捕获失败", e)
                        runOnUiThread {
                            showErrorAndFinish("后台线程屏幕捕获失败: ${e.message}")
                        }
                    }
                }.start()
            } else {
                // 非华为设备正常处理
                startScreenCapture(resultCode, data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "在前台服务中启动屏幕捕获失败", e)
            // 记录详细信息
            MyApplication.getInstance().logCrash("ScreenCapture", "启动屏幕捕获失败", e)
            showErrorAndFinish("在前台服务中启动屏幕捕获失败: ${e.message}")
        }
    }
    
    private fun startScreenCapture(resultCode: Int, data: Intent) {
        try {
            Log.d(TAG, "开始屏幕捕获: resultCode=$resultCode")
            
            // 屏幕捕获前准备UI
            runOnUiThread {
                statusTextView.text = "正在准备屏幕共享..."
            }
            
            // 添加检查确保信令已经准备好
            if (!signalingClient.isConnected()) {
                Log.e(TAG, "信令未连接，尝试重新连接")
                runOnUiThread {
                    statusTextView.text = "重新连接信令服务..."
                }
                
                // 先尝试断开现有连接
                try {
                    signalingClient.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "断开现有信令连接失败", e)
                }
                
                // 重新初始化信令
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        signalingClient.initialize(roomId, isInitiator)
                        // 等待信令连接成功后再继续
                        Handler(Looper.getMainLooper()).postDelayed({
                            continueScreenCapture(resultCode, data)
                        }, 1000)
                    } catch (e: Exception) {
                        Log.e(TAG, "重新连接信令失败", e)
                        MyApplication.getInstance().logCrash("Signaling", "重新连接信令失败", e)
                        showErrorAndFinish("连接信令服务器失败: ${e.message}")
                    }
                }, 500)
                return
            }
            
            // 如果信令已连接，继续捕获流程
            continueScreenCapture(resultCode, data)
            
        } catch (e: Exception) {
            Log.e(TAG, "启动屏幕捕获失败: ${e.message}", e)
            val stackTrace = e.stackTraceToString()
            Log.e(TAG, "堆栈跟踪: $stackTrace")
            MyApplication.getInstance().logCrash("ScreenCapture", "启动屏幕捕获总体失败", e)
            showErrorAndFinish("屏幕捕获失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    
    /**
     * 继续屏幕捕获流程 - 分离以提高代码可读性和可维护性
     */
    private fun continueScreenCapture(resultCode: Int, data: Intent) {
        try {
            // 检查PeerConnection是否可用
            if (peerConnection == null) {
                Log.e(TAG, "PeerConnection不可用，尝试重新初始化WebRTC")
                initWebRTC()
                if (peerConnection == null) {
                    showErrorAndFinish("无法创建WebRTC连接")
                    return
                }
            }
            
            // 1. 创建屏幕捕获器
            var screenCapturer: VideoCapturer? = null
            try {
                screenCapturer = createScreenCapturer(resultCode, data)
            } catch (e: Exception) {
                Log.e(TAG, "创建屏幕捕获器失败", e)
                showErrorAndFinish("无法创建屏幕捕获器: ${e.message}")
                return
            }
            
            // 2. 使用较低的分辨率和帧率来确保兼容性
            val width = 640
            val height = 480
            val fps = 15
            
            // 3. 初始化屏幕捕获
            try {
                peerConnectionHelper?.initScreenCapture(screenCapturer)
                Log.d(TAG, "屏幕捕获器初始化成功")
            } catch (e: Exception) {
                Log.e(TAG, "屏幕捕获器初始化失败", e)
                MyApplication.getInstance().logCrash("ScreenCapture", "初始化捕获器失败", e)
                showErrorAndFinish("初始化屏幕捕获器失败: ${e.message}")
                return
            }
            
            // 将后续步骤延迟到新的处理程序中以避免阻塞
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // 4. 开始捕获
                    startCaptureAndStream(width, height, fps)
                } catch (e: Exception) {
                    Log.e(TAG, "延迟启动屏幕捕获失败", e)
                    MyApplication.getInstance().logCrash("ScreenCapture", "延迟启动屏幕捕获失败", e)
                    showErrorAndFinish("启动屏幕捕获失败: ${e.message}")
                }
            }, 500) // 延迟500毫秒启动捕获
        } catch (e: Exception) {
            Log.e(TAG, "继续屏幕捕获流程失败", e)
            MyApplication.getInstance().logCrash("ScreenCapture", "继续屏幕捕获流程失败", e)
            showErrorAndFinish("屏幕捕获设置失败: ${e.message}")
        }
    }
    
    /**
     * 启动捕获和流式传输 - 为了更好的错误处理进一步分离
     */
    private fun startCaptureAndStream(width: Int, height: Int, fps: Int) {
        try {
            // 开始捕获
            try {
                peerConnectionHelper?.startScreenCapture(width, height, fps)
                Log.d(TAG, "开始屏幕捕获成功: ${width}x${height} @${fps}fps")
            } catch (e: Exception) {
                Log.e(TAG, "开始屏幕捕获失败", e)
                MyApplication.getInstance().logCrash("ScreenCapture", "启动捕获失败", e)
                showErrorAndFinish("启动屏幕捕获失败: ${e.message}")
                return
            }
            
            // 创建媒体流
            val localStream = try {
                peerConnectionHelper?.createLocalMediaStream()
            } catch (e: Exception) {
                Log.e(TAG, "创建媒体流失败", e)
                MyApplication.getInstance().logCrash("ScreenCapture", "创建媒体流失败", e)
                showErrorAndFinish("创建媒体流失败: ${e.message}")
                return
            }
            
            localStream?.let { stream ->
                try {
                    // 添加流到PeerConnection
                    peerConnection?.addStream(stream)
                    
                    // 在本地预览
                    runOnUiThread {
                        try {
                            val videoTrack = stream.videoTracks.firstOrNull()
                            if (videoTrack != null) {
                                videoTrack.addSink(remoteVideoView)
                                Log.d(TAG, "视频轨道添加到预览视图")
                                // 更新UI状态
                                statusTextView.text = "已开始屏幕共享，等待对方连接..."
                            } else {
                                Log.e(TAG, "视频轨道为空")
                                throw RuntimeException("无法获取视频轨道")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "设置视频预览失败", e)
                            throw e
                        }
                    }
                    
                    // 创建提议
                    createOffer()
                } catch (e: Exception) {
                    Log.e(TAG, "设置媒体流失败", e)
                    MyApplication.getInstance().logCrash("ScreenCapture", "设置媒体流失败", e)
                    showErrorAndFinish("设置媒体流失败: ${e.message}")
                }
            } ?: run {
                Log.e(TAG, "无法创建本地媒体流")
                showErrorAndFinish("无法创建媒体流")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动捕获和流式传输失败", e)
            MyApplication.getInstance().logCrash("ScreenCapture", "启动捕获和流式传输失败", e)
            showErrorAndFinish("屏幕捕获失败: ${e.message}")
        }
    }
    
    private fun createScreenCapturer(resultCode: Int, data: Intent): VideoCapturer {
        try {
            Log.d(TAG, "创建屏幕捕获器: resultCode=$resultCode")
            
            // 检查Intent数据
            if (data.extras == null) {
                Log.e(TAG, "屏幕捕获Intent数据无效")
                throw RuntimeException("屏幕捕获Intent数据无效")
            }
            
            // 使用防御性方式创建捕获器
            val isHuawei = MyApplication.isHuaweiDevice()
            val capturer = if (isHuawei) {
                Log.d(TAG, "华为设备使用特殊初始化方式")
                // 华为设备可能需要额外处理
                try {
                    val capturer = ScreenCapturerAndroid(
                        data,
                        createMediaProjectionCallback()
                    )
                    Log.d(TAG, "华为设备屏幕捕获器创建成功")
                    capturer
                } catch (e: Exception) {
                    Log.e(TAG, "华为设备创建捕获器失败，尝试标准方式", e)
                    // 如果特殊方式失败，回退到标准方式
                    ScreenCapturerAndroid(
                        data,
                        createMediaProjectionCallback()
                    )
                }
            } else {
                // 标准方式
                ScreenCapturerAndroid(
                    data,
                    createMediaProjectionCallback()
                )
            }
            
            // 确认捕获器是否有效
            if (capturer.isScreencast) {
                Log.d(TAG, "屏幕捕获器创建成功")
                return capturer
            } else {
                Log.e(TAG, "创建的捕获器不是屏幕捕获类型")
                throw RuntimeException("不支持的捕获器类型")
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建屏幕捕获器失败: ${e.javaClass.simpleName} - ${e.message}", e)
            e.printStackTrace()
            
            // 尝试记录更多关于设备的信息，可能对调试有帮助
            val deviceInfo = MyApplication.getDeviceInfo()
            Log.e(TAG, "设备信息: \n$deviceInfo")
            
            // 记录详细崩溃信息
            MyApplication.getInstance().logCrash("ScreenCapturer", "创建屏幕捕获器失败", e)
            
            throw RuntimeException("创建屏幕捕获器失败: ${e.message}", e)
        }
    }
    
    private fun createMediaProjectionCallback(): MediaProjection.Callback {
        return object : MediaProjection.Callback() {
            override fun onStop() {
                runOnUiThread {
                    Log.d(TAG, "屏幕捕获已停止")
                    statusTextView.text = "屏幕共享已停止"
                }
            }
        }
    }
    
    private fun createOffer() {
        try {
            if (peerConnection == null) {
                showMessage("无法创建连接请求：PeerConnection尚未初始化", false)
                return
            }
            
            Log.d(TAG, "创建Offer")
            
            // 创建媒体约束
            val constraints = MediaConstraints()
            constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            
            // 创建Offer
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        Log.d(TAG, "Offer创建成功")
                        
                        // 设置本地描述
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            
                            override fun onSetSuccess() {
                                Log.d(TAG, "本地描述设置成功")
                                
                                // 通过信令服务发送Offer
                                signalingClient.sendOffer(it)
                                runOnUiThread {
                                    showMessage("等待对方接受连接...", true)
                                }
                            }
                            
                            override fun onCreateFailure(p0: String?) {}
                            
                            override fun onSetFailure(p0: String?) {
                                Log.e(TAG, "设置本地描述失败: $p0")
                                showMessage("设置本地描述失败: $p0", false)
                            }
                        }, it)
                    }
                }
                
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "创建Offer失败: $error")
                    showMessage("创建Offer失败: $error", false)
                }
                
                override fun onSetSuccess() {}
                
                override fun onSetFailure(p0: String?) {}
            }, constraints)
            
        } catch (e: Exception) {
            Log.e(TAG, "创建Offer时发生异常", e)
            showMessage("创建连接请求失败: ${e.message}", false)
        }
    }
    
    // 断开连接并清理资源
    private fun disconnect() {
        try {
            signalingClient.disconnect()
            
            peerConnectionHelper?.stopCapture()
            peerConnectionHelper?.dispose()
            
            remoteVideoView.release()
            
            peerConnection?.close()
            peerConnection = null
            
            eglBase?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // 清理资源
    private fun cleanupResources() {
        try {
            // Define missing variables
            var localVideoTrack: VideoTrack? = null
            var screenCapturer: VideoCapturer? = null
            var mediaProjection: MediaProjection? = null
            
            // Dispose local video track if available
            localVideoTrack?.dispose()
            
            // Dispose screen capturer if available
            screenCapturer?.dispose()
            
            peerConnection?.dispose()
            peerConnection = null
            
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
            
            // 断开信令
            try {
                signalingClient.setRoomStatus("disconnected")
                signalingClient.disconnect()
            } catch (e: UninitializedPropertyAccessException) {
                Log.d(TAG, "信令客户端未初始化")
            }
            
            // 停止媒体投影
            mediaProjection?.stop()
            
            Log.d(TAG, "资源已清理")
        } catch (e: Exception) {
            Log.e(TAG, "清理资源时发生异常", e)
        }
    }
    
    // 显示消息（同时更新UI和记录日志）
    private fun showMessage(message: String, isInfo: Boolean) {
        runOnUiThread {
            statusTextView.text = message
            
            if (isInfo) {
                Log.i(TAG, message)
            } else {
                Log.e(TAG, message)
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // 显示错误并结束Activity
    private fun showErrorAndFinish(errorMessage: String) {
        try {
            showError(errorMessage)
            
            // 延迟结束activity以确保用户能看到错误信息
            remoteVideoView.postDelayed({
                try {
                    if (!isFinishing) {
                        finish()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "结束Activity失败", e)
                }
            }, 3000) // 3秒后关闭
        } catch (e: Exception) {
            Log.e(TAG, "显示错误并结束失败", e)
            finish()
        }
    }
    
    override fun onPause() {
        super.onPause()
        try {
            // 在界面不可见时停止屏幕共享，可以避免某些设备特有的问题
            if (isInitiator) {
                peerConnectionHelper?.stopCapture()
                Log.d(TAG, "暂停时停止屏幕捕获")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onPause中停止捕获失败", e)
        }
    }
    
    override fun onDestroy() {
        // 解绑服务
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
                isServiceBound = false
            } catch (e: Exception) {
                Log.e(TAG, "解绑服务失败", e)
            }
        }
        
        // 停止服务
        try {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java)
            stopService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "停止服务失败", e)
        }
        
        try {
            disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "销毁时断开连接失败", e)
        }
        super.onDestroy()
    }

    // 显示错误但不会立即结束Activity
    private fun showError(errorMessage: String) {
        try {
            // 记录错误
            Log.e(TAG, errorMessage)
            
            // 更新UI
            runOnUiThread {
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                statusTextView.text = "错误: $errorMessage"
            }
        } catch (e: Exception) {
            Log.e(TAG, "显示错误失败", e)
        }
    }
} 