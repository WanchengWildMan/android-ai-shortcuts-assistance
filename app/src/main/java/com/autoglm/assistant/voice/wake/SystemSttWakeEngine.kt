package com.autoglm.assistant.voice.wake

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.autoglm.assistant.util.Logger

/**
 * 系统 STT 文本匹配唤醒引擎（模仿 Operit 方案重构）
 *
 * 使用 Android 系统 SpeechRecognizer 进行持续语音识别，
 * 同时匹配 partial 和 final 结果中的唤醒词，实现快速唤醒。
 *
 * 关键设计（对齐 Operit）：
 * 1. 开启 EXTRA_PARTIAL_RESULTS → 边说边匹配，无需等说完
 * 2. 每段识别结束后自动重建 SpeechRecognizer 并重新开始
 * 3. 错误分级重试：权限错误→停止；其他错误→延迟重试
 * 4. 文本归一化：去除全部标点、空格、大小写统一
 */
class SystemSttWakeEngine(
    context: Context
) : SttWakeEngine(context) {

    companion object {
        // 错误重试延迟（毫秒）：分级策略
        private const val RESTART_DELAY_NORMAL_MS = 300L   // 无匹配/说话结束 → 短延迟
        private const val RESTART_DELAY_ERROR_MS = 1500L   // 一般错误 → 中延迟
        private const val RESTART_DELAY_BUSY_MS = 2500L    // 识别器忙 → 长延迟
        private const val RESTART_DELAY_NETWORK_MS = 5000L // 网络错误 → 更长延迟
    }

    override val engineType = WakeEngine.EngineType.STT_SYSTEM

    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    // 连续重试失败计数，用于指数退避
    private var consecutiveErrors = 0

    override suspend fun initialize(config: WakeEngineConfig): Boolean {
        if (config !is WakeEngineConfig.SttWakeConfig) {
            _lastError.value = "配置类型错误：需要 SttWakeConfig"
            _engineState.value = WakeEngine.EngineState.ERROR
            return false
        }

        _engineState.value = WakeEngine.EngineState.INITIALIZING
        _lastError.value = null

        return try {
            withContext(Dispatchers.Main) {
                checkRecognitionSupport()
            }
            currentConfig = config
            _engineState.value = WakeEngine.EngineState.READY
            Logger.i(Logger.WAKE, "初始化成功, 唤醒词: '${config.wakePhrase}', 正则: ${config.regexEnabled}")
            true
        } catch (e: IllegalStateException) {
            val errorMsg = "初始化失败: ${e.message}"
            _lastError.value = errorMsg
            _engineState.value = WakeEngine.EngineState.ERROR
            Logger.e(Logger.WAKE, errorMsg, e)
            false
        }
    }

    /** 检查设备是否支持系统语音识别 */
    private fun checkRecognitionSupport() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw IllegalStateException(
                "该设备不支持系统语音识别（可能是无 Google 服务的国产 ROM）。" +
                "建议：1) 在设置中改用「API 模式」唤醒词识别 2) 安装 Google 语音服务"
            )
        }
    }

    override suspend fun startListening(onWakeDetected: (confidence: Float) -> Unit) {
        if (_engineState.value == WakeEngine.EngineState.LISTENING) {
            Logger.w(Logger.WAKE, "已在监听中，忽略重复调用")
            return
        }
        if (_engineState.value != WakeEngine.EngineState.READY) {
            Logger.w(Logger.WAKE, "引擎未就绪（${_engineState.value}），无法启动监听")
            return
        }
        this.onWakeDetected = onWakeDetected
        _engineState.value = WakeEngine.EngineState.LISTENING
        consecutiveErrors = 0
        Logger.i(Logger.WAKE, "开始持续唤醒监听...")
        startRecognitionLoop()
    }

    // ==================== 识别循环核心 ====================

    /**
     * 启动识别循环。
     * 每次创建全新 SpeechRecognizer → 启动识别 → 回调中触发下一轮。
     * 参考 Operit 做法：每次识别段结束后销毁重建，避免系统实现差异导致的状态泄漏。
     */
    private fun startRecognitionLoop() {
        handler.post {
            // 步骤1: 检查是否仍在 LISTENING 状态
            if (_engineState.value != WakeEngine.EngineState.LISTENING) {
                Logger.d(Logger.WAKE, "非 LISTENING 状态，停止识别循环")
                return@post
            }
            try {
                startSingleRecognition()
            } catch (e: Exception) {
                Logger.e(Logger.WAKE, "启动识别异常: ${e.message}", e)
                scheduleRestart(RESTART_DELAY_ERROR_MS)
            }
        }
    }

    /** 单次识别：销毁旧实例 → 创建新实例 → 启动识别 */
    private fun startSingleRecognition() {
        val config = currentConfig ?: return

        // 步骤1: 权限校验
        if (!checkPermissions()) return

        // 步骤2: 销毁旧识别器
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Logger.w(Logger.WAKE, "销毁旧识别器时异常: ${e.message}")
        }

        // 步骤3: 创建新识别器并设置回调
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
            setRecognitionListener(createRecognitionListener())
        }

        // 步骤4: 启动识别
        val intent = createRecognizerIntent(config)
        speechRecognizer?.startListening(intent)
        Logger.d(Logger.WAKE, "已启动本轮识别, 唤醒词: '${config.wakePhrase}'")
    }

    /** 权限校验 */
    private fun checkPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            _lastError.value = "缺少 RECORD_AUDIO 权限"
            _engineState.value = WakeEngine.EngineState.ERROR
            Logger.e(Logger.WAKE, "权限不足: RECORD_AUDIO 未授予")
            return false
        }
        return true
    }

    /**
     * 创建识别 Intent — 关键改进点
     * 1. EXTRA_PARTIAL_RESULTS = true → 边说边匹配
     * 2. EXTRA_PREFER_OFFLINE = true → 降低延迟和网络依赖
     */
    private fun createRecognizerIntent(config: WakeEngineConfig.SttWakeConfig): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3) // 多候选提高命中率
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) // 关键：开启 partial results
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // 优先离线，降低延迟
        }
    }

    /**
     * 创建识别回调（核心逻辑，对齐 Operit 流程）
     *
     * partial → 立即匹配唤醒词（快速触发）
     * final   → 最终匹配 + 触发下一轮
     * error   → 分级延迟重试
     */
    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Logger.d(Logger.WAKE, "准备接收语音")
                consecutiveErrors = 0 // 成功启动，重置错误计数
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // 关键改进：partial 阶段即匹配唤醒词，不等说完
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches.isNullOrEmpty()) return
                for (text in matches) {
                    if (text.isBlank()) continue
                    Logger.d(Logger.WAKE, "partial: '$text'")
                    if (matchWakePhrase(text)) {
                        Logger.i(Logger.WAKE, "唤醒词在 partial 阶段命中: '$text'")
                        triggerWake()
                        return
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    for (text in matches) {
                        if (text.isBlank()) continue
                        Logger.d(Logger.WAKE, "final: '$text'")
                        if (matchWakePhrase(text)) {
                            Logger.i(Logger.WAKE, "唤醒词在 final 阶段命中: '$text'")
                            triggerWake()
                            return
                        }
                    }
                }
                // 未匹配，短延迟后继续下一轮
                scheduleRestart(RESTART_DELAY_NORMAL_MS)
            }

            override fun onError(error: Int) {
                val errorMsg = getErrorText(error)
                Logger.w(Logger.WAKE, "识别错误: $errorMsg (code=$error)")

                when (error) {
                    // 致命错误：权限 → 停止
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        _lastError.value = "RECORD_AUDIO 权限被拒绝"
                        _engineState.value = WakeEngine.EngineState.ERROR
                    }
                    // 无匹配/语音超时 → 正常，短延迟继续
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        scheduleRestart(RESTART_DELAY_NORMAL_MS)
                    }
                    // 识别器忙 → 较长延迟
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        scheduleRestart(RESTART_DELAY_BUSY_MS)
                    }
                    // 网络错误 → 长延迟（离线模式下也可能触发）
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> {
                        consecutiveErrors++
                        // 指数退避，最大30秒
                        val delay = (RESTART_DELAY_NETWORK_MS * consecutiveErrors).coerceAtMost(30_000L)
                        Logger.w(Logger.WAKE, "网络相关错误，${delay}ms 后重试 (连续错误: $consecutiveErrors)")
                        scheduleRestart(delay)
                    }
                    // 其他错误 → 中等延迟
                    else -> {
                        consecutiveErrors++
                        val delay = (RESTART_DELAY_ERROR_MS * consecutiveErrors).coerceAtMost(10_000L)
                        scheduleRestart(delay)
                    }
                }
            }

            override fun onEndOfSpeech() { Logger.d(Logger.WAKE, "说话结束") }
            override fun onBeginningOfSpeech() { Logger.d(Logger.WAKE, "开始说话") }
            override fun onRmsChanged(rmsdB: Float) { /* 静默，避免日志洪水 */ }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    // ==================== 唤醒触发 ====================

    /** 唤醒词命中 → 停止当前识别循环 → 回调上层 */
    private fun triggerWake() {
        // 步骤1: 停止识别循环
        handler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Logger.w(Logger.WAKE, "停止识别异常: ${e.message}")
        }

        // 步骤2: 状态切回 READY（上层会重新调用 startListening）
        _engineState.value = WakeEngine.EngineState.READY
        consecutiveErrors = 0

        // 步骤3: 回调上层
        onWakeDetected?.invoke(1.0f)
    }

    // ==================== 生命周期 ====================

    private fun scheduleRestart(delay: Long) {
        handler.postDelayed({
            if (_engineState.value == WakeEngine.EngineState.LISTENING) {
                startRecognitionLoop()
            }
        }, delay)
    }

    override suspend fun stopListening() {
        withContext(Dispatchers.Main) {
            handler.removeCallbacksAndMessages(null)
            _engineState.value = WakeEngine.EngineState.READY
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Logger.w(Logger.WAKE, "停止监听异常: ${e.message}")
            }
            consecutiveErrors = 0
            Logger.i(Logger.WAKE, "停止监听")
        }
    }

    override fun release() {
        handler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Logger.w(Logger.WAKE, "释放识别器异常: ${e.message}")
        }
        speechRecognizer = null
        consecutiveErrors = 0
        super.release()
        Logger.i(Logger.WAKE, "释放所有资源")
    }

    private fun getErrorText(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "音频错误"
            SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
            SpeechRecognizer.ERROR_NETWORK -> "网络错误"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
            SpeechRecognizer.ERROR_NO_MATCH -> "无匹配结果"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
            SpeechRecognizer.ERROR_SERVER -> "服务器错误"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
            else -> "未知错误($errorCode)"
        }
    }
}
