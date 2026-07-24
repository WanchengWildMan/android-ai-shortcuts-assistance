package com.autoglm.assistant.glass

/**
 * Agent 状态推送抽象。
 *
 * 业务目的：把 Agent 执行过程中的状态更新统一抽象，使手机悬浮窗与眼镜端渲染
 * 成为同一组状态的两个消费者，避免在 AgentCallbackBinder 里重复分支。
 * 接口方法与 AgentStatusOverlayController 的 public 方法一一对应。
 */
interface StatusSink {
    fun onTaskStarted(withCoordinator: Boolean)
    fun onTaskFinished()
    fun updateStep(step: Int)
    fun updateThinking(text: String)
    fun updateStreamingTail(accumulated: String)
    fun updateActionStatus(action: String)
    fun updateCoordinatorTask(task: String)
    fun updatePlannerStatus(status: String)
    /** 截图前隐藏（仅手机悬浮窗需要，眼镜端无悬浮窗遮挡问题） */
    fun hideForScreenshot()
    fun restoreAfterScreenshot()
}

/** 空实现：眼镜通道未启用或会话未就绪时占位 */
object NoopStatusSink : StatusSink {
    override fun onTaskStarted(withCoordinator: Boolean) {}
    override fun onTaskFinished() {}
    override fun updateStep(step: Int) {}
    override fun updateThinking(text: String) {}
    override fun updateStreamingTail(accumulated: String) {}
    override fun updateActionStatus(action: String) {}
    override fun updateCoordinatorTask(task: String) {}
    override fun updatePlannerStatus(status: String) {}
    override fun hideForScreenshot() {}
    override fun restoreAfterScreenshot() {}
}

/** 多路分发：同一组状态同时推给手机悬浮窗与眼镜端 */
class StatusSinkHub(private val sinks: List<StatusSink>) : StatusSink {
    constructor(vararg sinks: StatusSink) : this(sinks.toList())
    override fun onTaskStarted(withCoordinator: Boolean) = sinks.forEach { it.onTaskStarted(withCoordinator) }
    override fun onTaskFinished() = sinks.forEach { it.onTaskFinished() }
    override fun updateStep(step: Int) = sinks.forEach { it.updateStep(step) }
    override fun updateThinking(text: String) = sinks.forEach { it.updateThinking(text) }
    override fun updateStreamingTail(accumulated: String) = sinks.forEach { it.updateStreamingTail(accumulated) }
    override fun updateActionStatus(action: String) = sinks.forEach { it.updateActionStatus(action) }
    override fun updateCoordinatorTask(task: String) = sinks.forEach { it.updateCoordinatorTask(task) }
    override fun updatePlannerStatus(status: String) = sinks.forEach { it.updatePlannerStatus(status) }
    override fun hideForScreenshot() = sinks.forEach { it.hideForScreenshot() }
    override fun restoreAfterScreenshot() = sinks.forEach { it.restoreAfterScreenshot() }
}
