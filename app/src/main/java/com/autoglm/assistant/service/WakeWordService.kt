package com.autoglm.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
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

    // WakeLock 防止 CPU 休眠
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentTaskJob: Job? = null

    private val _serviceState = MutableStateFlow(ServiceState.IDLE)
    val serviceState: StateFlow<ServiceState> = _serviceState

    private val _lastRecognizedText = MutableStateFlow("")
    val lastRecognizedText: StateFlow<String> = _lastRecognizedText

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                App.NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(App.NOTIFICATION_ID, createNotification())
        }
        startWakeWordListening()
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
                )
            TaskPlannerConfig(
                enabled = prefs.smartCoordinatorEnabled,  // 这个字段现在表示"默认启用规划"
                plannerModelConfig = coordinatorModelConfig,
                enableSupervision = prefs.supervisionEnabled,
                supervisorModelConfig = coordinatorModelConfig,
                maxCorrections = prefs.maxCorrections,
                maxCoordinatorSteps = prefs.maxCoordinatorSteps
            ).also {
                android.util.Log.i("AutoGLM", "SmartCoordinator available: model=${prefs.coordinatorModelName}, defaultEnabled=${prefs.smartCoordinatorEnabled}, supervision=${prefs.supervisionEnabled}")
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
            plannerConfig = plannerConfig,
            optimizerConfig = optimizerConfig
        )

        phoneAgent = PhoneAgent(this, modelConfig, agentConfig).apply {
            initialize()

            onThinking = { thinking ->
                // 发送思考过程消息
                _agentMessage.value = AgentMessage(thinking, AgentMessageType.THINKING)
            }

            onAction = { action ->
                // 发送动作消息
                _agentMessage.value = AgentMessage(action, AgentMessageType.ACTION)
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
                _serviceState.value = ServiceState.IDLE
                this@WakeWordService.onError?.invoke(error)
                // 清理coordinator消息
                _coordinatorMessage.value = CoordinatorMessage(
                    type = CoordinatorMessageType.CLEAR,
                    content = ""
                )
                // Emit error as result
                _agentMessage.value = AgentMessage(error, AgentMessageType.RESULT)
                startWakeWordListening()
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

        _lastRecognizedText.value = text
        _serviceState.value = ServiceState.EXECUTING_TASK
        onSpeechRecognized?.invoke(text)
        onTaskStarted?.invoke(text)

        // 步骤2: 语音唤醒执行任务时使用全局配置的默认设置
        val prefs = App.instance.preferenceManager
        // Execute task with phone agent
        scope.launch {
            try {
                phoneAgent?.run(text, true, emptyList(), prefs.smartCoordinatorEnabled, true)
            } catch (e: Exception) {
                onError?.invoke("Task error: ${e.message}")
                startWakeWordListening()
            }
        }
    }

    fun startWakeWordListening() {
        // Log who called this and current state
        val stackTrace = Thread.currentThread().stackTrace
        val caller = if (stackTrace.size > 3) stackTrace[3].methodName else "unknown"
        android.util.Log.e("AutoGLM", "=== startWakeWordListening called by: $caller, current state: ${_serviceState.value}")

        // Don't interrupt task execution!
        if (_serviceState.value == ServiceState.EXECUTING_TASK) {
            android.util.Log.e("AutoGLM", "=== SKIPPING startWakeWordListening - task is executing!")
            return
        }

        if (!wakeWordEngine.isInitialized()) {
            val prefs = App.instance.preferenceManager
            if (prefs.porcupineAccessKey.isNotBlank()) {
                val wakeWordKeyword = prefs.wakeWordKeyword
                wakeWordEngine.initialize(keywordName = wakeWordKeyword)
            } else {
                onError?.invoke("Please configure Porcupine access key in settings")
                return
            }
        }

        _serviceState.value = ServiceState.LISTENING_WAKE_WORD
        android.util.Log.e("AutoGLM", "=== State changed to LISTENING_WAKE_WORD")
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
        phoneAgent?.setScreenCaptureData(resultCode, data)
    }

    // 步骤4: WakeWordService执行任务方法 - 默认参数仅作为兜底，实际调用都会传入明确的值
    fun executeTask(task: String, enablePlanning: Boolean = true, enableOptimizer: Boolean = true, context: List<SerializableMessage> = emptyList()) {
        android.util.Log.d("AutoGLM", "WakeWordService.executeTask called: task=$task, enablePlanning=$enablePlanning, enableOptimizer=$enableOptimizer, currentState=${_serviceState.value}")

        // Upgrade foreground service type to include MediaProjection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                startForeground(App.NOTIFICATION_ID, createNotification(), types)
            } catch (e: Exception) {
                android.util.Log.e("AutoGLM", "Failed to upgrade foreground service type: ${e.message}")
            }
        }

        if (_serviceState.value == ServiceState.EXECUTING_TASK) {
            onError?.invoke("Already executing a task")
            android.util.Log.d("AutoGLM", "Already executing, returning")
            return
        }

        _serviceState.value = ServiceState.EXECUTING_TASK
        android.util.Log.d("AutoGLM", "State changed to EXECUTING_TASK")
        onTaskStarted?.invoke(task)

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
        stopCurrentTask()
        scope.cancel()
        wakeWordEngine.release()
        speechRecognizer.release()
        textToSpeech.release()
        phoneAgent?.release()
    }
}
