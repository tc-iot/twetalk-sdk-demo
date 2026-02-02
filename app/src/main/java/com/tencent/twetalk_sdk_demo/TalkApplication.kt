package com.tencent.twetalk_sdk_demo

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.tencent.twetalk.mqtt.MqttManager
import com.tencent.twetalk_sdk_demo.data.Constants

class TalkApplication : Application() {
    private var _mqttManager: MqttManager? = null

    /**
     * 全局 MqttManager 实例
     * 只有在配置完成后才会初始化
     */
    val mqttManager: MqttManager?
        get() = _mqttManager

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        instance = this
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        // 如果已有设备配置，自动初始化 MQTT
        initializeMqttIfConfigured()
    }

    /**
     * 初始化或重新初始化 MQTT Manager
     * @param productId 产品 ID
     * @param deviceName 设备名称
     * @param deviceSecret 设备密钥
     * @param callback MQTT 连接回调
     * @return 初始化后的 MqttManager 实例
     */
    fun initializeMqtt(
        productId: String,
        deviceName: String,
        deviceSecret: String,
        callback: MqttManager.MqttConnectionCallback
    ): MqttManager {
        // 如果已存在，先释放旧的连接
        _mqttManager?.release()

        // 创建新的 MqttManager
        _mqttManager = MqttManager(
            this,
            productId,
            deviceName,
            deviceSecret,
            callback
        ).apply {
            // 可选：设置自定义 Broker URL
            // setBrokerUrl("ssl://your-broker-url:8883")
        }

        return _mqttManager!!
    }

    /**
     * 从 SharedPreferences 读取配置并初始化 MQTT
     */
    private fun initializeMqttIfConfigured() {
        val productId = sharedPreferences.getString(Constants.KEY_PRODUCT_ID, null)
        val deviceName = sharedPreferences.getString(Constants.KEY_DEVICE_NAME, null)
        val deviceSecret = sharedPreferences.getString(Constants.KEY_DEVICE_SECRET, null)

        if (!productId.isNullOrEmpty() && !deviceName.isNullOrEmpty() && !deviceSecret.isNullOrEmpty()) {
            Log.i(TAG, "Found saved device config, initializing MQTT...")

            // 使用默认回调初始化（各 Activity 可以后续覆盖）
            initializeMqtt(
                productId,
                deviceName,
                deviceSecret,
                object : MqttManager.MqttConnectionCallback {
                    override fun onConnected() {
                        Log.i(TAG, "MQTT connected automatically")
                    }

                    override fun onDisconnected(cause: Throwable?, isManual: Boolean) {
                        Log.w(TAG, "MQTT disconnected, isManual=$isManual", cause)
                    }

                    override fun onConnectFailed(cause: Throwable?) {
                        Log.e(TAG, "MQTT connection failed", cause)
                    }

                    override fun onMessageReceived(topic: String, method: String, params: Map<String, Any>) {
                        Log.d(TAG, "MQTT message received, topic: $topic, method: $method")
                        Log.d(TAG, "==== MQTT message received, params: ====")
                        for (entry in params) {
                            Log.d(TAG, "${entry.key}: ${entry.value}")
                        }
                        Log.d(TAG, "========")
                    }
                }
            )
        }
    }

    /**
     * 连接 MQTT（如果未连接）
     */
    fun connectMqtt() {
        _mqttManager?.let {
            if (!it.isConnected) {
                it.connect()
            }
        }
    }

    /**
     * 断开 MQTT 连接
     */
    fun disconnectMqtt() {
        _mqttManager?.disconnect()
    }

    /**
     * 释放 MQTT 资源
     */
    fun releaseMqtt() {
        _mqttManager?.release()
        _mqttManager = null
    }

    override fun onTerminate() {
        super.onTerminate()
        releaseMqtt()
    }

    companion object {
        private const val TAG = "TalkApplication"
        private lateinit var instance: TalkApplication

        /**
         * 获取 Application 实例
         */
        fun getInstance(): TalkApplication = instance
    }
}