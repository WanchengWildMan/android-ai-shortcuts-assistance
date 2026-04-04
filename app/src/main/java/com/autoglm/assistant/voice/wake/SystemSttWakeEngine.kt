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
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 系统 STT 文本匹配唤醒引擎
 *
 * 使用 Android 系统的 SpeechRecognizer 进行持续语音识别
 * 通过匹配识别文本中的唤醒词来触发唤醒
 *
 * 优点：
 * - 完全免费，无需 API Key
 * - 使用系统自带语音识别
 * - 可自定义唤醒词文本
 * - 支持正则表达式匹配
 *
 * 缺点：
 * - 需要网络连接（大多数系统 STT 需要联网）
 * - 识别延迟较高
 * - 功耗相对较高
 * - 在某些定制系统上可能不稳定
 */
class SystemSttWakeEngine(
    context: Context
) : SttWakeEngine(context) {

    companion object {
        private const val TAG = "WakeEngine:SystemSTT"
        private const val RESTART_DELAY_MS = 1000L
    }

    override val engineType = WakeEngine.EngineType.STT_SYSTEM

    private var speechRecognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    override suspend fun initialize(config: WakeEngineConfig): Boolean {
        if (config !is WakeEngineConfig.SttWakeConfig) {
            _lastError.value = "配置类型错误"
            _engineState.value = WakeEngine.EngineState.ERROR
            return false
        }

        _engineState.value = WakeEngine.EngineState.INITIALIZING
        _lastError.value = null

        return try {
            withContext(Dispatchers.Main) {
                checkRecognitionSupport(context)
            }
            currentConfig = config
            _engineState.value = WakeEngine.EngineState.READY
            Log.i(TAG, "✅ SystemSTT 引擎初始化成功, 唤醒词: '${config.wakePhrase}'")
            true
        } catch (e: IllegalStateException) {
            val errorMsg = "❌ SystemSTT 初始化失败: ${e.message}"
            _lastError.value = errorMsg
            _engineState.value = WakeEngine.EngineState.ERROR
            Log.e(TAG, errorMsg, e)
            false
        }
    }

    private fun checkRecognitionSupport(context: Context) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw IllegalStateException(
                "设备不支持系统语音识别服务。可能原因：1) 未安装 Google 语音服务 2) 语音服务被禁用 3) 设备不支持。"
            )
        }
        try {
            SpeechRecognizer.createSpeechRecognizer(context)?.destroy()
        } catch (e: Exception) {
            throw IllegalStateException("无法创建语音识别器实例。", e)
        }
    }

    override suspend fun startListening(onWakeDetected: (confidence: Float) -> Unit) {
        if (_engineState.value != WakeEngine.EngineState.READY) {
            Log.w(TAG, "⚠️ 引擎未就绪, 无法开始监听. 当前状态: ${_engineState.value}")
            return
        }
        this.onWakeDetected = onWakeDetected
        _engineState.value = WakeEngine.EngineState.LISTENING
        Log.i(TAG, "🚀 开始持续监听...")
        startRecognitionLoop()
    }

    private fun startRecognitionLoop() {
        handler.post {
            if (_engineState.value != WakeEngine.EngineState.LISTENING) {
                Log.w(TAG, "⚠️ 监听到非 LISTENING 状态，停止识别循环。")
                return@post
            }
            try {
                startSingleRecognition()
            } catch (e: Exception) {
                Log.e(TAG, "❌ 启动识别时发生异常", e)
                scheduleRestart()
            }
        }
    }

    private fun startSingleRecognition() {
        val config = currentConfig ?: return

        // 1. 权限与可用性检查
        if (!isServiceAvailable()) {
            return
        }

        // 2. 清理并创建新的识别器
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
            setRecognitionListener(createRecognitionListener())
        }

        // 3. 创建并启动 Intent
        val intent = createRecognizerIntent(config)
        speechRecognizer?.startListening(intent)
        Log.d(TAG, "🎤 已调用 startListening()")
    }

    private fun isServiceAvailable(): Boolean {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ 权限不足: RECORD_AUDIO 未被授予。")
            _lastError.value = "缺少 RECORD_AUDIO 权限"
            _engineState.value = WakeEngine.EngineState.ERROR
            return false
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "❌ 服务不可用: SpeechRecognizer.isRecognitionAvailable() 返回 false。")
            _lastError.value = "语音识别服务不可用"
            _engineState.value = WakeEngine.EngineState.ERROR
            return false
        }
        return true
    }

    private fun createRecognizerIntent(config: WakeEngineConfig.SttWakeConfig): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // 在某些设备上，这有助于减少网络使用和延迟
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "✅ 准备接收语音")
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    Log.i(TAG, "👂 STT 识别结果: '$text'")
                    if (matchWakePhrase(text)) {
                        Log.i(TAG, "🎯 唤醒词匹配成功!")
                        _engineState.value = WakeEngine.EngineState.READY // 停止循环
                        onWakeDetected?.invoke(1.0f)
                    } else {
                        scheduleRestart(500) // 未匹配，短暂延迟后继续
                    }
                } else {
                    scheduleRestart() // 无结果，正常延迟后继续
                }
            }

            override fun onError(error: Int) {
                val errorMsg = getErrorText(error)
                Log.e(TAG, "识别错误: $errorMsg (code: $error)")
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    _lastError.value = "权限不足。请检查应用的 RECORD_AUDIO 权限。"
                    _engineState.value = WakeEngine.EngineState.ERROR
                } else {
                    scheduleRestart()
                }
            }

            override fun onEndOfSpeech() { Log.d(TAG, "🗣️ 说话结束") }
            override fun onBeginningOfSpeech() { Log.d(TAG, "🗣️ 开始说话") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
    
    private fun scheduleRestart(delay: Long = RESTART_DELAY_MS) {
        handler.postDelayed({
            startRecognitionLoop()
        }, delay)
    }

    override suspend fun stopListening() {
        withContext(Dispatchers.Main) {
            handler.removeCallbacksAndMessages(null) // 取消所有待处理的重启任务
            _engineState.value = WakeEngine.EngineState.READY
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            Log.i(TAG, "🛑 停止监听")
        }
    }

    override fun release() {
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.release()
        Log.i(TAG, "🧹 释放所有资源")
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
            else -> "未知错误"
        }
    }
}
