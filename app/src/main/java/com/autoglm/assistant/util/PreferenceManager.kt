package com.autoglm.assistant.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "autoglm_prefs"

        private const val KEY_API_URL = "api_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_LANGUAGE = "language"
        // Agent执行单个任务的总操作步数上限（无论是否使用协调器）
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_PORCUPINE_ACCESS_KEY = "porcupine_access_key"
        private const val KEY_WAKE_WORD_KEYWORD = "wake_word_keyword"
        private const val KEY_SHOW_AGENT_PROCESS = "show_agent_process"

        // 多引擎唤醒配置
        private const val KEY_WAKE_ENGINE_TYPE = "wake_engine_type"
        private const val KEY_WAKE_SENSITIVITY = "wake_sensitivity"
        private const val KEY_WAKE_GREETING_ENABLED = "wake_greeting_enabled"
        private const val KEY_WAKE_GREETING_TEXT = "wake_greeting_text"

        // STT 唤醒配置
        private const val KEY_STT_WAKE_PHRASE = "stt_wake_phrase"
        private const val KEY_STT_WAKE_REGEX_ENABLED = "stt_wake_regex_enabled"
        private const val KEY_STT_WAKE_LANGUAGE = "stt_wake_language"
        private const val KEY_STT_WAKE_COOLDOWN_MS = "stt_wake_cooldown_ms"

        // 智能协调器设置
        private const val KEY_SMART_COORDINATOR_ENABLED = "smart_coordinator_enabled"
        private const val KEY_SHOW_COORDINATOR_THINKING = "show_coordinator_thinking"
        private const val KEY_COORDINATOR_API_URL = "coordinator_api_url"
        private const val KEY_COORDINATOR_API_KEY = "coordinator_api_key"
        private const val KEY_COORDINATOR_API_KEY_DEEPSEEK = "coordinator_api_key_deepseek"
        private const val KEY_COORDINATOR_API_KEY_BIGMODEL = "coordinator_api_key_bigmodel"
        private const val KEY_COORDINATOR_API_KEY_DOUBAO = "coordinator_api_key_doubao"
        private const val KEY_COORDINATOR_MODEL_NAME = "coordinator_model_name"
        // 自定义协调器系统提示词
        private const val KEY_COORDINATOR_SYSTEM_PROMPT = "coordinator_system_prompt"
        // 已废弃：监督模式下对单个子任务的最大纠正次数（当前协调器流程不再使用）
        private const val KEY_SUPERVISION_ENABLED = "supervision_enabled"
        private const val KEY_MAX_CORRECTIONS = "max_corrections"
        // 协调器每次任务的最大决策轮次（每轮包括：分析截图+决策下一步+Agent执行）
        private const val KEY_MAX_COORDINATOR_STEPS = "max_coordinator_steps"
        // 协调器单轮内，PhoneAgent 执行 nextInstruction 的最大连续决策轮次
        private const val KEY_MAX_AGENT_STEPS_PER_COORDINATOR_STEP = "max_agent_steps_per_coordinator_step"
        // 协调器模型是否支持 Vision（图片输入）
        private const val KEY_COORDINATOR_ENABLE_VISION = "coordinator_enable_vision"
        // 协调器是否启用模型思考/推理参数（仅对部分模型生效）
        private const val KEY_COORDINATOR_ENABLE_THINKING = "coordinator_enable_thinking"

        // 提示词优化器设置
        private const val KEY_PROMPT_OPTIMIZER_ENABLED = "prompt_optimizer_enabled"
        private const val KEY_OPTIMIZER_API_URL = "optimizer_api_url"
        private const val KEY_OPTIMIZER_API_KEY = "optimizer_api_key"
        private const val KEY_OPTIMIZER_API_KEY_DEEPSEEK = "optimizer_api_key_deepseek"
        private const val KEY_OPTIMIZER_API_KEY_BIGMODEL = "optimizer_api_key_bigmodel"
        private const val KEY_OPTIMIZER_API_KEY_DOUBAO = "optimizer_api_key_doubao"
        private const val KEY_OPTIMIZER_MODEL_NAME = "optimizer_model_name"
        private const val KEY_TASK_SUMMARY_ENABLED = "task_summary_enabled"
        // 自定义优化器系统提示词
        private const val KEY_OPTIMIZER_SYSTEM_PROMPT = "optimizer_system_prompt"

        // 自定义 Agent 系统提示词
        private const val KEY_AGENT_SYSTEM_PROMPT = "agent_system_prompt"

        // Shell 设置
        private const val KEY_USE_ROOT_MODE = "use_root_mode"

        // 默认值 - BigModel API
        const val DEFAULT_WAKE_WORD = "XIAOAI"  // 小爱自定义唤醒词
        const val DEFAULT_API_URL = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_MODEL_NAME = "autoglm-phone"
        const val DEFAULT_LANGUAGE = "cn"
        const val DEFAULT_MAX_STEPS = 100  // Agent执行单个任务的总操作步数上限
        // 多引擎唤醒默认值
        const val DEFAULT_WAKE_ENGINE_TYPE = "STT_SYSTEM"  // 默认使用免费的系统 STT
        const val DEFAULT_WAKE_SENSITIVITY = 0.7f
        const val DEFAULT_WAKE_GREETING_TEXT = "我在听"
        // STT 唤醒默认值
        const val DEFAULT_STT_WAKE_PHRASE = "小爱"
        const val DEFAULT_STT_WAKE_LANGUAGE = "zh-CN"
        const val DEFAULT_STT_WAKE_COOLDOWN_MS = 3000L
        // 智能协调器默认值 - DeepSeek
        const val DEFAULT_COORDINATOR_API_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_COORDINATOR_MODEL_NAME = "deepseek-chat"
        const val DEFAULT_MAX_CORRECTIONS = 2  // 已废弃：监督模式的纠正次数
        const val DEFAULT_MAX_COORDINATOR_STEPS = 20  // 协调器每次任务的最大决策轮次
        const val DEFAULT_MAX_AGENT_STEPS_PER_COORDINATOR_STEP = 10
        const val DEFAULT_COORDINATOR_ENABLE_THINKING = true
        // 提示词优化器默认值 - 默认使用协调器设置
        const val DEFAULT_OPTIMIZER_MODEL_NAME = "deepseek-chat"
    }

    var apiUrl: String
        get() = prefs.getString(KEY_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
        set(value) = prefs.edit { putString(KEY_API_URL, value) }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_API_KEY, value) }

    var modelName: String
        get() = prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL_NAME) ?: DEFAULT_MODEL_NAME
        set(value) = prefs.edit { putString(KEY_MODEL_NAME, value) }

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        set(value) = prefs.edit { putString(KEY_LANGUAGE, value) }

    var maxSteps: Int
        get() = prefs.getInt(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
        set(value) = prefs.edit { putInt(KEY_MAX_STEPS, value) }

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_WAKE_WORD_ENABLED, value) }

    var porcupineAccessKey: String
        get() = prefs.getString(KEY_PORCUPINE_ACCESS_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_PORCUPINE_ACCESS_KEY, value) }

    var wakeWordKeyword: String
        get() = prefs.getString(KEY_WAKE_WORD_KEYWORD, DEFAULT_WAKE_WORD) ?: DEFAULT_WAKE_WORD
        set(value) = prefs.edit { putString(KEY_WAKE_WORD_KEYWORD, value) }

    // 多引擎唤醒配置
    var wakeEngineType: String
        get() = prefs.getString(KEY_WAKE_ENGINE_TYPE, DEFAULT_WAKE_ENGINE_TYPE) ?: DEFAULT_WAKE_ENGINE_TYPE
        set(value) = prefs.edit { putString(KEY_WAKE_ENGINE_TYPE, value) }

    var wakeSensitivity: Float
        get() = prefs.getFloat(KEY_WAKE_SENSITIVITY, DEFAULT_WAKE_SENSITIVITY)
        set(value) = prefs.edit { putFloat(KEY_WAKE_SENSITIVITY, value) }

    var wakeGreetingEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_GREETING_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_WAKE_GREETING_ENABLED, value) }

    var wakeGreetingText: String
        get() = prefs.getString(KEY_WAKE_GREETING_TEXT, DEFAULT_WAKE_GREETING_TEXT) ?: DEFAULT_WAKE_GREETING_TEXT
        set(value) = prefs.edit { putString(KEY_WAKE_GREETING_TEXT, value) }

    // STT 唤醒配置
    var sttWakePhrase: String
        get() = prefs.getString(KEY_STT_WAKE_PHRASE, DEFAULT_STT_WAKE_PHRASE) ?: DEFAULT_STT_WAKE_PHRASE
        set(value) = prefs.edit { putString(KEY_STT_WAKE_PHRASE, value) }

    var sttWakeRegexEnabled: Boolean
        get() = prefs.getBoolean(KEY_STT_WAKE_REGEX_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_STT_WAKE_REGEX_ENABLED, value) }

    var sttWakeLanguage: String
        get() = prefs.getString(KEY_STT_WAKE_LANGUAGE, DEFAULT_STT_WAKE_LANGUAGE) ?: DEFAULT_STT_WAKE_LANGUAGE
        set(value) = prefs.edit { putString(KEY_STT_WAKE_LANGUAGE, value) }

    var sttWakeCooldownMs: Long
        get() = prefs.getLong(KEY_STT_WAKE_COOLDOWN_MS, DEFAULT_STT_WAKE_COOLDOWN_MS)
        set(value) = prefs.edit { putLong(KEY_STT_WAKE_COOLDOWN_MS, value) }

    // true = 显示所有过程消息, false = 仅显示最终结果
    var showAgentProcess: Boolean
        get() = prefs.getBoolean(KEY_SHOW_AGENT_PROCESS, true)  // 默认开启，方便调试
        set(value) = prefs.edit { putBoolean(KEY_SHOW_AGENT_PROCESS, value) }

    // 智能协调器设置
    var smartCoordinatorEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMART_COORDINATOR_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_SMART_COORDINATOR_ENABLED, value) }

    // 是否显示协调器思考正文（默认关闭，仅显示协调器结果）
    var showCoordinatorThinking: Boolean
        get() = prefs.getBoolean(KEY_SHOW_COORDINATOR_THINKING, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_COORDINATOR_THINKING, value) }

    var coordinatorApiUrl: String
        get() = prefs.getString(KEY_COORDINATOR_API_URL, DEFAULT_COORDINATOR_API_URL) ?: DEFAULT_COORDINATOR_API_URL
        set(value) = prefs.edit { putString(KEY_COORDINATOR_API_URL, value) }

    var coordinatorApiKey: String
        get() = prefs.getString(KEY_COORDINATOR_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_COORDINATOR_API_KEY, value) }

    // Provider-specific Coordinator API keys
    var coordinatorApiKeyDeepseek: String
        get() = prefs.getString(KEY_COORDINATOR_API_KEY_DEEPSEEK, coordinatorApiKey) ?: coordinatorApiKey
        set(value) = prefs.edit { putString(KEY_COORDINATOR_API_KEY_DEEPSEEK, value) }

    var coordinatorApiKeyBigmodel: String
        get() = prefs.getString(KEY_COORDINATOR_API_KEY_BIGMODEL, coordinatorApiKey) ?: coordinatorApiKey
        set(value) = prefs.edit { putString(KEY_COORDINATOR_API_KEY_BIGMODEL, value) }

    var coordinatorApiKeyDoubao: String
        get() = prefs.getString(KEY_COORDINATOR_API_KEY_DOUBAO, coordinatorApiKey) ?: coordinatorApiKey
        set(value) = prefs.edit { putString(KEY_COORDINATOR_API_KEY_DOUBAO, value) }

    var coordinatorModelName: String
        get() = prefs.getString(KEY_COORDINATOR_MODEL_NAME, DEFAULT_COORDINATOR_MODEL_NAME) ?: DEFAULT_COORDINATOR_MODEL_NAME
        set(value) = prefs.edit { putString(KEY_COORDINATOR_MODEL_NAME, value) }

    // 自定义 Coordinator 系统提示词（空字符串表示使用内置默认提示词）
    var coordinatorSystemPrompt: String
        get() = prefs.getString(KEY_COORDINATOR_SYSTEM_PROMPT, "") ?: ""
        set(value) = prefs.edit { putString(KEY_COORDINATOR_SYSTEM_PROMPT, value) }

    var supervisionEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUPERVISION_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_SUPERVISION_ENABLED, value) }

    // 已废弃：监督模式下对单个子任务的最大纠正次数
    var maxCorrections: Int
        get() = prefs.getInt(KEY_MAX_CORRECTIONS, DEFAULT_MAX_CORRECTIONS)
        set(value) = prefs.edit { putInt(KEY_MAX_CORRECTIONS, value) }

    // 协调器每次任务的最大决策轮次
    // 每轮包括：1)协调器分析截图和执行历史 2)决策下一步操作 3)Agent执行具体指令
    var maxCoordinatorSteps: Int
        get() = prefs.getInt(KEY_MAX_COORDINATOR_STEPS, DEFAULT_MAX_COORDINATOR_STEPS)
        set(value) = prefs.edit { putInt(KEY_MAX_COORDINATOR_STEPS, value) }

    // 协调器单轮内，PhoneAgent 执行单条 nextInstruction 的最大连续决策轮次
    var maxAgentStepsPerCoordinatorStep: Int
        get() = prefs.getInt(
            KEY_MAX_AGENT_STEPS_PER_COORDINATOR_STEP,
            DEFAULT_MAX_AGENT_STEPS_PER_COORDINATOR_STEP
        )
        set(value) = prefs.edit { putInt(KEY_MAX_AGENT_STEPS_PER_COORDINATOR_STEP, value) }

    // 协调器模型是否支持 Vision（图片输入）
    // 默认 false，因为大多数模型（如 DeepSeek）不支持 vision
    var coordinatorEnableVision: Boolean
        get() = prefs.getBoolean(KEY_COORDINATOR_ENABLE_VISION, false)
        set(value) = prefs.edit { putBoolean(KEY_COORDINATOR_ENABLE_VISION, value) }

    // 协调器是否启用模型思考/推理参数（仅对 DeepSeek/Qwen 等支持模型生效）
    var coordinatorEnableThinking: Boolean
        get() = prefs.getBoolean(
            KEY_COORDINATOR_ENABLE_THINKING,
            DEFAULT_COORDINATOR_ENABLE_THINKING
        )
        set(value) = prefs.edit { putBoolean(KEY_COORDINATOR_ENABLE_THINKING, value) }

    // PromptOptimizer settings
    var promptOptimizerEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROMPT_OPTIMIZER_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_PROMPT_OPTIMIZER_ENABLED, value) }

    var optimizerApiUrl: String
        get() = prefs.getString(KEY_OPTIMIZER_API_URL, DEFAULT_COORDINATOR_API_URL) ?: DEFAULT_COORDINATOR_API_URL
        set(value) = prefs.edit { putString(KEY_OPTIMIZER_API_URL, value) }

    var optimizerApiKey: String
        get() = prefs.getString(KEY_OPTIMIZER_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_OPTIMIZER_API_KEY, value) }

    // Provider-specific Optimizer API keys
    var optimizerApiKeyDeepseek: String
        get() = prefs.getString(KEY_OPTIMIZER_API_KEY_DEEPSEEK, optimizerApiKey) ?: optimizerApiKey
        set(value) = prefs.edit { putString(KEY_OPTIMIZER_API_KEY_DEEPSEEK, value) }

    var optimizerApiKeyBigmodel: String
        get() = prefs.getString(KEY_OPTIMIZER_API_KEY_BIGMODEL, optimizerApiKey) ?: optimizerApiKey
        set(value) = prefs.edit { putString(KEY_OPTIMIZER_API_KEY_BIGMODEL, value) }

    var optimizerApiKeyDoubao: String
        get() = prefs.getString(KEY_OPTIMIZER_API_KEY_DOUBAO, optimizerApiKey) ?: optimizerApiKey
        set(value) = prefs.edit { putString(KEY_OPTIMIZER_API_KEY_DOUBAO, value) }

    var optimizerModelName: String
        get() = prefs.getString(KEY_OPTIMIZER_MODEL_NAME, DEFAULT_OPTIMIZER_MODEL_NAME) ?: DEFAULT_OPTIMIZER_MODEL_NAME
        set(value) = prefs.edit { putString(KEY_OPTIMIZER_MODEL_NAME, value) }

    // 是否启用任务完成后的自动总结（使用优化器模型）
    var taskSummaryEnabled: Boolean
        get() = prefs.getBoolean(KEY_TASK_SUMMARY_ENABLED, false)  // 默认关闭
        set(value) = prefs.edit { putBoolean(KEY_TASK_SUMMARY_ENABLED, value) }

    // 自定义 Agent 系统提示词（空字符串表示使用内置默认提示词）
    var agentSystemPrompt: String
        get() = prefs.getString(KEY_AGENT_SYSTEM_PROMPT, "") ?: ""
        set(value) = prefs.edit { putString(KEY_AGENT_SYSTEM_PROMPT, value) }

    // 自定义优化器系统提示词（空字符串表示使用内置默认提示词）
    var optimizerSystemPrompt: String
        get() = prefs.getString(KEY_OPTIMIZER_SYSTEM_PROMPT, "") ?: ""
        set(value) = prefs.edit { putString(KEY_OPTIMIZER_SYSTEM_PROMPT, value) }

    // Shell settings - 是否使用 Root 模式执行命令
    var useRootMode: Boolean
        get() = prefs.getBoolean(KEY_USE_ROOT_MODE, true)  // 默认开启
        set(value) = prefs.edit { putBoolean(KEY_USE_ROOT_MODE, value) }

    // 配置监听器注册和注销
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
