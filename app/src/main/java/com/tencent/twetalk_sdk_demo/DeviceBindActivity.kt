package com.tencent.twetalk_sdk_demo

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.tencent.twetalk.mqtt.MqttManager
import com.tencent.twetalk_sdk_demo.data.Constants
import com.tencent.twetalk_sdk_demo.databinding.ActivityDeviceBindBinding

/**
 * 设备绑定界面
 * 用户首次使用或切换设备时需要输入设备三元组进行绑定
 */
class DeviceBindActivity : BaseActivity<ActivityDeviceBindBinding>() {

    private var isConnecting = false

    private val mqttCallback = object : MqttManager.MqttConnectionCallback {
        override fun onConnected() {
            runOnUiThread {
                showLoading(false)
                isConnecting = false

                // 保存设备信息
                val productId = binding.etProductId.text.toString().trim()
                val deviceName = binding.etDeviceName.text.toString().trim()
                val deviceSecret = binding.etDeviceSecret.text.toString().trim()

                saveDeviceInfo(productId, deviceName, deviceSecret)

                // 显示成功提示
                showSuccess()

                // 跳转到主界面
                navigateToMain()
            }
        }

        override fun onDisconnected(cause: Throwable?, isManual: Boolean) {
            // 正常断开连接（用户主动断开）不需要处理
        }

        override fun onConnectFailed(cause: Throwable?) {
            runOnUiThread {
                showLoading(false)
                isConnecting = false
                setInputEnabled(true)

                val errorMsg = cause?.message ?: getString(R.string.connection_failed)
                showError(errorMsg)
            }
        }

        override fun onMessageReceived(
            topic: String,
            method: String,
            params: Map<String, Any>
        ) {
            // 在绑定阶段不需要处理消息
        }
    }

    override fun getViewBinding() = ActivityDeviceBindBinding.inflate(layoutInflater)

    override fun initView() {
        loadSavedDeviceInfo()
        setupBindButton()
    }

    /**
     * 加载已保存的设备信息（如果有）
     */
    private fun loadSavedDeviceInfo() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        
        binding.etProductId.setText(prefs.getString(Constants.KEY_PRODUCT_ID, "") ?: "")
        binding.etDeviceName.setText(prefs.getString(Constants.KEY_DEVICE_NAME, "") ?: "")
        binding.etDeviceSecret.setText(prefs.getString(Constants.KEY_DEVICE_SECRET, "") ?: "")
    }

    /**
     * 设置绑定按钮
     */
    private fun setupBindButton() {
        binding.btnBind.setOnClickListener {
            if (isConnecting) return@setOnClickListener
            
            val productId = binding.etProductId.text.toString().trim()
            val deviceName = binding.etDeviceName.text.toString().trim()
            val deviceSecret = binding.etDeviceSecret.text.toString().trim()

            if (!validateInputs(productId, deviceName, deviceSecret)) {
                return@setOnClickListener
            }

            bindDevice(productId, deviceName, deviceSecret)
        }
    }

    /**
     * 验证输入
     */
    private fun validateInputs(productId: String, deviceName: String, deviceSecret: String): Boolean {
        if (productId.isEmpty()) {
            binding.etProductId.error = getString(R.string.required_field)
            binding.etProductId.requestFocus()
            return false
        }

        if (deviceName.isEmpty()) {
            binding.etDeviceName.error = getString(R.string.required_field)
            binding.etDeviceName.requestFocus()
            return false
        }

        if (deviceSecret.isEmpty()) {
            binding.etDeviceSecret.error = getString(R.string.required_field)
            binding.etDeviceSecret.requestFocus()
            return false
        }

        return true
    }

    /**
     * 绑定设备并验证连接
     */
    private fun bindDevice(productId: String, deviceName: String, deviceSecret: String) {
        isConnecting = true
        showLoading(true)
        setInputEnabled(false)

        // 初始化 MQTT Manager 并尝试连接
        val app = TalkApplication.getInstance()
        app.initializeMqtt(
            productId,
            deviceName,
            deviceSecret,
            mqttCallback
        ).connect()
    }

    /**
     * 保存设备信息到 SharedPreferences
     */
    private fun saveDeviceInfo(productId: String, deviceName: String, deviceSecret: String) {
        PreferenceManager.getDefaultSharedPreferences(this).edit {
            putString(Constants.KEY_PRODUCT_ID, productId)
            putString(Constants.KEY_DEVICE_NAME, deviceName)
            putString(Constants.KEY_DEVICE_SECRET, deviceSecret)
        }

        // 同时保存到设备信息 SharedPreferences（兼容旧代码）
        getSharedPreferences(Constants.KEY_DEVICE_INFO_PREF, MODE_PRIVATE).edit {
            putString(Constants.KEY_PRODUCT_ID, productId)
            putString(Constants.KEY_DEVICE_NAME, deviceName)
        }
    }

    /**
     * 显示成功动画
     */
    private fun showSuccess() {
        binding.layoutSuccess.visibility = View.VISIBLE
        
        val scaleAnimation = AnimationUtils.loadAnimation(this, R.anim.scale_success)
        binding.ivSuccess.startAnimation(scaleAnimation)
    }

    /**
     * 显示错误提示
     */
    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
        
        // 3秒后隐藏错误提示
        binding.tvError.postDelayed({
            binding.tvError.visibility = View.GONE
        }, 3000)
    }

    /**
     * 显示/隐藏加载状态
     */
    private fun showLoading(show: Boolean) {
        if (show) {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvLoading.visibility = View.VISIBLE
            binding.btnBind.text = ""
            binding.btnBind.isEnabled = false
        } else {
            binding.progressBar.visibility = View.GONE
            binding.tvLoading.visibility = View.GONE
            binding.btnBind.text = getString(R.string.bind_device)
            binding.btnBind.isEnabled = true
        }
    }

    /**
     * 设置输入框启用/禁用
     */
    private fun setInputEnabled(enabled: Boolean) {
        binding.etProductId.isEnabled = enabled
        binding.etDeviceName.isEnabled = enabled
        binding.etDeviceSecret.isEnabled = enabled
    }

    /**
     * 跳转到主界面
     */
    private fun navigateToMain() {
        // 延迟一下让用户看到成功提示
        binding.layoutSuccess.postDelayed({
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // 在跳转、finish 之前先设置 mqtt 的回调为 null
            // 否则可能因时序问题导致 MainActivity onResume 在 onDestroy 之后而使 mqtt 回调无法被正确设置
            mqttManager?.callback = null
            startActivity(intent)
            finish()
        }, 1500)
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnecting = false
    }

    companion object {
        private const val TAG = "DeviceBindActivity"
    }
}
