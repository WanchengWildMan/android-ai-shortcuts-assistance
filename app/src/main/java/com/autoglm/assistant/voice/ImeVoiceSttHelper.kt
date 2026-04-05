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
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 通过 IME（输入法）语音功能实现 STT 的辅助类
 *
 * 流程：
 * 1. 弹出悬浮输入框 → 获取焦点 → 输入法弹出
 * 2. 用 root 权限模拟长按输入法空格键 → 触发语音输入
 * 3. 监听输入框文字变化 → 当文字稳定后作为识别结果返回
 * 4. 收起输入框
 *
 * 注意：空格键坐标因设备和键盘布局而异，需在设置中配置
 */
class ImeVoiceSttHelper(private val context: Context) {

    companion object {
        private const val TAG = "ImeVoiceSTT"
        // 文字稳定后等待时间（毫秒），超过此时间无新文字则认为识别完成
        private const val TEXT_STABLE_DELAY_MS = 2000L
        // 长按空格键持续时间（毫秒）
        private const val LONG_PRESS_DURATION_MS = 600
        // 弹出输入法后等待键盘就绪的延迟（毫秒）
        private const val KEYBOARD_READY_DELAY_MS = 800L
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

            // 步骤4: 等待键盘弹出后模拟长按空格
            mainHandler.postDelayed({
                if (!isActive) return@postDelayed
                simulateLongPressSpace()
                onStatusChange?.invoke("请说话...")
                startTextMonitor()
                startTimeout()
            }, KEYBOARD_READY_DELAY_MS)
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
     * 模拟长按空格键（需要 root 权限）
     */
    private fun simulateLongPressSpace() {
        Thread {
            try {
                // 使用 input swipe 在同一点模拟长按
                val cmd = "su -c 'input swipe $spaceX $spaceY $spaceX $spaceY $LONG_PRESS_DURATION_MS'"
                Log.i(TAG, "模拟长按空格键: ($spaceX, $spaceY), 持续 ${LONG_PRESS_DURATION_MS}ms")
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
                    Log.e(TAG, "模拟长按失败: exitCode=$exitCode, error=$error")
                    mainHandler.post {
                        onError?.invoke("模拟长按失败（需要 root 权限）: $error")
                        cleanup()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "模拟长按异常", e)
                mainHandler.post {
                    onError?.invoke("模拟长按异常: ${e.message}")
                    cleanup()
                }
            }
        }.start()
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
                if (text.isBlank()) return

                lastText = text
                onStatusChange?.invoke("识别中: $text")

                // 每次文字变化，重置稳定计时器
                stableCheckRunnable?.let { mainHandler.removeCallbacks(it) }
                stableCheckRunnable = Runnable {
                    if (isActive && lastText.isNotBlank()) {
                        Log.i(TAG, "文字已稳定: $lastText")
                        isActive = false
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
     * 创建悬浮输入框
     */
    private fun createOverlay() {
        if (overlayView != null) return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F0181A20"))
                setStroke(dp(1), Color.parseColor("#4D64B5F6"))
            }
        }

        // 提示文字
        val hintLabel = TextView(context).apply {
            text = "🎤 语音输入中..."
            setTextColor(Color.parseColor("#E8EAED"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        container.addView(hintLabel)
        hintText = hintLabel

        // 输入框（接收 IME 语音识别的文字）
        val et = EditText(context).apply {
            hint = "等待语音输入..."
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isFocusable = true
            isFocusableInTouchMode = true
            maxLines = 3
        }
        val etParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        container.addView(et, etParams)
        editText = et

        overlayView = container

        // WindowManager 参数 —— 底部显示（靠近键盘）
        val screenWidth = context.resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams(
            (screenWidth * 0.85f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(300)  // 键盘上方
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
