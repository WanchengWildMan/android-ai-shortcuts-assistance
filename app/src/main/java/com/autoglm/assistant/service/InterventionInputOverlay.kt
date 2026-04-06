package com.autoglm.assistant.service

import android.content.Context
import android.graphics.Color
import com.autoglm.assistant.util.Logger
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
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 任务干预浮窗输入覆盖层
 *
 * 业务目的：当 Agent 正在执行任务时，用户可通过通知栏操作按钮呼出此浮窗，
 * 在不离开当前 app 的情况下输入干预指令（如修正方向、提供补充信息）。
 *
 * 流程：
 * 1. 用户点击通知栏"干预"按钮 → WakeWordService 调用 show()
 * 2. 浮窗覆盖在当前 app 之上，包含输入框和发送按钮
 * 3. 用户输入指令并发送 → 回调 onInterventionSubmit
 * 4. WakeWordService 将指令注入 PhoneAgent 的执行流程
 */
class InterventionInputOverlay(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null
    private var editText: EditText? = null

    // 用户提交干预指令时的回调
    var onInterventionSubmit: ((String) -> Unit)? = null

    // 可配置的标题和提示文字（用于Agent中途提问场景）
    var customTitle: String? = null
    var customHint: String? = null
    var customInputHint: String? = null

    /**
     * 显示干预输入浮窗
     */
    fun show() {
        mainHandler.post {
            if (overlayView != null) {
                // 已经显示，聚焦输入框
                editText?.requestFocus()
                showKeyboard()
                return@post
            }
            createAndShowOverlay()
        }
    }

    /**
     * 隐藏并销毁浮窗
     */
    fun dismiss() {
        mainHandler.post {
            hideKeyboard()
            overlayView?.let {
                runCatching { windowManager.removeView(it) }
                overlayView = null
                editText = null
            }
        }
    }

    val isShowing: Boolean
        get() = overlayView != null

    /**
     * 截图时临时隐藏（避免干预输入框出现在截图中）
     */
    fun hideForScreenshot() {
        mainHandler.post {
            overlayView?.visibility = View.GONE
        }
    }

    /**
     * 截图后恢复显示
     */
    fun restoreAfterScreenshot() {
        mainHandler.post {
            overlayView?.visibility = View.VISIBLE
        }
    }

    private fun createAndShowOverlay() {
        val resources = appContext.resources
        val screenWidth = resources.displayMetrics.widthPixels

        // 步骤1: 构建整体布局 — 半透明遮罩 + 居中卡片
        val rootLayout = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // 半透明黑色遮罩
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener { dismiss() } // 点击遮罩关闭
        }

        // 步骤2: 构建卡片容器
        val cardWidth = (screenWidth * 0.85f).toInt()
        val card = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#FF1E1E2E"))
            }
            // 阻止点击穿透到遮罩
            setOnClickListener { /* 不做任何事 */ }
        }
        val cardParams = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
        rootLayout.addView(card, cardParams)

        // 步骤3: 标题
        val titleView = TextView(appContext).apply {
            text = customTitle ?: "任务干预"
            setTextColor(Color.parseColor("#F0F0F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(titleView)

        // 步骤4: 提示文字
        val hintView = TextView(appContext).apply {
            text = customHint ?: "输入你的指令，Agent 将调整执行方向"
            setTextColor(Color.parseColor("#999999"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(12))
        }
        card.addView(hintView)

        // 步骤5: 输入框
        val inputField = EditText(appContext).apply {
            hint = customInputHint ?: "例如：不对，应该先打开设置..."
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.parseColor("#E0E0E0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#FF2A2A3A"))
                setStroke(dp(1), Color.parseColor("#FF444466"))
            }
        }
        card.addView(inputField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        editText = inputField

        // 步骤6: 按钮行（取消 + 发送）
        val buttonRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }

        val cancelButton = TextView(appContext).apply {
            text = "取消"
            setTextColor(Color.parseColor("#999999"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { dismiss() }
        }
        buttonRow.addView(cancelButton)

        val sendButton = TextView(appContext).apply {
            text = "发送"
            setTextColor(Color.parseColor("#82AAFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#FF333355"))
            }
            setOnClickListener {
                val text = inputField.text.toString().trim()
                if (text.isNotEmpty()) {
                    onInterventionSubmit?.invoke(text)
                    dismiss()
                }
            }
        }
        buttonRow.addView(sendButton)
        card.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 步骤7: 添加到 WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // FLAG_NOT_TOUCH_MODAL: 允许触摸事件传递到浮窗
            // FLAG_WATCH_OUTSIDE_TOUCH: 监听外部点击
            // 不设置 FLAG_NOT_FOCUSABLE 以便输入框能获取焦点和键盘
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            // 软键盘弹出时调整布局
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        runCatching {
            windowManager.addView(rootLayout, params)
            overlayView = rootLayout
            // 自动聚焦输入框并弹出键盘
            inputField.requestFocus()
            mainHandler.postDelayed({ showKeyboard() }, 200)
        }.onFailure {
            Logger.e(Logger.OVERLAY, "Failed to show overlay: ${it.message}")
        }
    }

    private fun showKeyboard() {
        editText?.let { et ->
            val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        editText?.let { et ->
            val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(et.windowToken, 0)
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            appContext.resources.displayMetrics
        ).toInt()
    }
}
