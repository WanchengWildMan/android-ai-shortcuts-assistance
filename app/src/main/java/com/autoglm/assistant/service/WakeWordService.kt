package com.autoglm.assistant.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.autoglm.assistant.App
import com.autoglm.assistant.MainActivity
import com.autoglm.assistant.R
import com.autoglm.assistant.ai.ModelConfig
import com.autoglm.assistant.core.agent.AgentConfig
import com.autoglm.assistant.core.agent.PhoneAgent
import com.autoglm.assistant.core.agent.PromptOptimizerConfig
import com.autoglm.assistant.core.agent.SerializableMessage
import com.autoglm.assistant.core.planner.TaskPlannerConfig
import com.autoglm.assistant.voice.SpeechRecognizer
import com.autoglm.assistant.voice.TextToSpeech
import com.autoglm.assistant.voice.WakeWordEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        
        var instance: WakeWordService? = null
            private set
    }

    private val binder = LocalBinder()
    // 使用 Default 而不是 Main，避免切后台时协程被取消
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var wakeWordEngine: WakeWordEngine
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var textToSpeech: TextToSpeech
    private var phoneAgent: PhoneAgent? = null
    private var agentStatusOverlay: AgentStatusOverlayController? = null

    // WakeLock 防止 CPU 休眠
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentTaskJob: Job? = null

    private val _serviceState = MutableStateFlow(ServiceState.IDLE)
    val serviceState: StateFlow<ServiceState> = _serviceState

    private val _lastRecognizedText = MutableStateFlow("")
    val lastRecognizedText: StateFlow<String> = _lastRecognizedText

    // 唤醒词引擎错误信息，供 UI 层观察展示
    private val _lastWakeWordError = MutableStateFlow<String?>(null)
    val lastWakeWordError: StateFlow<String?> = _lastWakeWordError

    // Agent 消息类型
    enum class AgentMessageType {
        THINKING,  // 思考过程
        ACTION,    // 正在执行的动作
        RESULT     // 最终结果
    }

    data class AgentMessage(
        val content: String,
        val type: AgentMessageType,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Coordinator 消息类型
    enum class CoordinatorMessageType {
        OPTIMIZER_STREAMING,    // 优化器流式输出中
        OPTIMIZER_COMPLETE,     // 优化完成
        PLANNING_STREAMING,     // 规划流式输出中
        PLAN_COMPLETE,          // 规划完成
        SUBTASK_CARD,           // 子任务卡片（流式生成时立即显示）
        SUBTASK_START,          // 子任务开始执行
        SUPERVISION_RESULT,     // 监督结果
        COORDINATOR_THINKING,   // 协调器思考
        COORDINATOR_STEP,       // 协调器当前执行步数
        SUMMARY_STREAMING,      // 任务总结流式输出中
        SUMMARY_COMPLETE,       // 任务总结完成
        CLEAR                   // 清除消息
    }

    data class CoordinatorMessage(
        val type: CoordinatorMessageType,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    // 带有类型标签的 Agent 响应消息，用于根据设置进行过滤
    private val _agentMessage = MutableStateFlow<AgentMessage?>(null)
    val agentMessage: StateFlow<AgentMessage?> = _agentMessage

    // Coordinator 消息 - 使用类型标签区分
    private val _coordinatorMessage = MutableStateFlow<CoordinatorMessage?>(null)
    val coordinatorMessage: StateFlow<CoordinatorMessage?> = _coordinatorMessage

    // 用于 UI 更新的回调
    var onWakeWordDetected: (() -> Unit)? = null
    var onSpeechRecognized: ((String) -> Unit)? = null
    var onTaskStarted: ((String) -> Unit)? = null
    var onTaskCompleted: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    enum class ServiceState {
        IDLE,
        LISTENING_WAKE_WORD,
        LISTENING_COMMAND,
        PROCESSING,
        EXECUTING_TASK
    }

    inner class LocalBinder : Binder() {
        fun getService(): WakeWordService = this@WakeWordService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        initializeComponents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 步骤1: 只在明确请求启动语音唤醒时才启动 microphone 前台服务
        val startWakeWord = intent?.getBooleanExtra("START_WAKE_WORD", false) ?: false
        
        if (startWakeWord) {
            // 需要语音唤醒功能
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "❌ 缺少 RECORD_AUDIO 权限，无法启动语音唤醒")
                stopSelf()
                return START_NOT_STICKY
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // 包含所有可能需要的服务类型：语音+屏幕投影+任务执行
                    startForeground(
                        App.NOTIFICATION_ID,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        App.NOTIFICATION_ID,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(App.NOTIFICATION_ID, createNotification())
                }
                startWakeWordListening()
            } catch (e: Exception) {
                Log.e(TAG, "❌ 启动前台服务失败: ${e.message}", e)
                stopSelf()
                return START_NOT_STICKY
            }
        } else {
            // 只需要任务执行服务，不需要语音功能
            // 包含 MediaProjection 用于屏幕截图，specialUse 用于任务执行
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startForeground(
                        App.NOTIFICATION_ID,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        App.NOTIFICATION_ID,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(App.NOTIFICATION_ID, createNotification())
                }
            } catch (e: Exception) {
                // 业务目的：启动前台服务失败时必须停止服务
                // 原因：若不成为前台服务，系统会在应用切后台时杀死服务导致任务中断
                Log.e(TAG, "❌ 启动前台服务失败，停止服务: ${e.message}", e)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        
        return START_STICKY
    }

    private fun initializeComponents() {
        val prefs = App.instance.preferenceManager

        // 初始化唤醒词引擎
        wakeWordEngine = WakeWordEngine(this, prefs.porcupineAccessKey)
        wakeWordEngine.onWakeWordDetected = {
            handleWakeWordDetected()
        }
        wakeWordEngine.onError = { error ->
            _lastWakeWordError.value = error
            onError?.invoke(error)
        }

        // 初始化语音识别
        speechRecognizer = SpeechRecognizer(this)
        speechRecognizer.onResult = { result ->
            handleSpeechResult(result)
        }
        speechRecognizer.onError = { error ->
            onError?.invoke(error)
            // 出错时恢复唤醒词监听
            startWakeWordListening()
        }
        speechRecognizer.initialize()

        // 初始化 TTS
        textToSpeech = TextToSpeech(this)
        textToSpeech.initialize()

        // 初始化 PhoneAgent
        initializePhoneAgent()
        // 执行期状态悬浮条
        agentStatusOverlay = AgentStatusOverlayController(this)
    }

    private fun initializePhoneAgent() {
        val prefs = App.instance.preferenceManager

        val modelConfig = ModelConfig(
            baseUrl = prefs.apiUrl,
            apiKey = prefs.apiKey,
            modelName = prefs.modelName
        )

        // 创建SmartCoordinator配置（只要配置了API信息就创建）
        // 全局开关现在控制"默认启用规划"，不影响是否初始化协调器
        val plannerConfig = if (prefs.coordinatorApiUrl.isNotBlank() &&
                                prefs.coordinatorApiKey.isNotBlank() &&
                                prefs.coordinatorModelName.isNotBlank()) {
                val coordinatorApiKey = when {
                    prefs.coordinatorModelName.startsWith("deepseek") -> prefs.coordinatorApiKeyDeepseek
                    prefs.coordinatorModelName.startsWith("glm-") -> prefs.coordinatorApiKeyBigmodel
                    prefs.coordinatorModelName.startsWith("doubao") -> prefs.coordinatorApiKeyDoubao
                    else -> prefs.coordinatorApiKey
                }
                val coordinatorModelConfig = ModelConfig(
                    baseUrl = prefs.coordinatorApiUrl,
                    apiKey = coordinatorApiKey,
                    modelName = prefs.coordinatorModelName,
                    enableThinking = prefs.coordinatorEnableThinking
                )
            TaskPlannerConfig(
                enabled = prefs.smartCoordinatorEnabled,  // 这个字段现在表示"默认启用规划"
                plannerModelConfig = coordinatorModelConfig,
                enableSupervision = prefs.supervisionEnabled,
                supervisorModelConfig = coordinatorModelConfig,
                maxCorrections = prefs.maxCorrections,
                maxCoordinatorSteps = prefs.maxCoordinatorSteps,
                maxAgentStepsPerCoordinatorStep = prefs.maxAgentStepsPerCoordinatorStep,
                customSystemPrompt = prefs.coordinatorSystemPrompt,
                enableVision = prefs.coordinatorEnableVision
            ).also {
                android.util.Log.i(
                    "AutoGLM",
                    "SmartCoordinator available: model=${prefs.coordinatorModelName}, defaultEnabled=${prefs.smartCoordinatorEnabled}, vision=${prefs.coordinatorEnableVision}, thinking=${prefs.coordinatorEnableThinking}"
                )
            }
        } else {
            android.util.Log.i("AutoGLM", "SmartCoordinator not configured (missing API info)")
            null
        }

        // 创建Prompt优化器配置（如果启用）
        val optimizerConfig = if (prefs.promptOptimizerEnabled) {
            val optimizerApiKey = when {
                prefs.optimizerModelName.startsWith("deepseek") -> prefs.optimizerApiKeyDeepseek
                prefs.optimizerModelName.startsWith("glm-") -> prefs.optimizerApiKeyBigmodel
                prefs.optimizerModelName.startsWith("doubao") -> prefs.optimizerApiKeyDoubao
                else -> prefs.optimizerApiKey
            }
            val optimizerModelConfig = ModelConfig(
                baseUrl = prefs.optimizerApiUrl,
                apiKey = optimizerApiKey,
                modelName = prefs.optimizerModelName
            )
            PromptOptimizerConfig(
                enabled = true,
                modelConfig = optimizerModelConfig,
                enableTaskSummary = prefs.taskSummaryEnabled,
                customSystemPrompt = prefs.optimizerSystemPrompt
            ).also {
                android.util.Log.i("AutoGLM", "PromptOptimizer enabled: model=${prefs.optimizerModelName}, taskSummary=${prefs.taskSummaryEnabled}")
            }
        } else {
            android.util.Log.i("AutoGLM", "PromptOptimizer disabled")
            null
        }

        val agentConfig = AgentConfig(
            maxSteps = prefs.maxSteps,
            language = prefs.language,
            systemPrompt = prefs.agentSystemPrompt.ifBlank { null },
            plannerConfig = plannerConfig,
            optimizerConfig = optimizerConfig
        )

        phoneAgent = PhoneAgent(this, modelConfig, agentConfig).apply {
            initialize()

            onStepStart = { step ->
                agentStatusOverlay?.updateStep(step)
            }

            onThinking = { thinking ->
                // 发送思考过程消息
                _agentMessage.value = AgentMessage(thinking, AgentMessageType.THINKING)
                agentStatusOverlay?.updateThinking(thinking)
            }

            onAction = { action ->
                // 发送动作消息
                _agentMessage.value = AgentMessage(action, AgentMessageType.ACTION)
            }

            onBeforeScreenshot = {
                agentStatusOverlay?.hideForScreenshot()
            }

            onAfterScreenshot = {
                agentStatusOverlay?.restoreAfterScreenshot()
            }

            // Prompt 优化器回调 - 支持流式输出
            var optimizerStreamingContent = StringBuilder()
            var isOptimizing = false
            var summaryStreamingContent = StringBuilder()
            var isSummarizing = false
            var summaryAlreadySent = false  // 用于跟踪总结是否已通过 SUMMARY_COMPLETE 发送

            onPromptOptimizing = {
                isOptimizing = true
                optimizerStreamingContent.clear()
                _coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.OPTIMIZER_STREAMING,
                    content = ""
                )
            }

            onPromptOptimized = { optimizedPrompt ->
                isOptimizing = false
                _coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.OPTIMIZER_COMPLETE,
                    content = optimizedPrompt
                )
            }

            onTaskSummarizing = {
                isSummarizing = true
                summaryAlreadySent = false  // 为新的总结生成重置标志
                summaryStreamingContent.clear()
                _coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.SUMMARY_STREAMING,
                    content = ""
                )
            }

            onTaskSummary = { summary ->
                isSummarizing = false
                summaryAlreadySent = true  // 标记总结已通过 SUMMARY_COMPLETE 发送
                _coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.SUMMARY_COMPLETE,
                    content = summary
                )
            }

            // SmartCoordinator 结构化回调 - 显示格式化内容
            var plannerStreamingContent = StringBuilder()
            var isPlanning = false

            phoneAgent?.onPlanningStart = {
                isPlanning = true
                plannerStreamingContent.clear()
                _coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.PLANNING_STREAMING,
                    content = ""
                )
            }

            onStreamToken = { token ->
                // 流式显示内容 - 根据当前状态决定是优化器、规划器还是总结器
                if (isOptimizing) {
                    optimizerStreamingContent.append(token)
                    _coordinatorMessage.value = CoordinatorMessage(
                        type = CoordinatorMessageType.OPTIMIZER_STREAMING,
                        content = optimizerStreamingContent.toString()
                    )
                } else if (isPlanning) {
                    plannerStreamingContent.append(token)
                    _coordinatorMessage.value = CoordinatorMessage(
                        type = CoordinatorMessageType.PLANNING_STREAMING,
                        content = plannerStreamingContent.toString()
                    )
                } else if (isSummarizing) {
                    summaryStreamingContent.append(token)
                    _coordinatorMessage.value = CoordinatorMessage(
                        type = CoordinatorMessageType.SUMMARY_STREAMING,
                        content = summaryStreamingContent.toString()
                    )
                }
            }

            onCoordinatorThinking = { thinking ->
                // 在聊天中展示协调器的思考分析过程（已格式化为自然语言）
                android.util.Log.d("AutoGLM", "[COORDINATOR] Thinking: ${thinking.take(100)}...")
                _coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.COORDINATOR_THINKING,
                    content = thinking
                )
            }

            onStreamEnd = {
                isPlanning = false
            }

            // 协调器步数回调 — 在消息中显示当前执行步数
            phoneAgent?.onCoordinatorStep = { currentStep, maxSteps ->
                _coordinatorMessage.value = CoordinatorMessage(
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
                // 在悬浮窗显示当前任务
                taskText?.let { agentStatusOverlay?.updateCoordinatorTask(it) }
                // 决策完成后显示决策结果（目标型指令）
                if (!decision.nextInstruction.isNullOrBlank()) {
                    _coordinatorMessage.value = CoordinatorMessage(
                        type = CoordinatorMessageType.SUBTASK_START,
                        content = "**下一步目标：** ${decision.nextInstruction}"
                    )
                } else if (decision.assessment.isNotBlank()) {
                    // 任务完成或失败 — 显示评估结果，不发送无意义的 0|0 步数
                    _coordinatorMessage.value = CoordinatorMessage(
                        type = CoordinatorMessageType.SUBTASK_START,
                        content = "**结果：** ${decision.assessment}"
                    )
                }
            }

            phoneAgent?.onPlanningComplete = { taskPlan ->
                if (taskPlan != null) {
                    // 使用TaskPlan的toReadableText方法，但去掉emoji前缀
                    val planText = taskPlan.toReadableText()
                    _coordinatorMessage.value = CoordinatorMessage(
                        type = CoordinatorMessageType.PLAN_COMPLETE,
                        content = planText
                    )
                    android.util.Log.i("AutoGLM", "[COORDINATOR] Task plan generated with ${taskPlan.subTasks.size} sub-tasks")
                } else {
                    _coordinatorMessage.value = CoordinatorMessage(
                        type = CoordinatorMessageType.PLAN_COMPLETE,
                        content = "任务规划失败，将直接执行"
                    )
                }
            }

            phoneAgent?.onSubTaskGenerated = { subTask ->
                // 子任务生成时立即显示卡片
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
                _coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.SUBTASK_CARD,
                    content = cardText.trim()
                )
                android.util.Log.i("AutoGLM", "[COORDINATOR] SubTask ${subTask.index} card displayed: ${subTask.goal}")
            }

            phoneAgent?.onSubTaskStart = { subTask ->
                agentStatusOverlay?.updateCoordinatorTask(subTask.goal)
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
                _coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.SUBTASK_START,
                    content = subTaskText.trim()
                )
            }

            phoneAgent?.onSupervisionResult = { result ->
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
                _coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.SUPERVISION_RESULT,
                    // 在content中存储状态和内容，用|分隔
                    content = "${result.status.name}|${resultText.trim()}"
                )
            }

            onTaskComplete = { message ->
                _serviceState.value = ServiceState.IDLE
                agentStatusOverlay?.onTaskFinished()
                onTaskCompleted?.invoke(message)
                // 清理coordinator消息
                _coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.CLEAR,
                    content = ""
                )
                // Emit final result message only if summary wasn't already sent
                // This avoids duplicate messages when task summary is enabled
                if (!summaryAlreadySent) {
                    _agentMessage.value = AgentMessage(message, AgentMessageType.RESULT)
                } else {
                    // Reset the flag for next task
                    summaryAlreadySent = false
                }
                speak(message)
                startWakeWordListening()
            }

            onError = { error ->
                // 安全检查：仅当任务协程确实已停止时才重置状态
                // 避免中间错误（如模型调用失败）过早将状态设为 IDLE，
                // 导致后续竞争条件（如协程被意外取消）
                val jobStillActive = currentTaskJob?.isActive == true
                if (!jobStillActive) {
                    _serviceState.value = ServiceState.IDLE
                    agentStatusOverlay?.onTaskFinished()
                    startWakeWordListening()
                } else {
                    android.util.Log.w("AutoGLM", "onError called while task job still active, skipping state reset. error=$error")
                }
                this@WakeWordService.onError?.invoke(error)
                // 清理coordinator消息
                _coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.CLEAR,
                    content = ""
                )
                // Emit error as result
                _agentMessage.value = AgentMessage(error, AgentMessageType.RESULT)
            }

            onHumanInterventionNeeded = { message ->
                speak(message)
                // Emit as action message
                _agentMessage.value = AgentMessage(message, AgentMessageType.ACTION)
            }
        }
    }

    private fun handleWakeWordDetected() {
        _serviceState.value = ServiceState.LISTENING_COMMAND
        onWakeWordDetected?.invoke()

        // Stop wake word listening
        wakeWordEngine.stopListening()

        // Vibrate to provide feedback
        vibrate()

        // Play acknowledgment sound or speak
        speak("我在听")

        // Start speech recognition
        speechRecognizer.startListening(
            if (App.instance.preferenceManager.language == "cn") "zh-CN" else "en-US"
        )
    }

    private fun handleSpeechResult(text: String) {
        if (text.isBlank()) {
            startWakeWordListening()
            return
        }

        // 步骤2: 语音唤醒执行任务时使用全局配置的默认设置
        val prefs = App.instance.preferenceManager

        _lastRecognizedText.value = text
        _serviceState.value = ServiceState.EXECUTING_TASK
        onSpeechRecognized?.invoke(text)
        onTaskStarted?.invoke(text)
        agentStatusOverlay?.onTaskStarted(withCoordinator = prefs.smartCoordinatorEnabled)

        // Execute task with phone agent
        // 与 executeTask() 保持一致：记录 Job 引用以便 stopCurrentTask() 能正确取消
        currentTaskJob = scope.launch {
            acquireWakeLock()
            try {
                phoneAgent?.run(text, true, emptyList(), prefs.smartCoordinatorEnabled, true)
            } catch (e: CancellationException) {
                android.util.Log.i("AutoGLM", "Voice task cancelled")
                onError?.invoke("Task stopped by user")
            } catch (e: Exception) {
                onError?.invoke("Task error: ${e.message}")
            } finally {
                releaseWakeLock()
                _serviceState.value = ServiceState.IDLE
                agentStatusOverlay?.onTaskFinished()
                startWakeWordListening()
            }
        }
    }

    fun startWakeWordListening() {
        val stackTrace = Thread.currentThread().stackTrace
        val caller = if (stackTrace.size > 3) stackTrace[3].methodName else "unknown"
        android.util.Log.i("AutoGLM", "=== startWakeWordListening called by: $caller, current state: ${_serviceState.value}")

        // 步骤1: 任务执行中不打断
        if (_serviceState.value == ServiceState.EXECUTING_TASK) {
            android.util.Log.i("AutoGLM", "=== SKIPPING startWakeWordListening - task is executing!")
            return
        }

        // 步骤2: 清除上次错误
        _lastWakeWordError.value = null

        if (!wakeWordEngine.isInitialized()) {
            val prefs = App.instance.preferenceManager
            if (prefs.porcupineAccessKey.isBlank()) {
                val errorMsg = "请在设置中配置 Porcupine Access Key"
                _lastWakeWordError.value = errorMsg
                _serviceState.value = ServiceState.IDLE
                return
            }

            val wakeWordKeyword = prefs.wakeWordKeyword
            // 步骤3: 检查 initialize() 返回值
            val initSuccess = wakeWordEngine.initialize(keywordName = wakeWordKeyword)

            if (!initSuccess) {
                // 步骤4: 降级策略 — 自定义唤醒词失败时尝试内置唤醒词
                android.util.Log.w("AutoGLM", "自定义唤醒词 '$wakeWordKeyword' 初始化失败，尝试降级到内置唤醒词 PORCUPINE")
                val fallbackSuccess = wakeWordEngine.initializeWithBuiltInKeyword(
                    ai.picovoice.porcupine.Porcupine.BuiltInKeyword.PORCUPINE
                )
                if (!fallbackSuccess) {
                    val errorMsg = "唤醒词引擎初始化失败，请检查 Access Key 是否有效"
                    android.util.Log.e("AutoGLM", errorMsg)
                    _lastWakeWordError.value = errorMsg
                    _serviceState.value = ServiceState.IDLE
                    return
                } else {
                    val warnMsg = "自定义唤醒词不兼容当前 SDK，已降级为内置唤醒词 'Porcupine'"
                    android.util.Log.w("AutoGLM", warnMsg)
                    _lastWakeWordError.value = warnMsg
                }
            }
        }

        // 步骤5: 只有初始化成功后才设置监听状态
        _serviceState.value = ServiceState.LISTENING_WAKE_WORD
        android.util.Log.i("AutoGLM", "=== State changed to LISTENING_WAKE_WORD")
        wakeWordEngine.startListening()
        updateNotification(getString(R.string.notification_listening))
    }

    fun stopWakeWordListening() {
        wakeWordEngine.stopListening()
        _serviceState.value = ServiceState.IDLE
    }

    fun reinitializeWakeWord() {
        val wasListening = wakeWordEngine.isListening.value
        wakeWordEngine.release()
        if (wasListening) {
            startWakeWordListening()
        }
    }

    /**
     * 重新初始化PhoneAgent（当协调器设置改变时调用）
     */
    fun reinitializePhoneAgent() {
        android.util.Log.i("AutoGLM", "Reinitializing PhoneAgent due to settings change")
        // 释放旧的Agent
        phoneAgent?.release()
        // 重新初始化
        initializePhoneAgent()
        // 注意：屏幕截图权限需要重新授予
    }

    fun setScreenCapturePermission(resultCode: Int, data: Intent) {
        // 步骤1: 在设置权限前，确保服务以MediaProjection类型运行
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 升级服务类型为包含MediaProjection
                startForeground(
                    App.NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    App.NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            }
            Log.d(TAG, "✅ 服务已升级为MediaProjection类型")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 升级服务类型失败: ${e.message}", e)
        }
        
        // 步骤2: 设置截图权限
        phoneAgent?.setScreenCaptureData(resultCode, data)
    }

    // 步骤4: WakeWordService执行任务方法 - 默认参数仅作为兜底，实际调用都会传入明确的值
    fun executeTask(task: String, enablePlanning: Boolean = true, enableOptimizer: Boolean = true, context: List<SerializableMessage> = emptyList()) {
        android.util.Log.d("AutoGLM", "WakeWordService.executeTask called: task=$task, enablePlanning=$enablePlanning, enableOptimizer=$enableOptimizer, currentState=${_serviceState.value}")

        if (_serviceState.value == ServiceState.EXECUTING_TASK) {
            onError?.invoke("Already executing a task")
            android.util.Log.d("AutoGLM", "Already executing, returning")
            return
        }

        _serviceState.value = ServiceState.EXECUTING_TASK
        android.util.Log.d("AutoGLM", "State changed to EXECUTING_TASK")
        onTaskStarted?.invoke(task)
        agentStatusOverlay?.onTaskStarted(withCoordinator = enablePlanning)

        currentTaskJob = scope.launch {
            // 获取 WakeLock 防止 CPU 休眠
            acquireWakeLock()
            try {
                phoneAgent?.run(task, true, context, enablePlanning, enableOptimizer)
            } catch (e: CancellationException) {
                android.util.Log.i("AutoGLM", "Task cancelled")
                onError?.invoke("Task stopped by user")
            } catch (e: Exception) {
                onError?.invoke("Task error: ${e.message}")
            } finally {
                releaseWakeLock()
                _serviceState.value = ServiceState.IDLE
                agentStatusOverlay?.onTaskFinished()
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AutoGLM::TaskWakeLock"
            )
        }
        wakeLock?.acquire(10 * 60 * 1000L) // 最多持有10分钟
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }

    fun stopCurrentTask() {
        android.util.Log.w("AutoGLM", "=== WakeWordService.stopCurrentTask() called ===")
        Exception("stopCurrentTask trace").printStackTrace()
        currentTaskJob?.cancel()
        phoneAgent?.stop()
        _serviceState.value = ServiceState.IDLE
        agentStatusOverlay?.onTaskFinished()
    }

    private fun speak(text: String) {
        textToSpeech.speak(text)
    }

    private fun vibrate() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(100)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, App.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_listening))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, App.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(App.NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        android.util.Log.w("AutoGLM", "=== WakeWordService.onDestroy() called - SERVICE IS BEING DESTROYED ===")
        instance = null
        super.onDestroy()
        
        // 业务目的：避免取消正在执行的任务
        // 原因：系统可能因资源压力暂时销毁前台服务，但任务应该继续执行
        // 步骤1：仅停止唤醒词监听，不停止任务执行
        // 步骤2：如果服务重启，任务可以通过协程恢复
        val isTaskRunning = _serviceState.value == ServiceState.EXECUTING_TASK
        if (isTaskRunning) {
            android.util.Log.w("AutoGLM", "=== 任务正在执行，不取消任务，仅清理唤醒词相关资源 ===")
            // 只释放唤醒词相关资源，保持任务继续执行
            try {
                wakeWordEngine.release()
            } catch (e: Exception) {
                android.util.Log.e("AutoGLM", "释放唤醒词引擎失败", e)
            }
            try {
                speechRecognizer.release()
            } catch (e: Exception) {
                android.util.Log.e("AutoGLM", "释放语音识别器失败", e)
            }
            try {
                textToSpeech.release()
            } catch (e: Exception) {
                android.util.Log.e("AutoGLM", "释放语音合成失败", e)
            }
            // 不释放 phoneAgent 和 agentStatusOverlay，让任务继续执行
            // 不取消 scope 和 currentTaskJob
        } else {
            android.util.Log.w("AutoGLM", "=== 无任务执行，正常清理所有资源 ===")
            // 无任务执行时才完全清理
            stopCurrentTask()
            scope.cancel()
            wakeWordEngine.release()
            speechRecognizer.release()
            textToSpeech.release()
            phoneAgent?.release()
            agentStatusOverlay?.release()
            agentStatusOverlay = null
        }
    }
}
