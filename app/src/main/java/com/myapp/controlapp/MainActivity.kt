package com.myapp.controlapp

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class MainActivity : AppCompatActivity() {
    
    private lateinit var roomIdEditText: EditText
    private lateinit var startSharingButton: Button
    private lateinit var joinRoomButton: Button
    private lateinit var statusView: View
    
    private val TAG = "MainActivity"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // 设置窗口边距
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        // 初始化视图
        initializeViews()
        
        // 设置按钮点击事件
        setupClickListeners()
    }
    
    private fun initializeViews() {
        roomIdEditText = findViewById(R.id.room_id_edit_text)
        startSharingButton = findViewById(R.id.start_sharing_button)
        joinRoomButton = findViewById(R.id.join_room_button)
        statusView = findViewById(R.id.connection_status_view)
        
        // 初始化时检查连接状态
        checkFirebaseConnection()
    }
    
    private fun setupClickListeners() {
        // 开始共享屏幕按钮
        startSharingButton.setOnClickListener {
            val roomId = roomIdEditText.text.toString().trim()
            if (roomId.isEmpty()) {
                Toast.makeText(this, "请输入房间ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // 启动监控活动，作为发起者
            startMonitoringActivity(roomId, true)
        }
        
        // 加入房间按钮
        joinRoomButton.setOnClickListener {
            val roomId = roomIdEditText.text.toString().trim()
            if (roomId.isEmpty()) {
                Toast.makeText(this, "请输入房间ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // 启动监控活动，作为接收者
            startMonitoringActivity(roomId, false)
        }
    }
    
    private fun startMonitoringActivity(roomId: String, isInitiator: Boolean) {
        // 先检查网络连接
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "网络连接不可用，请检查网络设置", Toast.LENGTH_LONG).show()
            return
        }
        
        // 检查房间ID合法性
        if (!isValidRoomId(roomId)) {
            Toast.makeText(this, "房间ID不合法，请使用4-12位字母数字组合", Toast.LENGTH_LONG).show()
            return
        }
        
        // 禁用按钮，防止重复点击
        disableButtons()
        
        // 检查Firebase连接并继续操作
        checkFirebaseAndProceed(roomId, isInitiator)
    }
    
    private fun checkFirebaseAndProceed(roomId: String, isInitiator: Boolean) {
        val firebaseDatabase = FirebaseDatabase.getInstance()
        
        Log.d(TAG, "检查Firebase连接状态")
        Toast.makeText(this, "正在连接服务器...", Toast.LENGTH_SHORT).show()
        
        firebaseDatabase.getReference(".info/connected").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                
                if (connected) {
                    Log.d(TAG, "Firebase已连接，准备启动会话")
                    
                    // 如果是接收者，先检查房间是否存在
                    if (!isInitiator) {
                        checkRoomExists(roomId, isInitiator)
                    } else {
                        // 发起者直接进入
                        launchMonitoringActivity(roomId, isInitiator)
                    }
                } else {
                    Log.e(TAG, "Firebase未连接")
                    Toast.makeText(this@MainActivity, "无法连接到服务器，请检查网络连接", Toast.LENGTH_LONG).show()
                    enableButtons()
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase连接检查取消: ${error.message}")
                Toast.makeText(this@MainActivity, "连接检查失败: ${error.message}", Toast.LENGTH_LONG).show()
                enableButtons()
            }
        })
    }
    
    private fun checkRoomExists(roomId: String, isInitiator: Boolean) {
        val firebaseDatabase = FirebaseDatabase.getInstance()
        val roomRef = firebaseDatabase.getReference("rooms/$roomId")
        
        roomRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val status = snapshot.child("status").getValue(String::class.java)
                    
                    if (status == "available") {
                        Log.d(TAG, "房间[$roomId]存在且可用")
                        launchMonitoringActivity(roomId, isInitiator)
                    } else {
                        Log.d(TAG, "房间[$roomId]状态不可用: $status")
                        Toast.makeText(this@MainActivity, "该房间不可用或已断开连接", Toast.LENGTH_LONG).show()
                        enableButtons()
                    }
                } else {
                    Log.d(TAG, "房间[$roomId]不存在")
                    Toast.makeText(this@MainActivity, "房间ID不存在，请检查是否正确", Toast.LENGTH_LONG).show()
                    enableButtons()
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "检查房间状态失败: ${error.message}")
                Toast.makeText(this@MainActivity, "检查房间状态失败: ${error.message}", Toast.LENGTH_LONG).show()
                enableButtons()
            }
        })
    }
    
    private fun launchMonitoringActivity(roomId: String, isInitiator: Boolean) {
        // 记录网络信息
        val networkInfo = getNetworkInfo()
        Log.d(TAG, "网络信息: $networkInfo")
        
        // 创建并启动活动意图
        val intent = Intent(this, MonitoringActivity::class.java).apply {
            putExtra("roomId", roomId)
            putExtra("isInitiator", isInitiator)
        }
        
        Log.d(TAG, "启动MonitoringActivity: 房间=$roomId, 角色=${if (isInitiator) "发起者" else "接收者"}")
        startActivity(intent)
        
        // 恢复按钮状态
        enableButtons()
    }
    
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            return activeNetworkInfo != null && activeNetworkInfo.isConnected
        }
    }
    
    private fun getNetworkInfo(): String {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = StringBuilder()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            
            networkInfo.append("网络类型: ")
            if (capabilities != null) {
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> networkInfo.append("WiFi")
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> networkInfo.append("蜂窝网络")
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> networkInfo.append("以太网")
                    else -> networkInfo.append("其他")
                }
            } else {
                networkInfo.append("无可用网络")
            }
        } else {
            @Suppress("DEPRECATION")
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            if (activeNetworkInfo != null && activeNetworkInfo.isConnected) {
                networkInfo.append("已连接 - ${activeNetworkInfo.typeName}")
            } else {
                networkInfo.append("无网络连接")
            }
        }
        
        return networkInfo.toString()
    }
    
    private fun isValidRoomId(roomId: String): Boolean {
        // 简单验证：4-12位字母数字
        return roomId.matches(Regex("^[a-zA-Z0-9]{4,12}$"))
    }
    
    private fun checkFirebaseConnection() {
        val firebaseDatabase = FirebaseDatabase.getInstance()
        
        firebaseDatabase.getReference(".info/connected").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                
                runOnUiThread {
                    statusView.visibility = View.VISIBLE
                    if (connected) {
                        statusView.setBackgroundColor(resources.getColor(android.R.color.holo_green_light, theme))
                    } else {
                        statusView.setBackgroundColor(resources.getColor(android.R.color.holo_red_light, theme))
                    }
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                runOnUiThread {
                    statusView.visibility = View.VISIBLE
                    statusView.setBackgroundColor(resources.getColor(android.R.color.holo_red_light, theme))
                }
            }
        })
    }
    
    private fun disableButtons() {
        startSharingButton.isEnabled = false
        joinRoomButton.isEnabled = false
    }
    
    private fun enableButtons() {
        runOnUiThread {
            startSharingButton.isEnabled = true
            joinRoomButton.isEnabled = true
        }
    }
}