package com.autoglm.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech as AndroidTTS
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

class TextToSpeech(private val context: Context) {

    private var tts: AndroidTTS? = null
    private var isInitialized = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    var onInitialized: ((Boolean) -> Unit)? = null
    var onSpeakStart: (() -> Unit)? = null
    var onSpeakDone: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _isSpeaking.value = true
            onSpeakStart?.invoke()
        }

        override fun onDone(utteranceId: String?) {
            _isSpeaking.value = false
            onSpeakDone?.invoke()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            _isSpeaking.value = false
            onError?.invoke("TTS error for utterance: $utteranceId")
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            _isSpeaking.value = false
            onError?.invoke("TTS error: $errorCode")
        }
    }

    fun initialize(language: Locale = Locale.CHINESE) {
        tts = AndroidTTS(context) { status ->
            if (status == AndroidTTS.SUCCESS) {
                val result = tts?.setLanguage(language)
                isInitialized = result != AndroidTTS.LANG_MISSING_DATA &&
                        result != AndroidTTS.LANG_NOT_SUPPORTED

                tts?.setOnUtteranceProgressListener(utteranceListener)
                onInitialized?.invoke(isInitialized)
            } else {
                isInitialized = false
                onInitialized?.invoke(false)
                onError?.invoke("TTS initialization failed")
            }
        }
    }

    fun speak(text: String, queueMode: Int = AndroidTTS.QUEUE_FLUSH) {
        if (!isInitialized) {
            onError?.invoke("TTS not initialized")
            return
        }

        val utteranceId = System.currentTimeMillis().toString()
        tts?.speak(text, queueMode, null, utteranceId)
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch)
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    fun isAvailable(): Boolean = isInitialized
}
