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
import android.widget.LinearLayout
import android.widget.TextView

class AgentStatusOverlayController(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null
    private var coordinatorTextView: TextView? = null   // 协调器行（独立预算最多2行）
    private var executorTextView: TextView? = null      // 执行器行（独立预算最多2行）

    // 点击位置指示器 — 在屏幕上显示一个小圆点标识最近一次点击的位置
    private var tapIndicatorView: View? = null
    private var tapIndicatorParams: WindowManager.LayoutParams? = null

    // 停止按钮回调
    var onStopRequested: (() -> Unit)? = null
    // 人工介入按钮回调
    var onInterventionRequested: (() -> Unit)? = null

    // 防抖：避免高频 updateThinking 导致 handler 积压
    private var pendingRefresh = false
    private val refreshRunnable = Runnable {
        pendingRefresh = false
        ensureView()
        updateTextViews()
        overlayView?.visibility = if (isTaskRunning && !isHiddenForScreenshot) View.VISIBLE else View.GONE
    }

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

    @Volatile
    private var plannerStatusLine: String = ""

    fun onTaskStarted(withCoordinator: Boolean) {
        coordinatorEnabled = withCoordinator
        coordinatorTaskLine = ""
        isTaskRunning = true
        refresh(immediate = true)
    }

    fun onTaskFinished() {
        isTaskRunning = false
        isHiddenForScreenshot = false
        currentStep = 0
        thinkingLine = ""
        coordinatorEnabled = false
        coordinatorTaskLine = ""
        plannerStatusLine = ""
        refresh(immediate = true)
    }

    fun updateStep(step: Int) {
        currentStep = step
        thinkingLine = ""       // 清除上一步残留的思考内容
        plannerStatusLine = ""  // 执行阶段开始，清除规划/优化状态
        refresh(immediate = true)
    }

    fun updateThinking(thinking: String) {
        thinkingLine = thinking
            .replace(Regex("\\s+"), " ")
            .trim()
        refresh()
    }

    /**
     * 更新流式内容 — 模型正在生成时，显示尾部内容让用户看到实时进展
     */
    fun updateStreamingTail(accumulated: String) {
        // 取末尾40字符显示，滞动效果
        val tail = accumulated.replace(Regex("\\s+"), " ").trim()
        thinkingLine = if (tail.length > 40) "…" + tail.takeLast(40) else tail
        refresh()  // 防抖，不积压
    }

    /**
     * 操作执行时更新状态 — 显示当前正在执行的操作
     */
    fun updateActionStatus(action: String) {
        thinkingLine = action
            .replace(Regex("\\s+"), " ")
            .trim()
        refresh(immediate = true)
    }

    /**
     * 隐藏悬浮窗 — 同步阻塞等待主线程完成，确保点击前悬浮窗已消失
     * 时序保证：使用 CountDownLatch 阻塞调用线程，直到主线程确认 View.GONE 已生效
     * 防止 MIUI SmartPower 冻结主线程时，IO 线程先于隐藏完成就执行了点击
     */
    fun hideForScreenshot() {
        isHiddenForScreenshot = true
        hideTapIndicator()
        // 取消所有待执行的刷新，防止 debounce 计划的 refresh 在隐藏后重新显示
        mainHandler.removeCallbacks(refreshRunnable)
        pendingRefresh = false

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // 已在主线程，直接执行
            overlayView?.visibility = View.GONE
        } else {
            // 非主线程：同步等待主线程完成隐藏
            val latch = java.util.concurrent.CountDownLatch(1)
            mainHandler.post {
                overlayView?.visibility = View.GONE
                latch.countDown()
            }
            // 最多等待 500ms（兼容 MIUI SmartPower 主线程被冻结的场景）
            latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    fun restoreAfterScreenshot() {
        isHiddenForScreenshot = false
        refresh(immediate = true)
    }

    fun updateCoordinatorTask(task: String) {
        coordinatorTaskLine = task
            .replace(Regex("\\s+"), " ")
            .trim()
        refresh(immediate = true)
    }

    /**
     * 更新规划器/优化器阶段状态
     * 业务目的：在任务规划、优化等前置阶段显示悬浮状态，让用户知道系统在工作
     */
    fun updatePlannerStatus(status: String) {
        plannerStatusLine = status
        refresh(immediate = true)
    }

    fun release() {
        mainHandler.post {
            overlayView?.let {
                runCatching { windowManager.removeView(it) }
            }
            overlayView = null
            coordinatorTextView = null
            executorTextView = null
            tapIndicatorView?.let {
                runCatching { windowManager.removeView(it) }
            }
            tapIndicatorView = null
            tapIndicatorParams = null
        }
    }

    private fun refresh(immediate: Boolean = false) {
        if (immediate) {
            // 关键状态变化（step切换、任务开始/结束）立即刷新
            mainHandler.removeCallbacks(refreshRunnable)
            pendingRefresh = false
            mainHandler.post(refreshRunnable)
        } else {
            // 高频更新（thinking/streaming）防抖，300ms 内合并，避免文字闪动
            if (!pendingRefresh) {
                pendingRefresh = true
                mainHandler.postDelayed(refreshRunnable, 300)
            }
        }
    }

    /**
     * 分别更新协调器和执行器两个 TextView 的内容和可见性
     */
    private fun updateTextViews() {
        val coordView = coordinatorTextView ?: return
        val execView = executorTextView ?: return

        // 规划/优化阶段：只在执行器行显示，隐藏协调器行
        if (plannerStatusLine.isNotBlank()) {
            coordView.visibility = View.GONE
            execView.text = plannerStatusLine
            execView.visibility = View.VISIBLE
            return
        }

        // 执行器行始终显示
        val stepText = if (currentStep > 0) "第${currentStep}步" else "执行中"
        execView.text = if (thinkingLine.isBlank()) {
            "⚡ $stepText | 思考中..."
        } else {
            "⚡ $stepText | $thinkingLine"
        }
        execView.visibility = View.VISIBLE

        // 协调器行：仅当协调器模式时显示
        if (coordinatorEnabled) {
            coordView.text = if (coordinatorTaskLine.isBlank()) {
                "🎯 正在规划当前任务"
            } else {
                "🎯 $coordinatorTaskLine"
            }
            coordView.visibility = View.VISIBLE
        } else {
            coordView.visibility = View.GONE
        }
    }

    private fun ensureView() {
        if (overlayView != null) return

        val screenWidth = appContext.resources.displayMetrics.widthPixels
        val textMaxWidth = (screenWidth * 0.72f).toInt()

        // 步骤1: 根布局 — 水平（文字列 + 按钮列）
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#CC121417"))
            }
            visibility = View.GONE
        }

        // 步骤2: 文字列 — 竖向排列（协调器行 + 执行器行），各自独立行数预算
        val textColumn = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 协调器行：最多2行，独立截断
        val coordTv = TextView(appContext).apply {
            setTextColor(Color.parseColor("#C3E88D"))  // 绿色区分
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = textMaxWidth
            setPadding(0, 0, 0, dp(2))
            visibility = View.GONE
        }
        textColumn.addView(coordTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 执行器行：最多2行，独立截断
        val execTv = TextView(appContext).apply {
            setTextColor(Color.parseColor("#F7F9FB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = textMaxWidth
        }
        textColumn.addView(execTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        container.addView(textColumn, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        // 步骤3: 按钮列（竖向：介入 + 停止）
        val btnColumn = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, 0, 0)
        }

        val interventionBtn = TextView(appContext).apply {
            text = "✎"
            setTextColor(Color.parseColor("#82AAFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            val size = dp(26)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                bottomMargin = dp(2)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FFFFFF"))
            }
            setOnClickListener { onInterventionRequested?.invoke() }
        }
        btnColumn.addView(interventionBtn)

        val stopBtn = TextView(appContext).apply {
            text = "■"
            setTextColor(Color.parseColor("#FF6B6B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            val size = dp(26)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FFFFFF"))
            }
            setOnClickListener { onStopRequested?.invoke() }
        }
        btnColumn.addView(stopBtn)

        container.addView(btnColumn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 步骤4: 窗口参数 — 固定宽度避免内容变化时按钮位置跳动
        val fixedWidth = (screenWidth * 0.85f).toInt()
        val params = WindowManager.LayoutParams(
            fixedWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(28)
        }

        runCatching { windowManager.addView(container, params) }
            .onSuccess {
                overlayView = container
                coordinatorTextView = coordTv
                executorTextView = execTv
            }
    }

    /**
     * 在屏幕上显示点击位置指示器（红色半透明圆点）
     * 截图和操作前自动隐藏，不干扰模型截图
     * @param x 实际屏幕像素坐标 X
     * @param y 实际屏幕像素坐标 Y
     */
    fun showTapIndicator(x: Int, y: Int) {
        mainHandler.post {
            // 指示器尺寸 — HARD: 直径20dp，红色半透明
            val sizePx = dp(20)
            val halfSize = sizePx / 2

            if (tapIndicatorView == null) {
                // 首次创建指示器 View
                val dot = View(appContext).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#99FF4444"))  // 半透明红
                        setStroke(dp(1), Color.parseColor("#CCFF0000"))
                    }
                }

                val params = WindowManager.LayoutParams(
                    sizePx, sizePx,
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
                    gravity = Gravity.TOP or Gravity.START
                    this.x = x - halfSize
                    this.y = y - halfSize
                }

                runCatching { windowManager.addView(dot, params) }
                    .onSuccess {
                        tapIndicatorView = dot
                        tapIndicatorParams = params
                    }
            } else {
                // 已存在，更新位置
                tapIndicatorParams?.let { params ->
                    params.x = x - halfSize
                    params.y = y - halfSize
                    tapIndicatorView?.visibility = View.VISIBLE
                    runCatching { windowManager.updateViewLayout(tapIndicatorView, params) }
                }
            }
        }
    }

    /**
     * 隐藏点击位置指示器（截图前/操作前调用）
     */
    fun hideTapIndicator() {
        mainHandler.post {
            tapIndicatorView?.visibility = View.GONE
        }
    }

    private fun dp(value: Int): Int {
        return (value * appContext.resources.displayMetrics.density).toInt()
    }

    private fun compact(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars - 1) + "…"
    }
}
