package com.autoglm.assistant.voice.wake

import kotlinx.coroutines.flow.StateFlow

/**
 * 语音唤醒引擎统一接口
 * 所有唤醒引擎（Porcupine / 个人模板 / STT文本匹配）均实现此接口
 *
 * 设计原则：
 * - 可观测：通过 StateFlow 暴露状态和错误
 * - 可配置：所有参数通过 WakeEngineConfig 传入，无硬编码
 * - 统一回调：onWakeDetected 返回置信度 0.0~1.0
 */
interface WakeEngine {

    /** 引擎类型枚举 */
    enum class EngineType {
        PORCUPINE,           // Picovoice Porcupine 商业引擎
        PERSONAL_TEMPLATE,   // MFCC+DTW 个人声纹模板
        STT_SYSTEM,          // Android 系统 SpeechRecognizer 文本匹配
        STT_SHERPA,          // Sherpa-NCNN 离线 STT 文本匹配
    }

    /** 引擎运行状态 */
    enum class EngineState {
        UNINITIALIZED,  // 未初始化
        INITIALIZING,   // 初始化中
        READY,          // 就绪（已初始化，未监听）
        LISTENING,      // 监听中
        ERROR,          // 出错
    }

    /** 引擎类型标识 */
    val engineType: EngineType

    /** 当前引擎状态（可观测） */
    val engineState: StateFlow<EngineState>

    /** 最近一次错误信息（可观测） */
    val lastError: StateFlow<String?>

    /** 是否正在监听 */
    val isListening: Boolean
        get() = engineState.value == EngineState.LISTENING

    /** 是否已初始化 */
    val isInitialized: Boolean
        get() = engineState.value != EngineState.UNINITIALIZED
                && engineState.value != EngineState.ERROR

    /**
     * 初始化引擎
     * @param config 引擎特定配置（通过 WakeEngineConfig 传入）
     * @return 是否成功
     */
    suspend fun initialize(config: WakeEngineConfig): Boolean

    /**
     * 开始监听唤醒词
     * @param onWakeDetected 唤醒回调，参数为置信度/相似度（0.0~1.0）
     */
    suspend fun startListening(onWakeDetected: (confidence: Float) -> Unit)

    /** 停止监听 */
    suspend fun stopListening()

    /** 释放所有资源 */
    fun release()
}
