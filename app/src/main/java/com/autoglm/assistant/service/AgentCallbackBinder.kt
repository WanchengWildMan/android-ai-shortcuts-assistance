package com.autoglm.assistant.service

import com.autoglm.assistant.ai.IntentResult
import com.autoglm.assistant.core.agent.AgentListener
import com.autoglm.assistant.core.agent.PhoneAgent
import com.autoglm.assistant.core.agent.StepResult
import com.autoglm.assistant.core.planner.CoordinatorDecision
import com.autoglm.assistant.core.planner.PlannedSubTask
import com.autoglm.assistant.core.planner.SupervisionResult
import com.autoglm.assistant.core.planner.TaskPlan
import com.autoglm.assistant.service.WakeWordService.AgentMessage
import com.autoglm.assistant.service.WakeWordService.AgentMessageType
import com.autoglm.assistant.service.WakeWordService.CoordinatorMessage
import com.autoglm.assistant.service.WakeWordService.CoordinatorMessageType
import com.autoglm.assistant.service.WakeWordService.ServiceState
import com.autoglm.assistant.util.Logger

/**
 * Agent 回调绑定器 — AgentListener 接口实现
 *
 * 【目的】将 PhoneAgent 的事件通知绑定到 WakeWordService 的 UI 与状态管理上下文，
 *          从 WakeWordService.initializePhoneAgent() 中提取（原 385 行），降低 God Class 行数。
 *
 * 【依赖】通过 WakeWordService 实例访问 internal 字段：
 *   - 状态流：_agentMessage、_coordinatorMessage、_serviceState
 *   - 悬浮窗：agentStatusOverlay、interventionOverlay、taskSummaryOverlay
 *   - 方法：updateNotification()、speak()、startWakeWordListening()、showTaskSummaryOverlay()
 *
 * 【调用位置】WakeWordService.initializePhoneAgent() → agent.listener = AgentCallbackBinder(this)
 */
