package com.autoglm.assistant.service

import com.autoglm.assistant.core.agent.PhoneAgent
import com.autoglm.assistant.service.WakeWordService.AgentMessage
import com.autoglm.assistant.service.WakeWordService.AgentMessageType
import com.autoglm.assistant.service.WakeWordService.CoordinatorMessage
import com.autoglm.assistant.service.WakeWordService.CoordinatorMessageType
import com.autoglm.assistant.service.WakeWordService.ServiceState
import com.autoglm.assistant.service.InterventionInputOverlay
import com.autoglm.assistant.util.Logger

/**
 * Agent 回调绑定器
 *
 * 【目的】将 PhoneAgent 的 30+ 回调绑定到 WakeWordService 的 UI 与状态管理上下文，
 *          从 WakeWordService.initializePhoneAgent() 中提取（原 385 行），降低 God Class 行数。
 *
 * 【依赖】通过 WakeWordService 实例访问 internal 字段：
 *   - 状态流：_agentMessage、_coordinatorMessage、_serviceState
 *   - 悬浮窗：agentStatusOverlay、interventionOverlay、taskSummaryOverlay
 *   - 方法：updateNotification()、speak()、startWakeWordListening()、showTaskSummaryOverlay()
 *
 * 【调用位置】WakeWordService.initializePhoneAgent()
 */
