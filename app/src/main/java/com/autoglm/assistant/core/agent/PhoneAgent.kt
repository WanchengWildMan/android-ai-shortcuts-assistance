package com.autoglm.assistant.core.agent

import android.content.Context
import com.autoglm.assistant.ai.Message
import com.autoglm.assistant.ai.MessageBuilder
import com.autoglm.assistant.ai.ModelClient
import com.autoglm.assistant.ai.ModelConfig
import com.autoglm.assistant.core.action.ActionExecutor
import com.autoglm.assistant.core.action.ActionParser
import com.autoglm.assistant.core.action.ActionType
import com.autoglm.assistant.core.planner.PromptOptimizer
import com.autoglm.assistant.core.planner.SmartCoordinator
import com.autoglm.assistant.core.planner.TaskPlan
import com.autoglm.assistant.core.planner.PlannedSubTask
import com.autoglm.assistant.core.planner.SupervisionStatus
import com.autoglm.assistant.core.screen.AppDetector
import com.autoglm.assistant.core.screen.ScreenCapture
import com.autoglm.assistant.util.Logger
import com.autoglm.assistant.util.ShellExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
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
    companion object {
        private const val MIN_SAMPLE_INTERVAL_MS = 300L
    }

    private lateinit var modelClient: ModelClient
    private lateinit var screenCapture: ScreenCapture
    private lateinit var actionExecutor: ActionExecutor
    private var smartCoordinator: SmartCoordinator? = null
    private var promptOptimizer: PromptOptimizer? = null
    private var intentRecognizer: com.autoglm.assistant.ai.IntentRecognizer? = null

    private val conversationHistory = mutableListOf<Message>()
    private var currentStep = 0
    private var currentTaskId: String? = null
    private var stopRequested = false  // 用于检测停止请求
    private var lastOriginalTask: String = ""  // 保存原始任务描述，干预优化时使用

    // Agent执行状态 - 用于监督器
    private var lastAgentThinking: String = ""
    private var lastAgentAction: String = ""


    // 任务规划相关状态
    private var currentTaskPlan: TaskPlan? = null
    private var currentSubTaskIndex: Int = 0
    private var lastSampleTimestampMs: Long = 0L

    // 回调
    var onStepStart: ((Int) -> Unit)? = null
    var onThinking: ((String) -> Unit)? = null
    var onAction: ((String) -> Unit)? = null
    var onStepComplete: ((StepResult) -> Unit)? = null
    var onTaskComplete: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onHumanInterventionNeeded: ((String) -> Unit)? = null
    var onUserQuestionAsked: (suspend (String) -> String)? = null  // Agent中途提问回调：传入问题，挂起等待用户回答
    var onMaxStepsReached: ((Int, String) -> Unit)? = null  // (步数, 任务ID) 达到最大步数时回调
    var onTaskSaved: ((String) -> Unit)? = null  // 任务保存时回调
    // 流式输出回调 - 用于打字机效果（如果需要）
    // SmartCoordinator 回调
    var onDecisionStart: (() -> Unit)? = null           // 开始决策
    var onDecisionComplete: ((com.autoglm.assistant.core.planner.CoordinatorDecision) -> Unit)? = null  // 决策完成
    var onStreamToken: ((String) -> Unit)? = null       // 流式 token 回调
    var onCoordinatorThinking: ((String) -> Unit)? = null  // 协调器思考过程
    var onStreamStart: (() -> Unit)? = null             // 流式输出开始
    var onStreamEnd: (() -> Unit)? = null               // 流式输出结束
    // Prompt 优化器回调
    var onPromptOptimizing: (() -> Unit)? = null        // 正在优化 prompt
    var onPromptOptimized: ((String) -> Unit)? = null   // prompt 优化完成
    var onTaskSummarizing: (() -> Unit)? = null         // 正在生成任务总结
    var onTaskSummary: ((String) -> Unit)? = null       // 任务总结完成
    // 意图识别器回调
    var onIntentRecognizing: (() -> Unit)? = null       // 正在识别意图
    var onIntentRecognized: ((com.autoglm.assistant.ai.IntentResult) -> Unit)? = null // 意图识别完成
    // 干预回调
    var onInterventionProcessed: ((String) -> Unit)? = null  // 干预指令被处理后通知
    
    // 协调器步数回调 — 用于在消息中显示当前步数
    var onCoordinatorStep: ((currentStep: Int, maxSteps: Int) -> Unit)? = null
    // 截图生命周期回调 — 用于控制悬浮窗在截图时临时隐藏
    var onBeforeScreenshot: (() -> Unit)? = null
    var onAfterScreenshot: (() -> Unit)? = null
    // 操作执行生命周期回调 — 控制悬浮窗在点击/滑动等操作时隐藏，避免遮挡目标
    var onBeforeAction: (() -> Unit)? = null
    var onAfterAction: (() -> Unit)? = null

    // SmartCoordinator 回调 - 用于任务规划
    var onPlanningStart: (() -> Unit)? = null
    var onPlanningComplete: ((TaskPlan?) -> Unit)? = null
    var onSubTaskGenerated: ((PlannedSubTask) -> Unit)? = null
    var onSubTaskStart: ((PlannedSubTask) -> Unit)? = null
    var onSupervisionResult: ((com.autoglm.assistant.core.planner.SupervisionResult) -> Unit)? = null

    // 状态
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

        // 初始化智能协调器（如果配置了API信息）
        // 只要配置了plannerConfig且有有效的模型配置，就初始化SmartCoordinator
        // 具体是否使用由每个任务的enablePlanning参数决定
        agentConfig.plannerConfig?.let { config ->
            if (config.plannerModelConfig != null) {
                smartCoordinator = SmartCoordinator(config).apply {
                    // 设置结构化回调 - 用于显示格式化内容

                    // 连接流式输出回调 - 用于打字机效果
                    onStreamToken = { token ->
                        Logger.d(Logger.AGENT, "[COORDINATOR->UI] Token: $token")
                        this@PhoneAgent.onStreamToken?.invoke(token)
                    }
                    onDecisionStart = {
                        this@PhoneAgent.onDecisionStart?.invoke()
                    }
                    onDecisionComplete = { decision ->
                        this@PhoneAgent.onDecisionComplete?.invoke(decision)
                    }
                    onStreamStart = {
                        Logger.i(Logger.AGENT, "[COORDINATOR->UI] Stream start")
                        this@PhoneAgent.onStreamStart?.invoke()
                    }
                    onStreamEnd = {
                        Logger.i(Logger.AGENT, "[COORDINATOR->UI] Stream end")
                        this@PhoneAgent.onStreamEnd?.invoke()
                    }
                    onCoordinatorThinking = { thinking ->
                        Logger.i(Logger.AGENT, "[COORDINATOR->UI] Thinking: ${thinking.take(100)}...")
                        this@PhoneAgent.onCoordinatorThinking?.invoke(thinking)
                    }
                }
                Logger.i(Logger.AGENT, "SmartCoordinator initialized with model: ${config.plannerModelConfig.modelName}")
            } else {
                Logger.i(Logger.AGENT, "SmartCoordinator not initialized: model config is null")
            }
        }

        // 初始化Prompt优化器（如果配置了）
        agentConfig.optimizerConfig?.let { config ->
            if (config.enabled && config.modelConfig != null) {
                promptOptimizer = PromptOptimizer(config.modelConfig, config.customSystemPrompt).apply {
                    onOptimizing = {
                        this@PhoneAgent.onPromptOptimizing?.invoke()
                    }
                    onOptimized = { optimizedPrompt ->
                        this@PhoneAgent.onPromptOptimized?.invoke(optimizedPrompt)
                    }
                    onStreamToken = { token ->
                        // Logger.d(Logger.AGENT, "[OPTIMIZER->UI] Token: $token")
                        this@PhoneAgent.onStreamToken?.invoke(token)
                    }
                    onSummarizing = {
                        this@PhoneAgent.onTaskSummarizing?.invoke()
                    }
                    onSummarized = { summary ->
                        this@PhoneAgent.onTaskSummary?.invoke(summary)
                    }
                }
                Logger.i(Logger.AGENT, "PromptOptimizer initialized with model: ${config.modelConfig.modelName}")
            }
        }

        // 初始化意图识别器（如果配置了）
        agentConfig.intentConfig?.let { config ->
            if (config.enabled && config.modelConfig != null) {
                intentRecognizer = com.autoglm.assistant.ai.IntentRecognizer(config.modelConfig).apply {
                    onRecognizing = {
                        this@PhoneAgent.onIntentRecognizing?.invoke()
                    }
                    onRecognized = { result ->
                        this@PhoneAgent.onIntentRecognized?.invoke(result)
                    }
                }
                Logger.i(Logger.AGENT, "IntentRecognizer initialized with model: ${config.modelConfig.modelName}")
            }
        }
    }

    fun setScreenCaptureData(resultCode: Int, data: android.content.Intent) {
        screenCapture.initMediaProjection(resultCode, data)
    }

    suspend fun executeTask(
        task: String,
        enablePlanning: Boolean = true,
        enableOptimizer: Boolean = true,
        contextMessages: List<SerializableMessage> = emptyList()
    ): String {
        return run(task, true, contextMessages, enablePlanning, enableOptimizer)
    }

    /**
     * 干预恢复执行 — 停止当前任务后，注入干预指令并重新开始执行
     * 流程：用户点击 ✎ → stop() 中断当前执行 → 用户输入指令 → 调用此方法
     * 行为：保留已有对话历史，注入干预消息，跳过意图识别但可选 prompt 优化，直接恢复协调器或 Agent 执行
     * 优化条件：promptOptimizer 存在且干预指令长度 > 5 字符时，结合执行上下文优化干预指令
     */
    suspend fun resumeWithIntervention(instruction: String): String {
        if (_isRunning.value) {
            Logger.agent("Agent is already running, cannot resume with intervention")
            return "Agent is already running"
        }

        Logger.i(Logger.AGENT, "========== RESUME WITH INTERVENTION ==========")
        Logger.i(Logger.AGENT, "Intervention: $instruction")

        _isRunning.value = true
        stopRequested = false

        // 步骤1: 干预指令优化 — 当优化器可用且指令>5字符时，结合执行上下文优化
        val effectiveInstruction = if (promptOptimizer != null && instruction.length > 5) {
            Logger.i(Logger.AGENT, "[Intervention] Optimizing intervention with context...")
            val executionHistory = conversationHistory.mapNotNull { msg ->
                when (msg) {
                    is Message.User -> "user" to msg.text
                    is Message.Assistant -> "assistant" to msg.content
                    else -> null
                }
            }
            promptOptimizer!!.optimizeIntervention(
                interventionInstruction = instruction,
                originalTask = lastOriginalTask,
                executionHistory = executionHistory,
                language = agentConfig.language
            )
        } else {
            instruction
        }

        Logger.i(Logger.AGENT, "Effective intervention: $effectiveInstruction")

        // 步骤2: 清理对话历史末尾的 finish/总结消息，避免模型误认为任务已完成
        // 从后往前找到最后一条包含 finish( 的 assistant 消息，移除它及之后的所有消息
        val lastFinishIndex = conversationHistory.indexOfLast { msg ->
            msg is Message.Assistant && msg.content.contains("finish(")
        }
        if (lastFinishIndex >= 0) {
            val removedCount = conversationHistory.size - lastFinishIndex
            while (conversationHistory.size > lastFinishIndex) {
                conversationHistory.removeAt(conversationHistory.size - 1)
            }
            Logger.i(Logger.AGENT, "[Intervention] Removed $removedCount trailing finish/summary messages from history")
        }

        // 步骤3: 注入干预消息到已有对话历史
        conversationHistory.add(Message.User(
            "【用户干预】用户要求调整执行方向：$effectiveInstruction"
        ))
        onInterventionProcessed?.invoke(instruction)

        // 步骤4: 干预恢复执行前开启任务级输入法会话（任务结束/打断时自动恢复）
        val imeSessionStarted = ShellExecutor.beginAdbKeyboardSession(context)
        Logger.i(Logger.AGENT, "[IME_SESSION] intervention start result=$imeSessionStarted")

        try {
            // 根据配置恢复协调器或直接执行模式
            val useCoordinator = smartCoordinator != null
            return if (useCoordinator) {
                executeWithCoordinator(instruction)
            } else {
                executeDirectly(instruction)
            }
        } catch (e: CancellationException) {
            Logger.w(Logger.AGENT, "Intervention task coroutine cancelled, rethrowing")
            throw e
        } catch (e: Exception) {
            val error = "Error: ${e.message}"
            Logger.e(Logger.AGENT, "Intervention task failed: $error", e)
            onError?.invoke(error)
            return error
        } finally {
            // 步骤: 用 NonCancellable 确保协程取消时仍能恢复输入法
            withContext(NonCancellable) {
                if (imeSessionStarted) {
                    ShellExecutor.endAdbKeyboardSession()
                }
            }
            _isRunning.value = false
            _currentTask.value = null
        }
    }

    suspend fun run(task: String, resetHistory: Boolean = true, context: List<SerializableMessage> = emptyList(), enablePlanning: Boolean = true, enableOptimizer: Boolean = true): String {
        if (_isRunning.value) {
            Logger.agent("Agent is already running, ignoring task: $task")
            return "Agent is already running"
        }

        Logger.i(Logger.AGENT, "========== START TASK ==========")
        Logger.i(Logger.AGENT, "Task: $task")
        Logger.i(Logger.AGENT, "enablePlanning: $enablePlanning, smartCoordinator: ${if (smartCoordinator != null) "available" else "null"}")
        Logger.agent("Max steps: ${agentConfig.maxSteps}, Language: ${agentConfig.language}")

        _isRunning.value = true
        _currentTask.value = task
        lastOriginalTask = task  // 保存原始任务，供干预优化使用
        stopRequested = false

        var shouldUseCoordinator = smartCoordinator != null && enablePlanning

        // 如果用户想使用规划但协调器未配置，给出提示
        if (enablePlanning && smartCoordinator == null) {
            val warningMsg = if (agentConfig.language == "cn") {
                "⚠️ 规划功能未生效：请在设置中配置智能协调器的 API 信息"
            } else {
                "⚠️ Planning not available: Please configure Smart Coordinator API in settings"
            }
            Logger.w(Logger.AGENT, warningMsg)
            onThinking?.invoke(warningMsg)
        }

        // 步骤: 意图识别 — 将用户自然语言输入匹配到快捷指令
        // 业务目的: 如果匹配到快捷指令，使用其结构化模板替代原始输入（提升执行准确度）
        var intentTask = task
        if (intentRecognizer != null) {
            if (stopRequested) {
                Logger.i(Logger.AGENT, "Task stopped by user before intent recognition")
                return "Task stopped by user"
            }
            Logger.i(Logger.AGENT, "[PhoneAgent] Running intent recognition...")
            val shortcuts = com.autoglm.assistant.ui.home.ShortcutManager(this.context).loadShortcuts()
            val intentResult = intentRecognizer!!.recognize(task, shortcuts, agentConfig.language)
            if (intentResult.matched && intentResult.filledPrompt != null) {
                Logger.i(Logger.AGENT, "[PhoneAgent] ✓ Intent matched: ${intentResult.matchedShortcutTitle}")
                Logger.i(Logger.AGENT, "[PhoneAgent] ✓ Filled prompt: ${intentResult.filledPrompt}")
                intentTask = intentResult.filledPrompt
                // 使用快捷指令自身的 enablePlanning 设置覆盖
                shouldUseCoordinator = smartCoordinator != null && intentResult.enablePlanning
            } else {
                Logger.i(Logger.AGENT, "[PhoneAgent] Intent not matched, using original task")
            }
            if (stopRequested) {
                Logger.i(Logger.AGENT, "Task stopped by user after intent recognition")
                return "Task stopped by user"
            }
        }

        // 使用Prompt优化器优化任务描述（如果启用）
        // 用户需求：允许协调器使用指令优化器来规划
        val shouldOptimizePrompt = promptOptimizer != null && enableOptimizer
        val effectiveTask = if (shouldOptimizePrompt) {
            if (stopRequested) {
                Logger.i(Logger.AGENT, "Task stopped by user before optimization")
                return "Task stopped by user"
            }
            Logger.i(Logger.AGENT, "[PhoneAgent] Optimizing prompt with PromptOptimizer...")
            // 将对话上下文转换为优化器需要的格式
            val conversationContext = context.map { msg -> msg.role to msg.content }
            val optimized = promptOptimizer!!.optimize(intentTask, agentConfig.language, conversationContext)
            
            if (stopRequested) {
                Logger.i(Logger.AGENT, "Task stopped by user after optimization")
                return "Task stopped by user"
            }

            Logger.i(Logger.AGENT, "[PhoneAgent] ✓ Prompt optimized: $optimized")
            optimized
        } else {
            if (promptOptimizer != null && shouldUseCoordinator) {
                Logger.i(Logger.AGENT, "[PhoneAgent] Skipping PromptOptimizer because SmartCoordinator is enabled")
            }
            intentTask
        }

        if (resetHistory) {
            conversationHistory.clear()
            currentStep = 0
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

        // 步骤: 任务执行前开启输入法会话，避免每次输入来回切换 IME
        val imeSessionStarted = ShellExecutor.beginAdbKeyboardSession(this.context)
        Logger.i(Logger.AGENT, "[IME_SESSION] task start result=$imeSessionStarted")

        try {
            if (stopRequested) {
                Logger.i(Logger.AGENT, "Task stopped by user before planning")
                return "Task stopped by user"
            }

            // 使用SmartCoordinator执行任务（如果启用）
            if (shouldUseCoordinator) {
                Logger.i(Logger.AGENT, "[PhoneAgent] Using SmartCoordinator for task execution")
                return executeWithCoordinator(effectiveTask)
            }

            // 否则按原来的方式直接执行
            return executeDirectly(effectiveTask)

        } catch (e: CancellationException) {
            // 步骤1: CancellationException 必须重新抛出，否则破坏协程取消机制
            // 吞掉该异常会导致协程状态混乱，表现为"StandaloneCoroutine was cancelled"错误
            Logger.w(Logger.AGENT, "Task coroutine cancelled, rethrowing for proper cleanup")
            throw e
        } catch (e: Exception) {
            val error = "Error: ${e.message}"
            Logger.e(Logger.AGENT, "Task failed: $error", e)
            onError?.invoke(error)
            return error
        } finally {
            // 步骤: 用 NonCancellable 确保协程取消时仍能恢复输入法
            withContext(NonCancellable) {
                if (imeSessionStarted) {
                    ShellExecutor.endAdbKeyboardSession()
                }
            }
            _isRunning.value = false
            _currentTask.value = null
        }
    }

    /**
     * 使用协调器执行任务（逐步导航模式）
     */
    private suspend fun executeWithCoordinator(task: String): String {
        Logger.i(Logger.AGENT, "========== EXECUTING WITH COORDINATOR ==========")

        // 协调器模式下，子步骤 finish 不应弹回 app
        actionExecutor.returnToAppOnFinish = false

        var coordinatorSteps = 0
        // 从协调器配置获取最大步数，避免硬编码
        val maxCoordinatorSteps = agentConfig.plannerConfig?.maxCoordinatorSteps ?: 20
        var consecutiveDecisionFailures = 0
        var consecutiveEmptyInstructions = 0
        val maxConsecutiveDecisionFailures = 3
        val maxConsecutiveEmptyInstructions = 3
        
        // 业务目的：记录协调器指令及其完成状态，便于构建清晰的执行历史
        // 避免协调器看到finish后不理解子任务已完成而继续重复执行
        val coordinatorInstructions = mutableListOf<Pair<String, Boolean>>()  // (指令, 是否完成)

        while (coordinatorSteps < maxCoordinatorSteps) {
            if (stopRequested) {
                Logger.i(Logger.AGENT, "Task stopped by user")
                // 步骤: 停止时也立即恢复输入法
                ShellExecutor.endAdbKeyboardSession()
                return generateTaskSummaryIfEnabled(task, stopped = true) ?: "Task stopped by user"
            }

            coordinatorSteps++
            Logger.i(Logger.AGENT, "---------- Coordinator Step $coordinatorSteps ----------")

            // 通知UI当前执行步数
            onCoordinatorStep?.invoke(coordinatorSteps, maxCoordinatorSteps)

            // 获取当前截图
            val screenshot = captureScreenWithOverlayControl()

            // 构建执行历史摘要
            // 业务目的：传入协调器指令历史，让协调器明确知道哪些子任务已完成
            val executionHistory = buildExecutionHistorySummary(coordinatorInstructions)

            // 让协调器决定下一步
            // 根据配置决定是否发送截图（只有支持 vision 的模型才发送）
            // 第一次协调不发送截图（界面是已知的 AutoGLM 页面）
            val screenshotForCoordinator = if (agentConfig.plannerConfig?.enableVision == true && coordinatorSteps > 1) {
                screenshot?.base64Data
            } else {
                null
            }
            val decision = smartCoordinator?.decideNextStep(
                originalTask = task,
                executionHistory = executionHistory,
                screenshotBase64 = screenshotForCoordinator,
                language = agentConfig.language
            )

            if (decision == null) {
                consecutiveDecisionFailures++
                Logger.e(
                    Logger.AGENT,
                    "Coordinator decision failed ($consecutiveDecisionFailures/$maxConsecutiveDecisionFailures), retrying coordinator"
                )
                if (consecutiveDecisionFailures >= maxConsecutiveDecisionFailures) {
                    val failMessage = if (agentConfig.language == "en") {
                        "Coordinator decision failed repeatedly, task stopped"
                    } else {
                        "协调器连续决策失败，任务已停止"
                    }
                    onError?.invoke(failMessage)
                    return failMessage
                }
                continue
            }
            consecutiveDecisionFailures = 0

            Logger.i(Logger.AGENT, "Coordinator decision: ${decision.status}")
            Logger.i(Logger.AGENT, "Assessment: ${decision.assessment}")

            when (decision.status) {
                com.autoglm.assistant.core.planner.DecisionStatus.COMPLETE -> {
                    Logger.i(Logger.AGENT, "✓ Coordinator: Task complete")
                    // 步骤1: 恢复输入法（不阻塞）
                    ShellExecutor.endAdbKeyboardSession()
                    // 步骤2: 立即通知服务任务完成，使唤醒监听尽快恢复
                    // 不等待总结生成（LLM 调用耗时 17-20 秒，会阻塞服务恢复）
                    if (!stopRequested) {
                        onTaskComplete?.invoke(decision.assessment)
                    }
                    // 步骤3: 异步生成任务总结，通过 onTaskSummary 回调更新
                    val summaryMessage = generateTaskSummaryIfEnabled(task, stopped = false) ?: decision.assessment
                    return summaryMessage
                }

                com.autoglm.assistant.core.planner.DecisionStatus.FAILED -> {
                    Logger.e(Logger.AGENT, "✗ Coordinator: Task failed")
                    // 任务失败，恢复默认行为并返回 app
                    actionExecutor.returnToAppOnFinish = true
                    actionExecutor.returnToAutoGLM()
                    val failMessage = if (agentConfig.language == "en") {
                        "Task failed: ${decision.assessment}"
                    } else {
                        "任务失败：${decision.assessment}"
                    }
                    onError?.invoke(failMessage)
                    return failMessage
                }

                com.autoglm.assistant.core.planner.DecisionStatus.CONTINUE -> {
                    if (decision.nextInstruction.isNullOrBlank()) {
                        consecutiveEmptyInstructions++
                        Logger.w(
                            Logger.AGENT,
                            "Coordinator returned CONTINUE but no instruction provided ($consecutiveEmptyInstructions/$maxConsecutiveEmptyInstructions)"
                        )
                        if (consecutiveEmptyInstructions >= maxConsecutiveEmptyInstructions) {
                            val failMessage = if (agentConfig.language == "en") {
                                "Coordinator did not provide next instruction repeatedly, task stopped"
                            } else {
                                "协调器连续未提供下一步指令，任务已停止"
                            }
                            onError?.invoke(failMessage)
                            return failMessage
                        }
                        continue
                    }
                    consecutiveEmptyInstructions = 0

                    Logger.i(Logger.AGENT, "Next instruction: ${decision.nextInstruction}")

                    // 步骤: 协调器发出新指令时压缩上下文
                    // 业务目的: 避免将全部多轮对话历史传给 AutoGLM（token 浪费且容易混淆），
                    // 只保留系统 prompt + 总体目标 + 已完成子任务摘要 + 当前指令
                    compressConversationForNewSubTask(
                        originalTask = task,
                        completedInstructions = coordinatorInstructions,
                        currentInstruction = decision.nextInstruction
                    )

                    // 执行UI Agent的步骤，直到它认为当前指令完成
                    val result = executeUntilFinish(
                        instruction = decision.nextInstruction,
                        respectGlobalStepLimit = false
                    )

                    if (result.needsHumanIntervention) {
                        Logger.w(Logger.AGENT, "Human intervention needed")
                        onHumanInterventionNeeded?.invoke(result.message ?: "Human intervention needed")
                        return result.message ?: "Task paused for human intervention"
                    }

                    // 业务目的：记录该指令的完成状态及干预信息
                    // 当Agent调用finish时，标记为已完成，避免协调器重复执行相同指令
                    // 干预指令以 "用户干预：" 前缀标识，附加到指令记录中供协调器参考
                    val isSubTaskFinished = result.finished
                    val isIntervention = result.message?.startsWith("用户干预：") == true
                    if (isIntervention) {
                        // 干预时记录两条：原指令被中断 + 干预内容，确保协调器看到用户修正
                        coordinatorInstructions.add(decision.nextInstruction to false)
                        coordinatorInstructions.add(result.message!! to true)
                        Logger.i(Logger.AGENT, "Sub-task interrupted by intervention: ${result.message}")
                    } else {
                        coordinatorInstructions.add(decision.nextInstruction to isSubTaskFinished)
                        Logger.i(Logger.AGENT, "Sub-task finished: $isSubTaskFinished")
                    }

                    // 继续下一轮协调
                }
            }
        }

        if (coordinatorSteps >= maxCoordinatorSteps) {
            Logger.w(Logger.AGENT, "Reached max coordinator steps")
        }

        val finalMessage = "Task execution completed after $coordinatorSteps coordinator steps"
        Logger.i(Logger.AGENT, "========== COORDINATOR EXECUTION COMPLETE ==========")

        // 步骤1: 恢复输入法（不阻塞）
        ShellExecutor.endAdbKeyboardSession()
        // 步骤2: 立即通知服务任务完成，使唤醒监听尽快恢复
        onTaskComplete?.invoke(finalMessage)
        // 步骤3: 异步生成任务总结（LLM 调用可能耗时 17-20 秒，不阻塞服务恢复）
        val summaryMessage = generateTaskSummaryIfEnabled(task, stopped = false) ?: finalMessage
        return summaryMessage
    }

    /**
     * 构建执行历史摘要
     * 业务目的：清晰展示协调器指令及其完成状态，避免协调器误解子任务状态而重复执行
     */
    private fun buildExecutionHistorySummary(coordinatorInstructions: List<Pair<String, Boolean>>): String {
        // 优先展示协调器级别的指令历史（更高层次的视角）
        if (coordinatorInstructions.isNotEmpty()) {
            val coordinatorHistory = coordinatorInstructions.mapIndexed { index, (instruction, finished) ->
                val status = if (finished) "✓ 已完成" else "○ 执行中"
                "协调器指令${index + 1}: $instruction [$status]"
            }.joinToString("\n")
            
            // 同时保留最近的Agent执行细节（最多3条）
            val recentAgentActions = conversationHistory
                .filter { it !is Message.System }
                .takeLast(3)
                .mapNotNull { message ->
                    when (message) {
                        is Message.Assistant -> {
                            val content = message.content
                            val actionMatch = Regex("<answer>(.*?)</answer>", RegexOption.DOT_MATCHES_ALL).find(content)
                            val action = actionMatch?.groupValues?.get(1)?.trim()
                            if (action != null && action.isNotBlank()) {
                                "  → ${action.take(80)}"
                            } else null
                        }
                        else -> null
                    }
                }
                .filter { it.isNotBlank() }
                .joinToString("\n")
            
            return buildString {
                append(coordinatorHistory)
                if (recentAgentActions.isNotBlank()) {
                    append("\n\n最近执行的操作：\n")
                    append(recentAgentActions)
                }
                // 步骤：将Agent最近的思考过程纳入协调器决策输入，
                // 帮助协调器理解Agent当前的判断和遇到的问题
                if (lastAgentThinking.isNotBlank()) {
                    append("\n\nAgent最近的思考：\n")
                    append(lastAgentThinking.take(500))
                }
            }
        }
        
        // 如果没有协调器指令历史，回退到原有逻辑（兼容性）
        val recentMessages = conversationHistory
            .filter { it !is Message.System }
            .takeLast(6)
            .mapIndexed { index, message ->
                when (message) {
                    is Message.User -> "步骤${index + 1}: ${message.text.take(100)}"
                    is Message.Assistant -> {
                        val content = message.content
                        val actionMatch = Regex("<answer>(.*?)</answer>", RegexOption.DOT_MATCHES_ALL).find(content)
                        val action = actionMatch?.groupValues?.get(1)?.trim() ?: content.take(100)
                        "  → 执行: $action"
                    }
                    else -> ""
                }
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")

        return if (recentMessages.isBlank()) {
            "尚未执行任何步骤"
        } else {
            buildString {
                append(recentMessages)
                // 步骤：回退路径同样包含Agent思考，保持两条路径口径一致
                if (lastAgentThinking.isNotBlank()) {
                    append("\n\nAgent最近的思考：\n")
                    append(lastAgentThinking.take(500))
                }
            }
        }
    }

    /**
     * 协调器发出新指令时压缩对话上下文。
     * 业务目的：每次协调器切换子任务时，清空旧的多轮对话历史（避免 token 浪费和上下文干扰），
     * 只保留系统 prompt，并将总体目标 + 已完成进度 + 当前指令合成为一条上下文消息。
     * 注意：子任务内部的多步执行仍保留完整对话上下文（不清空）。
     */
    private fun compressConversationForNewSubTask(
        originalTask: String,
        completedInstructions: List<Pair<String, Boolean>>,
        currentInstruction: String
    ) {
        // 步骤1: 提取系统 prompt（必须保留）
        val systemMessage = conversationHistory.firstOrNull { it is Message.System }
            ?: Message.System(agentConfig.getEffectiveSystemPrompt())

        // 步骤2: 清空对话历史
        val oldSize = conversationHistory.size
        conversationHistory.clear()

        // 步骤3: 恢复系统 prompt
        conversationHistory.add(systemMessage)

        // 步骤4: 构建压缩后的上下文消息（总体目标 + 进度 + 当前指令）
        val contextSummary = buildString {
            append("【总体任务目标】\n$originalTask\n\n")
            if (completedInstructions.isNotEmpty()) {
                append("【已完成的子任务】\n")
                completedInstructions.forEachIndexed { index, (instruction, finished) ->
                    val status = if (finished) "✓ 已完成" else "→ 已执行"
                    append("${index + 1}. $instruction [$status]\n")
                }
                append("\n")
            }
            append("【当前子任务】\n$currentInstruction")
        }
        // 步骤5: 以 user 消息注入上下文摘要，再加 assistant 确认，
        // 这样后续 executeStep 添加指令时模型能看到完整任务背景
        conversationHistory.add(Message.User(contextSummary))
        conversationHistory.add(Message.Assistant(
            "<think>了解任务背景和进度，当前需要执行：$currentInstruction</think>" +
            "<answer>好的，我将执行当前子任务。</answer>"
        ))

        Logger.i(Logger.AGENT, "Compressed conversation: $oldSize -> ${conversationHistory.size} messages")
    }

    /**
     * 执行UI Agent步骤直到FINISH或达到步数限制
     */
    private suspend fun executeUntilFinish(
        instruction: String,
        respectGlobalStepLimit: Boolean = true
    ): StepResult {
        // 第一步：使用指令初始化
        var result = executeStep(instruction, isNewTask = true)

        // 继续执行直到完成或达到限制
        var subSteps = 1
        val maxSubSteps = (agentConfig.plannerConfig?.maxAgentStepsPerCoordinatorStep ?: 10)
            .coerceAtLeast(1)

        while (!result.finished &&
            subSteps < maxSubSteps &&
            (!respectGlobalStepLimit || currentStep < agentConfig.maxSteps)
        ) {
            if (stopRequested) {
                return result.copy(
                    success = false,
                    finished = true,
                    message = if (agentConfig.language == "en") "Task stopped by user" else "任务已停止"
                )
            }

            if (result.needsHumanIntervention) {
                return result
            }

            // 步骤：处理Agent中途提问 — 暂停执行，等待用户回答，注入对话后继续
            if (result.userQuestion != null) {
                val question = result.userQuestion!!
                Logger.i(Logger.AGENT, "Agent asks user question: $question")
                val answer = onUserQuestionAsked?.invoke(question)
                if (answer != null) {
                    Logger.i(Logger.AGENT, "User answered: $answer")
                    conversationHistory.add(Message.User("【用户回答】$answer"))
                } else {
                    Logger.w(Logger.AGENT, "No onUserQuestionAsked callback or user cancelled")
                    conversationHistory.add(Message.User("【用户回答】用户未回答，请自行决定"))
                }
                // 继续执行下一步，不返回
            }

            result = executeStep(isNewTask = false)
            subSteps++
        }

        return result
    }

    /**
     * 直接执行任务（不使用协调器）
     */
    private suspend fun executeDirectly(task: String): String {
        if (stopRequested) {
            Logger.i(Logger.AGENT, "Task stopped by user before execution")
            return "Task stopped by user"
        }

        // 第一步：使用任务初始化对话
        var result = executeStep(task, isNewTask = true)

        // 继续执行直到完成或达到最大步数
        while (!result.finished && currentStep < agentConfig.maxSteps) {
            if (stopRequested) {
                Logger.i(Logger.AGENT, "Task stopped by user (stopRequested=true)")
                // 步骤: 停止时也立即恢复输入法
                ShellExecutor.endAdbKeyboardSession()
                return generateTaskSummaryIfEnabled(task, stopped = true) ?: "Task stopped by user"
            }

            if (result.needsHumanIntervention) {
                Logger.w(Logger.AGENT, "Human intervention needed: ${result.message}")
                onHumanInterventionNeeded?.invoke(result.message ?: "Human intervention needed")
                break
            }

            // 步骤：处理Agent中途提问 — 暂停执行，等待用户回答，注入对话后继续
            if (result.userQuestion != null) {
                val question = result.userQuestion!!
                Logger.i(Logger.AGENT, "Agent asks user question (direct mode): $question")
                val answer = onUserQuestionAsked?.invoke(question)
                if (answer != null) {
                    Logger.i(Logger.AGENT, "User answered: $answer")
                    conversationHistory.add(Message.User("【用户回答】$answer"))
                } else {
                    Logger.w(Logger.AGENT, "No onUserQuestionAsked callback or user cancelled")
                    conversationHistory.add(Message.User("【用户回答】用户未回答，请自行决定"))
                }
            }

            result = executeStep(isNewTask = false)
        }

        val finalMessage = result.message ?: "Task completed"
        Logger.i(Logger.AGENT, "========== TASK COMPLETED ==========")
        Logger.i(Logger.AGENT, "Result: $finalMessage (steps: $currentStep)")

        // 步骤1: 恢复输入法（不阻塞）
        ShellExecutor.endAdbKeyboardSession()
        // 步骤2: 立即通知服务任务完成，使唤醒监听尽快恢复
        // 不等待总结生成（LLM 调用耗时 17-20 秒，期间进程可能被系统杀死）
        if (!stopRequested) {
            onTaskComplete?.invoke(finalMessage)
        } else {
            Logger.w(Logger.AGENT, "Stop requested, skipping onTaskComplete")
        }
        // 步骤3: 异步生成任务总结
        val summaryMessage = generateTaskSummaryIfEnabled(task, stopped = false) ?: finalMessage
        return summaryMessage
    }

    /**
     * 执行一步Agent操作
     */
    private suspend fun executeStep(
        userPrompt: String? = null,
        isNewTask: Boolean = false
    ): StepResult {
        currentStep++
        Logger.agent("---------- Step $currentStep ----------")
        Logger.startTimer("step_$currentStep")
        onStepStart?.invoke(currentStep)

        // 给屏幕采样增加最小间隔，避免过于频繁地抓取导致状态抖动。
        val now = System.currentTimeMillis()
        val elapsedSinceLastSample = now - lastSampleTimestampMs
        if (lastSampleTimestampMs > 0 && elapsedSinceLastSample < MIN_SAMPLE_INTERVAL_MS) {
            delay(MIN_SAMPLE_INTERVAL_MS - elapsedSinceLastSample)
        }

        // 1. 截取当前屏幕
        Logger.startTimer("screenshot")
        val screenshot = captureScreenWithOverlayControl()
        lastSampleTimestampMs = System.currentTimeMillis()
        val screenshotTime = Logger.endTimer("screenshot", Logger.SCREEN)
        val base64Image = screenshot?.base64Data
        Logger.screen("Screenshot: ${screenshot?.width}x${screenshot?.height}, sensitive=${screenshot?.isSensitive}, time=${screenshotTime}ms")

        // 2. 获取当前应用信息
        val currentApp = AppDetector.getCurrentApp(context)
        Logger.agent("Current app: $currentApp")
        val screenInfo = MessageBuilder.buildScreenInfo(
            currentApp = currentApp,
            screenWidth = screenCapture.screenWidth,
            screenHeight = screenCapture.screenHeight
        )

        // 3. 构建消息
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

        // 4. 调用模型
        Logger.startTimer("model_request")
        Logger.model("Calling model: ${modelConfig.modelName}, messages: ${conversationHistory.size}")
        val response = modelClient.chat(conversationHistory, object : ModelClient.StreamCallback {
            override fun onToken(token: String) {
                // 流式 token 转发到 UI，悬浮窗实时显示思考内容
                onStreamToken?.invoke(token)
            }

            override fun onThinkingComplete(thinking: String) {
                onThinking?.invoke(thinking)
            }

            override fun onComplete(response: com.autoglm.assistant.ai.ModelResponse) {
                Logger.model("TTFT: ${response.timeToFirstToken}ms, Total: ${response.totalTime}ms")
            }

            override fun onError(error: String) {
                // 仅记录日志，不触发 PhoneAgent.onError
                // 原因：PhoneAgent.onError 会传播到 WakeWordService.onError，将状态重置为 IDLE，
                // 但任务协程仍在运行，状态不一致会导致竞争条件（如协程被取消）
                // 真正的错误会通过 modelClient.chat() 抛出异常来处理
                Logger.e(Logger.MODEL, "Model streaming error (will throw): $error")
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

        // 6. 解析并执行操作
        val parsedAction = ActionParser.parse(response.action)
        Logger.action("Parsed: type=${parsedAction.type}, params=${parsedAction.params}")

        Logger.startTimer("action_execute")
        onBeforeAction?.invoke()  // 操作执行前隐藏悬浮窗，避免遮挡点击目标
        delay(300)  // 等一帧，确保主线程完成悬浮窗 GONE 渲染后再执行点击
        val actionResult = actionExecutor.execute(parsedAction)
        onAfterAction?.invoke()   // 操作执行后恢复悬浮窗
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
            needsHumanIntervention = actionResult.needsHumanIntervention,
            userQuestion = actionResult.userQuestion
        )

        val stepTime = Logger.endTimer("step_$currentStep")
        Logger.agent("Step $currentStep completed: success=${stepResult.success}, finished=${stepResult.finished}, time=${stepTime}ms")

        onStepComplete?.invoke(stepResult)
        return stepResult
    }

    private suspend fun captureScreenWithOverlayControl() = try {
        onBeforeScreenshot?.invoke()
        // 给系统一个很短的窗口，把悬浮条从下一帧中移除
        delay(90)
        screenCapture.capture()
    } finally {
        onAfterScreenshot?.invoke()
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
     * 生成任务总结（如果启用）
     */
    private suspend fun generateTaskSummaryIfEnabled(originalTask: String, stopped: Boolean): String? {
        // 检查是否启用总结功能
        if (promptOptimizer == null || agentConfig.optimizerConfig?.enableTaskSummary != true) {
            return null
        }

        try {
            // 从对话历史中提取上下文（排除系统消息）
            val conversationContext = conversationHistory
                .filter { it !is Message.System }
                .mapNotNull { message ->
                    when (message) {
                        is Message.User -> "user" to message.text
                        is Message.Assistant -> "assistant" to message.content
                        else -> null
                    }
                }

            if (conversationContext.isEmpty()) {
                Logger.w(Logger.AGENT, "No conversation context for summary, skipping")
                return null
            }

            Logger.i(Logger.AGENT, "[PhoneAgent] Generating task summary...")
            val summary = promptOptimizer!!.summarize(originalTask, conversationContext, agentConfig.language)

            // 获取SmartCoordinator收集到的信息
            val gatheredInfo = smartCoordinator?.getInfoSummary()

            // 构建最终总结消息
            val finalSummary = buildString {
                // 任务停止说明
                if (stopped) {
                    if (agentConfig.language == "en") {
                        append("Task stopped. ")
                    } else {
                        append("任务已停止。")
                    }
                }

                // 添加总结
                append(summary)

                // 添加收集到的信息
                if (!gatheredInfo.isNullOrBlank() && gatheredInfo != "无收集到的信息") {
                    append("\n\n")
                    if (agentConfig.language == "en") {
                        append("**Gathered Information:**\n")
                    } else {
                        append("**收集到的信息：**\n")
                    }
                    append(gatheredInfo)
                }
            }

            return finalSummary

        } catch (e: kotlinx.coroutines.CancellationException) {
            // CancellationException 必须重新抛出，否则干预时 cancel 被吞掉，
            // 导致 executeDirectly 继续执行 onTaskComplete 把状态设回 IDLE
            Logger.w(Logger.AGENT, "Task summary cancelled, rethrowing")
            throw e
        } catch (e: Exception) {
            Logger.e(Logger.AGENT, "Failed to generate task summary", e)
            return null
        }
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
        promptOptimizer?.release()
        promptOptimizer = null
    }
}
