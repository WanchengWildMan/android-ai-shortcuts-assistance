package com.autoglm.assistant.voice.wake

/**
 * 唤醒引擎配置 — 密封类，每种引擎有独立的配置子类
 * 所有阈值、超时等参数均可配置，不硬编码
 */
sealed class WakeEngineConfig {

    /** Porcupine 引擎配置 */
    data class PorcupineConfig(
        val accessKey: String,
        val keywordPath: String? = null,      // 自定义 .ppn 文件路径
        val keywordName: String = "XIAOAI",   // 内置关键词名称
        val sensitivity: Float = 0.7f,        // 灵敏度 0.0~1.0
        val sampleRate: Int = 16000,
    ) : WakeEngineConfig()

    /** 个人模板引擎配置（MFCC+DTW） */
    data class PersonalTemplateConfig(
        val templates: List<FloatArray> = emptyList(),  // 用户录制的模板特征
        val sampleRate: Int = 16000,
        val frameSize: Int = 512,
        val maxSegmentMs: Long = 1600L,
        val minSegmentMs: Long = 250L,
        val endSilenceMs: Long = 350L,
        val similarityThreshold: Float = 0.865f,
        val dynamicThresholdMargin: Float = 0.02f,
        val minDynamicThresholdFloor: Float = 0.84f,
        val requiredTemplateMatches: Int = 1,
        val minDurationRatio: Float = 0.75f,
        val maxDurationRatio: Float = 1.25f,
        val dtwBand: Int = 4,
        val minRms: Float = 0.003f,
        val rmsNoiseMargin: Float = 0.001f,
        val noiseRmsEmaAlpha: Float = 0.05f,
        val vadModelAssetPath: String = "models/silero_vad.onnx",
    ) : WakeEngineConfig()

    /** STT 文本匹配引擎配置（通用，适用于系统 STT 和 Sherpa） */
    data class SttWakeConfig(
        val wakePhrase: String = "小爱",
        val regexEnabled: Boolean = false,
        val language: String = "zh-CN",
        val continuousMode: Boolean = true,
        val cooldownMs: Long = 3000L,         // 两次唤醒最小间隔
    ) : WakeEngineConfig()
}
