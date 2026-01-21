package com.tencent.twetalk_sdk_demo.call

import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.tencent.twetalk_sdk_demo.BaseActivity
import com.tencent.twetalk_sdk_demo.R
import com.tencent.twetalk_sdk_demo.data.Constants
import com.tencent.twetalk_sdk_demo.databinding.ActivityWxCallBinding
import com.tencent.twetalk_sdk_demo.utils.ScreenAdaptHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 微信通话页面
 */
class WxCallActivity : BaseActivity<ActivityWxCallBinding>() {

    companion object {
        private const val AUTO_FINISH_DELAY = 1000L  // 自动退出延迟
        private val VIBRATION_PATTERN = longArrayOf(0, 500, 500)  // 震动模式：等待0ms，震动500ms，暂停500ms
    }

    private var callType: CallType = CallType.OUTGOING
    private var callState: CallState = CallState.IDLE
    private var nickname: String = ""
    private var openId: String = ""
    private var roomId: String = ""
    private var isMuted: Boolean = false

    // 震动器
    private var vibrator: Vibrator? = null

    // 通话计时
    private var callStartTime: Long = 0
    private var timerJob: Job? = null
    
    // 小屏简化模式
    private var isTinyScreen = false

    override fun getViewBinding() = ActivityWxCallBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun initView() {
        parseIntent()
        checkScreenSize()
        setupUI()
        setupClickListeners()
        setupOnBackPressedCallback()
        updateUIForState()
        observeCallState()
    }
    
    /**
     * 检查屏幕尺寸，启用简化模式
     */
    private fun checkScreenSize() {
        isTinyScreen = ScreenAdaptHelper.isTinyScreen(this)
        
        if (isTinyScreen) {
            // 极小屏简化模式：隐藏 OpenId 区域
            binding.layoutOpenId.visibility = View.GONE
        }
    }

    private fun parseIntent() {
        val bundle = intent.getBundleExtra(Constants.KEY_CALL_BUNDLE) ?: return

        val callTypeStr = bundle.getString(Constants.KEY_CALL_TYPE, "outgoing")
        callType = if (callTypeStr == "incoming") CallType.INCOMING else CallType.OUTGOING

        nickname = bundle.getString(Constants.KEY_CALL_NICKNAME, "") ?: ""
        openId = bundle.getString(Constants.KEY_CALL_OPEN_ID, "") ?: ""
        roomId = bundle.getString(Constants.KEY_CALL_ROOM_ID, "") ?: ""

        // 根据通话类型设置初始状态
        callState = when (callType) {
            CallType.OUTGOING -> CallState.CALLING
            CallType.INCOMING -> CallState.INCOMING
        }
    }

    private fun setupUI() {
        if (nickname.isEmpty() && openId.isEmpty()) {
            binding.tvNickname.visibility = View.GONE
            binding.tvOpenId.visibility = View.GONE
            binding.layoutOpenId.visibility = View.GONE
            return
        }

        // 如果没有昵称，尝试从通讯录查找
        if (nickname.isEmpty() && openId.isNotEmpty()) {
            nickname = CallConfigManager.findNicknameByOpenId(this, openId) ?: openId
        }

        binding.tvNickname.text = nickname.ifEmpty { openId }
        binding.tvOpenId.text = openId

        // 点击显示完整 OpenID
        binding.layoutOpenId.setOnClickListener {
            showToast(openId)
        }
    }

    private fun setupClickListeners() {
        // 挂断按钮
        binding.fabHangup.setOnClickListener {
            handleHangup()
        }

        // 接听按钮
        binding.fabAnswer.setOnClickListener {
            handleAnswer()
        }

        // 静音按钮
        binding.fabMute.setOnClickListener {
            toggleMute()
        }
    }

    private fun setupOnBackPressedCallback() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 通话中禁止返回
                if (callState == CallState.IN_PROGRESS || callState == CallState.CALLING || callState == CallState.INCOMING) {
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

    /**
     * 监听通话状态变化
     */
    private fun observeCallState() {
        lifecycleScope.launch {
            WxCallManager.callStateFlow.collect { event ->
                // 检查 roomId 是否匹配 (如果有的话)
                if (event.roomId == null || event.roomId == roomId) {
                    callState = event.state
                    updateUIForState()
                }
            }
        }
    }

    private fun updateUIForState() {
        when (callState) {
            CallState.IDLE -> {
                // 不应该出现
            }
            CallState.CALLING -> {
                binding.tvStatus.text = getString(R.string.waiting_answer)
                binding.layoutAnswer.visibility = View.GONE
                binding.layoutMute.visibility = View.GONE
                binding.layoutHangup.visibility = View.VISIBLE
                enableHangup(true)
            }
            CallState.INCOMING -> {
                binding.tvStatus.text = getString(R.string.incoming_call)
                binding.layoutAnswer.visibility = View.VISIBLE
                binding.layoutMute.visibility = View.GONE
                binding.layoutHangup.visibility = View.VISIBLE
                enableHangup(true)
                startVibration()
            }
            CallState.IN_PROGRESS -> {
                binding.tvStatus.text = "00:00"
                binding.layoutAnswer.visibility = View.GONE
                binding.layoutMute.visibility = View.VISIBLE
                binding.layoutHangup.visibility = View.VISIBLE
                enableHangup(true)
                stopVibration()
                updateMuteUI()
                startCallTimer()
            }
            CallState.REJECTED -> {
                binding.tvStatus.text = getString(R.string.call_rejected)
                enableHangup(false)
                stopVibration()
                scheduleAutoFinish()
            }
            CallState.TIMEOUT -> {
                binding.tvStatus.text = getString(R.string.call_timeout)
                enableHangup(false)
                stopVibration()
                scheduleAutoFinish()
            }
            CallState.BUSY -> {
                binding.tvStatus.text = getString(R.string.call_busy)
                enableHangup(false)
                stopVibration()
                scheduleAutoFinish()
            }
            CallState.ERROR -> {
                binding.tvStatus.text = getString(R.string.call_error)
                enableHangup(false)
                stopVibration()
                scheduleAutoFinish()
            }
            CallState.ENDED -> {
                binding.tvStatus.text = getString(R.string.call_ended)
                enableHangup(false)
                stopCallTimer()
                stopVibration()
                scheduleAutoFinish()
            }
        }
    }

    private fun enableHangup(enabled: Boolean) {
        binding.fabHangup.isEnabled = enabled
        binding.fabHangup.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun handleHangup() {
        when (callState) {
            CallState.CALLING -> {
                // 取消呼叫
                WxCallManager.sendCallAction(CallAction.HANGUP, roomId)
                callState = CallState.ENDED
                updateUIForState()
            }
            CallState.INCOMING -> {
                // 拒接来电
                WxCallManager.sendCallAction(CallAction.REJECT, roomId)
                callState = CallState.ENDED
                updateUIForState()
            }
            CallState.IN_PROGRESS -> {
                // 挂断通话
                WxCallManager.sendCallAction(CallAction.HANGUP, roomId)
                callState = CallState.ENDED
                updateUIForState()
            }
            else -> {
                finish()
            }
        }
    }

    private fun handleAnswer() {
        if (callState == CallState.INCOMING) {
            WxCallManager.sendCallAction(CallAction.ANSWER, roomId)
            updateUIForState()
        }
    }

    private fun toggleMute() {
        if (isMuted) {
            WxCallManager.sendCallAction(CallAction.UNMUTE)
        } else {
            WxCallManager.sendCallAction(CallAction.MUTE)
        }

        isMuted = !isMuted
        updateMuteUI()
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
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCallTimer()
        timerJob = null
        stopVibration()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
