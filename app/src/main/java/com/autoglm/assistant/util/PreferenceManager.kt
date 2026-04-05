package com.autoglm.assistant.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject

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
        private const val KEY_CUSTOM_PPN_PATH = "custom_ppn_path"  // 用户自定义 .ppn 模型文件路径
        private const val KEY_CUSTOM_PPN_NAME = "custom_ppn_name"  // 用户自定义 .ppn 模型文件名（显示用）
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

        // 意图识别器设置
        private const val KEY_INTENT_RECOGNIZER_ENABLED = "intent_recognizer_enabled"
        private const val KEY_INTENT_API_URL = "intent_api_url"
        private const val KEY_INTENT_API_KEY = "intent_api_key"
        private const val KEY_INTENT_API_KEY_DEEPSEEK = "intent_api_key_deepseek"
        private const val KEY_INTENT_API_KEY_BIGMODEL = "intent_api_key_bigmodel"
        private const val KEY_INTENT_API_KEY_DOUBAO = "intent_api_key_doubao"
        private const val KEY_INTENT_MODEL_NAME = "intent_model_name"

        // 自定义 Agent 系统提示词
        private const val KEY_AGENT_SYSTEM_PROMPT = "agent_system_prompt"

        // Shell 设置
        private const val KEY_USE_ROOT_MODE = "use_root_mode"

        // 命令 STT 模式：唤醒后用哪种方式识别语音指令
        private const val KEY_COMMAND_STT_MODE = "command_stt_mode"
        // STT API 类型：OPENAI / ALI_NLS / IME_VOICE
        private const val KEY_STT_API_TYPE = "stt_api_type"
        // OpenAI 兼容 API STT 配置
        private const val KEY_STT_API_URL = "stt_api_url"
        private const val KEY_STT_API_KEY = "stt_api_key"
        private const val KEY_STT_MODEL_NAME = "stt_model_name"
        // 阿里 NLS 一句话识别配置
        private const val KEY_ALI_NLS_AK_ID = "ali_nls_ak_id"
        private const val KEY_ALI_NLS_AK_SECRET = "ali_nls_ak_secret"
        private const val KEY_ALI_NLS_APP_KEY = "ali_nls_app_key"
        // IME 语音输入配置（通过模拟长按输入法空格键触发语音输入）
        private const val KEY_IME_VOICE_SPACE_X = "ime_voice_space_x"
        private const val KEY_IME_VOICE_SPACE_Y = "ime_voice_space_y"

        // 默认值 - BigModel API
        const val DEFAULT_WAKE_WORD = "XIAOAI"  // 小爱自定义唤醒词
        const val DEFAULT_API_URL = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_MODEL_NAME = "autoglm-phone"
        const val DEFAULT_LANGUAGE = "cn"
        const val DEFAULT_MAX_STEPS = 100  // Agent执行单个任务的总操作步数上限
        // 多引擎唤醒默认值
        const val DEFAULT_WAKE_ENGINE_TYPE = "PORCUPINE"  // 默认使用 Porcupine 引擎
        const val DEFAULT_WAKE_SENSITIVITY = 0.7f
        const val DEFAULT_WAKE_GREETING_TEXT = "我在听"
        // STT 唤醒默认值
        const val DEFAULT_STT_WAKE_PHRASE = "你好小爱"
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
        // 意图识别器默认值 - 默认使用协调器设置
        const val DEFAULT_INTENT_MODEL_NAME = "deepseek-chat"

        // 命令 STT 默认值
        const val DEFAULT_COMMAND_STT_MODE = "API"  // 默认使用 API 模式（系统 STT 在 MIUI 等 ROM 上受限）
        const val DEFAULT_STT_API_TYPE = "ALI_NLS"  // 默认使用阿里 NLS
        const val DEFAULT_STT_API_URL = "https://api.openai.com/v1"
        const val DEFAULT_STT_MODEL_NAME = "whisper-1"
        // 阿里 NLS 默认值
        const val DEFAULT_ALI_NLS_GATEWAY = "https://nls-gateway-cn-shanghai.aliyuncs.com"
        // IME 语音输入默认坐标（需根据设备/键盘实际调整）
        const val DEFAULT_IME_VOICE_SPACE_X = 704
        const val DEFAULT_IME_VOICE_SPACE_Y = 2978
    }

    var apiUrl: String
        get() = prefs.getString(KEY_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
        set(value) = prefs.edit { putString(KEY_API_URL, value) }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_API_KEY, value) }

    // Provider-specific Agent API keys
    var apiKeyDeepseek: String
        get() = prefs.getString("api_key_deepseek", apiKey) ?: apiKey
        set(value) = prefs.edit { putString("api_key_deepseek", value) }

    var apiKeyBigmodel: String
        get() = prefs.getString("api_key_bigmodel", apiKey) ?: apiKey
        set(value) = prefs.edit { putString("api_key_bigmodel", value) }

    var apiKeyDoubao: String
        get() = prefs.getString("api_key_doubao", apiKey) ?: apiKey
        set(value) = prefs.edit { putString("api_key_doubao", value) }

    var apiKeyQwen: String
        get() = prefs.getString("api_key_qwen", apiKey) ?: apiKey
        set(value) = prefs.edit { putString("api_key_qwen", value) }

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

    /** 用户自定义 .ppn 模型文件的内部存储路径（绝对路径） */
    var customPpnPath: String
        get() = prefs.getString(KEY_CUSTOM_PPN_PATH, "") ?: ""
        set(value) = prefs.edit { putString(KEY_CUSTOM_PPN_PATH, value) }

    /** 用户自定义 .ppn 模型文件原始文件名（仅供设置页显示） */
    var customPpnName: String
        get() = prefs.getString(KEY_CUSTOM_PPN_NAME, "") ?: ""
        set(value) = prefs.edit { putString(KEY_CUSTOM_PPN_NAME, value) }

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

    var coordinatorApiKeyQwen: String
        get() = prefs.getString("coordinator_api_key_qwen", coordinatorApiKey) ?: coordinatorApiKey
        set(value) = prefs.edit { putString("coordinator_api_key_qwen", value) }

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

    // IntentRecognizer settings（意图识别器，用于自然语言匹配快捷指令）
    var intentRecognizerEnabled: Boolean
        get() = prefs.getBoolean(KEY_INTENT_RECOGNIZER_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_INTENT_RECOGNIZER_ENABLED, value) }

    var intentApiUrl: String
        get() = prefs.getString(KEY_INTENT_API_URL, DEFAULT_COORDINATOR_API_URL) ?: DEFAULT_COORDINATOR_API_URL
        set(value) = prefs.edit { putString(KEY_INTENT_API_URL, value) }

    var intentApiKey: String
        get() = prefs.getString(KEY_INTENT_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_INTENT_API_KEY, value) }

    // Provider-specific Intent Recognizer API keys
    var intentApiKeyDeepseek: String
        get() = prefs.getString(KEY_INTENT_API_KEY_DEEPSEEK, intentApiKey) ?: intentApiKey
        set(value) = prefs.edit { putString(KEY_INTENT_API_KEY_DEEPSEEK, value) }

    var intentApiKeyBigmodel: String
        get() = prefs.getString(KEY_INTENT_API_KEY_BIGMODEL, intentApiKey) ?: intentApiKey
        set(value) = prefs.edit { putString(KEY_INTENT_API_KEY_BIGMODEL, value) }

    var intentApiKeyDoubao: String
        get() = prefs.getString(KEY_INTENT_API_KEY_DOUBAO, intentApiKey) ?: intentApiKey
        set(value) = prefs.edit { putString(KEY_INTENT_API_KEY_DOUBAO, value) }

    var intentModelName: String
        get() = prefs.getString(KEY_INTENT_MODEL_NAME, DEFAULT_INTENT_MODEL_NAME) ?: DEFAULT_INTENT_MODEL_NAME
        set(value) = prefs.edit { putString(KEY_INTENT_MODEL_NAME, value) }

    // Shell settings - 是否使用 Root 模式执行命令
    var useRootMode: Boolean
        get() = prefs.getBoolean(KEY_USE_ROOT_MODE, true)  // 默认开启
        set(value) = prefs.edit { putBoolean(KEY_USE_ROOT_MODE, value) }

    // 唤醒后的语音识别模式：SYSTEM = 系统 SpeechRecognizer, API = AudioRecord + API
    var commandSttMode: String
        get() = prefs.getString(KEY_COMMAND_STT_MODE, DEFAULT_COMMAND_STT_MODE) ?: DEFAULT_COMMAND_STT_MODE
        set(value) = prefs.edit { putString(KEY_COMMAND_STT_MODE, value) }

    // STT API 类型：OPENAI = OpenAI Whisper 兼容, ALI_NLS = 阿里 NLS 一句话识别
    var sttApiType: String
        get() = prefs.getString(KEY_STT_API_TYPE, DEFAULT_STT_API_TYPE) ?: DEFAULT_STT_API_TYPE
        set(value) = prefs.edit { putString(KEY_STT_API_TYPE, value) }

    // OpenAI 兼容 STT 配置
    var sttApiUrl: String
        get() = prefs.getString(KEY_STT_API_URL, DEFAULT_STT_API_URL) ?: DEFAULT_STT_API_URL
        set(value) = prefs.edit { putString(KEY_STT_API_URL, value) }

    var sttApiKey: String
        get() = prefs.getString(KEY_STT_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_STT_API_KEY, value) }

    var sttModelName: String
        get() = prefs.getString(KEY_STT_MODEL_NAME, DEFAULT_STT_MODEL_NAME) ?: DEFAULT_STT_MODEL_NAME
        set(value) = prefs.edit { putString(KEY_STT_MODEL_NAME, value) }

    // 阿里 NLS 一句话识别配置
    var aliNlsAkId: String
        get() = prefs.getString(KEY_ALI_NLS_AK_ID, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ALI_NLS_AK_ID, value) }

    var aliNlsAkSecret: String
        get() = prefs.getString(KEY_ALI_NLS_AK_SECRET, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ALI_NLS_AK_SECRET, value) }

    var aliNlsAppKey: String
        get() = prefs.getString(KEY_ALI_NLS_APP_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ALI_NLS_APP_KEY, value) }

    // IME 语音输入：模拟长按空格键的坐标（像素）
    var imeVoiceSpaceX: Int
        get() = prefs.getInt(KEY_IME_VOICE_SPACE_X, DEFAULT_IME_VOICE_SPACE_X)
        set(value) = prefs.edit { putInt(KEY_IME_VOICE_SPACE_X, value) }

    var imeVoiceSpaceY: Int
        get() = prefs.getInt(KEY_IME_VOICE_SPACE_Y, DEFAULT_IME_VOICE_SPACE_Y)
        set(value) = prefs.edit { putInt(KEY_IME_VOICE_SPACE_Y, value) }

    // 配置监听器注册和注销
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * 导出所有配置为 JSON 字符串
     * 遍历 SharedPreferences 的全部条目，按类型序列化，避免遗漏任何字段
     */
    fun exportToJson(): String {
        val json = JSONObject()
        val allEntries = prefs.all
        for ((key, value) in allEntries) {
            when (value) {
                is String -> json.put(key, value)
                is Int -> json.put(key, value)
                is Long -> json.put(key, value)
                is Float -> json.put(key, value.toDouble())
                is Boolean -> json.put(key, value)
                // Set<String> 等其他类型暂不处理
            }
        }
        return json.toString(2)
    }

    /**
     * 从 JSON 字符串导入配置
     * 逐条解析并写入 SharedPreferences，根据现有值类型推断目标类型
     * @return 导入的配置项数量
     */
    fun importFromJson(jsonString: String): Int {
        val json = JSONObject(jsonString)
        var count = 0
        prefs.edit {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.get(key)
                // 步骤1: 如果该 key 已有旧值，按旧值类型写入；否则按 JSON 值类型推断
                val existingValue = prefs.all[key]
                when {
                    existingValue is Boolean || (existingValue == null && value is Boolean) ->
                        putBoolean(key, value as Boolean)
                    existingValue is Int || (existingValue == null && value is Int) ->
                        putInt(key, (value as Number).toInt())
                    existingValue is Long || (existingValue == null && value is Long) ->
                        putLong(key, (value as Number).toLong())
                    existingValue is Float ->
                        putFloat(key, (value as Number).toFloat())
                    existingValue is String || (existingValue == null && value is String) ->
                        putString(key, value as String)
                    // JSON 中 Number 默认为 Int/Long，Float 在 JSON 中表现为 Double
                    value is Number && existingValue == null -> {
                        val d = value.toDouble()
                        if (d == d.toLong().toDouble() && d <= Int.MAX_VALUE && d >= Int.MIN_VALUE) {
                            putInt(key, d.toInt())
                        } else {
                            putFloat(key, d.toFloat())
                        }
                    }
                    else -> putString(key, value.toString())
                }
                count++
            }
        }
        return count
    }
}