internal class AgentCallbackBinder(
    private val service: WakeWordService
) {
    companion object {
        private const val COORDINATOR_STREAM_DEBOUNCE_MS = 300L
    }

    /**
     * 绑定所有 PhoneAgent 回调到服务上下文
     *
     * 【流程】
     *   1. 初始化流式内容缓冲与状态标志
     *   2. 绑定 Agent 基础回调（步骤、思考、动作、截图前后）
     *   3. 绑定 Prompt 优化器回调（流式 + 完成）
     *   4. 绑定意图识别器回调
     *   5. 绑定协调器回调（规划、决策、子任务、监督）
     *   6. 绑定任务结束回调（完成、错误、干预、提问）
     */
    fun bind(agent: PhoneAgent) {
        // ---- 1. 流式内容缓冲与状态标志 ----
        var lastCoordinatorStreamEmitAtMs = 0L
        var agentStreamingContent = StringBuilder()

        // 限流发送协调器消息流（300ms 去抖）
        fun emitCoordinatorStream(type: CoordinatorMessageType, content: String, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && (now - lastCoordinatorStreamEmitAtMs) < COORDINATOR_STREAM_DEBOUNCE_MS) {
                return
            }
            lastCoordinatorStreamEmitAtMs = now
            service._coordinatorMessage.value = CoordinatorMessage(
                type = type,
                content = content
            )
        }

        agent.apply {
            // ---- 2. Agent 基础回调 ----
            onStepStart = { step ->
                service.agentStatusOverlay?.updateStep(step)
                agentStreamingContent.clear()
                service.updateNotification("执行中 第${step}步...")
            }

            onThinking = { thinking ->
                service._agentMessage.value = AgentMessage(thinking, AgentMessageType.THINKING)
                service.agentStatusOverlay?.updateThinking(thinking)
            }

            onAction = { action ->
                service._agentMessage.value = AgentMessage(action, AgentMessageType.ACTION)
                service.agentStatusOverlay?.updateActionStatus(action)
            }

            onBeforeScreenshot = {
                service.agentStatusOverlay?.hideForScreenshot()
                service.interventionOverlay?.hideForScreenshot()
                service.taskSummaryOverlay?.hideForScreenshot()
            }

            onAfterScreenshot = {
                service.agentStatusOverlay?.restoreAfterScreenshot()
                service.interventionOverlay?.restoreAfterScreenshot()
                service.taskSummaryOverlay?.restoreAfterScreenshot()
            }

            onBeforeAction = {
                service.agentStatusOverlay?.hideForScreenshot()
                service.interventionOverlay?.hideForScreenshot()
                service.taskSummaryOverlay?.hideForScreenshot()
            }

            onAfterAction = {
                service.agentStatusOverlay?.restoreAfterScreenshot()
                service.interventionOverlay?.restoreAfterScreenshot()
                service.taskSummaryOverlay?.restoreAfterScreenshot()
            }

            // ---- 3. Prompt 优化器回调 ----
            var optimizerStreamingContent = StringBuilder()
            var isOptimizing = false
            var summaryStreamingContent = StringBuilder()
            var isSummarizing = false
            var summaryAlreadySent = false

            onPromptOptimizing = {
                isOptimizing = true
                optimizerStreamingContent.clear()
                service.agentStatusOverlay?.updatePlannerStatus("✨ 正在优化指令...")
                emitCoordinatorStream(
                    type = CoordinatorMessageType.OPTIMIZER_STREAMING,
                    content = "",
                    force = true
                )
            }

            onPromptOptimized = { optimizedPrompt ->
                isOptimizing = false
                service.agentStatusOverlay?.updatePlannerStatus("")
                service._coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.OPTIMIZER_COMPLETE,
                    content = optimizedPrompt
                )
            }

            // ---- 4. 意图识别器回调 ----
            onIntentRecognizing = {
                Logger.d(Logger.INTENT, "IntentRecognizer: recognizing...")
                service.agentStatusOverlay?.updatePlannerStatus("正在识别意图...")
            }

            onIntentRecognized = { result ->
                service.agentStatusOverlay?.updatePlannerStatus("")
                if (result.matched) {
                    Logger.d(Logger.INTENT, "IntentRecognizer: matched shortcut '${result.matchedShortcutTitle}', prompt: ${result.filledPrompt}")
                } else {
                    Logger.d(Logger.INTENT, "IntentRecognizer: no match")
                }
            }

            // ADB Keyboard 未安装回调
            onAdbKeyboardNotInstalled = { service.showAdbKeyboardOverlay() }

            // 干预处理完成回调
            onInterventionProcessed = { instruction ->
                Logger.i(Logger.AGENT, "Intervention processed by Agent: $instruction")
            }

            // 任务总结回调
            onTaskSummarizing = {
                isSummarizing = true
                summaryAlreadySent = false
                summaryStreamingContent.clear()
                emitCoordinatorStream(
                    type = CoordinatorMessageType.SUMMARY_STREAMING,
                    content = "",
                    force = true
                )
            }

            onTaskSummary = { summary ->
                isSummarizing = false
                summaryAlreadySent = true
                service._coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.SUMMARY_COMPLETE,
                    content = summary
                )
                service.showTaskSummaryOverlay(summary)
            }

            // ---- 5. 协调器回调 ----
            var plannerStreamingContent = StringBuilder()
            var isPlanning = false

            agent.onPlanningStart = {
                isPlanning = true
                plannerStreamingContent.clear()
                service.agentStatusOverlay?.updatePlannerStatus("正在规划任务...")
                emitCoordinatorStream(
                    type = CoordinatorMessageType.PLANNING_STREAMING,
                    content = "",
                    force = true
                )
            }

            // 流式 Token 分发：根据当前阶段分发到优化器/规划器/总结器/Agent 悬浮窗
            onStreamToken = { token ->
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
                    service.agentStatusOverlay?.updateStreamingTail(agentStreamingContent.toString())
                }
            }

            onCoordinatorThinking = { thinking ->
                Logger.d(Logger.COORDINATOR, "Thinking: ${thinking.take(100)}...")
                service._coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.COORDINATOR_THINKING,
                    content = thinking
                )
                service.agentStatusOverlay?.updatePlannerStatus("${thinking.takeLast(30)}")
            }

            onStreamEnd = {
                isPlanning = false
                service.agentStatusOverlay?.updatePlannerStatus("")
            }

            agent.onCoordinatorStep = { currentStep, maxSteps ->
                service._coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.COORDINATOR_STEP,
                    content = "$currentStep|$maxSteps"
                )
            }

            onDecisionComplete = { decision ->
                val taskText = when {
                    !decision.nextInstruction.isNullOrBlank() -> decision.nextInstruction
                    decision.assessment.isNotBlank() -> decision.assessment
                    else -> null
                }
                taskText?.let { service.agentStatusOverlay?.updateCoordinatorTask(it) }
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

            agent.onPlanningComplete = { taskPlan ->
                if (taskPlan != null) {
                    val planText = taskPlan.toReadableText()
                    service._coordinatorMessage.value = CoordinatorMessage(
                        type = CoordinatorMessageType.PLAN_COMPLETE,
                        content = planText
                    )
                    Logger.i(Logger.COORDINATOR, "Task plan generated with ${taskPlan.subTasks.size} sub-tasks")
                } else {
                    service._coordinatorMessage.value = CoordinatorMessage(
                        type = CoordinatorMessageType.PLAN_COMPLETE,
                        content = "任务规划失败，将直接执行"
                    )
                }
            }

            agent.onSubTaskGenerated = { subTask ->
                val cardText = buildString {
                    appendLine("### 步骤 ${subTask.index}")
                    appendLine()
                    appendLine("**目标：** ${subTask.goal}")
                    if (subTask.currentState.isNotBlank()) {
                        appendLine()
                        appendLine("**当前状态：** ${subTask.currentState}")
                    }
                    if (subTask.actions.isNotBlank()) {
                        appendLine()
                        appendLine("**操作：** ${subTask.actions}")
                    }
                }
                service._coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.SUBTASK_CARD,
                    content = cardText.trim()
                )
                Logger.i(Logger.COORDINATOR, "SubTask ${subTask.index} card displayed: ${subTask.goal}")
            }

            agent.onSubTaskStart = { subTask ->
                service.agentStatusOverlay?.updateCoordinatorTask(subTask.goal)
                val subTaskText = buildString {
                    appendLine("### ▶️ 开始执行子任务 ${subTask.index}")
                    appendLine()
                    appendLine("**目标：** ${subTask.goal}")
                    if (subTask.actions.isNotBlank()) {
                        appendLine()
                        appendLine("**操作指导：**")
                        appendLine(subTask.actions)
                    }
                    if (subTask.context.isNotBlank() && subTask.context != "协调器提供的任务指导") {
                        appendLine()
                        appendLine("**注意事项：** ${subTask.context}")
                    }
                }
                service._coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.SUBTASK_START,
                    content = subTaskText.trim()
                )
            }

            agent.onSupervisionResult = { result ->
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

            // ---- 6. 任务结束回调 ----
            onTaskComplete = { message ->
                service._serviceState.value = ServiceState.IDLE
                service.agentStatusOverlay?.onTaskFinished()
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

            onError = { error ->
                // 安全检查：仅当任务协程确实已停止时才重置状态
                val jobStillActive = service.currentTaskJob?.isActive == true
                if (!jobStillActive) {
                    service._serviceState.value = ServiceState.IDLE
                    service.agentStatusOverlay?.onTaskFinished()
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

            onHumanInterventionNeeded = { message ->
                service.speak(message)
                service._agentMessage.value = AgentMessage(message, AgentMessageType.ACTION)
            }

            // Agent 中途提问回调 — 显示浮窗等待用户输入后返回
            onUserQuestionAsked = { question ->
                Logger.i(Logger.AGENT, "Agent asks question: $question")
                service.speak(question)
                service._agentMessage.value = AgentMessage("$question", AgentMessageType.ACTION)

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
                answer
            }
        }
    }
}