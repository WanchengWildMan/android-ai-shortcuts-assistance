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
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_PORCUPINE_ACCESS_KEY = "porcupine_access_key"
        private const val KEY_WAKE_WORD_KEYWORD = "wake_word_keyword"
        private const val KEY_SHOW_AGENT_PROCESS = "show_agent_process"

        // 智能协调器设置
        private const val KEY_SMART_COORDINATOR_ENABLED = "smart_coordinator_enabled"
        private const val KEY_COORDINATOR_API_URL = "coordinator_api_url"
        private const val KEY_COORDINATOR_API_KEY = "coordinator_api_key"
        private const val KEY_COORDINATOR_API_KEY_DEEPSEEK = "coordinator_api_key_deepseek"
        private const val KEY_COORDINATOR_API_KEY_BIGMODEL = "coordinator_api_key_bigmodel"
        private const val KEY_COORDINATOR_API_KEY_DOUBAO = "coordinator_api_key_doubao"
        private const val KEY_COORDINATOR_MODEL_NAME = "coordinator_model_name"
        private const val KEY_SUPERVISION_ENABLED = "supervision_enabled"
        private const val KEY_MAX_CORRECTIONS = "max_corrections"

        // 提示词优化器设置
        private const val KEY_PROMPT_OPTIMIZER_ENABLED = "prompt_optimizer_enabled"
        private const val KEY_OPTIMIZER_API_URL = "optimizer_api_url"
        private const val KEY_OPTIMIZER_API_KEY = "optimizer_api_key"
        private const val KEY_OPTIMIZER_API_KEY_DEEPSEEK = "optimizer_api_key_deepseek"
        private const val KEY_OPTIMIZER_API_KEY_BIGMODEL = "optimizer_api_key_bigmodel"
        private const val KEY_OPTIMIZER_API_KEY_DOUBAO = "optimizer_api_key_doubao"
        private const val KEY_OPTIMIZER_MODEL_NAME = "optimizer_model_name"
        private const val KEY_TASK_SUMMARY_ENABLED = "task_summary_enabled"

        // Shell 设置
        private const val KEY_USE_ROOT_MODE = "use_root_mode"

        // 默认值 - BigModel API
        const val DEFAULT_WAKE_WORD = "XIAOAI"  // 小爱自定义唤醒词
        const val DEFAULT_API_URL = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_MODEL_NAME = "autoglm-phone"
        const val DEFAULT_LANGUAGE = "cn"
        const val DEFAULT_MAX_STEPS = 100
        // 智能协调器默认值 - DeepSeek
        const val DEFAULT_COORDINATOR_API_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_COORDINATOR_MODEL_NAME = "deepseek-chat"
        const val DEFAULT_MAX_CORRECTIONS = 2
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

    // true = 显示所有过程消息, false = 仅显示最终结果
    var showAgentProcess: Boolean
        get() = prefs.getBoolean(KEY_SHOW_AGENT_PROCESS, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_AGENT_PROCESS, value) }

    // 智能协调器设置
    var smartCoordinatorEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMART_COORDINATOR_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_SMART_COORDINATOR_ENABLED, value) }

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

    var supervisionEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUPERVISION_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_SUPERVISION_ENABLED, value) }

    var maxCorrections: Int
        get() = prefs.getInt(KEY_MAX_CORRECTIONS, DEFAULT_MAX_CORRECTIONS)
        set(value) = prefs.edit { putInt(KEY_MAX_CORRECTIONS, value) }

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
