package com.autoglm.assistant.voice.wake

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineActivationException
import ai.picovoice.porcupine.PorcupineActivationLimitException
import ai.picovoice.porcupine.PorcupineActivationRefusedException
import ai.picovoice.porcupine.PorcupineActivationThrottledException
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineIOException
import ai.picovoice.porcupine.PorcupineRuntimeException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.autoglm.assistant.util.Logger

/**
 * Porcupine 唤醒引擎实现
 * 基于 Picovoice Porcupine SDK，支持自定义和内置唤醒词
 */
class PorcupineWakeEngine(
    private val context: Context
) : WakeEngine {

    companion object {
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
            // 步骤: 释放旧的 Porcupine native 实例（若存在）
            // 原因: reinitialize 路径会再次调 initialize()，若不先 delete 旧对象，
            //   旧 native 实例会泄漏内存，且在极端情况下可能占用音频资源
            porcupine?.delete()
            porcupine = null

            val builder = Porcupine.Builder()
                .setAccessKey(config.accessKey)
                .setSensitivity(config.sensitivity)

            when {
                config.keywordPath != null -> {
                    // 使用指定路径的自定义唤醒词文件
                    builder.setKeywordPath(config.keywordPath)
                    // 自定义 ppn 一律配合中文声学模型（porcupine_params_zh.pv）
                    // 原因：此 app 仅支持中文唤醒词；英文内置词通过 setKeyword() 路径走默认英文模型，不经过这里
                    // 之前依靠文件名是否含 "zh"/"xiaoai" 来判断语言，若文件名不包含这些字母则误用英文模型导致初始化失败并降级 STT
                    builder.setModelPath("porcupine_params_zh.pv")
                    Logger.d(Logger.WAKE, "初始化自定义唤醒词: ${config.keywordPath}，使用中文声学模型")
                }
                config.keywordName == "XIAOAI" -> {
                    // 使用 assets 中的"小爱"自定义唤醒词
                    builder.setKeywordPath("xiaoai.ppn")
                        .setModelPath("porcupine_params_zh.pv")
                    Logger.d(Logger.WAKE, "初始化小爱唤醒词, 配套中文模型")
                }
                else -> {
                    // 使用基于 keywordName 的内置关键词
                    val keyword = try {
                        Porcupine.BuiltInKeyword.valueOf(config.keywordName)
                    } catch (e: IllegalArgumentException) {
                        Logger.w(Logger.WAKE, "未知关键词 ${config.keywordName}，降级为 PORCUPINE")
                        Porcupine.BuiltInKeyword.PORCUPINE
                    }
                    builder.setKeyword(keyword)
                    Logger.d(Logger.WAKE, "初始化内置唤醒词: $keyword")
                }
            }

            porcupine = builder.build(context)
            currentConfig = config
            _engineState.value = WakeEngine.EngineState.READY
            Logger.i(Logger.WAKE, "初始化成功，灵敏度: ${config.sensitivity}")
            true
        } catch (e: PorcupineActivationRefusedException) {
            // 步骤: AccessKey 被 Picovoice 服务器拒绝 — 通常是同一 AccessKey 在过多设备上激活
            val errorMsg = "Porcupine AccessKey 被拒绝，同一 AccessKey 不能同时在多台设备上使用"
            _lastError.value = errorMsg
            _engineState.value = WakeEngine.EngineState.ERROR
            // 安全: 不传入 exception 对象，避免 Porcupine 的 stacktrace 携带 AccessKey 明文
            Logger.e(Logger.WAKE, errorMsg)
            false
        } catch (e: PorcupineActivationLimitException) {
            // 步骤: AccessKey 激活次数已达上限
            val errorMsg = "Porcupine AccessKey 激活次数已达上限，请更换或升级 AccessKey"
            _lastError.value = errorMsg
            _engineState.value = WakeEngine.EngineState.ERROR
            Logger.e(Logger.WAKE, errorMsg)
            false
        } catch (e: PorcupineActivationThrottledException) {
            // 步骤: 激活请求被限速，稍后可重试
            val errorMsg = "Porcupine AccessKey 激活请求过于频繁，请稍后重试"
            _lastError.value = errorMsg
            _engineState.value = WakeEngine.EngineState.ERROR
            Logger.e(Logger.WAKE, errorMsg)
            false
        } catch (e: PorcupineActivationException) {
            // 步骤: 通用激活失败 — AccessKey 无效或格式错误（Failed to parse AccessKey）
            val errorMsg = "Porcupine AccessKey 激活失败，请检查 AccessKey 是否有效（来自 picovoice.ai 控制台）"
            _lastError.value = errorMsg
            _engineState.value = WakeEngine.EngineState.ERROR
            Logger.e(Logger.WAKE, errorMsg)
            false
        } catch (e: PorcupineIOException) {
            // 步骤: ppn 文件不存在或无法读取
            val path = config.keywordPath ?: "内置词"
            val errorMsg = "Porcupine ppn 文件不存在或无法读取: $path"
            _lastError.value = errorMsg
            _engineState.value = WakeEngine.EngineState.ERROR
            Logger.e(Logger.WAKE, errorMsg)
            false
        } catch (e: PorcupineRuntimeException) {
            // 步骤: native 层运行时错误 — 常见原因：ppn 与 SDK 版本不兼容、模型文件损坏
            val errorMsg = "Porcupine 运行时错误（ppn 文件可能与 SDK v4 不兼容，或模型文件损坏）"
            _lastError.value = errorMsg
            _engineState.value = WakeEngine.EngineState.ERROR
            Logger.e(Logger.WAKE, errorMsg)
            false
        } catch (e: PorcupineException) {
            // 步骤: 其他 Porcupine 异常 — 对 e.message 脱敏，避免 AccessKey 明文泄露到日志
            val sanitized = e.message
                ?.replace(Regex("AccessKey `[^`]+`"), "AccessKey `***`")
                ?: "未知错误"
            val errorMsg = "Porcupine 初始化错误: $sanitized"
            _lastError.value = errorMsg
            _engineState.value = WakeEngine.EngineState.ERROR
            Logger.e(Logger.WAKE, errorMsg)
            false
        }
    }

    override suspend fun startListening(onWakeDetected: (confidence: Float) -> Unit) {
        if (_engineState.value == WakeEngine.EngineState.LISTENING) {
            Logger.w(Logger.WAKE, "已在监听中，忽略重复调用")
            return
        }

        if (porcupine == null) {
            val errorMsg = "引擎未初始化，无法开始监听"
            _lastError.value = errorMsg
            Logger.e(Logger.WAKE, errorMsg)
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        // 步骤: 防御性清理 — 如果实例变量残留了上一次的 AudioRecord（stopListening 和
        // startListening 存在竞争，stopListening 可能还没执行到 release 这里），先手动释放
        // 避免 native 层 AudioRecord 资源泄漏或状态不一致导致 releaseBuffer assert 崩溃
        audioRecord?.let { stale ->
            Logger.w(Logger.WAKE, "startListening: 发现残留 AudioRecord，执行防御性清理")
            try { stale.stop() } catch (_: Exception) {}
            try { stale.release() } catch (_: Exception) {}
        }
        audioRecord = null

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
            Logger.e(Logger.WAKE, errorMsg, e)
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            val errorMsg = "AudioRecord 初始化失败"
            _lastError.value = errorMsg
            _engineState.value = WakeEngine.EngineState.ERROR
            Logger.e(Logger.WAKE, errorMsg)
            return
        }

        audioRecord?.startRecording()
        _engineState.value = WakeEngine.EngineState.LISTENING
        Logger.i(Logger.WAKE, "开始监听唤醒词")

        processingJob = CoroutineScope(Dispatchers.IO).launch {
            val frameLength = porcupine?.frameLength ?: 512
            val buffer = ShortArray(frameLength)

            while (isActive && _engineState.value == WakeEngine.EngineState.LISTENING) {
                val numRead = audioRecord?.read(buffer, 0, frameLength) ?: 0

                if (numRead == frameLength) {
                    try {
                        val keywordIndex = porcupine?.process(buffer) ?: -1
                        if (keywordIndex >= 0) {
                            Logger.i(Logger.WAKE, "检测到唤醒词，索引: $keywordIndex")
                            withContext(Dispatchers.Main) {
                                // Porcupine 不提供置信度，固定返回 1.0
                                onWakeDetected(1.0f)
                            }
                        }
                    } catch (e: PorcupineException) {
                        withContext(Dispatchers.Main) {
                            val errorMsg = "处理错误: ${e.message}"
                            _lastError.value = errorMsg
                            Logger.e(Logger.WAKE, errorMsg, e)
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

        Logger.i(Logger.WAKE, "停止监听")
        _engineState.value = WakeEngine.EngineState.READY // 先改变状态，让 while 循环自然退出

        val job = processingJob
        processingJob = null

        // 步骤: 先取出老引用并立刻清空实例变量
        // 原因：stopListening 的 job.join() 是异步等待，等待期间并发的 startListening()
        // 可能已经将 audioRecord 替换为新对象。若直接对 this.audioRecord 操作，
        // 会把新的 AudioRecord stop/release 掉，导致新协程 read 时 native SIGABRT 崩溃。
        val recordToRelease = audioRecord
        audioRecord = null

        if (job != null) {
            job.cancel()
            // 绝不能在另一个线程粗暴 stop，小米等系统会发生 AudioRecord native crash
            // 因为 read() 一次只需 32ms，直接 join 等它跑完即可
            job.join()
        }

        try {
            recordToRelease?.stop()
        } catch (e: Exception) {}
        try {
            recordToRelease?.release()
        } catch (e: Exception) {}
    }

    override fun release() {
        Logger.i(Logger.WAKE, "释放资源")
        runBlocking {
            stopListening()
        }
        porcupine?.delete()
        porcupine = null
        currentConfig = null
        _engineState.value = WakeEngine.EngineState.UNINITIALIZED
    }
}
