package com.autoglm.assistant.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WakeWordEngine(
    private val context: Context,
    private val accessKey: String
) {
    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null
    private var processingJob: Job? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    var onWakeWordDetected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    companion object {
        private const val SAMPLE_RATE = 16000
        private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    fun initialize(keywordPath: String? = null, keywordName: String = "XIAOAI"): Boolean {
        return try {
            val builder = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setSensitivity(0.7f)

            when {
                keywordPath != null -> {
                    // Use custom wake word file from path
                    builder.setKeywordPath(keywordPath)
                }
                keywordName == "XIAOAI" -> {
                    // Use 小爱 custom wake word from assets
                    builder.setKeywordPath("xiaoai.ppn")
                }
                else -> {
                    // Use built-in keyword based on keywordName
                    val keyword = try {
                        Porcupine.BuiltInKeyword.valueOf(keywordName)
                    } catch (e: IllegalArgumentException) {
                        Porcupine.BuiltInKeyword.PORCUPINE
                    }
                    builder.setKeyword(keyword)
                }
            }

            porcupine = builder.build(context)
            true
        } catch (e: PorcupineException) {
            _lastError.value = "Porcupine init error: ${e.message}"
            onError?.invoke(_lastError.value!!)
            false
        }
    }

    fun initializeWithBuiltInKeyword(keyword: Porcupine.BuiltInKeyword): Boolean {
        return try {
            porcupine = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeyword(keyword)
                .setSensitivity(0.7f)
                .build(context)
            true
        } catch (e: PorcupineException) {
            _lastError.value = "Porcupine init error: ${e.message}"
            onError?.invoke(_lastError.value!!)
            false
        }
    }

    fun startListening() {
        if (_isListening.value || porcupine == null) {
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize * 2
            )
        } catch (e: SecurityException) {
            _lastError.value = "Microphone permission denied"
            onError?.invoke(_lastError.value!!)
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            _lastError.value = "AudioRecord initialization failed"
            onError?.invoke(_lastError.value!!)
            return
        }

        audioRecord?.startRecording()
        _isListening.value = true

        processingJob = CoroutineScope(Dispatchers.IO).launch {
            val frameLength = porcupine?.frameLength ?: 512
            val buffer = ShortArray(frameLength)

            while (isActive && _isListening.value) {
                val numRead = audioRecord?.read(buffer, 0, frameLength) ?: 0

                if (numRead == frameLength) {
                    try {
                        val keywordIndex = porcupine?.process(buffer) ?: -1
                        if (keywordIndex >= 0) {
                            withContext(Dispatchers.Main) {
                                onWakeWordDetected?.invoke()
                            }
                        }
                    } catch (e: PorcupineException) {
                        withContext(Dispatchers.Main) {
                            _lastError.value = "Processing error: ${e.message}"
                            onError?.invoke(_lastError.value!!)
                        }
                    }
                }
            }
        }
    }

    fun stopListening() {
        _isListening.value = false
        processingJob?.cancel()
        processingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun release() {
        stopListening()
        porcupine?.delete()
        porcupine = null
    }

    fun isInitialized(): Boolean = porcupine != null
}
