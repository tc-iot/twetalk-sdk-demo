package com.tencent.twetalk_sdk_demo.call

import android.Manifest
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.tencent.twetalk.core.ConnectionState
import com.tencent.twetalk.core.DefaultTWeTalkClient
import com.tencent.twetalk.core.TWeTalkClient
import com.tencent.twetalk.core.TWeTalkClientListener
import com.tencent.twetalk.core.TWeTalkConfig
import com.tencent.twetalk.metrics.MetricEvent
import com.tencent.twetalk.mqtt.MqttManager
import com.tencent.twetalk.protocol.AudioFormat
import com.tencent.twetalk.protocol.CallStream
import com.tencent.twetalk.protocol.CallSubType
import com.tencent.twetalk.protocol.TWeTalkMessage
import com.tencent.twetalk.protocol.TweCallMessage
import com.tencent.twetalk.transport.WebSocketTransport
import com.tencent.twetalk_sdk_demo.BaseActivity
import com.tencent.twetalk_sdk_demo.R
import com.tencent.twetalk_audio.TalkAudioController
import com.tencent.twetalk_audio.config.AudioConfig
import com.tencent.twetalk_audio.config.AudioFormatType
import com.tencent.twetalk_audio.config.FrameDurationType
import com.tencent.twetalk_audio.listener.OnRecordDataListener
import com.tencent.twetalk_sdk_demo.data.Constants
import com.tencent.twetalk_sdk_demo.databinding.ActivityWxCallBinding
import com.tencent.twetalk_sdk_demo.utils.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 仅通话页面 (MQTT 在线，WebSocket 离线时的来电场景)
 * 
 * 该 Activity 自己管理 WebSocket 连接，用于处理来电通话。
 * 与 WxCallActivity 不同，WxCallActivity 依赖于已存在的 WebSocket 连接（在 WebSocketChatActivity 中）。
 */
class WxCallOnlyActivity : BaseActivity<ActivityWxCallBinding>(), TWeTalkClientListener {

    companion object {
        private val TAG = WxCallOnlyActivity::class.simpleName
        private const val AUTO_FINISH_DELAY = 500L
        private val VIBRATION_PATTERN = longArrayOf(0, 500, 500)  // 震动模式
    }

    // 通话信息
    private var callState: CallState = CallState.INCOMING
    private var nickname: String = ""
    private var openId: String = ""
    private var roomId: String = ""
    private var isMuted: Boolean = false

    // 震动器
    private var vibrator: Vibrator? = null

    // WebSocket 客户端
    private var client: TWeTalkClient? = null
    private var isWebSocketConnected = false

    // MQTT 回调
    private lateinit var mqttCallback: MqttManager.MqttConnectionCallback

    // 统一音频控制器
    private var audioController: TalkAudioController? = null
    @Volatile private var isAudioControllerInitialized = false

    // 通话计时
    private var callStartTime: Long = 0
    private var timerJob: Job? = null

