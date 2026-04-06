package com.autoglm.assistant.voice.wake

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import com.autoglm.assistant.util.Logger

/**
 * 个人声纹模板唤醒引擎 (Phase 2 基础框架)
 * 
 * 目前处于基础框架实现阶段，使用录音能量检测作为简单占位
 * 未来将实现 MFCC 特征提取与 DTW 算法匹配
 */
class PersonalTemplateWakeEngine(private val context: Context) : WakeEngine {
    companion object {
        private const val TAG = "WakeEngine:Personal"
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE = 1024
        private const val ENERGY_THRESHOLD = 2000 // 简单能量阈值，用于演示
    }

    override val engineType = WakeEngine.EngineType.PERSONAL_TEMPLATE
    
    private val _engineState = MutableStateFlow(WakeEngine.EngineState.UNINITIALIZED)
    override val engineState = _engineState.asStateFlow()
    
    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError = _lastError.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    private var onWakeDetected: ((Float) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override suspend fun initialize(config: WakeEngineConfig): Boolean {
        Logger.i(Logger.WAKE, "初始化个人模板引擎...")
        _engineState.value = WakeEngine.EngineState.INITIALIZING
        
        if (config !is WakeEngineConfig.PersonalTemplateConfig) {
            _lastError.value = "配置错误：需要 PersonalTemplateConfig"
            _engineState.value = WakeEngine.EngineState.ERROR
            return false
        }

        // TODO: 加载保存在文件系统中的声纹模板
        // val templateFile = File(context.filesDir, "wake_template.dat")
        
        _engineState.value = WakeEngine.EngineState.READY
        Logger.i(Logger.WAKE, "初始化完成")
        return true
    }

    override suspend fun startListening(onWakeDetected: (Float) -> Unit) {
        if (_engineState.value != WakeEngine.EngineState.READY) {
            Logger.w(Logger.WAKE, "引擎未就绪")
            return
        }

        this.onWakeDetected = onWakeDetected
        _engineState.value = WakeEngine.EngineState.LISTENING
        
        job = scope.launch(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, 
                AudioFormat.CHANNEL_IN_MONO, 
                AudioFormat.ENCODING_PCM_16BIT
            )

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize.coerceAtLeast(BUFFER_SIZE)
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    throw Exception("AudioRecord 初始化失败")
                }

                audioRecord?.startRecording()
                Logger.i(Logger.WAKE, "开始录音监听...")

                val buffer = ShortArray(BUFFER_SIZE)
                while (isActive && _engineState.value == WakeEngine.EngineState.LISTENING) {
                    val read = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: 0
                    if (read > 0) {
                        // 简单的能量计算作为占位逻辑
                        var sum = 0L
                        for (i in 0 until read) {
                            sum += Math.abs(buffer[i].toInt())
                        }
                        val avgEnergy = sum / read
                        
                        // 注意：这里仅为占位，不建议实际使用 ENERGY_THRESHOLD 触发
                        // 在 Phase 2 中将替换为真正的 DTW 匹配
                        if (avgEnergy > ENERGY_THRESHOLD * 5) {
                            // Log.v(TAG, "检测到高能量声音: $avgEnergy")
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(Logger.WAKE, "监听过程出错: ${e.message}")
                _lastError.value = e.message
                _engineState.value = WakeEngine.EngineState.ERROR
            } finally {
                stopAudio()
            }
        }
    }

    private fun stopAudio() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioRecord = null
    }

    override suspend fun stopListening() {
        Logger.i(Logger.WAKE, "停止监听")
        _engineState.value = WakeEngine.EngineState.READY
        val j = job
        job = null
        if (j != null) {
            j.cancel()
            j.join()
        }
        try { audioRecord?.stop() } catch (e: Exception) {}
        try { audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null
    }

    override fun release() {
        Logger.i(Logger.WAKE, "释放引擎资源")
        _engineState.value = WakeEngine.EngineState.UNINITIALIZED
        val j = job
        if (j != null) {
            j.cancel()
            runBlocking { j.join() }
        }
        job = null
        stopAudio()
    }
}
