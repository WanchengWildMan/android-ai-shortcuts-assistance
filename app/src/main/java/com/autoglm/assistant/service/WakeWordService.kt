package com.autoglm.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
import com.autoglm.assistant.core.agent.SerializableMessage
import com.autoglm.assistant.voice.SpeechRecognizer
import com.autoglm.assistant.voice.TextToSpeech
import com.autoglm.assistant.voice.WakeWordEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WakeWordService : Service() {

    private val binder = LocalBinder()
    // 使用 Default 而不是 Main，避免切后台时协程被取消
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var wakeWordEngine: WakeWordEngine
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var textToSpeech: TextToSpeech
    private var phoneAgent: PhoneAgent? = null

    // WakeLock 防止 CPU 休眠
    private var wakeLock: PowerManager.WakeLock? = null

    private val _serviceState = MutableStateFlow(ServiceState.IDLE)
    val serviceState: StateFlow<ServiceState> = _serviceState

    private val _lastRecognizedText = MutableStateFlow("")
    val lastRecognizedText: StateFlow<String> = _lastRecognizedText

    // Agent message types
    enum class AgentMessageType {
        THINKING,  // Thinking process
        ACTION,    // Action being executed
        RESULT     // Final result
    }

    data class AgentMessage(
        val content: String,
        val type: AgentMessageType,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Agent response message with type to filter by setting
    private val _agentMessage = MutableStateFlow<AgentMessage?>(null)
    val agentMessage: StateFlow<AgentMessage?> = _agentMessage

    // Callbacks for UI updates
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
        initializeComponents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(App.NOTIFICATION_ID, createNotification())
        startWakeWordListening()
        return START_STICKY
    }

    private fun initializeComponents() {
        val prefs = App.instance.preferenceManager

        // Initialize wake word engine
        wakeWordEngine = WakeWordEngine(this, prefs.porcupineAccessKey)
        wakeWordEngine.onWakeWordDetected = {
            handleWakeWordDetected()
        }
        wakeWordEngine.onError = { error ->
            onError?.invoke(error)
        }

        // Initialize speech recognizer
        speechRecognizer = SpeechRecognizer(this)
        speechRecognizer.onResult = { result ->
            handleSpeechResult(result)
        }
        speechRecognizer.onError = { error ->
            onError?.invoke(error)
            // Resume wake word listening on error
            startWakeWordListening()
        }
        speechRecognizer.initialize()

        // Initialize TTS
        textToSpeech = TextToSpeech(this)
        textToSpeech.initialize()

        // Initialize phone agent
        initializePhoneAgent()
    }

    private fun initializePhoneAgent() {
        val prefs = App.instance.preferenceManager

        val modelConfig = ModelConfig(
            baseUrl = prefs.apiUrl,
            apiKey = prefs.apiKey,
            modelName = prefs.modelName
        )

        val agentConfig = AgentConfig(
            maxSteps = prefs.maxSteps,
            language = prefs.language
        )

        phoneAgent = PhoneAgent(this, modelConfig, agentConfig).apply {
            initialize()

            onThinking = { thinking ->
                // Emit thinking process message
                _agentMessage.value = AgentMessage(thinking, AgentMessageType.THINKING)
            }

            onAction = { action ->
                // Emit action message
                _agentMessage.value = AgentMessage(action, AgentMessageType.ACTION)
            }

            onTaskComplete = { message ->
                _serviceState.value = ServiceState.IDLE
                onTaskCompleted?.invoke(message)
                // Emit final result message
                _agentMessage.value = AgentMessage(message, AgentMessageType.RESULT)
                speak(message)
                startWakeWordListening()
            }

            onError = { error ->
                _serviceState.value = ServiceState.IDLE
                this@WakeWordService.onError?.invoke(error)
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

        // Execute task with phone agent
        scope.launch {
            try {
                phoneAgent?.run(text)
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

    fun setScreenCapturePermission(resultCode: Int, data: Intent) {
        phoneAgent?.setScreenCaptureData(resultCode, data)
    }

    fun executeTask(task: String, resetHistory: Boolean = true, context: List<SerializableMessage> = emptyList()) {
        android.util.Log.d("AutoGLM", "WakeWordService.executeTask called: task=$task, currentState=${_serviceState.value}")
        if (_serviceState.value == ServiceState.EXECUTING_TASK) {
            onError?.invoke("Already executing a task")
            android.util.Log.d("AutoGLM", "Already executing, returning")
            return
        }

        _serviceState.value = ServiceState.EXECUTING_TASK
        android.util.Log.d("AutoGLM", "State changed to EXECUTING_TASK")
        onTaskStarted?.invoke(task)

        scope.launch {
            // 获取 WakeLock 防止 CPU 休眠
            acquireWakeLock()
            try {
                // 使用 NonCancellable 防止任务被取消
                withContext(NonCancellable) {
                    phoneAgent?.run(task, resetHistory, context)
                }
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
        super.onDestroy()
        stopCurrentTask()
        scope.cancel()
        wakeWordEngine.release()
        speechRecognizer.release()
        textToSpeech.release()
        phoneAgent?.release()
    }
}