internal class AgentCallbackBinder(
    private val service: WakeWordService
) : AgentListener {

    companion object {
        private const val COORDINATOR_STREAM_DEBOUNCE_MS = 300L
    }

    // ---- 流式内容缓冲与状态标志 ----
    private var lastCoordinatorStreamEmitAtMs = 0L
    private val agentStreamingContent = StringBuilder()
    private val optimizerStreamingContent = StringBuilder()
    private var isOptimizing = false
    private val summaryStreamingContent = StringBuilder()
    private var isSummarizing = false
    private var summaryAlreadySent = false
    private val plannerStreamingContent = StringBuilder()
    private var isPlanning = false

    /**
     * 限流发送协调器消息流（300ms 去抖）
     */
    private fun emitCoordinatorStream(type: CoordinatorMessageType, content: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastCoordinatorStreamEmitAtMs) < COORDINATOR_STREAM_DEBOUNCE_MS) {
            return
        }
        lastCoordinatorStreamEmitAtMs = now
        service._coordinatorMessage.value = CoordinatorMessage(type = type, content = content)
    }

    /**
     * 将 listener 注册到 agent
     *
     * 【流程】设置 agent.listener = this
     */
    fun bind(agent: PhoneAgent) {
        agent.listener = this
    }

    // ===== 1. Agent 基础回调 =====

    override fun onStepStart(step: Int) {
        service.statusSink.updateStep(step)
        agentStreamingContent.clear()
        service.updateNotification("执行中 第${step}步...")
    }

    override fun onThinking(thought: String) {
        service._agentMessage.value = AgentMessage(thought, AgentMessageType.THINKING)
        service.statusSink.updateThinking(thought)
    }

    override fun onAction(action: String) {
        service._agentMessage.value = AgentMessage(action, AgentMessageType.ACTION)
        service.statusSink.updateActionStatus(action)
    }

    override fun onBeforeScreenshot() {
        service.statusSink.hideForScreenshot()
        service.interventionOverlay?.hideForScreenshot()
        service.taskSummaryOverlay?.hideForScreenshot()
    }

    override fun onAfterScreenshot() {
        service.statusSink.restoreAfterScreenshot()
        service.interventionOverlay?.restoreAfterScreenshot()
        service.taskSummaryOverlay?.restoreAfterScreenshot()
    }

    override fun onBeforeAction() {
        service.statusSink.hideForScreenshot()
        service.interventionOverlay?.hideForScreenshot()
        service.taskSummaryOverlay?.hideForScreenshot()
    }

    override fun onAfterAction() {
        service.statusSink.restoreAfterScreenshot()
        service.interventionOverlay?.restoreAfterScreenshot()
        service.taskSummaryOverlay?.restoreAfterScreenshot()
    }

    // ===== 2. Prompt 优化器回调 =====

    override fun onPromptOptimizing() {
        isOptimizing = true
        optimizerStreamingContent.clear()
        service.statusSink.updatePlannerStatus("正在优化指令...")
        emitCoordinatorStream(
            type = CoordinatorMessageType.OPTIMIZER_STREAMING,
            content = "",
            force = true
        )
    }

    override fun onPromptOptimized(optimizedPrompt: String) {
        isOptimizing = false
        service.statusSink.updatePlannerStatus("")
        service._coordinatorMessage.value = CoordinatorMessage(
            type = CoordinatorMessageType.OPTIMIZER_COMPLETE,
            content = optimizedPrompt
        )
    }

    // ===== 3. 意图识别器回调 =====

    override fun onIntentRecognizing() {
        Logger.d(Logger.INTENT, "IntentRecognizer: recognizing...")
        service.statusSink.updatePlannerStatus("正在识别意图...")
    }

    override fun onIntentRecognized(result: IntentResult) {
        service.statusSink.updatePlannerStatus("")
        if (result.matched) {
            Logger.d(Logger.INTENT, "IntentRecognizer: matched shortcut '${result.matchedShortcutTitle}', prompt: ${result.filledPrompt}")
        } else {
            Logger.d(Logger.INTENT, "IntentRecognizer: no match")
        }
    }

    override fun onAdbKeyboardNotInstalled() {
        service.showAdbKeyboardOverlay()
    }

    override fun onInterventionProcessed(input: String) {
        Logger.i(Logger.AGENT, "Intervention processed by Agent: $input")
    }

    override fun onTaskSummarizing() {
        isSummarizing = true
        summaryAlreadySent = false
        summaryStreamingContent.clear()
        emitCoordinatorStream(
            type = CoordinatorMessageType.SUMMARY_STREAMING,
            content = "",
            force = true
        )
    }

    override fun onTaskSummary(summary: String) {
        isSummarizing = false
        summaryAlreadySent = true
        service._coordinatorMessage.value = CoordinatorMessage(
            type = CoordinatorMessageType.SUMMARY_COMPLETE,
            content = summary
        )
        service.showTaskSummaryOverlay(summary)
    }

    // ===== 4. 协调器回调 =====

    override fun onPlanningStart() {
        isPlanning = true
        plannerStreamingContent.clear()
        service.statusSink.updatePlannerStatus("正在规划任务...")
        emitCoordinatorStream(
            type = CoordinatorMessageType.PLANNING_STREAMING,
            content = "",
            force = true
        )
    }

    // 流式 Token 分发：根据当前阶段分发到优化器/规划器/总结器/Agent 悬浮窗
    override fun onStreamToken(token: String) {
        if (isOptimizing) {
            optimizerStreamingContent.append(token)
            emitCoordinatorStream(
                type = CoordinatorMessageType.OPTIMIZER_STREAMING,
                content = optimizerStreamingContent.toString()
            )
        } else if (isPlanning) {
            plannerStreamingContent.append(token)
            emitCoordinatorStream(
                type = CoordinatorMessageType.PLANNING_STREAMING,
                content = plannerStreamingContent.toString()
            )
        } else if (isSummarizing) {
            summaryStreamingContent.append(token)
            emitCoordinatorStream(
                type = CoordinatorMessageType.SUMMARY_STREAMING,
                content = summaryStreamingContent.toString()
            )
        } else {
            agentStreamingContent.append(token)
            service.statusSink.updateStreamingTail(agentStreamingContent.toString())
        }
    }

    override fun onCoordinatorThinking(thinking: String) {
        Logger.d(Logger.COORDINATOR, "Thinking: ${thinking.take(100)}...")
        service._coordinatorMessage.value = CoordinatorMessage(
            type = CoordinatorMessageType.COORDINATOR_THINKING,
            content = thinking
        )
        service.statusSink.updatePlannerStatus(thinking.takeLast(30))
    }

    override fun onStreamEnd() {
        isPlanning = false
        service.statusSink.updatePlannerStatus("")
    }

    override fun onCoordinatorStep(currentStep: Int, maxSteps: Int) {
        service._coordinatorMessage.value = CoordinatorMessage(
            type = CoordinatorMessageType.COORDINATOR_STEP,
            content = "$currentStep|$maxSteps"
        )
    }

    override fun onDecisionComplete(decision: CoordinatorDecision) {
        val taskText = when {
            !decision.nextInstruction.isNullOrBlank() -> decision.nextInstruction
            decision.assessment.isNotBlank() -> decision.assessment
            else -> null
        }
        taskText?.let { service.statusSink.updateCoordinatorTask(it) }
        if (!decision.nextInstruction.isNullOrBlank()) {
            service._coordinatorMessage.value = CoordinatorMessage(
                type = CoordinatorMessageType.SUBTASK_START,
                content = "**下一步目标：** ${decision.nextInstruction}"
            )
        } else if (decision.assessment.isNotBlank()) {
            service._coordinatorMessage.value = CoordinatorMessage(
                type = CoordinatorMessageType.SUBTASK_START,
                content = "**结果：** ${decision.assessment}"
            )
        }
    }

    override fun onPlanningComplete(plan: TaskPlan?) {
        if (plan != null) {
            val planText = plan.toReadableText()
            service._coordinatorMessage.value = CoordinatorMessage(
                type = CoordinatorMessageType.PLAN_COMPLETE,
                content = planText
            )
            Logger.i(Logger.COORDINATOR, "Task plan generated with ${plan.subTasks.size} sub-tasks")
        } else {
            service._coordinatorMessage.value = CoordinatorMessage(
                type = CoordinatorMessageType.PLAN_COMPLETE,
                content = "任务规划失败，将直接执行"
            )
        }
    }

    override fun onSubTaskGenerated(task: PlannedSubTask) {
        val cardText = buildString {
            appendLine("### 步骤 ${task.index}")
            appendLine()
            appendLine("**目标：** ${task.goal}")
            if (task.currentState.isNotBlank()) {
                appendLine()
                appendLine("**当前状态：** ${task.currentState}")
            }
            if (task.actions.isNotBlank()) {
                appendLine()
                appendLine("**操作：** ${task.actions}")
            }
        }
        service._coordinatorMessage.value = CoordinatorMessage(
            type = CoordinatorMessageType.SUBTASK_CARD,
            content = cardText.trim()
        )
        Logger.i(Logger.COORDINATOR, "SubTask ${task.index} card displayed: ${task.goal}")
    }

    override fun onSubTaskStart(task: PlannedSubTask) {
        service.statusSink.updateCoordinatorTask(task.goal)
        val subTaskText = buildString {
            appendLine("### > 开始执行子任务 ${task.index}")
            appendLine()
            appendLine("**目标：** ${task.goal}")
            if (task.actions.isNotBlank()) {
                appendLine()
                appendLine("**操作指导：**")
                appendLine(task.actions)
            }
            if (task.context.isNotBlank() && task.context != "协调器提供的任务指导") {
                appendLine()
                appendLine("**注意事项：** ${task.context}")
            }
        }
        service._coordinatorMessage.value = CoordinatorMessage(
            type = CoordinatorMessageType.SUBTASK_START,
            content = subTaskText.trim()
        )
    }

    override fun onSupervisionResult(result: SupervisionResult) {
        val resultText = buildString {
            appendLine("**评估：**${result.assessment}")
            if (result.correctionInstruction != null) {
                appendLine()
                appendLine("**纠正指令：**${result.correctionInstruction}")
            }
            if (result.gatheredInfo.isNotBlank()) {
                appendLine()
                appendLine("**收集信息：**${result.gatheredInfo}")
            }
        }
        service._coordinatorMessage.value = CoordinatorMessage(
            type = CoordinatorMessageType.SUPERVISION_RESULT,
            content = "${result.status.name}|${resultText.trim()}"
        )
    }

    // ===== 5. 任务结束回调 =====

    override fun onTaskComplete(message: String) {
        service._serviceState.value = ServiceState.IDLE
        service.statusSink.onTaskFinished()
        service.updateNotification("任务已完成")
        service.onTaskCompleted?.invoke(message)
        service._coordinatorMessage.value = CoordinatorMessage(
            type = CoordinatorMessageType.CLEAR,
            content = ""
        )
        // 避免和任务总结重复发送结果
        if (!summaryAlreadySent) {
            service._agentMessage.value = AgentMessage(message, AgentMessageType.RESULT)
        } else {
            summaryAlreadySent = false
        }
        service.speak(message)
        service.startWakeWordListening()
    }

    override fun onError(error: String) {
        // 安全检查：仅当任务协程确实已停止时才重置状态
        val jobStillActive = service.currentTaskJob?.isActive == true
        if (!jobStillActive) {
            service._serviceState.value = ServiceState.IDLE
            service.statusSink.onTaskFinished()
            service.startWakeWordListening()
        } else {
            Logger.w(Logger.AGENT, "onError called while task job still active, skipping state reset. error=$error")
        }
        service.onError?.invoke(error)
        service._coordinatorMessage.value = CoordinatorMessage(
            type = CoordinatorMessageType.CLEAR,
            content = ""
        )
        service._agentMessage.value = AgentMessage(error, AgentMessageType.RESULT)
    }

    override fun onHumanInterventionNeeded(reason: String) {
        service.speak(reason)
        service._agentMessage.value = AgentMessage(reason, AgentMessageType.ACTION)
    }

    // Agent 中途提问回调 — 显示浮窗等待用户输入后返回
    override suspend fun onUserQuestionAsked(question: String): String {
        Logger.i(Logger.AGENT, "Agent asks question: $question")
        service.speak(question)
        service._agentMessage.value = AgentMessage(question, AgentMessageType.ACTION)

        val answerDeferred = kotlinx.coroutines.CompletableDeferred<String>()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val questionOverlay = InterventionInputOverlay(service).apply {
                customTitle = "助手提问"
                customHint = question
                customInputHint = "请输入你的回答..."
                onInterventionSubmit = { answer ->
                    answerDeferred.complete(answer)
                    dismiss()
                }
            }
            questionOverlay.show()
        }

        val answer = answerDeferred.await()
        Logger.i(Logger.AGENT, "User answered question: $answer")
        service._agentMessage.value = AgentMessage("用户回答：$answer", AgentMessageType.ACTION)
        return answer
    }
}