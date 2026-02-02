package com.tencent.twetalk_sdk_demo

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager.getDefaultSharedPreferences
import com.google.android.material.card.MaterialCardView
import com.tencent.twetalk.core.TWeTalkConfig
import com.tencent.twetalk.mqtt.MqttManager
import com.tencent.twetalk_sdk_demo.call.CallConfigActivity
import com.tencent.twetalk_sdk_demo.call.CallConfigManager
import com.tencent.twetalk_sdk_demo.call.WxCallOnlyActivity
import com.tencent.twetalk_sdk_demo.chat.TRTCChatActivity
import com.tencent.twetalk_sdk_demo.chat.WebSocketChatActivity
import com.tencent.twetalk_sdk_demo.data.Constants
import com.tencent.twetalk_sdk_demo.databinding.ActivityMainBinding

class MainActivity : BaseActivity<ActivityMainBinding>() {
    private var selectedLanguage = "zh"
    private var connectionType = TWeTalkConfig.TransportType.WEBSOCKET

    private val mqttCallback = object : MqttManager.MqttConnectionCallback {
        override fun onConnected() {
            runOnUiThread {
                updateMqttStatus(true)
                enableConnect(true)
                showToast(getString(R.string.mqtt_connected_success))
            }
        }

        override fun onDisconnected(cause: Throwable?, isManual: Boolean) {
            Log.w(TAG, "MQTT disconnected, isManual=$isManual", cause)

            runOnUiThread {
                updateMqttStatus(false)
                enableConnect(false)
                showToast(getString(R.string.mqtt_disconnected))
            }
        }

        override fun onConnectFailed(cause: Throwable?) {
            runOnUiThread {
                updateMqttStatus(false)
                enableConnect(false)
                val errorMsg = cause?.message ?: getString(R.string.mqtt_connect_failed)
                showToast(getString(R.string.mqtt_connect_failed_with_reason, errorMsg))
            }
        }

        override fun onMessageReceived(
            topic: String,
            method: String,
            params: Map<String, Any>
        ) {
            Log.d(TAG, "MQTT message received, topic: $topic, method: $method")
            // 处理 MQTT 在线但 WebSocket 离线时的来电
            when (method) {
                MqttManager.RECEIVED_VOIP_JOIN -> {
                    val roomId = params["roomId"] as? String ?: return
                    val openId = params["openId"] as? String ?: ""
                    handleIncomingCallFromMqtt(roomId, openId)
                }
            }
        }
    }

