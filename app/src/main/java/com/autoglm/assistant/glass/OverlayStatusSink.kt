package com.autoglm.assistant.glass

import com.autoglm.assistant.service.AgentStatusOverlayController

/**
 * 适配既有 AgentStatusOverlayController 为 StatusSink。
 * 业务目的：不改动 AgentStatusOverlayController 的签名，仅做一层委托，
 * 使其能与眼镜端 GlassStatusSink 并存于 StatusSinkHub。
 */
class OverlayStatusSink(private val ctrl: AgentStatusOverlayController) : StatusSink {
    override fun onTaskStarted(withCoordinator: Boolean) = ctrl.onTaskStarted(withCoordinator)
    override fun onTaskFinished() = ctrl.onTaskFinished()
    override fun updateStep(step: Int) = ctrl.updateStep(step)
    override fun updateThinking(text: String) = ctrl.updateThinking(text)
    override fun updateStreamingTail(accumulated: String) = ctrl.updateStreamingTail(accumulated)
    override fun updateActionStatus(action: String) = ctrl.updateActionStatus(action)
    override fun updateCoordinatorTask(task: String) = ctrl.updateCoordinatorTask(task)
    override fun updatePlannerStatus(status: String) = ctrl.updatePlannerStatus(status)
    override fun hideForScreenshot() = ctrl.hideForScreenshot()
    override fun restoreAfterScreenshot() = ctrl.restoreAfterScreenshot()
}
