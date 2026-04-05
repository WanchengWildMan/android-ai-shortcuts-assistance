package com.autoglm.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as AndroidSpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

class SpeechRecognizer(private val context: Context) {

    private var recognizer: AndroidSpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _partialResult = MutableStateFlow("")
    val partialResult: StateFlow<String> = _partialResult

    var onResult: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onReadyForSpeech: (() -> Unit)? = null
    var onEndOfSpeech: (() -> Unit)? = null

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _isListening.value = true
            onReadyForSpeech?.invoke()
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _isListening.value = false
            onEndOfSpeech?.invoke()
        }

        override fun onError(error: Int) {
            _isListening.value = false
            val errorMessage = when (error) {
                AndroidSpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                AndroidSpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                AndroidSpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                AndroidSpeechRecognizer.ERROR_NETWORK -> "网络错误"
                AndroidSpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                AndroidSpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                AndroidSpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
                AndroidSpeechRecognizer.ERROR_SERVER -> "服务器错误"
                AndroidSpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
                else -> "未知错误: $error"
            }
            onError?.invoke(errorMessage)
        }

        override fun onResults(results: Bundle?) {
            _isListening.value = false
            val matches = results?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)
            val result = matches?.firstOrNull() ?: ""
            onResult?.invoke(result)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull() ?: ""
            _partialResult.value = partial
            onPartialResult?.invoke(partial)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun initialize(): Boolean {
        return if (AndroidSpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = AndroidSpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(recognitionListener)
            true
        } else {
            onError?.invoke("语音识别不可用")
            false
        }
    }

    fun startListening(language: String = "zh-CN") {
        if (_isListening.value) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        recognizer?.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
        _isListening.value = false
    }

    fun cancel() {
        recognizer?.cancel()
        _isListening.value = false
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }
}