    // 权限请求
    private val reqPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (audioGranted) {
            initAudioController()
        } else {
            showToast("麦克风权限被拒绝，无法进行通话")
            selfFinish()
        }
    }

    override fun getViewBinding() = ActivityWxCallBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun initView() {
        parseIntent()
        setupUI()
        setupClickListeners()
        updateUIForState()
        setupMqttCallback()
        setupOnBackPressedCallback()
        ensurePermissionsAndStart()
    }

    private fun parseIntent() {
        val bundle = intent.getBundleExtra(Constants.KEY_CALL_BUNDLE) ?: run {
            showToast("缺少通话参数")
            selfFinish()
            return
        }

        nickname = bundle.getString(Constants.KEY_CALL_NICKNAME, "") ?: ""
        openId = bundle.getString(Constants.KEY_CALL_OPEN_ID, "") ?: ""
        roomId = bundle.getString(Constants.KEY_CALL_ROOM_ID, "") ?: ""

        if (roomId.isEmpty()) {
            showToast("缺少房间 ID")
            selfFinish()
            return
        }

        callState = CallState.INCOMING
    }

    private fun setupUI() {
        if (nickname.isEmpty() && openId.isEmpty()) {
            binding.tvNickname.visibility = View.GONE
            binding.tvOpenId.visibility = View.GONE
            binding.layoutOpenId.visibility = View.GONE
            return
        }

        binding.tvNickname.text = nickname.ifEmpty { openId }
        binding.tvOpenId.text = openId

        binding.layoutOpenId.setOnClickListener {
            showToast(openId)
        }
    }

    private fun setupClickListeners() {
        binding.fabHangup.setOnClickListener {
            handleHangup()
        }

        binding.fabAnswer.setOnClickListener {
            handleAnswer()
        }

        binding.fabMute.setOnClickListener {
            toggleMute()
        }
    }

    private fun setupMqttCallback() {
        mqttCallback = object : MqttManager.MqttConnectionCallback {
            override fun onConnected() {}
            override fun onDisconnected(cause: Throwable?, isManual: Boolean) {
                // MQTT 断开，结束通话
                runOnUiThread {
                    showToast("设备已断开连接")
                    callState = CallState.ENDED
                    updateUIForState()
                }
            }
            override fun onConnectFailed(cause: Throwable?) {}

            override fun onMessageReceived(topic: String?, method: String?, params: Map<String?, Any?>?) {
                when (method) {
                    MqttManager.REPLY_QUERY_WEBSOCKET_URL -> {
                        // 收到 WebSocket URL，根据是否是拒接操作来处理
                        if (pendingReject) {
                            pendingReject = false
                            handleWebSocketUrlReplyForReject(params)
                        } else {
                            handleWebSocketUrlReply(params)
                        }
                    }
                    MqttManager.RECEIVED_VOIP_CANCEL -> {
                        // 小程序取消呼叫
                        val cancelRoomId = params?.get("roomId") as? String
                        if (cancelRoomId == roomId) {
                            runOnUiThread {
                                callState = CallState.ENDED
                                updateUIForState()
                            }
                        }
                    }
                }
            }
        }
        mqttManager?.callback = mqttCallback
    }

    private fun setupOnBackPressedCallback() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 通话中和来电中时不可退回
                if (callState == CallState.IN_PROGRESS || callState == CallState.INCOMING) {
                    showToast(getString(R.string.call_in_progress))
                } else {
                    // 反之放行
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun ensurePermissionsAndStart() {
        if (PermissionHelper.hasPermissions(this, PermissionHelper.AUDIO_MODE_PERMISSIONS)) {
            initAudioController()
        } else {
            val missingPermissions = PermissionHelper.getMissingPermissions(this, PermissionHelper.AUDIO_MODE_PERMISSIONS)
            reqPermissions.launch(missingPermissions.toTypedArray())
        }
    }

    private fun initAudioController() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val audioConfig = AudioConfig().apply {
                    frameDuration = FrameDurationType.MS_60
                    formatType = AudioFormatType.OPUS
                }

                audioController = TalkAudioController(this@WxCallOnlyActivity, audioConfig).also { controller ->
                    controller.setOnRecordDataListener(object : OnRecordDataListener {
                        override fun onPcmData(data: ByteArray, size: Int) {
                            // PCM 数据回调（不使用）
                        }

                        override fun onOpusData(data: ByteArray, size: Int) {
                            // Opus 数据回调
                            if (isWebSocketConnected && callState == CallState.IN_PROGRESS) {
                                client?.sendCustomAudioData(data, audioConfig.sampleRate, audioConfig.channelCount)
                            }
                        }

                        override fun onRecordError(errorCode: Int, message: String) {
                            Log.e(TAG, "录音错误[$errorCode]: $message")
                        }
                    })

                    controller.init()
                    isAudioControllerInitialized = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "TalkAudioController init failed", e)
            }
        }
    }

    private fun handleAnswer() {
        if (callState != CallState.INCOMING) return

        // 查询 WebSocket URL，附带 connect_type = "call" 和 room_id
        val params = mapOf(
            "connect_type" to MqttManager.WebSocketConnectType.CALL.value,
            "room_id" to roomId
        )
        mqttManager?.queryWebSocketUrl(params)

        // 更新 UI 为连接中状态
        binding.tvStatus.text = getString(R.string.connecting)
        binding.fabAnswer.isEnabled = false
        binding.fabHangup.isEnabled = false
    }

    private fun handleWebSocketUrlReply(params: Map<String?, Any?>?) {
        val token = params?.get("token") as? String
        val websocketUrl = params?.get("websocket_url") as? String
//        val websocketUrl = "ws://43.144.104.72:7860/ws_voip"

        if (token.isNullOrEmpty() || websocketUrl.isNullOrEmpty()) {
            runOnUiThread {
                showToast("获取连接信息失败")
                callState = CallState.ERROR
                updateUIForState()
            }
            return
        }

        // 获取设备信息
        val prefs = getSharedPreferences(Constants.KEY_DEVICE_INFO_PREF, MODE_PRIVATE)
        val productId = prefs.getString(Constants.KEY_PRODUCT_ID, "") ?: ""
        val deviceName = prefs.getString(Constants.KEY_DEVICE_NAME, "") ?: ""

        // 获取 appId 和 modelId（如果有）
        val appId = CallConfigManager.getWxaAppId(this)
        val modelId = CallConfigManager.getWxaModelId(this)

        // 构建通话连接参数
        val callParams = WebSocketTransport.CallConnectParams.builder()
            .baseUrl(websocketUrl)
            .token(token)
            .productId(productId)
            .deviceName(deviceName)
            .roomId(roomId)
            .appId(appId)
            .modelId(modelId)
            .response("answer")  // 接听
            .build()

        // 创建空配置的 client（实际连接参数通过 connectForCall 传入）
        val authConfig = TWeTalkConfig.AuthConfig(
            productId,
            deviceName,
            token,
            "opus",
            "zh"
        )

        val config = TWeTalkConfig.builder()
            .authConfig(authConfig)
            .build()

        client = DefaultTWeTalkClient(config)
        client?.addListener(this)
        client?.connectForCall(callParams)
    }

    private fun handleHangup() {
        when (callState) {
            CallState.INCOMING -> {
                // 拒接来电 - 通过 URL 参数 response=reject 拒接
                sendRejectAndFinish()
            }
            CallState.IN_PROGRESS -> {
                // 挂断通话 - 直接断开 WebSocket 连接
                client?.close()
                callState = CallState.ENDED
                updateUIForState()
            }
            else -> {
                selfFinish()
            }
        }
    }

    private fun sendRejectAndFinish() {
        // 拒接通过 URL 参数 response=reject 处理
        pendingReject = true
        val params = mapOf(
            "connect_type" to MqttManager.WebSocketConnectType.CALL.value,
        )
        mqttManager?.queryWebSocketUrl(params)
    }

    // 标记是否是拒接操作
    private var pendingReject = false

    private fun handleWebSocketUrlReplyForReject(params: Map<String?, Any?>?) {
        val token = params?.get("token") as? String
        val websocketUrl = params?.get("websocket_url") as? String
//        val websocketUrl = "ws://43.144.104.72:7860/ws_voip"

        if (token.isNullOrEmpty() || websocketUrl.isNullOrEmpty()) {
            runOnUiThread {
                callState = CallState.ENDED
                updateUIForState()
            }
            return
        }

        // 获取设备信息
        val prefs = getSharedPreferences(Constants.KEY_DEVICE_INFO_PREF, MODE_PRIVATE)
        val productId = prefs.getString(Constants.KEY_PRODUCT_ID, "") ?: ""
        val deviceName = prefs.getString(Constants.KEY_DEVICE_NAME, "") ?: ""

        // 获取 appId 和 modelId
        val appId = CallConfigManager.getWxaAppId(this)
        val modelId = CallConfigManager.getWxaModelId(this)

        // 构建拒接连接参数
        val callParams = WebSocketTransport.CallConnectParams.builder()
            .baseUrl(websocketUrl)
            .token(token)
            .productId(productId)
            .deviceName(deviceName)
            .roomId(roomId)
            .appId(appId)
            .modelId(modelId)
            .response("reject")  // 拒接
            .build()

        // 创建 client 并连接（服务端会根据 response=reject 处理拒接逻辑）
        val authConfig = TWeTalkConfig.AuthConfig(
            productId,
            deviceName,
            token,
            "opus",
            "zh"
        )

        val config = TWeTalkConfig.builder()
            .authConfig(authConfig)
            .build()

        client = DefaultTWeTalkClient(config)
        // 不添加 listener，连接后立即关闭
        client?.connectForCall(callParams)

        // 直接结束
        runOnUiThread {
            callState = CallState.ENDED
            updateUIForState()
        }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        updateMuteUI()

        // 使用 TalkAudioController 的静音功能
        audioController?.setMicMute(isMuted)
    }

    private fun updateMuteUI() {
        if (isMuted) {
            // 静音状态
            binding.fabMute.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.call_button_inactive))
            binding.fabMute.setImageResource(R.drawable.ic_mic_off)
            binding.fabMute.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
            binding.tvMuteLabel.text = getString(R.string.unmute)
        } else {
            // 未静音状态
            binding.fabMute.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.call_button_active))
            binding.fabMute.setImageResource(R.drawable.ic_mic)
            binding.fabMute.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.black))
            binding.tvMuteLabel.text = getString(R.string.mute)
        }
    }

    private fun updateUIForState() {
        when (callState) {
            CallState.IDLE -> {}
            CallState.CALLING -> {}
            CallState.INCOMING -> {
                binding.tvStatus.text = getString(R.string.incoming_call)
                binding.layoutAnswer.visibility = View.VISIBLE
                binding.layoutMute.visibility = View.GONE
                binding.layoutHangup.visibility = View.VISIBLE
                enableButtons(true)
                startVibration()
            }
            CallState.IN_PROGRESS -> {
                binding.layoutAnswer.visibility = View.GONE
                binding.layoutMute.visibility = View.VISIBLE
                binding.layoutHangup.visibility = View.VISIBLE
                enableButtons(true)
                stopVibration()
                updateMuteUI()
                startRecording()
            }
            CallState.REJECTED, CallState.TIMEOUT, CallState.BUSY, CallState.ERROR -> {
                binding.tvStatus.text = when (callState) {
                    CallState.REJECTED -> getString(R.string.call_rejected)
                    CallState.TIMEOUT -> getString(R.string.call_timeout)
                    CallState.BUSY -> getString(R.string.call_busy)
                    else -> getString(R.string.call_error)
                }
                enableButtons(false)
                stopVibration()
                scheduleAutoFinish()
            }
            CallState.ENDED -> {
                binding.tvStatus.text = getString(R.string.call_ended)
                enableButtons(false)
                stopCallTimer()
                stopRecording()
                stopVibration()
                scheduleAutoFinish()
            }
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VIBRATION_PATTERN, 0)
        }
    }

    private fun stopVibration() {
        vibrator?.cancel()
        vibrator = null
    }

    private fun enableButtons(enabled: Boolean) {
        binding.fabHangup.isEnabled = enabled
        binding.fabHangup.alpha = if (enabled) 1.0f else 0.5f
        binding.fabAnswer.isEnabled = enabled
        binding.fabAnswer.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun startRecording() {
        if (isAudioControllerInitialized && !isMuted) {
            audioController?.startRecord()
        }
    }

    private fun stopRecording() {
        audioController?.stopRecord()
    }

    private fun startCallTimer() {
        lifecycleScope.launch {
            binding.tvStatus.text = "00:00"
        }

        callStartTime = System.currentTimeMillis()

        if (timerJob == null) {
            timerJob = lifecycleScope.launch {
                while (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    updateCallDuration()
                    delay(1000)
                }
            }
        }

        timerJob!!.start()
    }

    private fun stopCallTimer() {
        timerJob?.cancel()
    }

    private fun updateCallDuration() {
        val duration = (System.currentTimeMillis() - callStartTime) / 1000
        val minutes = duration / 60
        val seconds = duration % 60
        binding.tvStatus.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun scheduleAutoFinish() {
        lifecycleScope.launch {
            delay(AUTO_FINISH_DELAY)
            selfFinish()
        }
    }

    private fun selfFinish() {
        mqttManager?.callback = null
        finish()
    }

    // ====================== TWeTalkClientListener 实现 ====================== //

    override fun onStateChanged(state: ConnectionState) {
        runOnUiThread {
            when (state) {
                ConnectionState.CONNECTED -> {
                    isWebSocketConnected = true
                    // 通过 URL 参数 response=answer 连接就是接听，不需要再发送消息
                    // 更新状态为通话中
                    callState = CallState.IN_PROGRESS
                    updateUIForState()
                }

                ConnectionState.CLOSED -> {
                    isWebSocketConnected = false
                    if (callState == CallState.IN_PROGRESS || callState == CallState.INCOMING) {
                        callState = CallState.ENDED
                        updateUIForState()
                    }
                }
                else -> {}
            }
        }
    }

    private var isFirstAudioFrameArrive = false
    override fun onRecvAudio(audio: ByteArray, sampleRate: Int, channels: Int, format: AudioFormat) {
        if (!isFirstAudioFrameArrive) {
            isFirstAudioFrameArrive = true
            startCallTimer()
        }

        val sr = if (sampleRate > 0) sampleRate else 16000
        val ch = if (channels > 0) channels else 1
        val isPcm = format == AudioFormat.PCM
        val formatType = if (isPcm) AudioFormatType.PCM else AudioFormatType.OPUS
        audioController?.play(audio, sr, ch, formatType)
    }

    override fun onRecvTalkMessage(type: TWeTalkMessage.TWeTalkMessageType, text: String?) {
        // 通话场景不处理对话消息
    }

    override fun onRecvCallMessage(stream: CallStream, subType: CallSubType, data: TweCallMessage.TweCallData) {
    }

    override fun onMetrics(metrics: MetricEvent?) {}

    override fun onError(error: Throwable?) {
        Log.e(TAG, "onError", error)
        runOnUiThread {
            showToast("连接出错")
            callState = CallState.ERROR
            updateUIForState()
        }
    }

    // ====================== 生命周期 ====================== //

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        timerJob = null
        client?.close()
        client = null
        stopRecording()
        stopVibration()
        audioController?.release()
        audioController = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
