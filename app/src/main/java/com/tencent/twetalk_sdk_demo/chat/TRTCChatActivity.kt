package com.tencent.twetalk_sdk_demo.chat

import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.tencent.twetalk.metrics.MetricEvent
import com.tencent.twetalk.protocol.AudioFormat
import com.tencent.twetalk.protocol.CallStream
import com.tencent.twetalk.protocol.CallSubType
import com.tencent.twetalk.protocol.ImageMessage
import com.tencent.twetalk.protocol.TWeTalkMessage
import com.tencent.twetalk.protocol.TweCallMessage
import com.tencent.twetalk_sdk_demo.R
import com.tencent.twetalk_sdk_demo.data.Constants
import com.tencent.twetalk_sdk_trtc.config.TRTCConfig
import com.tencent.twetalk_sdk_trtc.core.DefaultTRTCClient
import com.tencent.twetalk_sdk_trtc.core.TRTCClientListener
import com.tencent.twetalk_sdk_trtc.core.TRTCClientState
import com.tencent.twetalk_sdk_trtc.core.TWeTalkTRTCClient

class TRTCChatActivity: BaseChatActivity(), TRTCClientListener {
    companion object {
        private val TAG = TRTCChatActivity::class.simpleName
    }

    private lateinit var client: TWeTalkTRTCClient
    private lateinit var config: TRTCConfig

    override fun initClient() {
        val bundle = intent.getBundleExtra(Constants.KEY_CHAT_BUNDLE)

        if (bundle == null) {
            showToast("没有读取到连接配置")
            finish()
            return
        }

        val language = bundle.getString(Constants.KEY_LANGUAGE, "zh")

        // 从 SharedPreferences 获取设备三元组
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val productId = prefs.getString(Constants.KEY_PRODUCT_ID, "") ?: ""
        val deviceName = prefs.getString(Constants.KEY_DEVICE_NAME, "") ?: ""
        val deviceSecret = prefs.getString(Constants.KEY_DEVICE_SECRET, "") ?: ""

        if (productId.isEmpty() || deviceName.isEmpty() || deviceSecret.isEmpty()) {
            showToast("设备信息不完整，请先绑定设备")
            finish()
            return
        }

        // 使用设备三元组初始化配置
        config = TRTCConfig(applicationContext)
        config.productId = productId
        config.deviceName = deviceName
        config.deviceSecret = deviceSecret
        config.language = language
        config.useTRTCRecord = true

        client = DefaultTRTCClient(config)
        client.addListener(this)
    }

    override fun startChat() {
        // 直接调用 startConversation，SDK 内部会通过 HTTP 获取房间信息
        client.startConversation()
    }

    override fun stopChat() {
        client.stopConversation()
    }

    override fun onAudioData(
        audioData: ByteArray,
        sampleRate: Int,
        channels: Int
    ) {
        if (!config.useTRTCRecord) {
            client.sendCustomAudioData(audioData, sampleRate, channels)
        }
    }

    override fun onImageCaptured(imgMsg: ImageMessage) {
        throw UnsupportedOperationException("TRTC does not support sending image.")
    }

    // TRTC 模式暂不支持通话功能
    override fun sendDeviceAnswerMessage(roomId: String) {}
    override fun sendDeviceRejectMessage(roomId: String) {}
    override fun sendDeviceHangupForIncomingMessage(roomId: String) {}
    override fun sendDeviceHangupForOutgoingMessage() {}

    override fun onStateChanged(state: TRTCClientState?) {
        when (state) {
            TRTCClientState.IDLE -> {}
            TRTCClientState.ENTERING -> showLoading(true, getString(R.string.entering))
            TRTCClientState.ENTERED -> showLoading(false)
            TRTCClientState.LEAVING -> showLoading(true, getString(R.string.leaving))

            TRTCClientState.LEAVED -> {
                isConnected = false
                updateConnectState()
                finish()
            }

            TRTCClientState.WAITING ->
                ConversationManager.onSystemMessage("已进入房间，等待对方进入...")

            TRTCClientState.ON_CALLING -> {
                isConnected = true
                updateConnectState()
                ConversationManager.onSystemMessage("对方已进入房间，开始对话")
                saveConfig()
            }

            null -> Log.e(TAG, "onStateChanged, unexpected state")
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

    override fun onError(errCode: Int, errMsg: String?) {
        Log.e(TAG, "onError, errCode: $errCode, errMsg: $errMsg")
        runOnUiThread {
            showLoading(false)
            ConversationManager.onSystemMessage("连接出现错误：$errMsg")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        client.destroy()
    }

    private fun saveConfig() {
        // 其它连接参数信息
        getSharedPreferences(Constants.KEY_CONNECT_PARAMS_PREF, MODE_PRIVATE).edit {
            putString(Constants.KEY_LANGUAGE, config.language)
        }
    }
}