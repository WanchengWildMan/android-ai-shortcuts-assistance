package com.autoglm.assistant.voice.wake

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Porcupine 唤醒引擎实现
 * 基于 Picovoice Porcupine SDK，支持自定义和内置唤醒词
 */
class PorcupineWakeEngine(
    private val context: Context
) : WakeEngine {

    companion object {
        private const val TAG = "WakeEngine:Porcupine"
        private const val SAMPLE_RATE = 16000
        private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    override val engineType = WakeEngine.EngineType.PORCUPINE

    private val _engineState = MutableStateFlow(WakeEngine.EngineState.UNINITIALIZED)
    override val engineState: StateFlow<WakeEngine.EngineState> = _engineState

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError

    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null
    private var processingJob: Job? = null
    private var currentConfig: WakeEngineConfig.PorcupineConfig? = null

    override suspend fun initialize(config: WakeEngineConfig): Boolean {
        if (config !is WakeEngineConfig.PorcupineConfig) {
            _lastError.value = "配置类型错误：需要 PorcupineConfig"
            _engineState.value = WakeEngine.EngineState.ERROR
            return false
        }

        _engineState.value = WakeEngine.EngineState.INITIALIZING
        _lastError.value = null

        return try {
            val builder = Porcupine.Builder()
                .setAccessKey(config.accessKey)
                .setSensitivity(config.sensitivity)

            when {
                config.keywordPath != null -> {
                    // 使用指定路径的自定义唤醒词文件
                    builder.setKeywordPath(config.keywordPath)
                    Log.d(TAG, "初始化自定义唤醒词: ${config.keywordPath}")
                }
                config.keywordName == "XIAOAI" -> {
                    // 使用 assets 中的"小爱"自定义唤醒词
                    builder.setKeywordPath("xiaoai.ppn")
                    Log.d(TAG, "初始化小爱唤醒词")
                }
                else -> {
                    // 使用基于 keywordName 的内置关键词
                    val keyword = try {
                        Porcupine.BuiltInKeyword.valueOf(config.keywordName)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "未知关键词 ${config.keywordName}，降级为 PORCUPINE")
                        Porcupine.BuiltInKeyword.PORCUPINE
                    }
                    builder.setKeyword(keyword)
                    Log.d(TAG, "初始化内置唤醒词: $keyword")
                }
            }

            porcupine = builder.build(context)
            currentConfig = config
            _engineState.value = WakeEngine.EngineState.READY
            Log.i(TAG, "初始化成功，灵敏度: ${config.sensitivity}")
            true
        } catch (e: PorcupineException) {
            val errorMsg = "Porcupine 初始化错误: ${e.message}"
            _lastError.value = errorMsg
            _engineState.value = WakeEngine.EngineState.ERROR
            Log.e(TAG, errorMsg, e)
            false
        }
    }

    override suspend fun startListening(onWakeDetected: (confidence: Float) -> Unit) {
        if (_engineState.value == WakeEngine.EngineState.LISTENING) {
            Log.w(TAG, "已在监听中，忽略重复调用")
            return
        }

        if (porcupine == null) {
            val errorMsg = "引擎未初始化，无法开始监听"
            _lastError.value = errorMsg
            Log.e(TAG, errorMsg)
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
            val errorMsg = "麦克风权限被拒绝"
            _lastError.value = errorMsg
            _engineState.value = WakeEngine.EngineState.ERROR
            Log.e(TAG, errorMsg, e)
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            val errorMsg = "AudioRecord 初始化失败"
            _lastError.value = errorMsg
            _engineState.value = WakeEngine.EngineState.ERROR
            Log.e(TAG, errorMsg)
            return
        }

        audioRecord?.startRecording()
        _engineState.value = WakeEngine.EngineState.LISTENING
        Log.i(TAG, "开始监听唤醒词")

        processingJob = CoroutineScope(Dispatchers.IO).launch {
            val frameLength = porcupine?.frameLength ?: 512
            val buffer = ShortArray(frameLength)

            while (isActive && _engineState.value == WakeEngine.EngineState.LISTENING) {
                val numRead = audioRecord?.read(buffer, 0, frameLength) ?: 0

                if (numRead == frameLength) {
                    try {
                        val keywordIndex = porcupine?.process(buffer) ?: -1
                        if (keywordIndex >= 0) {
                            Log.i(TAG, "检测到唤醒词，索引: $keywordIndex")
                            withContext(Dispatchers.Main) {
                                // Porcupine 不提供置信度，固定返回 1.0
                                onWakeDetected(1.0f)
                            }
                        }
                    } catch (e: PorcupineException) {
                        withContext(Dispatchers.Main) {
                            val errorMsg = "处理错误: ${e.message}"
                            _lastError.value = errorMsg
                            Log.e(TAG, errorMsg, e)
                        }
                    }
                }
            }
        }
    }

    override suspend fun stopListening() {
        if (_engineState.value != WakeEngine.EngineState.LISTENING) {
            return
        }

        Log.i(TAG, "停止监听")
        _engineState.value = WakeEngine.EngineState.READY

        processingJob?.cancel()
        processingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    override fun release() {
        Log.i(TAG, "释放资源")
        runBlocking {
            stopListening()
        }
        porcupine?.delete()
        porcupine = null
        currentConfig = null
        _engineState.value = WakeEngine.EngineState.UNINITIALIZED
    }
}
