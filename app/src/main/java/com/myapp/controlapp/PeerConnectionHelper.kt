package com.myapp.controlapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.*

/**
 * 管理WebRTC PeerConnection的辅助类
 */
class PeerConnectionHelper(
    private val context: Context,
    private val eglBaseContext: EglBase.Context,
    private val peerConnectionObserver: PeerConnection.Observer
) {
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )
    
    private val peerConnectionFactory: PeerConnectionFactory
    private val mediaConstraints = MediaConstraints().apply {
        optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
    }
    
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val localTracks = mutableListOf<MediaStreamTrack>()
    
    // 主线程处理器
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isCapturing = false
    
    companion object {
        private const val TAG = "PeerConnectionHelper"
    }
    
    init {
        // 初始化PeerConnectionFactory
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
        
        val encoderFactory = DefaultVideoEncoderFactory(eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBaseContext)
        
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }
    
    /**
     * 创建PeerConnection
     */
    fun createPeerConnection(): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        
        return peerConnectionFactory.createPeerConnection(rtcConfig, peerConnectionObserver)
    }
    
    /**
     * 初始化屏幕捕获器
     */
    fun initScreenCapture(videoCapturer: VideoCapturer) {
        try {
            this.videoCapturer = videoCapturer
            
            // 确保先创建SurfaceTextureHelper
            if (surfaceTextureHelper == null) {
                // 确保在WebRTC线程上创建
                surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
            }
            
            // 创建视频源
            if (videoSource == null) {
                videoSource = peerConnectionFactory.createVideoSource(true)
            }
            
            // 初始化捕获器
            try {
                videoCapturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
                Log.d(TAG, "屏幕捕获器初始化成功")
            } catch (e: Exception) {
                Log.e(TAG, "初始化屏幕捕获器失败: ${e.message}", e)
                
                // 再次尝试，确保在主线程
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    Log.d(TAG, "在非主线程，尝试在主线程初始化")
                    mainHandler.post {
                        try {
                            videoCapturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
                            Log.d(TAG, "在主线程重新初始化捕获器成功")
                        } catch (e: Exception) {
                            Log.e(TAG, "在主线程重新初始化捕获器失败: ${e.message}", e)
                            throw e
                        }
                    }
                } else {
                    throw e
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化屏幕捕获器失败: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * 开始屏幕捕获
     */
    fun startScreenCapture(width: Int, height: Int, fps: Int) {
        try {
            if (isCapturing) {
                Log.w(TAG, "已经在捕获中，忽略此次调用")
                return
            }
            
            if (videoCapturer == null) {
                Log.e(TAG, "videoCapturer为null，无法开始捕获")
                throw IllegalStateException("屏幕捕获器未初始化")
            }
            
            if (surfaceTextureHelper == null) {
                Log.e(TAG, "surfaceTextureHelper为null，无法开始捕获")
                throw IllegalStateException("surfaceTextureHelper未初始化")
            }
            
            if (videoSource == null) {
                Log.e(TAG, "videoSource为null，无法开始捕获")
                throw IllegalStateException("videoSource未初始化")
            }
            
            // 确保宽高和帧率在合理范围内
            val safeWidth = if (width <= 0) 640 else width
            val safeHeight = if (height <= 0) 480 else height
            val safeFps = if (fps <= 0 || fps > 30) 15 else fps
            
            // 检查捕获器状态
            val capturer = videoCapturer
            if (capturer is ScreenCapturerAndroid) {
                Log.d(TAG, "开始屏幕捕获: ${safeWidth}x${safeHeight} @${safeFps}fps")
                
                // 尝试在当前线程启动
                try {
                    // 标记为已开始捕获
                    isCapturing = true
                    capturer.startCapture(safeWidth, safeHeight, safeFps)
                    Log.d(TAG, "屏幕捕获开始成功")
                } catch (e: RuntimeException) {
                    Log.e(TAG, "当前线程启动捕获失败，尝试在主线程启动: ${e.message}", e)
                    isCapturing = false
                    
                    // 在主线程上尝试启动捕获
                    if (Looper.myLooper() != Looper.getMainLooper()) {
                        mainHandler.post {
                            try {
                                isCapturing = true
                                capturer.startCapture(safeWidth, safeHeight, safeFps)
                                Log.d(TAG, "在主线程启动捕获成功")
                            } catch (e: Exception) {
                                isCapturing = false
                                Log.e(TAG, "在主线程启动捕获也失败: ${e.message}", e)
                            }
                        }
                    } else {
                        throw e
                    }
                }
            } else {
                Log.e(TAG, "捕获器类型不正确: ${videoCapturer!!::class.java.simpleName}")
                throw IllegalStateException("捕获器类型不支持")
            }
        } catch (e: Exception) {
            isCapturing = false
            Log.e(TAG, "开始屏幕捕获失败: ${e.message}", e)
            Log.e(TAG, "异常类型: ${e.javaClass.name}")
            e.printStackTrace()
            throw e
        }
    }
    
    /**
     * 创建本地媒体流
     */
    fun createLocalMediaStream(): MediaStream {
        val localStream = peerConnectionFactory.createLocalMediaStream("local_stream")
        
        // 添加音频轨道
        audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        val audioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource)
        localStream.addTrack(audioTrack)
        localTracks.add(audioTrack)
        
        // 添加视频轨道
        videoSource?.let {
            val videoTrack = peerConnectionFactory.createVideoTrack("video_track", it)
            localStream.addTrack(videoTrack)
            localTracks.add(videoTrack)
        }
        
        return localStream
    }
    
    /**
     * 创建提议
     */
    fun createOffer(mediaConstraints: MediaConstraints, sdpObserver: SdpObserver): Boolean {
        val peerConnection = createPeerConnection() ?: return false
        peerConnection.createOffer(sdpObserver, mediaConstraints)
        return true
    }
    
    /**
     * 创建应答
     */
    fun createAnswer(peerConnection: PeerConnection, mediaConstraints: MediaConstraints, sdpObserver: SdpObserver) {
        peerConnection.createAnswer(sdpObserver, mediaConstraints)
    }
    
    /**
     * 停止捕获
     */
    fun stopCapture() {
        try {
            if (!isCapturing) {
                Log.d(TAG, "未在捕获状态，无需停止")
                return
            }
            
            val capturer = videoCapturer
            if (capturer != null) {
                Log.d(TAG, "停止屏幕捕获")
                
                try {
                    capturer.stopCapture()
                    isCapturing = false
                    Log.d(TAG, "屏幕捕获已停止")
                } catch (e: Exception) {
                    Log.e(TAG, "停止捕获失败，尝试在主线程停止: ${e.message}", e)
                    
                    // 在主线程尝试停止
                    if (Looper.myLooper() != Looper.getMainLooper()) {
                        mainHandler.post {
                            try {
                                capturer.stopCapture()
                                isCapturing = false
                                Log.d(TAG, "在主线程停止捕获成功")
                            } catch (e: Exception) {
                                Log.e(TAG, "在主线程停止捕获也失败: ${e.message}", e)
                            }
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "停止捕获被中断", e)
            isCapturing = false
            e.printStackTrace()
        } catch (e: Exception) {
            Log.e(TAG, "停止捕获时发生错误: ${e.message}", e)
            isCapturing = false
            e.printStackTrace()
        }
    }
    
    /**
     * 释放资源
     */
    fun dispose() {
        try {
            stopCapture()
            
            videoCapturer?.dispose()
            videoCapturer = null
            
            if (surfaceTextureHelper != null) {
                surfaceTextureHelper?.dispose()
                surfaceTextureHelper = null
            }
            
            if (videoSource != null) {
                videoSource?.dispose()
                videoSource = null
            }
            
            if (audioSource != null) {
                audioSource?.dispose()
                audioSource = null
            }
            
            localTracks.clear()
            
            peerConnectionFactory.dispose()
            
            Log.d(TAG, "资源已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放资源时出错: ${e.message}", e)
            e.printStackTrace()
        }
    }
} 