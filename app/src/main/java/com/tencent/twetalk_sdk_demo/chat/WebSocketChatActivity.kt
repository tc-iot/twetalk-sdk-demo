package com.tencent.twetalk_sdk_demo.chat

import android.util.Log
import androidx.core.content.edit
import com.alibaba.fastjson2.JSON
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
import com.tencent.twetalk.protocol.FrameProcessor
import com.tencent.twetalk.protocol.ImageMessage
import com.tencent.twetalk.protocol.TWeTalkMessage
import com.tencent.twetalk.protocol.TweCallMessage
import com.tencent.twetalk_sdk_demo.R
import com.tencent.twetalk_sdk_demo.call.CallConfigManager
import com.tencent.twetalk_sdk_demo.data.Constants

class WebSocketChatActivity : BaseChatActivity(), TWeTalkClientListener {
    companion object {
        private val TAG = WebSocketChatActivity::class.simpleName
    }

    private lateinit var client: TWeTalkClient
    private lateinit var config: TWeTalkConfig
    private lateinit var mqttCallback: MqttManager.MqttConnectionCallback

    override fun initClient() {
        val bundle = intent.getBundleExtra(Constants.KEY_CHAT_BUNDLE)

        if (bundle == null) {
            showToast("没有读取到连接配置")
            finish()
        }

        val productId = bundle?.getString(Constants.KEY_PRODUCT_ID, "")
        val deviceName = bundle?.getString(Constants.KEY_DEVICE_NAME, "")
        val audioType = bundle?.getString(Constants.KEY_AUDIO_TYPE, "PCM")
        val language = bundle?.getString(Constants.KEY_LANGUAGE, "zh")
        val botId = bundle?.getString(Constants.KEY_BOT_ID, "")

        mqttCallback = object : MqttManager.MqttConnectionCallback {
            override fun onConnected() {
                // nothing to do
            }

            override fun onDisconnected(cause: Throwable?, isManual: Boolean) {
                // 非主动断开连接时提示报错
                if (!isManual) {
                    showToast("设备已断开连接，请尝试重新连接！")
                    finish()
                }
            }

            override fun onConnectFailed(cause: Throwable?) {
                // nothing to do
            }

            override fun onMessageReceived(
                topic: String?,
                method: String?,
                params: Map<String?, Any?>?
            ) {
                when (method) {
                    MqttManager.REPLY_QUERY_WEBSOCKET_URL -> {
                        val authConfig = TWeTalkConfig.AuthConfig(
                            productId,
                            deviceName,
                            params!!["token"] as String,
                            audioType,
                            language
                        ).apply {
                            val url = params["websocket_url"] as String

                            baseUrl = if (isVideoMode) {
                                "${url}_vl"
                            } else {
                                url
                            }
                            
                            // 设置 botId（如果不为空）
                            if (!botId.isNullOrEmpty()) {
                                setBotId(botId)
                            }
                        }

                        config = TWeTalkConfig.builder()
                            .authConfig(authConfig)
                            .isMetricOpen(true)
                            .build()

                        client = DefaultTWeTalkClient(config)
                        client.addListener(this@WebSocketChatActivity)
                        client.connect()
                    }

                    MqttManager.RECEIVED_VOIP_JOIN -> {
                        // 小程序呼叫设备 (来电)
                        val roomId = params?.get("roomId") as? String ?: return
                        val openId = params["openId"] as? String ?: ""
                        handleIncomingCall(roomId, openId)
                    }

                    MqttManager.RECEIVED_VOIP_CANCEL -> {
                        // 小程序取消呼叫
                        val roomId = params?.get("roomId") as? String
                        handleCallCancelled(roomId)
                    }
                }
            }
        }

        mqttManager?.callback = mqttCallback
    }

    override fun startChat() {
        val params = mapOf (
            "connect_type" to MqttManager.WebSocketConnectType.TALK.value,
            "language" to "en"
        )

        mqttManager?.queryWebSocketUrl(params)
    }

    override fun stopChat() {
        mqttManager?.callback = null
        client.disconnect()
    }

    override fun onAudioData(audioData: ByteArray, sampleRate: Int, channels: Int) {
        if (!isCalling) {
            client.sendCustomAudioData(audioData, sampleRate, channels)
        }
    }

    override fun onImageCaptured(imgMsg: ImageMessage) {
        if (isNotInCall()) {
            client.sendImage(imgMsg)
        }
    }

    // ====================== TWeTalkClientListener 实现 ====================== //

