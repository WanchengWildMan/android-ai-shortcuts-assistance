package com.autoglm.assistant.service

import android.content.ClipData
import android.content.ClipboardManager
import com.autoglm.assistant.util.Logger
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * ADB Keyboard 未安装提示悬浮窗
 *
 * 业务目的：当文字输入操作检测到 ADB Keyboard 未安装时，弹出半透明悬浮卡片，
 * 展示下载链接并提供一键复制和打开浏览器两种操作，方便用户安装。
 *
 * 流程：
 * 1. ActionExecutor 触发 onAdbKeyboardNotInstalled 回调
 * 2. WakeWordService 调用 AdbKeyboardOverlay.show()
 * 3. 用户点击"复制链接"或"打开下载页"后手动安装
 */
class AdbKeyboardOverlay(private val context: Context) {

    // HARD: ADB Keyboard GitHub 发布页地址
    private val downloadUrl = "https://github.com/senzhk/ADBKeyBoard/releases"

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null

    val isShowing: Boolean get() = overlayView != null

    /** 显示安装提示悬浮窗 */
    fun show() {
        mainHandler.post {
            if (overlayView != null) return@post
            createAndShowOverlay()
        }
    }

    /** 关闭悬浮窗 */
    fun dismiss() {
        mainHandler.post {
            overlayView?.let {
                runCatching { windowManager.removeView(it) }
                overlayView = null
            }
        }
    }

    private fun createAndShowOverlay() {
        val metrics = appContext.resources.displayMetrics
        val screenWidth = metrics.widthPixels

        // 步骤1: 根布局 — 半透明遮罩，点击关闭
        val rootLayout = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener { dismiss() }
        }

        // 步骤2: 卡片容器 — 圆角深色背景（与 TaskSummaryOverlay 保持一致样式）
        val cardWidth = (screenWidth * 0.88f).toInt()
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
        rootLayout.addView(card, LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 步骤3: 标题
        val title = TextView(appContext).apply {
            text = "安装 ADB Keyboard"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        }
        card.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(10) })

        // 步骤4: 说明文字
        val desc = TextView(appContext).apply {
            text = "AutoGLM 需要 ADB Keyboard 来输入文字（广播方案，兼容多数应用）。\n\n安装后请在 设置 → 语言与输入法 中启用 ADB Keyboard。"
            setTextColor(Color.parseColor("#CCCCCC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        card.addView(desc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(10) })

        // 步骤5: 链接栏 — 左侧显示 URL，右侧"复制"按钮
        val urlRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#22FFFFFF"))
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val urlText = TextView(appContext).apply {
            text = downloadUrl
            setTextColor(Color.parseColor("#82AAFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val copyBtn = TextView(appContext).apply {
            text = "复制"
            setTextColor(Color.parseColor("#82AAFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("url", downloadUrl))
                Toast.makeText(appContext, "链接已复制", Toast.LENGTH_SHORT).show()
            }
        }
        urlRow.addView(urlText)
        urlRow.addView(copyBtn)
        card.addView(urlRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(14) })

        // 步骤6: 底部按钮行 — "打开下载页" 和 "关闭"
        val btnRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val openBtn = makeButton("打开下载页") {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
            }
            dismiss()
        }
        val closeBtn = makeButton("关闭") { dismiss() }
        btnRow.addView(openBtn)
        btnRow.addView(closeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = dp(10) })
        card.addView(btnRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 步骤7: 添加到 WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        runCatching {
            windowManager.addView(rootLayout, params)
            overlayView = rootLayout
        }.onFailure {
            Logger.e(Logger.OVERLAY, "显示悬浮窗失败: ${it.message}")
        }
    }

    private fun makeButton(label: String, onClick: () -> Unit): TextView {
        return TextView(appContext).apply {
            text = label
            setTextColor(Color.parseColor("#82AAFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#22FFFFFF"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int {
        return (value * appContext.resources.displayMetrics.density).toInt()
    }
}
