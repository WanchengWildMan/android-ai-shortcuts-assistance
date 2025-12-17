package com.autoglm.assistant.core.agent

import android.content.Context
import com.autoglm.assistant.ai.Message
import com.autoglm.assistant.ai.MessageBuilder
import com.autoglm.assistant.ai.ModelClient
import com.autoglm.assistant.ai.ModelConfig
import com.autoglm.assistant.core.action.ActionExecutor
import com.autoglm.assistant.core.action.ActionParser
import com.autoglm.assistant.core.action.ActionType
import com.autoglm.assistant.core.planner.SmartCoordinator
import com.autoglm.assistant.core.planner.TaskPlan
import com.autoglm.assistant.core.planner.PlannedSubTask
import com.autoglm.assistant.core.planner.SupervisionStatus
import com.autoglm.assistant.core.screen.AppDetector
import com.autoglm.assistant.core.screen.ScreenCapture
import com.autoglm.assistant.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 任务状态快照，用于保存和恢复任务
 */
data class TaskSnapshot(
    val taskId: String,
    val originalTask: String,
    val currentStep: Int,
    val conversationHistory: List<SerializableMessage>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 可序列化的消息格式
 */
data class SerializableMessage(
    val role: String,  // "system", "user", "assistant"
    val content: String,
    val imageBase64: String? = null
)

class PhoneAgent(
    private val context: Context,
    private val modelConfig: ModelConfig,
    private val agentConfig: AgentConfig = AgentConfig()
) {
    private lateinit var modelClient: ModelClient
    private lateinit var screenCapture: ScreenCapture
    private lateinit var actionExecutor: ActionExecutor
    private var smartCoordinator: SmartCoordinator? = null

    private val conversationHistory = mutableListOf<Message>()
    private var currentStep = 0
    private var currentTaskId: String? = null
    private var stopRequested = false  // 用于检测停止请求

    // 任务规划相关状态
    private var currentTaskPlan: TaskPlan? = null
    private var currentSubTaskIndex: Int = 0
    private var lastAgentThinking: String = ""  // 保存最后一次执行的thinking
    private var lastAgentAction: String = ""    // 保存最后一次执行的action

    // Callbacks
    var onStepStart: ((Int) -> Unit)? = null
    var onThinking: ((String) -> Unit)? = null
    var onAction: ((String) -> Unit)? = null
    var onStepComplete: ((StepResult) -> Unit)? = null
    var onTaskComplete: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onHumanInterventionNeeded: ((String) -> Unit)? = null
    var onMaxStepsReached: ((Int, String) -> Unit)? = null  // (步数, 任务ID) 达到最大步数时回调
    var onTaskSaved: ((String) -> Unit)? = null  // 任务保存时回调

    // State
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _currentTask = MutableStateFlow<String?>(null)
    val currentTask: StateFlow<String?> = _currentTask

    // 任务保存目录
    private val taskSaveDir: File by lazy {
        File(context.filesDir, "saved_tasks").also { it.mkdirs() }
    }
    private val gson = Gson()

    fun initialize() {
        modelClient = ModelClient(modelConfig)
        screenCapture = ScreenCapture(context)
        actionExecutor = ActionExecutor(
            context,
            screenCapture.screenWidth,
            screenCapture.screenHeight
        )

        // 初始化智能协调器（如果配置了）
        agentConfig.plannerConfig?.let { config ->
            if (config.enabled) {
                smartCoordinator = SmartCoordinator(config)
                Logger.i(Logger.AGENT, "SmartCoordinator initialized with model: ${config.plannerModelConfig?.modelName}")
            }
        }
    }

    fun setScreenCaptureData(resultCode: Int, data: android.content.Intent) {
        screenCapture.initMediaProjection(resultCode, data)
    }

    suspend fun run(task: String, resetHistory: Boolean = true, context: List<SerializableMessage> = emptyList()): String {
        if (_isRunning.value) {
            Logger.agent("Agent is already running, ignoring task: $task")
            return "Agent is already running"
        }

        Logger.i(Logger.AGENT, "========== START TASK ==========")
        Logger.i(Logger.AGENT, "Task: $task")
        Logger.agent("Max steps: ${agentConfig.maxSteps}, Language: ${agentConfig.language}")

        _isRunning.value = true
        _currentTask.value = task
        stopRequested = false

        if (resetHistory) {
            conversationHistory.clear()
            currentStep = 0
            currentTaskPlan = null
            currentSubTaskIndex = 0
        }

        if (conversationHistory.isEmpty()) {
            conversationHistory.add(
                Message.System(agentConfig.getEffectiveSystemPrompt())
            )

            // Restore context if provided
            if (context.isNotEmpty()) {
                context.forEach { msg ->
                    if (msg.role == "user") {
                        conversationHistory.add(Message.User(msg.content))
                    } else if (msg.role == "assistant") {
                        conversationHistory.add(Message.Assistant(msg.content))
                    }
                }
            }
        }

        try {
            // 尝试使用SmartCoordinator分解任务（如果启用）
            if (smartCoordinator != null && currentTaskPlan == null) {
                Logger.i(Logger.AGENT, "Attempting to plan task with SmartCoordinator...")
                currentTaskPlan = smartCoordinator?.planTask(task, agentConfig.language)

                if (currentTaskPlan != null) {
                    Logger.i(Logger.AGENT, "Task successfully planned into ${currentTaskPlan!!.subTasks.size} sub-tasks")
                    Logger.i(Logger.AGENT, "Task analysis: ${currentTaskPlan!!.analysis}")
                    currentSubTaskIndex = 0
                } else {
                    Logger.w(Logger.AGENT, "Task planning failed, falling back to direct execution")
                }
            }

            // 如果有任务规划，按照规划执行子任务
            if (currentTaskPlan != null) {
                return executeWithPlan(currentTaskPlan!!)
            }

            // 否则按原来的方式直接执行
            return executeDirectly(task)

        } catch (e: Exception) {
            val error = "Error: ${e.message}"
            Logger.e(Logger.AGENT, "Task failed: $error", e)
            onError?.invoke(error)
            return error
        } finally {
            _isRunning.value = false
            _currentTask.value = null
        }
    }

    /**
     * 按照任务规划执行（有规划模型分解的子任务）
     */
    private suspend fun executeWithPlan(plan: TaskPlan): String {
        Logger.i(Logger.AGENT, "========== EXECUTING WITH TASK PLAN ==========")
        Logger.i(Logger.AGENT, "Total sub-tasks: ${plan.subTasks.size}")

        val results = mutableListOf<String>()

        for ((index, subTask) in plan.subTasks.withIndex()) {
            if (stopRequested) {
                Logger.i(Logger.AGENT, "Task stopped by user")
                return "Task stopped by user at sub-task ${index + 1}"
            }

            Logger.i(Logger.AGENT, "---------- Executing Sub-task ${index + 1}/${plan.subTasks.size} ----------")
            Logger.i(Logger.AGENT, "Goal: ${subTask.goal}")

            currentSubTaskIndex = index

            // 构建子任务的详细prompt
            val subTaskPrompt = buildSubTaskPrompt(subTask, plan.originalTask)

            // 执行子任务
            val result = executeSubTask(subTaskPrompt, subTask)

            if (result.finished) {
                Logger.i(Logger.AGENT, "Sub-task ${index + 1} completed: ${result.message}")
                results.add("Sub-task ${index + 1}: ${result.message}")
            } else if (result.needsHumanIntervention) {
                Logger.w(Logger.AGENT, "Human intervention needed at sub-task ${index + 1}")
                onHumanInterventionNeeded?.invoke(result.message ?: "Human intervention needed")
                return "Task paused at sub-task ${index + 1}: ${result.message}"
            } else {
                Logger.w(Logger.AGENT, "Sub-task ${index + 1} not finished, continuing...")
            }
        }

        val finalMessage = "All ${plan.subTasks.size} sub-tasks completed successfully"
        Logger.i(Logger.AGENT, "========== TASK PLAN COMPLETED ==========")
        Logger.i(Logger.AGENT, finalMessage)
        onTaskComplete?.invoke(finalMessage)
        return finalMessage
    }

    /**
     * 直接执行任务（不使用任务规划）
     */
    private suspend fun executeDirectly(task: String): String {
        // First step: initialize conversation with task
        var result = executeStep(task, isNewTask = true)

        // Continue until finished or max steps reached
        while (!result.finished && currentStep < agentConfig.maxSteps) {
            if (stopRequested) {
                Logger.i(Logger.AGENT, "Task stopped by user (stopRequested=true)")
                return "Task stopped by user"
            }

            if (result.needsHumanIntervention) {
                Logger.w(Logger.AGENT, "Human intervention needed: ${result.message}")
                onHumanInterventionNeeded?.invoke(result.message ?: "Human intervention needed")
                break
            }

            result = executeStep(isNewTask = false)
        }

        val finalMessage = result.message ?: "Task completed"
        Logger.i(Logger.AGENT, "========== TASK COMPLETED ==========")
        Logger.i(Logger.AGENT, "Result: $finalMessage (steps: $currentStep)")
        onTaskComplete?.invoke(finalMessage)
        return finalMessage
    }

    /**
     * 为子任务构建详细的prompt
     */
    private fun buildSubTaskPrompt(subTask: PlannedSubTask, originalTask: String): String {
        return """
原始任务：$originalTask

当前子任务 ${subTask.index}：

【目标】
${subTask.goal}

【当前状态】
${subTask.currentState}

【需要执行的操作】
${subTask.actions}

【上下文和注意事项】
${subTask.context}

请根据以上信息完成当前子任务。
        """.trimIndent()
    }

    /**
     * 执行单个子任务（带监督和纠正）
     */
    private suspend fun executeSubTask(prompt: String, subTask: PlannedSubTask): StepResult {
        val maxCorrections = agentConfig.plannerConfig?.maxCorrections ?: 2
        var correctionAttempts = 0

        while (correctionAttempts <= maxCorrections) {
            // 执行子任务
            var result = executeStep(prompt, isNewTask = (correctionAttempts == 0))

            // Continue until sub-task is finished or max steps reached
            var subTaskSteps = 1
            val maxSubTaskSteps = 20  // 每个子任务最多20步

            while (!result.finished && subTaskSteps < maxSubTaskSteps && currentStep < agentConfig.maxSteps) {
                if (stopRequested) {
                    break
                }

                if (result.needsHumanIntervention) {
                    break
                }

                result = executeStep(isNewTask = false)
                subTaskSteps++
            }

            // 如果有监督器，让协调器检查执行结果
            if (smartCoordinator != null && agentConfig.plannerConfig?.enableSupervision == true) {
                Logger.i(Logger.AGENT, "Requesting supervision for sub-task ${subTask.index}...")

                // 获取最新的截图
                val screenshot = screenCapture.capture()

                val supervision = smartCoordinator!!.superviseExecution(
                    subTask = subTask,
                    agentThinking = lastAgentThinking,
                    agentAction = lastAgentAction,
                    screenshotBase64 = screenshot?.base64Data,
                    language = agentConfig.language
                )

                when (supervision.status) {
                    SupervisionStatus.SUCCESS -> {
                        Logger.i(Logger.AGENT, "✓ Supervision: Sub-task ${subTask.index} completed successfully")
                        Logger.i(Logger.AGENT, "Assessment: ${supervision.assessment}")
                        // 成功，返回结果
                        return result.copy(
                            finished = true,
                            message = supervision.assessment
                        )
                    }

                    SupervisionStatus.NEEDS_CORRECTION -> {
                        correctionAttempts++
                        if (correctionAttempts <= maxCorrections) {
                            Logger.w(Logger.AGENT, "⚠ Supervision: Needs correction (attempt $correctionAttempts/$maxCorrections)")
                            Logger.w(Logger.AGENT, "Correction instruction: ${supervision.correctionInstruction}")

                            // 构建纠正prompt
                            val correctionPrompt = """
前一次执行存在问题，需要纠正。

**监督反馈：**
${supervision.assessment}

**纠正指令：**
${supervision.correctionInstruction}

请根据纠正指令重新执行操作，确保达成子任务目标：${subTask.goal}
                            """.trimIndent()

                            // 添加纠正指令到对话历史
                            conversationHistory.add(Message.User(correctionPrompt))

                            // 继续下一轮尝试
                            continue
                        } else {
                            Logger.e(Logger.AGENT, "✗ Max correction attempts reached for sub-task ${subTask.index}")
                            return result.copy(
                                success = false,
                                message = "达到最大纠正次数，子任务未能正确完成: ${supervision.assessment}"
                            )
                        }
                    }

                    SupervisionStatus.FAILED -> {
                        Logger.e(Logger.AGENT, "✗ Supervision: Sub-task ${subTask.index} failed")
                        Logger.e(Logger.AGENT, "Assessment: ${supervision.assessment}")
                        return result.copy(
                            success = false,
                            finished = true,
                            message = "子任务执行失败: ${supervision.assessment}"
                        )
                    }

                    SupervisionStatus.UNCERTAIN -> {
                        Logger.w(Logger.AGENT, "? Supervision uncertain, assuming success")
                        // 不确定时，假设成功并继续
                        return result
                    }
                }
            } else {
                // 没有监督器，直接返回结果
                return result
            }
        }

        // 理论上不会到这里
        return StepResult(
            success = false,
            finished = true,
            action = null,
            thinking = "",
            message = "子任务执行异常结束"
        )
    }

    private suspend fun executeStep(
        userPrompt: String? = null,
        isNewTask: Boolean = false
    ): StepResult {
        currentStep++
        Logger.agent("---------- Step $currentStep ----------")
        Logger.startTimer("step_$currentStep")
        onStepStart?.invoke(currentStep)

        // 1. Capture current screen
        Logger.startTimer("screenshot")
        val screenshot = screenCapture.capture()
        val screenshotTime = Logger.endTimer("screenshot", Logger.SCREEN)
        val base64Image = screenshot?.base64Data
        Logger.screen("Screenshot: ${screenshot?.width}x${screenshot?.height}, sensitive=${screenshot?.isSensitive}, time=${screenshotTime}ms")

        // 2. Get current app info
        val currentApp = AppDetector.getCurrentApp(context)
        Logger.agent("Current app: $currentApp")
        val screenInfo = MessageBuilder.buildScreenInfo(
            currentApp = currentApp,
            screenWidth = screenCapture.screenWidth,
            screenHeight = screenCapture.screenHeight
        )

        // 3. Build messages
        if (isNewTask) {
            val taskPrompt = MessageBuilder.buildTaskPrompt(userPrompt ?: "", screenInfo)
            conversationHistory.add(
                Message.User(taskPrompt, base64Image)
            )
        } else {
            val continuePrompt = MessageBuilder.buildContinuePrompt(screenInfo)
            conversationHistory.add(
                Message.User(continuePrompt, base64Image)
            )
        }

        // 4. Call model
        Logger.startTimer("model_request")
        Logger.model("Calling model: ${modelConfig.modelName}, messages: ${conversationHistory.size}")
        val response = modelClient.chat(conversationHistory, object : ModelClient.StreamCallback {
            override fun onToken(token: String) {
                // Could update UI with streaming tokens
            }

            override fun onThinkingComplete(thinking: String) {
                onThinking?.invoke(thinking)
            }

            override fun onComplete(response: com.autoglm.assistant.ai.ModelResponse) {
                Logger.model("TTFT: ${response.timeToFirstToken}ms, Total: ${response.totalTime}ms")
            }

            override fun onError(error: String) {
                Logger.e(Logger.MODEL, "Model error: $error")
                onError?.invoke(error)
            }
        })
        Logger.endTimer("model_request", Logger.MODEL)

        Logger.agent("Thinking: ${response.thinking.take(100)}...")
        Logger.agent("Action: ${response.action}")
        onThinking?.invoke(response.thinking)
        onAction?.invoke(response.action)

        // 保存最后一次执行的thinking和action，供监督器使用
        lastAgentThinking = response.thinking
        lastAgentAction = response.action

        // 5. 移除最后一条用户消息中的图片（节省上下文长度，与Python一致）
        removeLastUserMessageImage()
        Logger.agent("Context messages: ${conversationHistory.size}")

        // 6. Parse and execute action
        val parsedAction = ActionParser.parse(response.action)
        Logger.action("Parsed: type=${parsedAction.type}, params=${parsedAction.params}")

        Logger.startTimer("action_execute")
        val actionResult = actionExecutor.execute(parsedAction)
        val actionTime = Logger.endTimer("action_execute", Logger.ACTION)
        Logger.action("Result: success=${actionResult.success}, message=${actionResult.message}, time=${actionTime}ms")

        // 7. 添加助手回复到历史（使用Python格式：<think>...</think><answer>...</answer>）
        val assistantContent = "<think>${response.thinking}</think><answer>${response.action}</answer>"
        conversationHistory.add(Message.Assistant(assistantContent))

        val stepResult = StepResult(
            success = actionResult.success,
            finished = parsedAction.isFinish || parsedAction.type == ActionType.FINISH,
            action = response.action,
            thinking = response.thinking,
            message = actionResult.message,
            needsHumanIntervention = actionResult.needsHumanIntervention
        )

        val stepTime = Logger.endTimer("step_$currentStep")
        Logger.agent("Step $currentStep completed: success=${stepResult.success}, finished=${stepResult.finished}, time=${stepTime}ms")

        onStepComplete?.invoke(stepResult)
        return stepResult
    }

    /**
     * 移除最后一条用户消息中的图片（与Python一致）
     * 在调用模型后立即执行，节省上下文长度
     */
    private fun removeLastUserMessageImage() {
        for (i in conversationHistory.indices.reversed()) {
            val message = conversationHistory[i]
            if (message is Message.User && message.imageBase64 != null) {
                conversationHistory[i] = Message.User(message.text, null)
                break // 只移除最后一条
            }
        }
    }

    fun stop() {
        Logger.w(Logger.AGENT, "PhoneAgent.stop() called - who called this?")
        Exception("Stop trace").printStackTrace() // Print stack trace to logcat
        stopRequested = true
        _isRunning.value = false
    }

    /**
     * 获取智能协调器收集到的所有信息
     */
    fun getGatheredInfo(): String? {
        return smartCoordinator?.getInfoSummary()
    }

    /**
     * 清空收集的信息
     */
    fun clearGatheredInfo() {
        smartCoordinator?.clearGatheredInfo()
    }

    fun release() {
        screenCapture.release()
        smartCoordinator?.release()
        smartCoordinator = null
    }
}