    override fun onStateChanged(state: ConnectionState) {
        when (state) {
            ConnectionState.IDLE -> {}
            ConnectionState.CONNECTING -> showLoading(true)

            ConnectionState.CONNECTED -> {
                showLoading(false)
                isConnected = true
                updateConnectState()

                if (isVideoMode) {
                    startRecording()
                    cameraManager?.startCamera()
                } else {
                    // 音频模式：非按键说话模式下自动开始录音
                    if (!isPushToTalkMode) {
                        startRecording()
                    }
                }

                if (!isVideoMode) {
                    // 添加欢迎消息
                    ConversationManager.onSystemMessage("连接已建立，开始对话")
                }

                // 保存参数
                saveConfig()

                // 发送通话配置信息
                sendTweCallConfig()

                // 监听通话操作
                observeCallActions()
            }

            ConnectionState.RECONNECTING -> showLoading(true, getString(R.string.reconnecting))
            ConnectionState.CLOSING -> showLoading(true, getString(R.string.closing))

            ConnectionState.CLOSED -> {
                isConnected = false
                updateConnectState()
                finish()
            }
        }
    }

    override fun onRecvAudio(audio: ByteArray, sampleRate: Int, channels: Int, format: AudioFormat) {
        handleRecvAudio(audio, sampleRate, channels, format)
    }

    override fun onRecvTalkMessage(type: TWeTalkMessage.TWeTalkMessageType, text: String?) {
        handleRecvTalkMessage(type, text)
    }

    override fun onRecvCallMessage(
        stream: CallStream,
        subType: CallSubType,
        data: TweCallMessage.TweCallData
    ) {
        handleRecvCallMessage(stream, subType, data)
    }

    override fun onMetrics(metrics: MetricEvent?) {
        if (metrics?.type == MetricEvent.Type.RTT) {
            Log.d(TAG, "onMetrics: $metrics")
        }
    }

    override fun onError(error: Throwable?) {
        Log.e(TAG, "onError", error)
        ConversationManager.onSystemMessage("连接出现错误，对话已结束")
    }

    // ====================== 通话消息发送 ====================== //

    override fun sendDeviceAnswerMessage(roomId: String) {
        val deviceId = CallConfigManager.getDeviceId(this)
        val msg = FrameProcessor.buildTweCallDeviceAnswerMsg(roomId, deviceId)
        client.sendCustomMsg(msg)
    }

    override fun sendDeviceRejectMessage(roomId: String) {
        val deviceId = CallConfigManager.getDeviceId(this)
        val msg = FrameProcessor.buildTweCallDeviceRejectMsg(roomId, deviceId)
        client.sendCustomMsg(msg)
    }

    override fun sendDeviceHangupForIncomingMessage(roomId: String) {
        val deviceId = CallConfigManager.getDeviceId(this)
        val msg = FrameProcessor.buildTweCallDeviceHangupForIncomingMsg(roomId, deviceId)
        client.sendCustomMsg(msg)
    }

    override fun sendDeviceHangupForOutgoingMessage() {
        val msg = FrameProcessor.buildTweCallDeviceHangupForOutgoingMsg()
        client.sendCustomMsg(msg)
    }

    // ====================== 私有方法 ====================== //

    override fun onDestroy() {
        super.onDestroy()
        client.close()
    }

    private fun saveConfig() {
        // 绑定的设备信息
        getSharedPreferences(Constants.KEY_DEVICE_INFO_PREF, MODE_PRIVATE).edit {
            putString(Constants.KEY_PRODUCT_ID, config.authConfig.productId)
            putString(Constants.KEY_DEVICE_NAME, config.authConfig.deviceName)
        }

        // 其它连接参数信息
        getSharedPreferences(Constants.KEY_CONNECT_PARAMS_PREF, MODE_PRIVATE).edit {
            putString(Constants.KEY_AUDIO_TYPE, config.authConfig.audioType)
            putString(Constants.KEY_LANGUAGE, config.authConfig.language)
            putBoolean(Constants.KEY_VIDEO_MODE, isVideoMode)
        }
    }

    /**
     * 发送通话配置信息 (基本通话信息 + 通讯录)
     */
    private fun sendTweCallConfig() {
        try {
            // 1. 发送基本通话信息
            val wxaAppId = CallConfigManager.getWxaAppId(this)
            val wxaModelId = CallConfigManager.getWxaModelId(this)
            val deviceId = CallConfigManager.getDeviceId(this)

            val basicMsg = FrameProcessor.buildTweCallBasicMsg(wxaAppId, wxaModelId, deviceId)
            client.sendCustomMsg(basicMsg)
            Log.d(TAG, "sendTweCallConfig: basic msg sent")

            // 2. 发送通讯录
            val openIdsList = CallConfigManager.buildOpenIdsList(this)
            if (openIdsList.isNotEmpty()) {
                val openIdsJson = JSON.toJSONString(openIdsList)
                val openIdsMsg = FrameProcessor.buildTweCallOpenidsMsg(openIdsJson)
                client.sendCustomMsg(openIdsMsg)
                Log.d(TAG, "sendTweCallConfig: openids msg sent, count=${openIdsList.size}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendTweCallConfig error", e)
        }
    }
}
