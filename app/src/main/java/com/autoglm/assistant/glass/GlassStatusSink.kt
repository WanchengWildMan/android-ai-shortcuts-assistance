package com.autoglm.assistant.glass

import android.os.Handler
import android.os.Looper
import com.autoglm.assistant.App
import com.autoglm.assistant.util.Logger

/**
 * 眼镜端状态推送实现：把 Agent 状态经 sendCustomCmd 推送到眼镜端渲染。
 *
 * 业务目的：作为 StatusSink 的眼镜端消费者，与手机悬浮窗并行接收同一组状态。
 *
 * 关键约束：
 *   - 仅在链路就绪 && 会话构建完成后才推送，否则静默丢弃（避免断链/未就绪时调用导致错误）
 *   - 高频更新（updateThinking/updateStreamingTail）防抖 150ms，避免淹没蓝牙带宽
 *   - updateStreamingTail 取末尾 40 字符，与手机悬浮窗一致
 */
class GlassStatusSink(private val sessionManager: GlassSessionManager) : StatusSink {

    @Volatile private var running = false
    @Volatile private var step = 0
    @Volatile private var thinking = ""
    @Volatile private var coordEnabled = false
    @Volatile private var coordTask = ""
    @Volatile private var planner = ""
    @Volatile private var lastSummary: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var pending = false
    private val flushRunnable = Runnable {
        pending = false
        push()
    }

    /** 防抖调度：150ms 内合并多次更新 */
    private fun schedule(immediate: Boolean = false) {
        if (immediate) {
            mainHandler.removeCallbacks(flushRunnable)
            pending = false
            mainHandler.post(flushRunnable)
        } else if (!pending) {
            pending = true
            mainHandler.postDelayed(flushRunnable, 150L)
        }
    }

    override fun onTaskStarted(withCoordinator: Boolean) {
        running = true; coordEnabled = withCoordinator; coordTask = ""; thinking = ""; planner = ""
        schedule(immediate = true)
    }

    override fun onTaskFinished() {
        running = false; step = 0; thinking = ""; coordEnabled = false; coordTask = ""; planner = ""
        // 任务结束立即推送一次最终状态 + task_done
        schedule(immediate = true)
        pushTaskDone()
    }

    override fun updateStep(step: Int) {
        this.step = step; thinking = ""; planner = ""
        schedule(immediate = true)
    }

    override fun updateThinking(text: String) {
        thinking = text
        schedule()
    }

    override fun updateStreamingTail(accumulated: String) {
        val tail = accumulated.replace(Regex("\\s+"), " ").trim()
        thinking = if (tail.length > 40) "…$tail".takeLast(41) else tail
        schedule()
    }

    override fun updateActionStatus(action: String) {
        thinking = action
        schedule(immediate = true)
    }

    override fun updateCoordinatorTask(task: String) {
        coordTask = task
        schedule(immediate = true)
    }

    override fun updatePlannerStatus(status: String) {
        planner = status
        schedule(immediate = true)
    }

    /** 眼镜端无悬浮窗遮挡截图问题，无需隐藏 */
    override fun hideForScreenshot() {}
    override fun restoreAfterScreenshot() {}

    /** 推送当前状态快照到眼镜端 */
    private fun push() {
        val link = App.instance.sharedLink ?: return
        if (!sessionManager.isLinkReady || !sessionManager.sessionBuilt) return
        runCatching {
            val caps = encodeStatusCaps(running, step, thinking, coordEnabled, coordTask, planner, lastSummary)
            link.sendCustomCmd(GlassProtocol.KEY_PHONE_TO_GLASS, caps)
        }.onFailure { Logger.w(Logger.GLASS, "推送状态失败", it) }
    }

    /** 推送任务结束（含最终摘要） */
    fun pushTaskDone(summary: String? = null) {
        lastSummary = summary
        val link = App.instance.sharedLink ?: return
        if (!sessionManager.isLinkReady || !sessionManager.sessionBuilt) return
        runCatching {
            link.sendCustomCmd(GlassProtocol.KEY_PHONE_TO_GLASS, encodeTaskDoneCaps(summary ?: lastSummary))
        }.onFailure { Logger.w(Logger.GLASS, "推送 task_done 失败", it) }
    }

    /** 推送纯文本提示到眼镜端 */
    fun pushLog(text: String) {
        val link = App.instance.sharedLink ?: return
        if (!sessionManager.isLinkReady || !sessionManager.sessionBuilt) return
        runCatching {
            link.sendCustomCmd(GlassProtocol.KEY_PHONE_TO_GLASS, encodeLogCaps(text))
        }.onFailure { Logger.w(Logger.GLASS, "推送 log 失败", it) }
    }
}