    override fun getViewBinding() = ActivityMainBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 检查是否已绑定设备
        if (!isDeviceBound()) {
            navigateToDeviceBind()
            return
        }
    }

    override fun initView() {
        setupToolbar()
        setupLanguageSelection()
        setupConnectionTypeSelection()
        setupConnectButton()
        setupNavigationButtons()
        loadDefaultConfig()
        
        // 先注册监听器，再尝试连接
        observeMqttStatus()
        setupDeviceInfo()
    }

    override fun onResume() {
        super.onResume()
        mqttManager?.callback = mqttCallback
    }

    /**
     * 检查是否已绑定设备
     */
    private fun isDeviceBound(): Boolean {
        val prefs = getDefaultSharedPreferences(this)
        val productId = prefs.getString(Constants.KEY_PRODUCT_ID, null)
        val deviceName = prefs.getString(Constants.KEY_DEVICE_NAME, null)
        val deviceSecret = prefs.getString(Constants.KEY_DEVICE_SECRET, null)
        
        return !productId.isNullOrEmpty() && !deviceName.isNullOrEmpty() && !deviceSecret.isNullOrEmpty()
    }

    /**
     * 跳转到设备绑定界面
     */
    private fun navigateToDeviceBind() {
        val intent = Intent(this, DeviceBindActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    /**
     * 设置设备信息显示和状态
     */
    private fun setupDeviceInfo() {
        val prefs = getDefaultSharedPreferences(this)
        val productId = prefs.getString(Constants.KEY_PRODUCT_ID, "") ?: ""
        val deviceName = prefs.getString(Constants.KEY_DEVICE_NAME, "") ?: ""
        
        binding.tvDeviceInfo.text = getString(R.string.device_info_format, productId, deviceName)
        
        // 设置切换设备按钮
        binding.btnChangeDevice.setOnClickListener {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("切换设备")
                .setMessage("该操作将会使当前设备下线，是否继续？")
                .setPositiveButton("是") { _,_ ->
                    run {
                        mqttManager?.disconnect()
                        navigateToDeviceBind()
                    }
                }
                .setNegativeButton("否", null)
                .show()
        }
        
        // 自动登录：尝试连接 MQTT（如果未连接）
        if (mqttManager == null) {
            // MQTT 未初始化，提示用户重新绑定
            showToast(getString(R.string.mqtt_not_initialized))
            navigateToDeviceBind()
            return
        }

        if (!mqttManager!!.isConnected) {
            // 显示连接中状态
            showToast(getString(R.string.mqtt_connecting))
            mqttManager!!.connect()
        }
    }

    /**
     * 监听 MQTT 连接状态
     */
    private fun observeMqttStatus() {
        mqttManager?.callback = mqttCallback
        
        // 初始状态
        val isConnected = mqttManager?.isConnected ?: false
        updateMqttStatus(isConnected)
        enableConnect(isConnected)
    }

    /**
     * 更新 MQTT 连接状态显示
     */
    private fun updateMqttStatus(connected: Boolean) {
        with(binding.chipMqttStatus) {
            if (connected) {
                text = getString(R.string.mqtt_online)
                chipIcon = AppCompatResources.getDrawable(
                    this@MainActivity, 
                    R.drawable.ic_connected
                )
                chipBackgroundColor = ContextCompat.getColorStateList(
                    this@MainActivity, 
                    R.color.success_green
                )
            } else {
                text = getString(R.string.mqtt_offline)
                chipIcon = AppCompatResources.getDrawable(
                    this@MainActivity,
                    R.drawable.ic_disconnected
                )
                chipBackgroundColor = ContextCompat.getColorStateList(
                    this@MainActivity,
                    R.color.error_red
                )
            }
        }
    }

    /**
     * 启用/禁用连接功能
     */
    private fun enableConnect(enabled: Boolean) {
        binding.fabConnect.isEnabled = enabled
        binding.cardWebSocket.isEnabled = enabled
        binding.cardTRTC.isEnabled = enabled
        binding.cardAudioFormat.alpha = if (enabled) 1.0f else 0.5f
        binding.cardChatSettings.alpha = if (enabled) 1.0f else 0.5f
        
        if (!enabled) {
            binding.tvMqttHint.visibility = View.VISIBLE
        } else {
            binding.tvMqttHint.visibility = View.GONE
        }
    }

    private fun loadDefaultConfig() {
        with(binding) {
            getSharedPreferences(Constants.KEY_CONNECT_PARAMS_PREF, MODE_PRIVATE).run {
                val connectionTypeStr = this.getString(Constants.KEY_CONNECTION_TYPE, "WEBSOCKET")

                if (connectionTypeStr == "TRTC") {
                    selectConnectionType(TWeTalkConfig.TransportType.TRTC)
                } else {
                    selectConnectionType(TWeTalkConfig.TransportType.WEBSOCKET)
                }

                // WebSocket 其它参数反向渲染
                val audioType = this.getString(Constants.KEY_AUDIO_TYPE, "PCM")

                if (audioType == "opus") {
                    rbOpus.isChecked = true
                    rbPCM.isChecked = false
                } else {
                    rbOpus.isChecked = false
                    rbPCM.isChecked = true
                }

                val isVideoMode = this.getBoolean(Constants.KEY_VIDEO_MODE, false)
                switchVideoChat.isChecked = isVideoMode

                val isPushToTalk = this.getBoolean(Constants.KEY_PUSH_TO_TALK, false)
                switchPushToTalk.isChecked = isPushToTalk

                val language = this.getString(Constants.KEY_LANGUAGE, "zh")

                if (language == "en") {
                    spinnerLanguage.setSelection(1)
                } else {
                    spinnerLanguage.setSelection(0)
                }

                // 加载 botId
                val botId = this.getString(Constants.KEY_BOT_ID, "")
                etBotId.setText(botId)
            }
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
    }

    private fun setupLanguageSelection() {
        val languageValues = resources.getStringArray(R.array.language_values)
        
        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLanguage = languageValues[position]
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // 默认选择第一个选项（中文）
                selectedLanguage = languageValues[0]
            }
        }
    }

    private fun setupConnectionTypeSelection() {
        binding.cardWebSocket.setOnClickListener {
            selectConnectionType(TWeTalkConfig.TransportType.WEBSOCKET)
        }

        binding.cardTRTC.setOnClickListener {
            selectConnectionType(TWeTalkConfig.TransportType.TRTC)
        }
    }

    private fun selectConnectionType(type: TWeTalkConfig.TransportType) {
        connectionType = type
        
        // 重置所有卡片样式
        resetCardSelection(binding.cardWebSocket)
        resetCardSelection(binding.cardTRTC)
        
        // 设置选中的卡片样式
        when (type) {
            TWeTalkConfig.TransportType.WEBSOCKET -> {
                setCardSelected(binding.cardWebSocket)
                // WebSocket 模式下显示音频格式和聊天设置
                binding.cardAudioFormat.visibility = View.VISIBLE
                binding.cardChatSettings.visibility = View.VISIBLE
            }

            TWeTalkConfig.TransportType.TRTC -> {
                setCardSelected(binding.cardTRTC)
                
                // TRTC 模式下强制 PCM
                if (binding.rbOpus.isChecked) {
                    binding.rbOpus.isChecked = false
                    binding.rbPCM.isChecked = true
                }
                
                binding.cardAudioFormat.visibility = View.GONE
                
                // TRTC 连接时暂不支持视频聊天
                binding.switchVideoChat.isChecked = false
                binding.cardChatSettings.visibility = View.GONE
            }
        }
    }

    private fun resetCardSelection(card: MaterialCardView) {
        card.strokeWidth = 0
        card.cardElevation = 2f
    }

    private fun setCardSelected(card: MaterialCardView) {
        card.strokeWidth = 4
        card.strokeColor = ContextCompat.getColor(this, R.color.primary_blue)
        card.cardElevation = 8f
    }

    private fun setupConnectButton() {
        binding.fabConnect.setOnClickListener {
            connect()
        }
    }

    private fun connect() {
        // 获取已保存的设备信息
        val prefs = getDefaultSharedPreferences(this)
        val productId = prefs.getString(Constants.KEY_PRODUCT_ID, "") ?: ""
        val deviceName = prefs.getString(Constants.KEY_DEVICE_NAME, "") ?: ""
        val botId = binding.etBotId.text?.toString()?.trim() ?: ""

        // 跳转到聊天界面
        val bundle = Bundle().apply {
            putString(Constants.KEY_CONNECTION_TYPE, connectionType.name)
            putString(Constants.KEY_AUDIO_TYPE, getSelectedAudioType())
            putString(Constants.KEY_PRODUCT_ID, productId)
            putString(Constants.KEY_DEVICE_NAME, deviceName)
            putString(Constants.KEY_LANGUAGE, selectedLanguage)
            putBoolean(Constants.KEY_VIDEO_MODE, binding.switchVideoChat.isChecked)
            putBoolean(Constants.KEY_PUSH_TO_TALK, binding.switchPushToTalk.isChecked)
            putString(Constants.KEY_BOT_ID, botId)
        }

        val intent = when (connectionType) {
            TWeTalkConfig.TransportType.WEBSOCKET -> {
                Intent(this@MainActivity, WebSocketChatActivity::class.java)
                    .putExtra(Constants.KEY_CHAT_BUNDLE, bundle)
            }

            TWeTalkConfig.TransportType.TRTC -> {
                Intent(this@MainActivity, TRTCChatActivity::class.java)
                    .putExtra(Constants.KEY_CHAT_BUNDLE, bundle)
            }
        }

        // 保存上次连接的方式
        getSharedPreferences(Constants.KEY_CONNECT_PARAMS_PREF, MODE_PRIVATE).edit {
            putString(Constants.KEY_CONNECTION_TYPE, connectionType.name)
            putString(Constants.KEY_AUDIO_TYPE, getSelectedAudioType())
            putString(Constants.KEY_LANGUAGE, selectedLanguage)
            putBoolean(Constants.KEY_VIDEO_MODE, binding.switchVideoChat.isChecked)
            putBoolean(Constants.KEY_PUSH_TO_TALK, binding.switchPushToTalk.isChecked)
            putString(Constants.KEY_BOT_ID, botId)
        }

        startActivity(intent)
    }

    private fun getSelectedAudioType(): String {
        return when (binding.rgAudioFormat.checkedRadioButtonId) {
            R.id.rbOpus -> "OPUS"
            R.id.rbPCM -> "PCM"
            else -> "OPUS"
        }
    }

    private fun setupNavigationButtons() {
        binding.btnCallConfig.setOnClickListener {
            val intent = Intent(this, CallConfigActivity::class.java)
            startActivity(intent)
        }

        binding.btnAiConfig.setOnClickListener {
            val intent = Intent(this, AiConfigActivity::class.java)
            startActivity(intent)
        }

        binding.btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        binding.btnHistory.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }
    }

    /**
     * 处理 MQTT 在线但 WebSocket 离线时的来电
     * 启动 WxCallOnlyActivity 来处理通话
     */
    private fun handleIncomingCallFromMqtt(roomId: String, openId: String) {
        // 查找昵称
        val nickname = CallConfigManager.findNicknameByOpenId(this, openId) ?: ""

        runOnUiThread {
            val intent = Intent(this, WxCallOnlyActivity::class.java).apply {
                putExtra(Constants.KEY_CALL_BUNDLE, Bundle().apply {
                    putString(Constants.KEY_CALL_TYPE, "incoming")
                    putString(Constants.KEY_CALL_NICKNAME, nickname)
                    putString(Constants.KEY_CALL_OPEN_ID, openId)
                    putString(Constants.KEY_CALL_ROOM_ID, roomId)
                })
            }
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mqttManager?.callback = null
    }

    companion object {
        private val TAG = MainActivity::class.simpleName
    }
}