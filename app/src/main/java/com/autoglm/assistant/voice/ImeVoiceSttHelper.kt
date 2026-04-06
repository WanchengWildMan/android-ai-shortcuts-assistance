package com.autoglm.assistant.voice

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.autoglm.assistant.accessibility.UIHierarchyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 通过 IME（输入法）语音功能实现 STT 的辅助类
 *
 * 流程：
 * 1. 弹出悬浮输入框 → 获取焦点 → 输入法弹出
 * 2. 通过无障碍服务点击语音按钮坐标 → 开始录音
 * 3. 监听输入框文字变化 → 当文字稳定后作为识别结果返回
 * 4. 再次点击同一坐标结束录音，收起输入框
 *
 * 注意：语音按钮坐标因设备和键盘布局而异，需在设置中配置
 */
class ImeVoiceSttHelper(private val context: Context) {

    companion object {
        private const val TAG = "ImeVoiceSTT"
        // 文字稳定后等待时间（毫秒），超过此时间无新文字则认为识别完成
        private const val TEXT_STABLE_DELAY_MS = 2000L
        // 弹出输入法后等待键盘就绪的延迟后备值（毫秒）；实际延迟由实例变量 keyboardReadyDelay 决定
        // HARD: 此默认值仅作兜底，业务上应从 PreferenceManager.DEFAULT_IME_VOICE_KEYBOARD_DELAY_MS 读取
        private const val KEYBOARD_READY_DELAY_DEFAULT_MS = 1200L
        // 最大等待时间（毫秒），超过后自动超时
        private const val MAX_WAIT_MS = 30000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private var overlayView: View? = null
    private var editText: EditText? = null
    private var hintText: TextView? = null

    // 回调
    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStatusChange: ((String) -> Unit)? = null

    // 空格键坐标
    var spaceX: Int = 704
    var spaceY: Int = 2978
    // 步骤: 键盘就绪等待延迟（毫秒）；可由 WakeWordService 从 PreferenceManager 注入
    var keyboardReadyDelay: Long = KEYBOARD_READY_DELAY_DEFAULT_MS

    // 文字稳定检测
    private var stableCheckRunnable: Runnable? = null
    private var lastText: String = ""
    private var timeoutRunnable: Runnable? = null
    private var isActive = false

    /**
     * 启动 IME 语音输入流程
     */
    fun start() {
        if (isActive) return
        isActive = true

        mainHandler.post {
            // 步骤1: 创建悬浮输入框
            createOverlay()
            // 步骤2: 显示并获取焦点
            overlayView?.visibility = View.VISIBLE
            editText?.requestFocus()
            // 步骤3: 弹出输入法
            editText?.let { et ->
                imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }
            onStatusChange?.invoke("等待键盘就绪...")

            // 步骤4: 等待键盘弹出后点击语音按钮开始录音
            mainHandler.postDelayed({
                if (!isActive) return@postDelayed
                tapVoiceButton()
                onStatusChange?.invoke("请说话...")
                startTextMonitor()
                startTimeout()
            }, keyboardReadyDelay)
        }
    }

    /**
     * 取消并清理
     */
    fun cancel() {
        isActive = false
        cleanup()
    }

    /**
     * 点击语音按钮（开始或结束录音）
     * 复用与 ActionExecutor.getEffectiveMode() 一致的模式选择逻辑：
     * 步骤1: Root 模式开启 → ShellExecutor.tap()（更稳定，无需无障碍服务已连接）
     * 步骤2: 无 Root → UIHierarchyManager.performClick()（无障碍服务手势）
     * 步骤3: 两种方式均失败时仅记录警告，不中断主流程
     */
    private fun tapVoiceButton() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val success = if (com.autoglm.assistant.util.ShellExecutor.globalUseRoot) {
                    // 步骤1: Root 模式 → input tap（与 ActionExecutor SHELL_INPUT 分支一致）
                    com.autoglm.assistant.util.ShellExecutor.tap(spaceX, spaceY)
                } else {
                    // 步骤2: 无 Root → 无障碍服务手势（与 ActionExecutor ACCESSIBILITY 分支一致）
                    UIHierarchyManager.performClick(context, spaceX, spaceY)
                }
                // 步骤3: 记录最终结果
                Log.i(TAG, "点击语音按钮 ($spaceX, $spaceY) [root=${com.autoglm.assistant.util.ShellExecutor.globalUseRoot}]: ${if (success) "成功" else "失败"}")
            } catch (e: Exception) {
                Log.w(TAG, "点击语音按钮异常: ${e.message}")
            }
        }
    }

    /**
     * 监听输入框文字变化，文字稳定后返回结果
     */
    private fun startTextMonitor() {
        editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                if (text.isBlank()) {
                    // 步骤: 退格完全清空时，同步通知外部清空 inputField 显示，但不触发稳定检测
                    lastText = ""
                    onStatusChange?.invoke("识别中: ")
                    return
                }

                lastText = text
                onStatusChange?.invoke("识别中: $text")

                // 每次文字变化，重置稳定计时器
                stableCheckRunnable?.let { mainHandler.removeCallbacks(it) }
                stableCheckRunnable = Runnable {
                    if (isActive && lastText.isNotBlank()) {
                        Log.i(TAG, "文字已稳定: $lastText")
                        isActive = false
                        // 步骤: 再次点击原位结束录音
                        tapVoiceButton()
                        onResult?.invoke(lastText)
                        cleanup()
                    }
                }
                mainHandler.postDelayed(stableCheckRunnable!!, TEXT_STABLE_DELAY_MS)
            }
        })
    }

    /**
     * 超时保护
     */
    private fun startTimeout() {
        timeoutRunnable = Runnable {
            if (isActive) {
                Log.w(TAG, "IME 语音输入超时")
                isActive = false
                // 步骤: 点击原位结束录音
                tapVoiceButton()
                if (lastText.isNotBlank()) {
                    onResult?.invoke(lastText)
                } else {
                    onError?.invoke("语音输入超时，未检测到文字")
                }
                cleanup()
            }
        }
        mainHandler.postDelayed(timeoutRunnable!!, MAX_WAIT_MS)
    }

    /**
     * 清理所有资源
     */
    private fun cleanup() {
        mainHandler.post {
            stableCheckRunnable?.let { mainHandler.removeCallbacks(it) }
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            stableCheckRunnable = null
            timeoutRunnable = null

            // 收起键盘
            editText?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }

            // 移除悬浮窗
            overlayView?.let {
                runCatching { windowManager.removeView(it) }
            }
            overlayView = null
            editText = null
            hintText = null
            lastText = ""
        }
    }

    /**
     * 创建不可见的焦点承载窗口
     * 目的: 仅用于获取输入法焦点、接收 IME 语音识别回填的文字，本身完全透明不可见
     * 状态和结果通过 onStatusChange/onResult 回调传给外层悬浮卡片（WakeListeningOverlay）显示
     */
    private fun createOverlay() {
        if (overlayView != null) return

        // 步骤1: 透明容器，无任何视觉元素（不显示给用户）
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        // 步骤2: 功能性 EditText — 透明不可见，仅用于接收键盘焦点和 IME 语音回填文字
        val et = EditText(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.TRANSPARENT)
            setHintTextColor(Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true
            maxLines = 3
        }
        container.addView(et, LinearLayout.LayoutParams(1, 1))  // 1x1 像素，不占屏幕空间
        editText = et
        hintText = null  // 无提示文字控件

        overlayView = container

        // 步骤3: WindowManager 参数 — 透明、不拦截触摸、可获取焦点（输入法必须）
        val params = WindowManager.LayoutParams(
            1, 1,  // 1x1 像素，视觉上不可见
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            // FLAG_NOT_TOUCH_MODAL: 不拦截窗口外触摸事件
            // 不加 FLAG_NOT_FOCUSABLE: 允许获得焦点（输入法弹出必须）
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        }

        windowManager.addView(container, params)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
