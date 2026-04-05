package com.autoglm.assistant.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 唤醒监听悬浮窗 — 在唤醒词检测到后弹出，告知用户当前状态
 *
 * 状态流程：
 * 1. showWakeDetected → 🎤 正在听...（呼吸灯动画） + 底部输入区
 * 2. showRecognizing  → ⏳ 正在识别...
 * 3. showResult       → ✅ 显示识别文本，延时后自动消失
 * 4. dismiss          → 隐藏悬浮窗
 *
 * 支持：
 * - 取消回调：点击取消按钮触发 onCancel
 * - 文本输入：用户可切换到键盘输入模式，提交后触发 onTextSubmit
 */
class WakeListeningOverlay(context: Context) {

    companion object {
        private const val TAG = "WakeListeningOverlay"
        // 识别结果显示后自动消失的延时（毫秒）
        private const val RESULT_DISMISS_DELAY_MS = 1500L
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null
    private var statusIcon: TextView? = null
    private var statusText: TextView? = null
    private var partialText: TextView? = null
    private var inputArea: LinearLayout? = null
    private var inputField: EditText? = null

    // 当前是否处于文本输入模式
    private var isInputMode = false

    // 呼吸灯动画
    private var breatheAnimation: Animation? = null

    /** 用户点击取消按钮的回调 */
    var onCancel: (() -> Unit)? = null

    /** 用户通过输入框提交文本指令的回调 */
    var onTextSubmit: ((String) -> Unit)? = null

    /**
     * 显示唤醒成功 → "正在听..."（呼吸灯动画），同时显示输入区
     */
    fun showWakeDetected() {
        mainHandler.post {
            ensureView()
            isInputMode = false
            statusIcon?.text = "🎤"
            statusText?.text = "正在听..."
            partialText?.text = ""
            partialText?.visibility = View.GONE
            inputArea?.visibility = View.VISIBLE
            inputField?.text?.clear()
            overlayView?.visibility = View.VISIBLE
            // 语音模式：不抢焦点
            updateWindowFlags(focusable = false)
            startBreatheAnimation()
        }
    }

    /**
     * 切换到"正在识别..."状态（录音结束，等待 API 返回）
     */
    fun showRecognizing() {
        mainHandler.post {
            stopBreatheAnimation()
            statusIcon?.text = "⏳"
            statusText?.text = "正在识别..."
            partialText?.visibility = View.GONE
            // 识别中隐藏输入区
            inputArea?.visibility = View.GONE
        }
    }

    /**
     * 显示识别结果，延时后自动消失
     */
    fun showResult(text: String) {
        mainHandler.post {
            stopBreatheAnimation()
            statusIcon?.text = "✅"
            statusText?.text = if (text.isBlank()) "未检测到语音" else text
            partialText?.visibility = View.GONE
            inputArea?.visibility = View.GONE
            // 延时后自动消失
            mainHandler.postDelayed({ dismiss() }, RESULT_DISMISS_DELAY_MS)
        }
    }

    /**
     * 将文字设置到"输入指令"输入框中（供外部调用，如语音识别中间结果回填）
     */
    fun setInputFieldText(text: String) {
        mainHandler.post {
            inputField?.setText(text)
            inputField?.setSelection(text.length)
        }
    }

    /**
     * 更新 STT 实时识别的部分结果
     */
    fun updatePartialResult(partial: String) {
        mainHandler.post {
            if (partial.isNotBlank()) {
                partialText?.text = "\"$partial\""
                partialText?.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 切换到文本输入模式：取消语音录音，聚焦输入框
     */
    private fun switchToInputMode() {
        isInputMode = true
        stopBreatheAnimation()
        statusIcon?.text = "⌨️"
        statusText?.text = "键入指令..."
        partialText?.visibility = View.GONE

        inputArea?.visibility = View.VISIBLE

        // 切换为可获取焦点模式，以便弹出键盘
        updateWindowFlags(focusable = true)

        // 聚焦输入框并弹出键盘
        inputField?.requestFocus()
        mainHandler.postDelayed({
            inputField?.let {
                val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 200)

        // 取消正在进行的语音录音
        onCancel?.invoke()
    }

    /**
     * 提交输入框文本
     */
    private fun submitText() {
        val text = inputField?.text?.toString()?.trim() ?: ""
        if (text.isNotEmpty()) {
            hideKeyboard()
            showResult(text)
            onTextSubmit?.invoke(text)
        }
    }

    /**
     * 隐藏悬浮窗（识别完成或出错时调用）
     */
    fun dismiss() {
        mainHandler.post {
            stopBreatheAnimation()
            if (isInputMode) hideKeyboard()
            isInputMode = false
            overlayView?.visibility = View.GONE
        }
    }

    /**
     * 释放所有资源
     */
    fun release() {
        mainHandler.post {
            stopBreatheAnimation()
            if (isInputMode) hideKeyboard()
            overlayView?.let {
                runCatching { windowManager.removeView(it) }
            }
            overlayView = null
            statusIcon = null
            statusText = null
            partialText = null
            inputArea = null
            inputField = null
        }
    }

    // ── 键盘控制 ──

    private fun hideKeyboard() {
        inputField?.let {
            val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    /**
     * 动态切换悬浮窗焦点模式
     * focusable=false → 不抢焦点，适合语音状态展示
     * focusable=true  → 可获焦点，适合键盘输入
     */
    private fun updateWindowFlags(focusable: Boolean) {
        val view = overlayView ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        lp.flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        if (focusable) {
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    // ── 动画 ──

    private fun startBreatheAnimation() {
        stopBreatheAnimation()
        breatheAnimation = AlphaAnimation(1.0f, 0.3f).apply {
            duration = 800
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        statusIcon?.startAnimation(breatheAnimation)
    }

    private fun stopBreatheAnimation() {
        statusIcon?.clearAnimation()
        breatheAnimation = null
    }

    // ── 布局构建 ──

    private fun ensureView() {
        if (overlayView != null) return

        val screenWidth = appContext.resources.displayMetrics.widthPixels
        val overlayWidth = (screenWidth * 0.85f).toInt()

        // 步骤1: 根容器 — 垂直布局，圆角半透明黑底
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#E6181A20"))  // 深色半透明
                setStroke(dp(1), Color.parseColor("#4D64B5F6"))  // 蓝色细边框
            }
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }

        // ── 步骤2: 顶部状态行（图标 + 文字列） ──
        val statusRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 麦克风图标（带呼吸灯动画）
        val icon = TextView(appContext).apply {
            text = "🎤"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(0, 0, dp(10), 0)
        }
        statusRow.addView(icon)

        // 文字列
        val textColumn = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // 主状态文字
        val mainText = TextView(appContext).apply {
            text = "正在听..."
            setTextColor(Color.parseColor("#E8EAED"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            includeFontPadding = false
        }
        textColumn.addView(mainText)

        // 实时识别文字
        val partialTv = TextView(appContext).apply {
            setTextColor(Color.parseColor("#8AB4F8"))  // 蓝色高亮
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            maxLines = 2
            visibility = View.GONE
            setPadding(0, dp(2), 0, 0)
        }
        textColumn.addView(partialTv)

        statusRow.addView(textColumn)
        container.addView(statusRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── 步骤3: 底部输入区（EditText + 发送 + 停止，水平排列） ──
        val inputRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            visibility = View.VISIBLE
        }

        val editField = EditText(appContext).apply {
            hint = "输入指令..."
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            setTextColor(Color.parseColor("#E0E0E0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            isSingleLine = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#FF2A2A3A"))
                setStroke(dp(1), Color.parseColor("#4D64B5F6"))
            }
            // 键盘"发送"按键 → 提交文本
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitText()
                    true
                } else false
            }            // 点击输入框 → 自动切换到文本输入模式（终止语音录音）
            setOnClickListener {
                if (!isInputMode) switchToInputMode()
            }        }
        inputRow.addView(editField, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        // 发送按钮
        val sendBtn = TextView(appContext).apply {
            text = "➤"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.parseColor("#8AB4F8"))
            setPadding(dp(10), dp(6), dp(4), dp(6))
            setOnClickListener { submitText() }
        }
        inputRow.addView(sendBtn)

        // ■ 停止按钮 — 红色圆形，与主界面中断按钮风格一致
        val stopBtn = FrameLayout(appContext).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC3333"))
            }
            // 内部白色方块图标
            val stopSquare = View(appContext).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(2).toFloat()
                    setColor(Color.WHITE)
                }
            }
            addView(stopSquare, FrameLayout.LayoutParams(dp(10), dp(10)).apply {
                gravity = Gravity.CENTER
            })
            setOnClickListener { onCancel?.invoke() }
        }
        inputRow.addView(stopBtn, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
            setMargins(dp(6), 0, dp(2), 0)
            gravity = Gravity.CENTER_VERTICAL
        })

        container.addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── 保存引用 ──
        statusIcon = icon
        statusText = mainText
        partialText = partialTv
        inputArea = inputRow
        inputField = editField
        overlayView = container

        // 步骤5: WindowManager 参数 — 顶部居中显示
        val params = WindowManager.LayoutParams(
            overlayWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(80)  // 距离顶部一定距离
        }

        windowManager.addView(container, params)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            appContext.resources.displayMetrics
        ).toInt()
    }
}
