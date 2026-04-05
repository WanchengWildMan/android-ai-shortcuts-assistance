package com.autoglm.assistant.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 任务总结悬浮窗
 *
 * 业务目的：任务完成后以半透明悬浮窗展示 Markdown 格式的任务总结，
 * 用户可滚动查看内容并点击关闭按钮或遮罩区域关闭。
 *
 * 流程：
 * 1. PhoneAgent 完成任务总结生成 → onTaskSummary 回调
 * 2. WakeWordService 调用 TaskSummaryOverlay.show(summary)
 * 3. 用户浏览后点击关闭按钮或遮罩 → dismiss()
 */
class TaskSummaryOverlay(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null
    private var contentTextView: TextView? = null

    val isShowing: Boolean
        get() = overlayView != null

    /**
     * 显示任务总结悬浮窗
     * @param summary Markdown 格式的任务总结内容
     */
    fun show(summary: String) {
        mainHandler.post {
            if (overlayView != null) {
                // 已显示 → 更新内容
                updateContent(summary)
                return@post
            }
            createAndShowOverlay(summary)
        }
    }

    /**
     * 关闭并销毁悬浮窗
     */
    fun dismiss() {
        mainHandler.post {
            overlayView?.let {
                runCatching { windowManager.removeView(it) }
                overlayView = null
                contentTextView = null
            }
        }
    }

    /**
     * 更新已显示的总结内容（流式场景）
     */
    fun updateContent(summary: String) {
        mainHandler.post {
            contentTextView?.text = Html.fromHtml(
                markdownToHtml(summary),
                Html.FROM_HTML_MODE_COMPACT
            )
        }
    }

    /**
     * 截图时临时隐藏
     */
    fun hideForScreenshot() {
        mainHandler.post { overlayView?.visibility = View.GONE }
    }

    /**
     * 截图后恢复显示
     */
    fun restoreAfterScreenshot() {
        mainHandler.post { overlayView?.visibility = View.VISIBLE }
    }

    private fun createAndShowOverlay(summary: String) {
        val metrics = appContext.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        // 步骤1: 根布局 — 半透明黑色遮罩 + 居中内容
        val rootLayout = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener { dismiss() }
        }

        // 步骤2: 卡片容器 — 圆角深色背景
        val cardWidth = (screenWidth * 0.88f).toInt()
        val cardMaxHeight = (screenHeight * 0.65f).toInt()
        val card = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F01E1E2E"))
            }
            setOnClickListener { /* 阻止点击穿透到遮罩 */ }
        }
        val cardParams = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            // 限制卡片最大高度
        }
        rootLayout.addView(card, cardParams)

        // 步骤3: 标题栏 — "任务总结" + 关闭按钮
        val titleBar = createTitleBar()
        card.addView(titleBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 步骤4: 分割线
        val divider = View(appContext).apply {
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }
        card.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(8); bottomMargin = dp(12) })

        // 步骤5: 可滚动内容区域
        val scrollView = ScrollView(appContext).apply {
            isVerticalScrollBarEnabled = true
            // 限制最大高度避免超出屏幕
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val contentView = TextView(appContext).apply {
            text = Html.fromHtml(markdownToHtml(summary), Html.FROM_HTML_MODE_COMPACT)
            setTextColor(Color.parseColor("#D8D8D8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(2), 0, dp(2), dp(8))
            // 支持文本内链接可点击
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            setLinkTextColor(Color.parseColor("#82AAFF"))
        }
        contentTextView = contentView
        scrollView.addView(contentView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        card.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            // 使用权重限制最大高度
        })

        // 步骤6: 底部关闭按钮
        val closeBar = createCloseBar()
        card.addView(closeBar, LinearLayout.LayoutParams(
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
            // 不可聚焦（无输入框）+ 不拦截其他触摸
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        runCatching {
            windowManager.addView(rootLayout, params)
            overlayView = rootLayout
        }.onFailure {
            android.util.Log.e("TaskSummaryOverlay", "显示总结悬浮窗失败: ${it.message}")
        }
    }

    /**
     * 创建标题栏：左侧图标+标题文字，右侧关闭按钮
     */
    private fun createTitleBar(): LinearLayout {
        return LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            // 左侧：标题
            val titleText = TextView(appContext).apply {
                text = "  任务总结"
                setTextColor(Color.parseColor("#F0F0F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, Typeface.BOLD)
            }
            addView(titleText, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))

            // 右侧：关闭按钮
            val closeButton = TextView(appContext).apply {
                text = "  \u2715  " // X 符号
                setTextColor(Color.parseColor("#999999"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#22FFFFFF"))
                }
                setOnClickListener { dismiss() }
            }
            addView(closeButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    /**
     * 创建底部关闭按钮栏
     */
    private fun createCloseBar(): LinearLayout {
        return LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))

            val dismissButton = TextView(appContext).apply {
                text = "关闭"
                setTextColor(Color.parseColor("#82AAFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(8), dp(32), dp(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#FF333355"))
                }
                setOnClickListener { dismiss() }
            }
            addView(dismissButton)
        }
    }

    /**
     * Markdown 简易转换为 HTML
     * 支持: **粗体**, *斜体*, # 标题, - 列表项, 换行
     */
    private fun markdownToHtml(markdown: String): String {
        val lines = markdown.split("\n")
        val html = StringBuilder()
        var inList = false

        for (line in lines) {
            val trimmed = line.trim()
            when {
                // 空行
                trimmed.isEmpty() -> {
                    if (inList) { html.append("</ul>"); inList = false }
                    html.append("<br>")
                }
                // 标题 (## / # )
                trimmed.startsWith("### ") -> {
                    if (inList) { html.append("</ul>"); inList = false }
                    html.append("<h4><font color='#C3E88D'>${formatInlineStyles(trimmed.removePrefix("### "))}</font></h4>")
                }
                trimmed.startsWith("## ") -> {
                    if (inList) { html.append("</ul>"); inList = false }
                    html.append("<h3><font color='#C3E88D'>${formatInlineStyles(trimmed.removePrefix("## "))}</font></h3>")
                }
                trimmed.startsWith("# ") -> {
                    if (inList) { html.append("</ul>"); inList = false }
                    html.append("<h3><font color='#C3E88D'>${formatInlineStyles(trimmed.removePrefix("# "))}</font></h3>")
                }
                // 无序列表项 (- / * )
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    if (!inList) { html.append("<ul>"); inList = true }
                    html.append("<li>${formatInlineStyles(trimmed.substring(2))}</li>")
                }
                // 普通段落
                else -> {
                    if (inList) { html.append("</ul>"); inList = false }
                    html.append("<p>${formatInlineStyles(trimmed)}</p>")
                }
            }
        }
        if (inList) html.append("</ul>")
        return html.toString()
    }

    /**
     * 行内样式转换: **粗体** → <b>, *斜体* → <i>
     */
    private fun formatInlineStyles(text: String): String {
        var result = text
        // **粗体** → <b>粗体</b>
        result = result.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b><font color='#F0F0F0'>$1</font></b>")
        // *斜体* → <i>斜体</i>（排除已处理的粗体）
        result = result.replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "<i>$1</i>")
        // `代码` → <tt>代码</tt>
        result = result.replace(Regex("`(.+?)`"), "<tt><font color='#F78C6C'>$1</font></tt>")
        return result
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            appContext.resources.displayMetrics
        ).toInt()
    }
}
