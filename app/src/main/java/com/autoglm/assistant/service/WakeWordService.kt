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
import com.autoglm.assistant.util.ScreenUnlocker
import com.autoglm.assistant.util.ShellExecutor
import com.autoglm.assistant.voice.ApiSpeechRecognizer
import com.autoglm.assistant.voice.ImeVoiceSttHelper
import com.autoglm.assistant.voice.SpeechRecognizer
import com.autoglm.assistant.voice.TextToSpeech
import com.autoglm.assistant.voice.wake.WakeEngine
import com.autoglm.assistant.voice.wake.WakeEngineConfig
import com.autoglm.assistant.voice.wake.WakeEngineManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        const val ACTION_SHOW_INTERVENTION = "com.autoglm.assistant.ACTION_SHOW_INTERVENTION"
        var instance: WakeWordService? = null
            private set
    }

    private val binder = LocalBinder()
    // 使用 Default 而不是 Main，避免切后台时协程被取消
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    lateinit var wakeEngineManager: WakeEngineManager
    private var speechRecognizer: SpeechRecognizer? = null
    private var apiSpeechRecognizer: ApiSpeechRecognizer? = null
    private var imeVoiceSttHelper: ImeVoiceSttHelper? = null
    private lateinit var textToSpeech: TextToSpeech
    private var phoneAgent: PhoneAgent? = null
    internal var agentStatusOverlay: AgentStatusOverlayController? = null
    internal var interventionOverlay: InterventionInputOverlay? = null
    private var wakeListeningOverlay: WakeListeningOverlay? = null
    internal var taskSummaryOverlay: TaskSummaryOverlay? = null
    private var adbKeyboardOverlay: AdbKeyboardOverlay? = null

    // WakeLock 防止 CPU 休眠
    private var wakeLock: PowerManager.WakeLock? = null
    internal var currentTaskJob: Job? = null
    // 步骤: 防止 startWakeWordListening 并发重入（invoke + onStartCommand 同时触发时只保留一次初始化）
    private var wakeListeningJob: Job? = null

    internal val _serviceState = MutableStateFlow(ServiceState.IDLE)
    val serviceState: StateFlow<ServiceState> = _serviceState

    private val _lastRecognizedText = MutableStateFlow("")
    val lastRecognizedText: StateFlow<String> = _lastRecognizedText

    // STT 实时识别结果（用于调试）
    private val _lastSttResult = MutableStateFlow("")
    val lastSttResult: StateFlow<String> = _lastSttResult

    // 唤醒词引擎错误信息，供 UI 层观察展示
    private val _lastWakeWordError = MutableStateFlow<String?>(null)
    val lastWakeWordError: StateFlow<String?> = _lastWakeWordError

    // 当前活跃的唤醒引擎类型，供 UI 层展示
    val activeEngineType: StateFlow<WakeEngine.EngineType?>
        get() = wakeEngineManager.activeEngineType

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
    internal val _agentMessage = MutableStateFlow<AgentMessage?>(null)
    val agentMessage: StateFlow<AgentMessage?> = _agentMessage

    // Coordinator 消息 - 使用类型标签区分
    internal val _coordinatorMessage = MutableStateFlow<CoordinatorMessage?>(null)
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
        android.util.Log.d("AutoGLM_START", "WakeWordService onStartCommand called. intent action=${intent?.action}, extra START_WAKE_WORD=${intent?.getBooleanExtra("START_WAKE_WORD", false)}")
        // 处理干预操作请求（通知栏按钮触发）
        if (intent?.action == ACTION_SHOW_INTERVENTION) {
            showInterventionInput()
            return START_STICKY
        }

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
                    // 包含所有可能需要的服务类型：语音+任务执行 (在自动启动阶段不要携带 MEDIA_PROJECTION 避免崩溃)
                    startForeground(
                        App.NOTIFICATION_ID,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        App.NOTIFICATION_ID,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(App.NOTIFICATION_ID, createNotification())
                }
                startWakeWordListening()
            } catch (e: Exception) {
                android.util.Log.e("AutoGLM_START", "❌ 启动前台服务失败: ${e.message}", e)
                stopSelf()
                return START_NOT_STICKY
            }
        } else {
            android.util.Log.d("AutoGLM_START", "startWakeWord is false. Service started without wake word listening.")
            // 只需要任务执行服务，不需要语音功能
            // 不使用 MEDIA_PROJECTION（Android 14 要求 MediaProjection Token，但截图通过无障碍服务实现）
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startForeground(
                        App.NOTIFICATION_ID,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        App.NOTIFICATION_ID,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
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

        // 初始化唤醒引擎管理器
        wakeEngineManager = WakeEngineManager(this)

        // 步骤: 仅在使用系统 STT 模式时初始化系统语音识别器
        // 无 GMS 的设备上 isRecognitionAvailable() 返回 false，会触发误导性的错误 toast
        if (prefs.commandSttMode == "SYSTEM") {
            initOrRefreshSpeechRecognizer(this)
        }

        // 初始化 TTS
        textToSpeech = TextToSpeech(this)
        textToSpeech.initialize()

        // 初始化 PhoneAgent
        initializePhoneAgent()
        // 唤醒监听悬浮窗 — 唤醒后显示"正在听"状态
        wakeListeningOverlay = WakeListeningOverlay(this).apply {
            onCancel = {
                // 用户点击悬浮窗取消录音
                apiSpeechRecognizer?.cancel()
                imeVoiceSttHelper?.cancel()
                dismiss()
                startWakeWordListening()
            }
            onTextSubmit = { text ->
                // 用户通过输入框提交文本指令，等同于语音识别结果
                android.util.Log.i(TAG, "用户文本输入: $text")
                handleSpeechResult(text)
            }
        }
        // 执行期状态悬浮条
        agentStatusOverlay = AgentStatusOverlayController(this).apply {
            onStopRequested = { stopCurrentTask() }
            onInterventionRequested = {
                // 立即中断当前执行（cancel 协程 + stop agent），但保留对话历史和服务状态
                currentTaskJob?.cancel()
                phoneAgent?.stop()
                // 保持 ServiceState.EXECUTING_TASK 以允许输入框弹出
                showInterventionInput()
            }
        }
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
                val coordinatorModelConfig = ModelConfig(
                    baseUrl = prefs.coordinatorApiUrl,
                    apiKey = prefs.resolveApiKey(prefs.coordinatorModelName, com.autoglm.assistant.util.PreferenceManager.ApiModule.COORDINATOR),
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
            val optimizerModelConfig = ModelConfig(
                baseUrl = prefs.optimizerApiUrl,
                apiKey = prefs.resolveApiKey(prefs.optimizerModelName, com.autoglm.assistant.util.PreferenceManager.ApiModule.OPTIMIZER),
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

        // 创建意图识别器配置（如果启用）
        val intentConfig = if (prefs.intentRecognizerEnabled) {
            val intentModelConfig = ModelConfig(
                baseUrl = prefs.intentApiUrl,
                apiKey = prefs.resolveApiKey(prefs.intentModelName, com.autoglm.assistant.util.PreferenceManager.ApiModule.INTENT),
                modelName = prefs.intentModelName
            )
            com.autoglm.assistant.core.agent.IntentRecognizerConfig(
                enabled = true,
                modelConfig = intentModelConfig
            ).also {
                android.util.Log.i("AutoGLM", "IntentRecognizer enabled: model=${prefs.intentModelName}")
            }
        } else {
            android.util.Log.i("AutoGLM", "IntentRecognizer disabled")
            null
        }

        val agentConfig = AgentConfig(
            maxSteps = prefs.maxSteps,
            language = prefs.language,
            systemPrompt = prefs.agentSystemPrompt.ifBlank { null },
            plannerConfig = plannerConfig,
            optimizerConfig = optimizerConfig,
            intentConfig = intentConfig
        )

        phoneAgent = PhoneAgent(this, modelConfig, agentConfig).apply {
            initialize()
        }

        // 步骤：通过 AgentCallbackBinder 绑定所有回调到服务上下文
        AgentCallbackBinder(this).bind(phoneAgent!!)
    }

    private var sttActivity: android.app.Activity? = null

    // 关键改变：用 Activity context 每次重建 STT，绕过 Android 后台麦克风限制
    private fun initOrRefreshSpeechRecognizer(context: android.content.Context) {
        speechRecognizer?.release()
        speechRecognizer = SpeechRecognizer(context)
        // 防止 onError 重复回调导致多次恢复
        var sttErrorHandled = false
        speechRecognizer?.onResult = { result ->
            // 识别完成 → 关闭监听悬浮窗 → 处理指令
            wakeListeningOverlay?.dismiss()
            finishSttActivity()
            handleSpeechResult(result)
        }
        speechRecognizer?.onPartialResult = { partial ->
            // 实时显示用户正在说的内容
            wakeListeningOverlay?.updatePartialResult(partial)
        }
        speechRecognizer?.onError = { error ->
            if (sttErrorHandled) {
                android.util.Log.w("AutoGLM_START", "STT Error 重复回调已忽略: $error")
            } else {
                sttErrorHandled = true
                android.util.Log.e("AutoGLM_START", "STT Error during command listening: $error")
                android.util.Log.e(TAG, "STT Error during command listening: $error")
                // 出错时关闭监听悬浮窗
                wakeListeningOverlay?.showResult("语音识别失败")
                finishSttActivity()
                onError?.invoke(error)
                // 出错时恢复唤醒词监听
                startWakeWordListening()
            }
        }
        speechRecognizer?.initialize()
    }

    fun onSttActivityCreated(activity: android.app.Activity) {
        android.util.Log.i("AutoGLM_START", "onSttActivityCreated called. Recreating STT with Activity Context.")
        sttActivity = activity
        // 步骤1: 用 Activity Context 重新初始化 SpeechRecognizer
        initOrRefreshSpeechRecognizer(activity)
        // 步骤2: 延迟启动 STT 监听
        // SpeechRecognizer.createSpeechRecognizer() 内部异步绑定系统语音服务
        // 如果绑定未完成就调用 startListening，MIUI 会直接返回 ERROR_INSUFFICIENT_PERMISSIONS
        // 延迟 600ms 确保语音服务绑定完成
        scope.launch(Dispatchers.Main) {
            delay(600)
            if (sttActivity != null && _serviceState.value == ServiceState.LISTENING_COMMAND) {
                android.util.Log.i("AutoGLM_START", "延迟后启动 STT 监听")
                speechRecognizer?.startListening(
                    if (App.instance.preferenceManager.language == "cn") "zh-CN" else "en-US"
                )
            } else {
                android.util.Log.w("AutoGLM_START", "延迟后状态已变化，跳过 STT 启动 (activity=${sttActivity != null}, state=${_serviceState.value})")
            }
        }
    }

    fun onSttActivityDestroyed() {
        android.util.Log.i("AutoGLM_START", "onSttActivityDestroyed called.")
        sttActivity = null
    }

    private fun finishSttActivity() {
        sttActivity?.let {
            if (!it.isFinishing) {
                it.finish()
            }
        }
        sttActivity = null
    }

    private fun handleWakeWordDetected() {
        _serviceState.value = ServiceState.LISTENING_COMMAND
        onWakeWordDetected?.invoke()

        // 使用 Main 线程执行，确保顺序
        scope.launch(Dispatchers.Main) {
            // 步骤0: 息屏唤醒 — 先亮屏解锁再进行后续操作
            if (ScreenUnlocker.isScreenOff(this@WakeWordService) || ScreenUnlocker.isKeyguardLocked(this@WakeWordService)) {
                Log.i(TAG, "息屏/锁屏状态，尝试自动解锁...")
                val unlocked = withContext(Dispatchers.IO) {
                    ScreenUnlocker.ensureScreenUnlocked(this@WakeWordService)
                }
                if (!unlocked) {
                    Log.w(TAG, "自动解锁失败，仍继续尝试 STT（可能需要用户手动解锁）")
                }
                delay(300) // 等待解锁后界面稳定
            }

            // 步骤1: 停止唤醒词监听并释放引擎占用的资源
            wakeEngineManager.stopListening()

            // 步骤2: 弹出悬浮窗 — 让用户知道唤醒成功
            wakeListeningOverlay?.showWakeDetected()

            // 步骤3: TTS 问候语（使用配置的问候语）
            val prefs = App.instance.preferenceManager
            if (prefs.wakeGreetingEnabled) {
                speak(prefs.wakeGreetingText)
            }

            // 步骤4: 更新通知栏
            updateNotification("正在听您说话...")

            // 步骤5: 根据 STT 模式选择识别方式
            val sttMode = prefs.commandSttMode
            Log.i(TAG, "唤醒词检测完成，STT 模式: $sttMode, API 类型: ${prefs.sttApiType}")

            if (sttMode == "API") {
                delay(300) // 延迟确保麦克风资源释放（唤醒引擎刚停止）
                if (prefs.sttApiType == "IME_VOICE") {
                    // IME 语音模式：震动延迟到语音就绪后（onStatusChange "请说话..."时触发）
                    startImeVoiceStt()
                } else {
                    // API 模式：直接用 AudioRecord + API，立即可录音
                    vibrate()
                    startApiStt()
                }
            } else {
                // SYSTEM 模式：启动透明 Activity 绕过 Android 11+ 后台麦克风限制
                vibrate()
                delay(300)
                val intent = Intent(this@WakeWordService, com.autoglm.assistant.voice.AssistantActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
            }
        }
    }

    /**
     * API 模式的语音识别
     * 直接使用 AudioRecord + OpenAI 兼容 API，无需启动 Activity
     * AudioRecord 在前台服务中可正常工作（已被 Porcupine 唤醒引擎验证）
     */
    private fun startApiStt() {
        val prefs = App.instance.preferenceManager

        // 步骤1: 创建并配置 ApiSpeechRecognizer
        apiSpeechRecognizer?.release()
        apiSpeechRecognizer = ApiSpeechRecognizer(this).apply {
            apiType = prefs.sttApiType
            // OpenAI 兼容 API 配置
            apiBaseUrl = prefs.sttApiUrl
            apiKey = prefs.sttApiKey
            model = prefs.sttModelName
            // 阿里 NLS 配置
            aliNlsAkId = prefs.aliNlsAkId
            aliNlsAkSecret = prefs.aliNlsAkSecret
            aliNlsAppKey = prefs.aliNlsAppKey

            // 步骤2: 绑定回调
            onResult = { result ->
                Log.i(TAG, "API STT 识别结果: $result")
                // 显示识别结果后延时自动消失
                wakeListeningOverlay?.showResult(result)
                handleSpeechResult(result)
            }
            onPartialResult = { partial ->
                // "正在录音..." / "正在识别..." 等状态由 ApiSpeechRecognizer 发出
                if (partial == "正在识别...") {
                    wakeListeningOverlay?.showRecognizing()
                } else {
                    wakeListeningOverlay?.updatePartialResult(partial)
                }
            }
            onReadyForSpeech = {
                Log.i(TAG, "API STT 就绪，正在录音")
            }
            onEndOfSpeech = {
                Log.i(TAG, "API STT 录音结束，等待识别结果")
            }
            onError = { error ->
                Log.e(TAG, "API STT 错误: $error")
                // 步骤: 通过悬浮窗显示错误状态，避免在用户其他 app 内弹出 Toast 打扰
                wakeListeningOverlay?.showResult("语音识别失败")
                this@WakeWordService.onError?.invoke(error)
                startWakeWordListening()
            }
        }

        // 步骤3: 开始录音
        val language = if (prefs.language == "cn") "zh" else "en"
        apiSpeechRecognizer?.startListening(language)
    }

    /**
     * 通过 IME 语音输入法实现 STT
     * 弹出输入框 → 触发输入法 → 优先无障碍服务单击语音按钮，降级为 su 模拟长按空格 → 监听输入框文字变化
     */
    private fun startImeVoiceStt() {
        val prefs = App.instance.preferenceManager
        imeVoiceSttHelper?.cancel()
        imeVoiceSttHelper = ImeVoiceSttHelper(this).apply {
            spaceX = prefs.imeVoiceSpaceX
            spaceY = prefs.imeVoiceSpaceY
            // 步骤: 从配置读取键盘就绪延迟，允许用户在设置中针对不同设备调整
            keyboardReadyDelay = prefs.imeVoiceKeyboardDelay
            onResult = { result ->
                Log.i(TAG, "IME 语音识别结果: $result")
                wakeListeningOverlay?.showResult(result)
                handleSpeechResult(result)
            }
            onError = { error ->
                Log.e(TAG, "IME 语音识别错误: $error")
                // 步骤: 通过悬浮窗显示错误状态，避免在用户其他 app 内弹出 Toast 打扰
                wakeListeningOverlay?.showResult("语音输入失败")
                startWakeWordListening()
            }
            onStatusChange = { status ->
                if (status.startsWith("识别中: ")) {
                    // 步骤: 纯识别文字只写入输入框，不再同时写 partialText 避免重复
                    val rawText = status.removePrefix("识别中: ")
                    wakeListeningOverlay?.setInputFieldText(rawText)
                } else {
                    // 步骤: 非识别状态（"等待键盘就绪..."/"请说话..."）显示在蓝色状态文字区
                    wakeListeningOverlay?.updatePartialResult(status)
                    // 步骤: "请说话..." 表示 IME 语音就绪，此时震动提示用户开始说话
                    if (status == "请说话...") {
                        vibrate()
                    }
                }
            }
        }
        imeVoiceSttHelper?.start()
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
        android.util.Log.d("AutoGLM_START", "=== startWakeWordListening called by: $caller, current state: ${_serviceState.value} ===")
        android.util.Log.i("AutoGLM", "=== startWakeWordListening called by: $caller, current state: ${_serviceState.value}")

        // 步骤1: 任务执行中不打断
        if (_serviceState.value == ServiceState.EXECUTING_TASK) {
            android.util.Log.i("AutoGLM", "=== SKIPPING startWakeWordListening - task is executing!")
            return
        }

        // 步骤2: 防止并发重入 — invoke 和 onStartCommand 可能在 12ms 内连续触发
        // wakeListeningJob 处于 active 说明上一次初始化协程尚未完成，跳过本次
        if (wakeListeningJob?.isActive == true) {
            android.util.Log.w("AutoGLM", "=== SKIPPING startWakeWordListening - 初始化协程已在运行中 (caller=$caller)")
            return
        }

        // 步骤3: 清除上次错误
        _lastWakeWordError.value = null

        val prefs = App.instance.preferenceManager

        // 步骤4: 根据配置的引擎类型初始化
        wakeListeningJob = scope.launch {
            try {
                val engineType = try {
                    WakeEngine.EngineType.valueOf(prefs.wakeEngineType)
                } catch (e: IllegalArgumentException) {
                    android.util.Log.w("AutoGLM", "未知引擎类型: ${prefs.wakeEngineType}，降级为 PORCUPINE")
                    WakeEngine.EngineType.PORCUPINE
                }

                // 如果引擎未初始化或类型变更，需要切换引擎
                if (!wakeEngineManager.isInitialized || wakeEngineManager.activeEngineType.value != engineType) {
                    val config = createEngineConfig(engineType, prefs)
                    val success = wakeEngineManager.switchEngine(engineType, config)

                    if (!success) {
                        // Porcupine 失败时自动降级到系统 STT（模仿 Operit 降级策略）
                        if (engineType == WakeEngine.EngineType.PORCUPINE) {
                            val porcupineError = wakeEngineManager.lastError.value ?: "未知错误"
                            android.util.Log.w("AutoGLM", "Porcupine 初始化失败: $porcupineError，自动降级到系统 STT")
                            val sttConfig = createEngineConfig(WakeEngine.EngineType.STT_SYSTEM, prefs)
                            val fallbackSuccess = wakeEngineManager.switchEngine(WakeEngine.EngineType.STT_SYSTEM, sttConfig)
                            if (!fallbackSuccess) {
                                // 步骤: Porcupine 和系统 STT 均失败 — 主动 Toast 提示用户，避免静默失败
                                val sttError = wakeEngineManager.lastError.value ?: "未知错误"
                                val errorMsg = "语音唤醒不可用\n" +
                                    "• Porcupine: $porcupineError\n" +
                                    "• 系统 STT: $sttError\n" +
                                    "建议改用「设置 > 唤醒引擎 > API 模式」"
                                android.util.Log.e("AutoGLM", "Porcupine 和系统 STT 均初始化失败. Porcupine: $porcupineError, STT: $sttError")
                                // 步骤: 将完整的多行错误消息写入 StateFlow，供 UI 卡片显示（之前错误地赋了单行字符串而不是 errorMsg）
                                _lastWakeWordError.value = errorMsg
                                // 在主线程弹出 Toast，告知用户原因和解决建议
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(
                                        this@WakeWordService,
                                        errorMsg,
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                                _serviceState.value = ServiceState.IDLE
                                return@launch
                            }
                            // 降级成功，提示用户
                            _lastWakeWordError.value = "Porcupine 不可用($porcupineError)，已降级到系统 STT"
                        } else {
                            val errorMsg = "引擎初始化失败: $engineType"
                            android.util.Log.e("AutoGLM", errorMsg)
                            _lastWakeWordError.value = wakeEngineManager.lastError.value ?: errorMsg
                            _serviceState.value = ServiceState.IDLE
                            return@launch
                        }
                    }
                }

                // 步骤5: 开始监听
                _serviceState.value = ServiceState.LISTENING_WAKE_WORD
                // 记录当前活跃引擎类型，供 UI 显示
                val activeEngine = wakeEngineManager.activeEngineType.value
                android.util.Log.i("AutoGLM", "=== State changed to LISTENING_WAKE_WORD, engine=$activeEngine")

                wakeEngineManager.startListening { confidence ->
                    android.util.Log.i("AutoGLM", "唤醒检测，置信度: $confidence")
                    handleWakeWordDetected()
                }

                // 通知栏显示当前引擎类型
                val engineLabel = when (activeEngine) {
                    WakeEngine.EngineType.PORCUPINE -> "Porcupine"
                    WakeEngine.EngineType.STT_SYSTEM -> "系统STT"
                    else -> activeEngine?.name ?: "未知"
                }
                updateNotification("${getString(R.string.notification_listening)} [$engineLabel]")
            } catch (e: Exception) {
                val errorMsg = "启动唤醒监听失败: ${e.message}"
                android.util.Log.e("AutoGLM", errorMsg, e)
                _lastWakeWordError.value = errorMsg
                _serviceState.value = ServiceState.IDLE
            }
        }
    }

    /**
     * 根据引擎类型和配置创建对应的 WakeEngineConfig
     */
    private fun createEngineConfig(
        engineType: WakeEngine.EngineType,
        prefs: com.autoglm.assistant.util.PreferenceManager
    ): WakeEngineConfig {
        return when (engineType) {
            WakeEngine.EngineType.PORCUPINE -> {
                // 当用户选择"自定义模型"时，使用 customPpnPath 指定模型文件
                val keywordPath = if (prefs.wakeWordKeyword == "CUSTOM" && prefs.customPpnPath.isNotBlank()) {
                    prefs.customPpnPath
                } else {
                    null  // 由 PorcupineWakeEngine 根据 keywordName 决定
                }
                WakeEngineConfig.PorcupineConfig(
                    accessKey = prefs.porcupineAccessKey,
                    keywordPath = keywordPath,
                    keywordName = prefs.wakeWordKeyword,
                    sensitivity = prefs.wakeSensitivity
                )
            }
            WakeEngine.EngineType.PERSONAL_TEMPLATE -> {
                // TODO: Phase 2 实现，从 prefs 读取模板
                WakeEngineConfig.PersonalTemplateConfig()
            }
            WakeEngine.EngineType.STT_SYSTEM,
            WakeEngine.EngineType.STT_SHERPA -> {
                WakeEngineConfig.SttWakeConfig(
                    wakePhrase = prefs.sttWakePhrase,
                    regexEnabled = prefs.sttWakeRegexEnabled,
                    language = prefs.sttWakeLanguage,
                    cooldownMs = prefs.sttWakeCooldownMs
                )
            }
        }
    }

    fun stopWakeWordListening() {
        scope.launch {
            wakeEngineManager.stopListening()
        }
        _serviceState.value = ServiceState.IDLE
    }

    fun reinitializeWakeWord() {
        scope.launch {
            // 步骤1: 彻底释放当前引擎的所有资源（停止监听 + 释放 native 对象 + 重置状态为 UNINITIALIZED）
            // 原因: 之前用 reinitialize() 存在两个问题：
            //   (a) 若 Porcupine 曾失败降级到 STT，currentEngine 类型为 SystemSttWakeEngine，
            //       传入 PorcupineConfig 会因类型不匹配直接返回 false，引擎进入 ERROR 状态
            //   (b) reinitialize 不切换引擎类型，无法从降级后的 STT 回到 Porcupine
            // 改为 release() 彻底清理后由 startWakeWordListening() 根据最新 prefs 全新初始化
            wakeEngineManager.release()
            // 步骤2: 根据最新 prefs 重新创建引擎并开始监听
            // startWakeWordListening 内部会检测到 isInitialized==false，走 switchEngine 全新路径
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
        // 步骤：新任务开始时关闭旧的总结悬浮窗
        taskSummaryOverlay?.dismiss()
        // 步骤：任务开始时同步更新通知栏
        updateNotification("正在执行任务...")

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
                // 安全网：确保任务结束/取消后 IME 被还原（即使 PhoneAgent.finally 失败）
                try {
                    ShellExecutor.endAdbKeyboardSession()
                } catch (e: Exception) {
                    android.util.Log.w("AutoGLM", "任务结束后还原输入法失败: ${e.message}")
                }
                _serviceState.value = ServiceState.IDLE
                agentStatusOverlay?.onTaskFinished()
                // 步骤: 任务结束后更新通知栏
                updateNotification("就绪")
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
        currentTaskJob?.cancel()
        phoneAgent?.stop()
        _serviceState.value = ServiceState.IDLE
        agentStatusOverlay?.onTaskFinished()
        interventionOverlay?.dismiss()
        taskSummaryOverlay?.dismiss()
        // 步骤: 停止任务后恢复唤醒词监听，避免用户需要手动重启服务
        startWakeWordListening()
    }

    /**
     * 显示干预输入浮窗
     * 调用位置：通知栏"干预"按钮 → onStartCommand → 此方法
     */
    private fun showInterventionInput() {
        android.util.Log.i(TAG, "showInterventionInput called, serviceState=${_serviceState.value}")
        if (_serviceState.value != ServiceState.EXECUTING_TASK) {
            android.util.Log.w(TAG, "No task running, ignore intervention request (state=${_serviceState.value})")
            return
        }
        if (interventionOverlay == null) {
            interventionOverlay = InterventionInputOverlay(this).apply {
                onInterventionSubmit = { instruction ->
                    handleIntervention(instruction)
                }
            }
        }
        interventionOverlay?.show()
    }

    /**
     * 显示任务总结悬浮窗
     * 调用位置：onTaskSummary 回调 → 总结生成完成后展示
     */
    internal fun showTaskSummaryOverlay(summary: String) {
        if (taskSummaryOverlay == null) {
            taskSummaryOverlay = TaskSummaryOverlay(this)
        }
        taskSummaryOverlay?.show(summary)
    }

    /**
     * 显示 ADB Keyboard 未安装提示悬浮窗
     * 调用位置：onAdbKeyboardNotInstalled 回调 → adb keyboard 输入失败时展示
     */
    internal fun showAdbKeyboardOverlay() {
        if (adbKeyboardOverlay == null) {
            adbKeyboardOverlay = AdbKeyboardOverlay(this)
        }
        adbKeyboardOverlay?.show()
    }

    /**
     * 处理用户的干预指令
     * 流程：stop 已中断当前执行 → 注入干预消息到对话历史 → 重新启动 agent 执行
     * 行为类似"发送新对话"，但保留已有对话上下文
     */
    private fun handleIntervention(instruction: String) {
        android.util.Log.i(TAG, "Handling intervention (resume): $instruction")
        interventionOverlay?.dismiss()

        // 恢复执行状态和悬浮窗（旧协程 cancel 后 finally 会关闭它们）
        _serviceState.value = ServiceState.EXECUTING_TASK
        val prefs = App.instance.preferenceManager
        agentStatusOverlay?.onTaskStarted(withCoordinator = prefs.smartCoordinatorEnabled)
        agentStatusOverlay?.updateCoordinatorTask("干预: ${instruction.take(30)}...")
        updateNotification("正在处理干预：${instruction.take(30)}...")

        // 通知 UI 添加干预消息气泡（利用 _lastRecognizedText 驱动 LaunchedEffect）
        // 先清空再设值，确保 StateFlow 能触发（即使内容相同）
        _lastRecognizedText.value = ""
        _lastRecognizedText.value = "✎ $instruction"

        // 以干预指令重新启动执行（保留对话历史）
        currentTaskJob = scope.launch {
            acquireWakeLock()
            try {
                val result = phoneAgent?.resumeWithIntervention(instruction) ?: "No agent"
                android.util.Log.i(TAG, "Intervention task completed: ${result.take(100)}")
            } catch (e: CancellationException) {
                android.util.Log.i(TAG, "Intervention task cancelled")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Intervention task error: ${e.message}", e)
                onError?.invoke("Intervention error: ${e.message}")
            } finally {
                releaseWakeLock()
                // 注意：不设 IDLE，让 onTaskComplete/onError 回调处理状态转换
            }
        }
    }

    internal fun speak(text: String) {
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

    internal fun updateNotification(text: String) {
        // 构建干预操作的 PendingIntent（仅任务执行中时显示）
        val isExecuting = _serviceState.value == ServiceState.EXECUTING_TASK

        val builder = NotificationCompat.Builder(this, App.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)

        if (isExecuting) {
            val interventionIntent = Intent(this, WakeWordService::class.java).apply {
                action = ACTION_SHOW_INTERVENTION
            }
            val interventionPending = PendingIntent.getService(
                this, 1, interventionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_dialog_info,
                "干预",
                interventionPending
            )
        }

        val notification = builder.build()
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(App.NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        android.util.Log.w("AutoGLM", "=== WakeWordService.onDestroy() called - SERVICE IS BEING DESTROYED ===")
        instance = null
        super.onDestroy()
        
        // 步骤0: 服务销毁时始终还原输入法（避免用户退出后 IME 仍停留在 ADB Keyboard）
        try {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                ShellExecutor.endAdbKeyboardSession()
            }
        } catch (e: Exception) {
            android.util.Log.e("AutoGLM", "还原输入法失败: ${e.message}", e)
        }
        
        // 业务目的：避免取消正在执行的任务
        // 原因：系统可能因资源压力暂时销毁前台服务，但任务应该继续执行
        // 步骤1：仅停止唤醒词监听，不停止任务执行
        // 步骤2：如果服务重启，任务可以通过协程恢复
        val isTaskRunning = _serviceState.value == ServiceState.EXECUTING_TASK
        if (isTaskRunning) {
            android.util.Log.w("AutoGLM", "=== 任务正在执行，不取消任务，仅清理唤醒词相关资源 ===")
            // 只释放唤醒词相关资源，保持任务继续执行
            try {
                wakeEngineManager.release()
            } catch (e: Exception) {
                android.util.Log.e("AutoGLM", "释放唤醒引擎失败", e)
            }
            try {
                speechRecognizer?.release()
            } catch (e: Exception) {
                android.util.Log.e("AutoGLM", "释放语音识别器失败", e)
            }
            try {
                apiSpeechRecognizer?.release()
            } catch (e: Exception) {
                android.util.Log.e("AutoGLM", "释放 API 语音识别器失败", e)
            }
            try {
                imeVoiceSttHelper?.cancel()
            } catch (e: Exception) {
                android.util.Log.e("AutoGLM", "释放 IME 语音识别器失败", e)
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
            wakeEngineManager.release()
            speechRecognizer?.release()
            apiSpeechRecognizer?.release()
            imeVoiceSttHelper?.cancel()
            textToSpeech.release()
            phoneAgent?.release()
            agentStatusOverlay?.release()
            agentStatusOverlay = null
            wakeListeningOverlay?.release()
            wakeListeningOverlay = null
            taskSummaryOverlay?.dismiss()
            taskSummaryOverlay = null
            adbKeyboardOverlay?.dismiss()
            adbKeyboardOverlay = null
        }
    }
}