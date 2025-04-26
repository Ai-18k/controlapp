package com.myapp.controlapp

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

class MyApplication : Application() {
    
    companion object {
        private const val TAG = "MyApplication"
        
        // 全局实例
        private lateinit var instance: MyApplication
        
        fun getInstance(): MyApplication {
            return instance
        }
        
        // 设备信息收集
        fun getDeviceInfo(): String {
            return """
                制造商=${Build.MANUFACTURER}
                型号=${Build.MODEL}
                品牌=${Build.BRAND}
                设备=${Build.DEVICE}
                产品=${Build.PRODUCT}
                SDK=${Build.VERSION.SDK_INT}
                Android版本=${Build.VERSION.RELEASE}
                指纹=${Build.FINGERPRINT}
            """.trimIndent()
        }
        
        // 检查是否为华为设备
        fun isHuaweiDevice(): Boolean {
            val manufacturer = Build.MANUFACTURER?.lowercase(Locale.ROOT) ?: ""
            return manufacturer.contains("huawei") || manufacturer.contains("honor")
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // 记录设备信息
        logDeviceInfo()
        
        // 设置全局未捕获异常处理器
        setupExceptionHandler()
        
        // 初始化Firebase
        try {
            FirebaseApp.initializeApp(this)
            // 启用Firebase离线持久化
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
            Log.d(TAG, "Firebase初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase初始化失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun logDeviceInfo() {
        val deviceInfo = getDeviceInfo()
        Log.i(TAG, "设备信息:\n$deviceInfo")
        
        if (isHuaweiDevice()) {
            Log.i(TAG, "检测到华为设备，将应用特定的兼容性处理")
        }
    }
    
    private fun setupExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // 记录崩溃日志到文件
                saveExceptionToFile(throwable)
                
                // 记录到系统日志
                Log.e(TAG, "应用发生未捕获异常", throwable)
                
                // 如果是屏幕捕获相关异常，记录更详细的信息
                if (throwable.stackTrace.any { it.className.contains("ScreenCapturer") || it.className.contains("WebRTC") }) {
                    Log.e(TAG, "WebRTC屏幕捕获异常: ${throwable.message}")
                    Log.e(TAG, "设备信息:\n${getDeviceInfo()}")
                }
                
                // 如果是华为设备发生的异常，记录更多信息
                if (isHuaweiDevice()) {
                    Log.e(TAG, "华为设备异常，可能需要特殊处理: ${throwable.message}")
                }
            } catch (e: Exception) {
                // 确保异常处理本身不会引发更多问题
                Log.e(TAG, "记录异常时出错", e)
            } finally {
                // 交给默认处理器处理
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
    
    private fun saveExceptionToFile(throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "crash_${Build.MANUFACTURER}_${Build.MODEL}_$timestamp.txt"
            val dir = File(filesDir, "crash_logs")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            
            val crashFile = File(dir, filename)
            val writer = PrintWriter(FileOutputStream(crashFile))
            writer.println("时间: $timestamp")
            writer.println("设备信息:")
            writer.println(getDeviceInfo())
            writer.println("\n异常: ${throwable.javaClass.name}: ${throwable.message}")
            writer.println("\n堆栈跟踪:")
            throwable.printStackTrace(writer)
            writer.close()
            
            Log.i(TAG, "崩溃日志已保存至: $crashFile")
        } catch (e: Exception) {
            Log.e(TAG, "保存崩溃日志失败", e)
        }
    }
    
    // 在主线程中显示Toast消息的辅助方法
    fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        android.os.Handler(mainLooper).post {
            Toast.makeText(this, message, duration).show()
        }
    }
    
    // 创建具有详细信息的崩溃日志
    fun logCrash(tag: String, message: String, e: Throwable? = null) {
        val fullMessage = if (isHuaweiDevice()) {
            "华为设备错误 - $message"
        } else {
            message
        }
        
        Log.e(tag, fullMessage, e)
        
        // 保存到文件
        saveLogToFile(tag, fullMessage, e)
    }
    
    private fun saveLogToFile(tag: String, message: String, throwable: Throwable?) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "log_${tag}_$timestamp.txt"
            val dir = File(filesDir, "app_logs")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            
            val logFile = File(dir, filename)
            val writer = PrintWriter(FileOutputStream(logFile))
            writer.println("时间: $timestamp")
            writer.println("设备信息:")
            writer.println(getDeviceInfo())
            writer.println("\n消息: $message")
            
            if (throwable != null) {
                writer.println("\n堆栈跟踪:")
                throwable.printStackTrace(writer)
            }
            
            writer.close()
            
            Log.i(TAG, "日志已保存至: $logFile")
        } catch (e: Exception) {
            Log.e(TAG, "保存日志失败", e)
        }
    }
} 