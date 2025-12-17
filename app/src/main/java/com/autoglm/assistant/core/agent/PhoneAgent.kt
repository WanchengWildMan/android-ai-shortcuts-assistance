package com.autoglm.assistant.core.agent

import android.content.Context
import com.autoglm.assistant.ai.Message
import com.autoglm.assistant.ai.MessageBuilder
import com.autoglm.assistant.ai.ModelClient
import com.autoglm.assistant.ai.ModelConfig
import com.autoglm.assistant.core.action.ActionExecutor
import com.autoglm.assistant.core.action.ActionParser
import com.autoglm.assistant.core.action.ActionType
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

    private val conversationHistory = mutableListOf<Message>()
    private var currentStep = 0
    private var currentTaskId: String? = null
    private var stopRequested = false  // 用于检测停止请求

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

    fun release() {
        screenCapture.release()
    }
}
