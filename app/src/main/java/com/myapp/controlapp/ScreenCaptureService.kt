package com.myapp.controlapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import android.content.ComponentName
import android.graphics.Color
import android.os.Handler
import android.os.Looper

/**
 * 屏幕捕获前台服务，用于Android 10以上版本
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1
        
        // 服务启动超时时间
        private const val SERVICE_START_TIMEOUT = 3000L // 3秒
        
        // 存储服务启动状态
        private var isServiceStarting = false
        private var isServiceStarted = false
    }

    private val binder = ScreenCaptureBinder()

    inner class ScreenCaptureBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ScreenCaptureService创建")
        
        try {
            createNotificationChannel()
            isServiceStarting = true
            
            // 华为设备特殊处理
            if (MyApplication.isHuaweiDevice()) {
                Log.d(TAG, "华为设备，使用特殊启动逻辑")
                // 在主线程中延迟一点时间后再启动前台服务
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        startForegroundCompat()
                        Log.d(TAG, "华为设备前台服务启动成功")
                    } catch (e: Exception) {
                        Log.e(TAG, "华为设备前台服务启动失败", e)
                        MyApplication.getInstance().logCrash("ScreenCaptureService", "华为设备前台服务启动失败", e)
                    }
                }, 200)
            } else {
                // 非华为设备正常启动
                startForegroundCompat()
            }
        } catch (e: Exception) {
            Log.e(TAG, "ScreenCaptureService创建失败", e)
            MyApplication.getInstance().logCrash("ScreenCaptureService", "服务创建失败", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ScreenCaptureService.onStartCommand 调用")
        
        try {
            // 如果Intent包含isScreenCapture标志，说明是屏幕捕获相关的启动
            val isScreenCapture = intent?.getBooleanExtra("isScreenCapture", false) ?: false
            Log.d(TAG, "启动类型: 屏幕捕获=${isScreenCapture}")
            
            // 服务启动完成
            isServiceStarting = false
            isServiceStarted = true
            
            // 如果服务已经处于前台状态，记录日志
            if (isServiceStarted) {
                Log.d(TAG, "服务已经处于前台状态")
            }
            
            // 如果还未启动前台服务，再次尝试启动
            if (!isServiceStarted) {
                startForegroundCompat()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand处理失败", e)
            MyApplication.getInstance().logCrash("ScreenCaptureService", "onStartCommand失败", e)
        }
        
        // 返回粘性标志，如果服务被系统强制停止，应该尝试自动重启
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "ScreenCaptureService.onBind 调用")
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ScreenCaptureService被销毁")
        isServiceStarted = false
    }
    
    /**
     * 兼容不同Android版本的前台服务启动
     */
    private fun startForegroundCompat() {
        try {
            val notification = createNotification()
            
            // Android 10 (API 29)及以上版本需要指定前台服务类型
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "以Android 10+方式启动前台服务")
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                Log.d(TAG, "以Android 10以下方式启动前台服务")
                startForeground(NOTIFICATION_ID, notification)
            }
            
            isServiceStarted = true
            Log.d(TAG, "前台服务启动成功")
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败", e)
            MyApplication.getInstance().logCrash("ScreenCaptureService", "启动前台服务失败", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "屏幕共享"
            val description = "用于屏幕共享的通知"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                this.description = description
                enableLights(true)
                lightColor = Color.BLUE
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "通知渠道创建成功")
        } else {
            Log.d(TAG, "Android 8.0以下不需要创建通知渠道")
        }
    }

    private fun createNotification(): Notification {
        // 创建一个启动主活动的PendingIntent
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        // 构建通知
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕共享进行中")
            .setContentText("点击返回应用")
            .setSmallIcon(R.drawable.ic_screen_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }
} 