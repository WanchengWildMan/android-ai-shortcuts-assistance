package com.autoglm.assistant.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class AgentStatusOverlayController(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var statusView: TextView? = null

    @Volatile
    private var isTaskRunning = false

    @Volatile
    private var isHiddenForScreenshot = false

    @Volatile
    private var currentStep = 0

    @Volatile
    private var thinkingLine: String = ""

    @Volatile
    private var coordinatorEnabled = false

    @Volatile
    private var coordinatorTaskLine: String = ""

    fun onTaskStarted(withCoordinator: Boolean) {
        coordinatorEnabled = withCoordinator
        coordinatorTaskLine = ""
        isTaskRunning = true
        refresh()
    }

    fun onTaskFinished() {
        isTaskRunning = false
        isHiddenForScreenshot = false
        currentStep = 0
        thinkingLine = ""
        coordinatorEnabled = false
        coordinatorTaskLine = ""
        refresh()
    }

    fun updateStep(step: Int) {
        currentStep = step
        refresh()
    }

    fun updateThinking(thinking: String) {
        thinkingLine = thinking
            .replace(Regex("\\s+"), " ")
            .trim()
        refresh()
    }

    fun hideForScreenshot() {
        isHiddenForScreenshot = true
        refresh()
    }

    fun restoreAfterScreenshot() {
        isHiddenForScreenshot = false
        refresh()
    }

    fun updateCoordinatorTask(task: String) {
        coordinatorTaskLine = task
            .replace(Regex("\\s+"), " ")
            .trim()
        refresh()
    }

    fun release() {
        mainHandler.post {
            statusView?.let {
                runCatching { windowManager.removeView(it) }
            }
            statusView = null
        }
    }

    private fun refresh() {
        mainHandler.post {
            ensureView()
            val textView = statusView ?: return@post
            textView.text = buildStatusText()
            textView.visibility = if (isTaskRunning && !isHiddenForScreenshot) View.VISIBLE else View.GONE
        }
    }

    private fun ensureView() {
        if (statusView != null) return

        val view = TextView(appContext).apply {
            setTextColor(Color.parseColor("#F7F9FB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            includeFontPadding = false
            isSingleLine = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (resources.displayMetrics.widthPixels * 0.92f).toInt()
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#CC121417"))
            }
            visibility = View.GONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(28)
        }

        runCatching { windowManager.addView(view, params) }
            .onSuccess { statusView = view }
    }

    private fun buildStatusText(): String {
        val stepText = if (currentStep > 0) "第${currentStep}步" else "执行中"
        val detailLine = if (thinkingLine.isBlank()) {
            "$stepText | 思考中..."
        } else {
            "$stepText | $thinkingLine"
        }
        val compactDetailLine = compact(detailLine, 56)
        if (!coordinatorEnabled) return compactDetailLine

        val coordinatorLine = if (coordinatorTaskLine.isBlank()) {
            "协调器：正在规划当前任务"
        } else {
            "协调器：$coordinatorTaskLine"
        }
        return "${compact(coordinatorLine, 46)}\n$compactDetailLine"
    }

    private fun dp(value: Int): Int {
        return (value * appContext.resources.displayMetrics.density).toInt()
    }

    private fun compact(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars - 1) + "…"
    }
}
