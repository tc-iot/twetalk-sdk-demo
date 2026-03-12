package com.tencent.twetalk_sdk_demo.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tencent.twetalk.protocol.AudioFormat
import com.tencent.twetalk.protocol.call.CallStream
import com.tencent.twetalk.protocol.call.CallSubType
import com.tencent.twetalk.protocol.ImageMessage
import com.tencent.twetalk.protocol.TWeTalkMessage
import com.tencent.twetalk.protocol.call.TweCallMessage
import com.tencent.twetalk_sdk_demo.BaseActivity
import com.tencent.twetalk_sdk_demo.R
import com.tencent.twetalk_sdk_demo.adapter.ChatMessageAdapter
import com.tencent.twetalk_audio.TalkAudioController
import com.tencent.twetalk_audio.config.AudioConfig
import com.tencent.twetalk_audio.config.AudioFormatType
import com.tencent.twetalk_audio.listener.OnRecordDataListener
import com.tencent.twetalk_sdk_demo.call.CallAction
import com.tencent.twetalk_sdk_demo.call.CallConfigManager
import com.tencent.twetalk_sdk_demo.call.CallState
import com.tencent.twetalk_sdk_demo.call.CallType
import com.tencent.twetalk_sdk_demo.call.WxCallActivity
import com.tencent.twetalk_sdk_demo.call.WxCallManager
import com.tencent.twetalk_sdk_demo.data.ChatMessage
import com.tencent.twetalk_sdk_demo.data.Constants
import com.tencent.twetalk_sdk_demo.data.MessageStatus
import com.tencent.twetalk_sdk_demo.databinding.ActivityChatBinding
import com.tencent.twetalk_sdk_demo.utils.PermissionHelper
import com.tencent.twetalk_sdk_demo.utils.ScreenAdaptHelper
import com.tencent.twetalk_sdk_demo.video.VideoChatCameraManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseChatActivity : BaseActivity<ActivityChatBinding>() {
    companion object {
        private val TAG = BaseChatActivity::class.simpleName
    }

    private lateinit var messageAdapter: ChatMessageAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var isRecording = false
    private var connectionType: String = ""
    private var audioFormatStr: String = ""
    protected var isConnected = false
    protected var isVideoMode = false
    protected var isPushToTalkMode = false  // 按键说话模式
    protected var cameraManager: VideoChatCameraManager? = null

    // 统一音频控制器
    private var audioController: TalkAudioController? = null
    @Volatile private var isAudioControllerInitialized = false

    // 通话状态
    protected var isCalling = false  // 正在来电/呼叫中
    protected var isInProgress = false  // 正在通话状态
    protected var currentCallType: CallType? = null
    protected var currentCallOpenId: String? = null
    protected var currentCallNickname: String? = null
    protected var currentCallRoomId: String? = null
    
    // 小屏简化模式
    protected var isTinyScreen = false

    // 通话页面启动器
    protected val callActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 通话页面返回，重置通话状态
        isCalling = false
        isInProgress = false
        currentCallType = null
        currentCallOpenId = null
        currentCallNickname = null
        currentCallRoomId = null
    }

    protected val reqPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false

        when {
            isVideoMode -> {
                if ((audioGranted && cameraGranted) || PermissionHelper.hasPermissions(this,
                        PermissionHelper.VIDEO_MODE_PERMISSIONS)) {
                    startChat()
                    initAudioController()
                } else {
                    val deniedPermissions = mutableListOf<String>()
                    if (!audioGranted) deniedPermissions.add("麦克风")
                    if (!cameraGranted) deniedPermissions.add("摄像头")

                    showToast("${deniedPermissions.joinToString("、")}权限被拒绝")
                    finish()
                }
            }

            else -> {
                if (audioGranted) {
                    startChat()
                    initAudioController()
                } else {
                    showToast("麦克风权限被拒绝")
                    finish()
                }
            }
        }
    }

    override fun getViewBinding() = ActivityChatBinding.inflate(layoutInflater)

    override fun initView() {
        // 检查屏幕尺寸
        checkScreenSize()
        
        isVideoMode = intent.getBundleExtra(Constants.KEY_CHAT_BUNDLE)
            ?.getBoolean(Constants.KEY_VIDEO_MODE) ?: false
        isPushToTalkMode = intent.getBundleExtra(Constants.KEY_CHAT_BUNDLE)
            ?.getBoolean(Constants.KEY_PUSH_TO_TALK) ?: false

        loadConnectionInfo()

        if (isVideoMode) {
            showVideoUI()
            setupVideoUI()
        } else {
            showAudioUI()
            setupAudioUI()
        }
    }
    
    /**
     * 检查屏幕尺寸，启用简化模式
     */
    private fun checkScreenSize() {
        isTinyScreen = ScreenAdaptHelper.isTinyScreen(this)
    }

    private fun setupAudioUI() {
        setupToolbar()
        setupAudioConnectionInfo()
        setupAudioRecyclerView()
        setupAudioControls()
    }

    private fun setupVideoUI() {
        setupVideoRecyclerView()
        updateConnectState()

        cameraManager = VideoChatCameraManager(
            this,
            binding.videoChat.previewView
        ) { imgMsg ->
            onImageCaptured(imgMsg)
        }

        binding.videoChat.fabEndCall.setOnClickListener {
            stopRecording()
            audioController?.stopPlay()
            stopChat()
        }

        binding.videoChat.fabSwitchCamera.setOnClickListener {
            cameraManager?.switchCamera()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initClient()
        ensurePermissionsAndStart()
        bindCollector()
    }

    abstract fun initClient()
    abstract fun startChat()
    abstract fun stopChat()
    abstract fun onAudioData(audioData: ByteArray, sampleRate: Int, channels: Int)
    abstract fun onImageCaptured(imgMsg: ImageMessage)

    // 通话相关抽象方法，子类需要实现
    abstract fun sendDeviceAnswerMessage(roomId: String)
    abstract fun sendDeviceRejectMessage(roomId: String)
    abstract fun sendDeviceHangupForIncomingMessage(roomId: String)
    abstract fun sendDeviceHangupForOutgoingMessage()

    protected fun updateConnectState() {
        // 更新状态显示
        lifecycleScope.launch {
            if (isVideoMode) {
                with(binding.videoChat) {
                    if (isConnected) {
                        chipConnectionStatus.text = getString(R.string.connected)
                        chipConnectionStatus.chipIcon =
                            AppCompatResources.getDrawable(this@BaseChatActivity, R.drawable.ic_connected)
                        chipConnectionStatus.chipBackgroundColor =
                            ContextCompat.getColorStateList(this@BaseChatActivity, R.color.success_green)
                    } else {
                        chipConnectionStatus.text = getString(R.string.disconnected)
                        chipConnectionStatus.chipIcon =
                            AppCompatResources.getDrawable(this@BaseChatActivity, R.drawable.ic_disconnected)
                        chipConnectionStatus.chipBackgroundColor =
                            ContextCompat.getColorStateList(this@BaseChatActivity, R.color.error_red)
                    }
                }
            } else {
                with(binding) {
                    if (isConnected) {
                        chipConnectionStatus.text = getString(R.string.connected)
                        chipConnectionStatus.chipIcon =
                            AppCompatResources.getDrawable(this@BaseChatActivity, R.drawable.ic_connected)
                        chipConnectionStatus.chipBackgroundColor =
                            ContextCompat.getColorStateList(this@BaseChatActivity, R.color.success_green)
                    } else {
                        chipConnectionStatus.text = getString(R.string.disconnected)
                        chipConnectionStatus.chipIcon =
                            AppCompatResources.getDrawable(this@BaseChatActivity, R.drawable.ic_disconnected)
                        chipConnectionStatus.chipBackgroundColor =
                            ContextCompat.getColorStateList(this@BaseChatActivity, R.color.error_red)
                    }
                }
            }
        }
    }

    private var rotationAnimation: Animation? = null

    protected fun showLoading(isShow: Boolean, tips: String = getString(R.string.connecting)) {
        lifecycleScope.launch {
            if (isShow) {
                binding.loading.tvLoadingText.text = tips
                binding.loadingLayout.isVisible = true
                binding.loadingLayout.bringToFront()

                rotationAnimation = AnimationUtils.loadAnimation(
                    this@BaseChatActivity,
                    R.anim.rotate_loading
                ).also {
                    // 确保动画以ImageView中心为旋转点
                    binding.loading.ivLoading.pivotX = binding.loading.ivLoading.width.div(2f)
                    binding.loading.ivLoading.pivotY = binding.loading.ivLoading.height.div(2f)
                    binding.loading.ivLoading.startAnimation(it)
                }
            } else {
                rotationAnimation?.cancel()
                binding.loadingLayout.isVisible = false
            }
        }
    }

    private fun initAudioController() {
        // TODO 修改适配一下,如果是使用 TRTC 采集音频的情况
        if (isTRTCConnected()) {
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val audioConfig = if (audioFormatStr.equals("OPUS", true)) {
                    AudioConfig().apply {
                        formatType = AudioFormatType.OPUS
                    }
                } else {
                    AudioConfig()
                }

                audioController = TalkAudioController(this@BaseChatActivity, audioConfig).also { controller ->
                    controller.setOnRecordDataListener(object : OnRecordDataListener {
                        override fun onPcmData(data: ByteArray, size: Int) {
                            // PCM 数据回调（如果需要）
                        }

                        override fun onOpusData(data: ByteArray, size: Int) {
                            // Opus 数据回调
                            if (audioConfig.formatType == AudioFormatType.OPUS) {
                                onAudioData(data, audioConfig.sampleRate, audioConfig.channelCount)
                            }
                        }

                        override fun onRecordError(errorCode: Int, message: String) {
                            Log.e(TAG, "录音错误[$errorCode]: $message")
                        }
                    })

                    // 如果是 PCM 格式，需要在 onPcmData 中回调
                    if (audioConfig.formatType == AudioFormatType.PCM) {
                        controller.setOnRecordDataListener(object : OnRecordDataListener {
                            override fun onPcmData(data: ByteArray, size: Int) {
                                onAudioData(data, audioConfig.sampleRate, audioConfig.channelCount)
                            }

                            override fun onOpusData(data: ByteArray, size: Int) {}

                            override fun onRecordError(errorCode: Int, message: String) {
                                Log.e(TAG, "录音错误[$errorCode]: $message")
                            }
                        })
                    }

                    controller.init()
                    isAudioControllerInitialized = true
                    Log.d(TAG, "TalkAudioController 初始化完成")
                }
            } catch (e: Exception) {
                Log.e(TAG, "TalkAudioController init failed", e)

                lifecycleScope.launch(Dispatchers.Main) {
                    showToast("音频控制器初始化失败: ${e.message}")
                }
            }
        }
    }

    private fun loadConnectionInfo() {
        val bundle = intent.getBundleExtra(Constants.KEY_CHAT_BUNDLE)

        bundle?.run {
            connectionType = getString(Constants.KEY_CONNECTION_TYPE, "WEBSOCKET")
            audioFormatStr = getString(Constants.KEY_AUDIO_TYPE, "PCM") ?: "PCM"
        } ?: run {
            showToast("没有读取到配置")
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        
        binding.toolbar.setNavigationOnClickListener {
            AlertDialog.Builder(this@BaseChatActivity)
                .setTitle("结束对话")
                .setMessage("是否要结束对话？")
                .setPositiveButton("确定") { _,_ -> stopChat() }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun setupVideoRecyclerView() {
        messageAdapter = ChatMessageAdapter(true, isTinyScreen)

        binding.videoChat.recyclerViewMessages.apply {
            adapter = messageAdapter
            layoutManager = LinearLayoutManager(this@BaseChatActivity)
        }
    }

    private fun setupAudioRecyclerView() {
        messageAdapter = ChatMessageAdapter(isTinyScreen = isTinyScreen)

        binding.recyclerViewMessages.apply {
            adapter = messageAdapter
            layoutManager = LinearLayoutManager(this@BaseChatActivity)
        }
    }

    private fun setupAudioConnectionInfo() {
        updateConnectState()

        if (isTRTCConnected()) {
            binding.chipAudioFormat.isVisible = false
        }
        
        // 极小屏简化模式：
        // 1. 不展示音频控制区（无法手动停止录音）
        // 2. 不展示 statusBar
        // 3. 隐藏对话头像
        // 4. UI 尺寸数值调整
        if (isTinyScreen) {
            binding.statusBar.visibility = View.GONE
            binding.audioControlPanel.visibility = View.GONE
        }

        binding.chipAudioFormat.text = audioFormatStr
        binding.btnEndChat.setOnClickListener {
            stopChat()
        }
    }

    private fun setupAudioControls() {
        setupRecordButton()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRecordButton() {
        if (isPushToTalkMode) {
            // 按键说话模式：按住录音，松开停止
            binding.fabRecord.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!isRecording) {
                            startRecording()
                        }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isRecording) {
                            stopRecording()
                        }
                        true
                    }

                    else -> false
                }
            }
            
            // 更新提示文本
            binding.tvRecordHint.text = getString(R.string.hold_to_speak)
        } else {
            // 非按键说话模式：点击切换录音状态
            binding.fabRecord.setOnClickListener {
                if (isRecording) {
                    stopRecording()
                } else {
                    startRecording()
                }
            }
            
            // 更新提示文本
            binding.tvRecordHint.text = getString(R.string.start_recording)
        }
    }

    private fun bindCollector() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ConversationManager.messages.collect(this@BaseChatActivity::onMessageUpdate)
                }

                launch {
                    ConversationManager.assistantTyping.collect(this@BaseChatActivity::onLLMTyping)
                }
            }
        }
    }

    private fun showAudioUI() {
        with(binding) {
            videoChatLayout.visibility = View.GONE
            toolbar.visibility = View.VISIBLE
            statusBar.visibility = View.VISIBLE
            recyclerViewMessages.visibility = View.VISIBLE

            if (isTRTCConnected()) {
                audioControlPanel.visibility = View.GONE
            } else {
                audioControlPanel.visibility = View.VISIBLE
            }
        }
    }

    private fun showVideoUI() {
        with(binding) {
            videoChatLayout.visibility = View.VISIBLE
            toolbar.visibility = View.GONE
            statusBar.visibility = View.GONE
            recyclerViewMessages.visibility = View.GONE
            audioControlPanel.visibility = View.GONE
        }
    }

    private fun onMessageUpdate(messageList: List<ChatMessage>) {
        val lastPosition = messageList.size - 1

        messageAdapter.submitList(messageList) {
            if (lastPosition >= 0) {
                val lastMessage = messageList[lastPosition]

                // bot 消息流式输出时在开始输出和结束输出时滑动
                if ((lastMessage.status != MessageStatus.STREAMING) || (lastMessage.content == "")) {
                    val rv = if (isVideoMode) binding.videoChat.recyclerViewMessages else binding.recyclerViewMessages
                    rv.smoothScrollToPosition(lastPosition)
                }
            }
        }
    }

    private fun onLLMTyping(isTyping: Boolean) {
        Log.d(TAG, "collect typing: $isTyping")

        if (!isVideoMode) {
            if (isTyping) {
                binding.tvAudioStatus.text = getString(R.string.processing)
                // 极小屏简化模式：不显示音频波形区域
                if (!isTinyScreen) {
                    binding.audioVisualizerContainer.visibility = View.VISIBLE
                }
            } else {
                binding.audioVisualizerContainer.visibility = View.GONE
            }
        }
    }

    private fun ensurePermissionsAndStart() {
        val requiredPermissions = if (isVideoMode) {
            PermissionHelper.VIDEO_MODE_PERMISSIONS
        } else {
            PermissionHelper.AUDIO_MODE_PERMISSIONS
        }
        
        if (PermissionHelper.hasPermissions(this, requiredPermissions)) {
            startChat()
            initAudioController()
        } else {
            // 请求缺失的权限
            val missingPermissions = PermissionHelper.getMissingPermissions(this, requiredPermissions)
            reqPermissions.launch(missingPermissions.toTypedArray())
        }
    }

    protected fun startRecording() {
        if (isRecording) {
            return
        }

        if (isAudioControllerInitialized) {
            performRecording()
            return
        }

        lifecycleScope.launch {
            // 如果没有完成初始化则等待
            withContext(Dispatchers.IO) {
                val startTime = System.currentTimeMillis()
                val timeout = 5000L // 5秒超时

                while (!isAudioControllerInitialized && (System.currentTimeMillis() - startTime) < timeout) {
                    Thread.sleep(50)
                }

                if (!isAudioControllerInitialized) {
                    Log.e(TAG, "TalkAudioController initialization timeout")

                    withContext(Dispatchers.Main) {
                        showToast("音频控制器初始化超时，请重试")
                    }

                    return@withContext
                }
            }

            withContext(Dispatchers.Main) {
                performRecording()
            }
        }
    }

    private fun performRecording() {
        if (audioController == null) {
            Log.e(TAG, "TalkAudioController is null")
            showToast("音频控制器未就绪，请重试")
            return
        }
        
        if (isRecording) {
            return
        }

        isRecording = true
        audioController?.startRecord()

        if (!isVideoMode && isNotInCall()) {
            lifecycleScope.launch {
                updateRecordingUI(true)
                if (isPushToTalkMode) {
                    binding.tvRecordHint.text = getString(R.string.release_to_send)
                } else {
                    binding.tvRecordHint.text = getString(R.string.stop_recording)
                }

                animateRecording()
            }
        }
    }

    protected fun stopRecording() {
        if (!isRecording) {
            return
        }
        
        isRecording = false
        audioController?.stopRecord()

        if (!isVideoMode && isNotInCall()) {
            lifecycleScope.launch {
                updateRecordingUI(false)
                if (isPushToTalkMode) {
                    binding.tvRecordHint.text = getString(R.string.hold_to_speak)
                } else {
                    binding.tvRecordHint.text = getString(R.string.start_recording)
                }
                binding.tvAudioStatus.text = getString(R.string.processing)
            }
        }
    }

    private fun updateRecordingUI(recording: Boolean) {
        if (recording) {
            binding.fabRecord.backgroundTintList = 
                ContextCompat.getColorStateList(this, R.color.error_red)
            binding.fabRecord.setImageResource(R.drawable.ic_mic_recording)
        } else {
            binding.fabRecord.backgroundTintList = 
                ContextCompat.getColorStateList(this, R.color.primary_blue)
            binding.fabRecord.setImageResource(R.drawable.ic_mic)
        }
    }

    private fun animateRecording() {
        // 简单的录音动画效果
        if (isRecording) {
            binding.tvAudioStatus.alpha = if (binding.tvAudioStatus.alpha == 1f) 0.5f else 1f
            handler.postDelayed({ animateRecording() }, 500)
        } else {
            binding.tvAudioStatus.alpha = 1f
        }
    }

    /**
     * 处理音频数据回调
     */
    protected fun handleRecvAudio(audio: ByteArray, sampleRate: Int, channels: Int, format: AudioFormat) {
        // 如果来电或呼叫，先不播放 AI 音频
        if (isCalling) return

        val sr = if (sampleRate > 0) sampleRate else 16000
        val ch = if (channels > 0) channels else 1
        val isPcm = format == AudioFormat.PCM
        val formatType = if (isPcm) AudioFormatType.PCM else AudioFormatType.OPUS
        audioController?.play(audio, sr, ch, formatType)
    }

    /**
     * 处理对话消息回调
     */
    protected fun handleRecvTalkMessage(type: TWeTalkMessage.TWeTalkMessageType, text: String?) {
        when (type) {
            TWeTalkMessage.TWeTalkMessageType.BOT_READY -> {}
            TWeTalkMessage.TWeTalkMessageType.ERROR -> {}
            TWeTalkMessage.TWeTalkMessageType.REQUEST_IMAGE -> {
                // 服务端请求图片，捕获相机并发送
                cameraManager?.captureImage()
            }

            TWeTalkMessage.TWeTalkMessageType.USER_LLM_TEXT -> {
                // 打断机器人的话
                ConversationManager.interruptAssistant()
                audioController?.stopPlay()
                // 通知用户对话
                ConversationManager.onUserLLMText(text ?: "")
            }

            TWeTalkMessage.TWeTalkMessageType.BOT_LLM_STARTED -> {
                ConversationManager.onBotLLMStarted()
            }

            TWeTalkMessage.TWeTalkMessageType.BOT_LLM_TEXT -> {
                ConversationManager.onBotLLMText(text ?: "")
            }

            TWeTalkMessage.TWeTalkMessageType.BOT_LLM_STOPPED -> {
                ConversationManager.onBotLLMStopped()
            }

            TWeTalkMessage.TWeTalkMessageType.USER_STARTED_SPEAKING -> {
                Log.d("Metric", "User start speaking...")
            }

            TWeTalkMessage.TWeTalkMessageType.USER_STOPPED_SPEAKING -> {
                Log.d("Metric", "User stop speaking.")
            }

            TWeTalkMessage.TWeTalkMessageType.BOT_STARTED_SPEAKING -> {
                Log.d("Metric", "Bot start speaking...")
            }

            TWeTalkMessage.TWeTalkMessageType.BOT_STOPPED_SPEAKING -> {
                Log.d("Metric", "Bot stop speaking.")
            }

            TWeTalkMessage.TWeTalkMessageType.BOT_TRANSCRIPTION -> {
                // 暂不处理
            }

            // 其余消息根据情况处理
            TWeTalkMessage.TWeTalkMessageType.USER_TRANSCRIPTION,
            TWeTalkMessage.TWeTalkMessageType.BOT_TTS_TEXT,
            TWeTalkMessage.TWeTalkMessageType.BOT_TTS_STARTED,
            TWeTalkMessage.TWeTalkMessageType.BOT_TTS_STOPPED -> {
                // 暂不处理
            }
        }
    }

    /**
     * 处理通话消息回调
     */
    protected fun handleRecvCallMessage(
        stream: CallStream,
        subType: CallSubType,
        data: TweCallMessage.TweCallData
    ) {
        Log.d(TAG, "handleRecvCallMessage: stream=$stream, subType=$subType, data=$data")

        when (stream) {
            CallStream.DEVICE_TO_USER -> {
                // 设备呼叫小程序的响应
                handleDeviceToUserMessage(subType, data)
            }
            CallStream.USER_TO_DEVICE -> {
                // 小程序呼叫设备的响应 (通过 WebSocket)
                handleUserToDeviceMessage(subType, data)
            }
        }
    }

    /**
     * 处理设备呼叫小程序时收到的消息
     */
    private fun handleDeviceToUserMessage(subType: CallSubType, data: TweCallMessage.TweCallData) {
        when (subType) {
            CallSubType.USER_CALLING -> {
                if (isNotInCall()) {
                    // 正在呼叫，跳转到呼叫页面
                    currentCallType = CallType.OUTGOING
                    currentCallOpenId = data.openId
                    currentCallNickname = data.called
                    updateCallState(CallState.CALLING)
                    launchCallActivity(CallType.OUTGOING, data.called ?: "", data.openId ?: "", "")
                }
            }

            CallSubType.USER_ANSWERED -> {
                // 小程序已接听
                startRecording()
                updateCallState(CallState.IN_PROGRESS)
            }

            CallSubType.USER_REJECT -> {
                // 小程序拒接
                updateCallState(CallState.REJECTED)
            }

            CallSubType.USER_TIMEOUT -> {
                // 呼叫超时
                updateCallState(CallState.TIMEOUT)
            }

            CallSubType.USER_BUSY -> {
                // 小程序占线
                updateCallState(CallState.BUSY)
            }

            CallSubType.USER_ERROR -> {
                // 呼叫出错
                updateCallState(CallState.ERROR)
            }

            CallSubType.USER_HANGUP -> {
                // 小程序挂断
                updateCallState(CallState.ENDED)
            }
        }
    }

    /**
     * 处理小程序呼叫设备时收到的消息 (通过 WebSocket)
     */
    private fun handleUserToDeviceMessage(subType: CallSubType, data: TweCallMessage.TweCallData) {
        when (subType) {
            CallSubType.USER_HANGUP -> {
                // 小程序挂断
                updateCallState(CallState.ENDED)
            }

            CallSubType.USER_ANSWERED -> {
                // 小程序呼叫设备 -> 设备同意时，发现也会同步这条消息
                updateCallState(CallState.IN_PROGRESS)
            }

            else -> {
                // 其他类型暂不处理
            }
        }
    }

    /**
     * 处理来电 (通过 MQTT)
     */
    protected fun handleIncomingCall(roomId: String, openId: String) {
        // 检查是否正在通话中
        if (!isNotInCall()) {
            // 设备占线，发送拒接消息
            sendDeviceRejectMessage(roomId)
            return
        }

        // 查找昵称
        val nickname = CallConfigManager.findNicknameByOpenId(this, openId) ?: ""

        currentCallType = CallType.INCOMING
        currentCallOpenId = openId
        currentCallNickname = nickname
        currentCallRoomId = roomId
        updateCallState(CallState.INCOMING)

        runOnUiThread {
            launchCallActivity(CallType.INCOMING, nickname, openId, roomId)
        }
    }

    /**
     * 处理小程序取消呼叫 (通过 MQTT)
     */
    protected fun handleCallCancelled(roomId: String?) {
        if (roomId == currentCallRoomId) {
            updateCallState(CallState.ENDED)
        }
    }

    /**
     * 启动通话页面
     */
    private fun launchCallActivity(callType: CallType, nickname: String, openId: String, roomId: String) {
        val intent = Intent(this, WxCallActivity::class.java).apply {
            putExtra(Constants.KEY_CALL_BUNDLE, Bundle().apply {
                putString(Constants.KEY_CALL_TYPE, if (callType == CallType.INCOMING) "incoming" else "outgoing")
                putString(Constants.KEY_CALL_NICKNAME, nickname)
                putString(Constants.KEY_CALL_OPEN_ID, openId)
                putString(Constants.KEY_CALL_ROOM_ID, roomId)
            })
        }
        callActivityLauncher.launch(intent)
    }

    /**
     * 更新通话状态
     */
    protected fun updateCallState(state: CallState) {
        WxCallManager.updateCallState(state, currentCallRoomId)
        Log.d(TAG, "updateCallState: $state")

        when (state) {
            CallState.IDLE -> {}

            CallState.CALLING, CallState.INCOMING -> {
                isCalling = true
                isInProgress = false
            }

            CallState.IN_PROGRESS -> {
                isCalling = false
                isInProgress = true
            }

            CallState.REJECTED, CallState.TIMEOUT,
            CallState.BUSY, CallState.ERROR, CallState.ENDED -> {
                stopRecording()
                isCalling = false
                isInProgress = false
            }
        }
    }

    /**
     * 监听通话操作 (从 WxCallActivity 发送)
     */
    protected fun observeCallActions() {
        lifecycleScope.launch {
            WxCallManager.callActionFlow.collect { event ->
                handleCallAction(event.action, event.roomId)
            }
        }
    }

    /**
     * 处理通话操作
     */
    private fun handleCallAction(action: CallAction, roomId: String?) {
        when (action) {
            CallAction.ANSWER -> {
                // 接听来电
                if (roomId != null) {
                    startRecording()
                    sendDeviceAnswerMessage(roomId)
                }
            }

            CallAction.REJECT -> {
                // 拒接来电
                if (roomId != null) {
                    sendDeviceRejectMessage(roomId)
                }
            }

            CallAction.HANGUP -> {
                // 挂断
                if (currentCallType == CallType.INCOMING) {
                    if (roomId != null) {
                        sendDeviceHangupForIncomingMessage(roomId)
                    }
                } else {
                    sendDeviceHangupForOutgoingMessage()
                }

                stopRecording()
            }

            CallAction.MUTE -> {
                stopRecording()
            }

            CallAction.UNMUTE -> {
                startRecording()
            }
        }
    }

    protected fun isNotInCall(): Boolean = !isCalling && !isInProgress

    protected fun isTRTCConnected(): Boolean = connectionType == "TRTC"

    private fun releaseInternal() {
        isAudioControllerInitialized = false
        audioController?.release()
        audioController = null
        cameraManager?.release()
        cameraManager = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        ConversationManager.clearMessage()
        releaseInternal()
    }
}
