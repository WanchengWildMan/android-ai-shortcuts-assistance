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
        private const val KEY_IME_VOICE_KEYBOARD_DELAY = "ime_voice_keyboard_delay"

        // Rokid 眼镜通道
        private const val KEY_GLASS_CHANNEL_ENABLED = "glass_channel_enabled"
        private const val KEY_GLASS_TARGET_PACKAGE = "glass_target_package"
        private const val KEY_GLASS_AUTH_TOKEN = "glass_auth_token"
        private const val KEY_GLASS_AUTO_START_APP = "glass_auto_start_app"
        private const val KEY_GLASS_VAD_SILENCE_THRESHOLD = "glass_vad_silence_threshold"
        private const val KEY_GLASS_VAD_SILENCE_DURATION_MS = "glass_vad_silence_duration_ms"
        private const val KEY_GLASS_MAX_RECORD_DURATION_MS = "glass_max_record_duration_ms"
        private const val KEY_GLASS_FEISHU_WEBHOOK_URL = "glass_feishu_webhook_url"

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
        // IME 语音键盘就绪等待时间默认值（毫秒）；不同机型弹键盘速度不同，可在设置中调整
        const val DEFAULT_IME_VOICE_KEYBOARD_DELAY_MS = 1200L
        // Rokid 眼镜通道默认值
        const val DEFAULT_GLASS_TARGET_PACKAGE = "com.autoglm.glass"
        const val DEFAULT_GLASS_AUTO_START_APP = true
        const val DEFAULT_GLASS_VAD_SILENCE_THRESHOLD = 800
        const val DEFAULT_GLASS_VAD_SILENCE_DURATION_MS = 1500L
        const val DEFAULT_GLASS_MAX_RECORD_DURATION_MS = 15000L
        const val DEFAULT_GLASS_FEISHU_WEBHOOK_URL = ""
    }

    var apiUrl: String
        get() = prefs.getString(KEY_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
        set(value) = prefs.edit { putString(KEY_API_URL, value) }

    // ---- Rokid 眼镜通道 ----
    /** 眼镜通道总开关，默认关闭；旧机或未鉴权时走原手机麦克风唤醒路径 */
    var glassChannelEnabled: Boolean
        get() = prefs.getBoolean(KEY_GLASS_CHANNEL_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_GLASS_CHANNEL_ENABLED, value) }

    /** 眼镜端目标 App 包名，必须与 :glass 模块 applicationId 一致 */
    var glassTargetPackage: String
        get() = prefs.getString(KEY_GLASS_TARGET_PACKAGE, DEFAULT_GLASS_TARGET_PACKAGE) ?: DEFAULT_GLASS_TARGET_PACKAGE
        set(value) = prefs.edit { putString(KEY_GLASS_TARGET_PACKAGE, value) }

    /** Rokid 鉴权 token，鉴权成功后持久化，重启免重鉴权 */
    var glassAuthToken: String
        get() = prefs.getString(KEY_GLASS_AUTH_TOKEN, "") ?: ""
        set(value) = prefs.edit { putString(KEY_GLASS_AUTH_TOKEN, value) }

    /** 链路就绪后是否自动推装并启动眼镜端 App */
    var glassAutoStartApp: Boolean
        get() = prefs.getBoolean(KEY_GLASS_AUTO_START_APP, DEFAULT_GLASS_AUTO_START_APP)
        set(value) = prefs.edit { putBoolean(KEY_GLASS_AUTO_START_APP, value) }

    var glassVadSilenceThreshold: Int
        get() = prefs.getInt(KEY_GLASS_VAD_SILENCE_THRESHOLD, DEFAULT_GLASS_VAD_SILENCE_THRESHOLD)
        set(value) = prefs.edit { putInt(KEY_GLASS_VAD_SILENCE_THRESHOLD, value) }

    var glassVadSilenceDurationMs: Long
        get() = prefs.getLong(KEY_GLASS_VAD_SILENCE_DURATION_MS, DEFAULT_GLASS_VAD_SILENCE_DURATION_MS)
        set(value) = prefs.edit { putLong(KEY_GLASS_VAD_SILENCE_DURATION_MS, value) }

    var glassMaxRecordDurationMs: Long
        get() = prefs.getLong(KEY_GLASS_MAX_RECORD_DURATION_MS, DEFAULT_GLASS_MAX_RECORD_DURATION_MS)
        set(value) = prefs.edit { putLong(KEY_GLASS_MAX_RECORD_DURATION_MS, value) }

    /** 眼镜识别文本经飞书 webhook 中转的地址（vivo 作为桥接端时使用，电脑侧用另一个飞书应用的长连接订阅同一群收到消息）；为空则不中转 */
    var glassFeishuWebhookUrl: String
        get() = prefs.getString(KEY_GLASS_FEISHU_WEBHOOK_URL, DEFAULT_GLASS_FEISHU_WEBHOOK_URL) ?: DEFAULT_GLASS_FEISHU_WEBHOOK_URL
        set(value) = prefs.edit { putString(KEY_GLASS_FEISHU_WEBHOOK_URL, value) }

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

    // IME 语音：弹出输入法后等待键盘就绪的延迟（毫秒），过短会导致语音按钮点击无效
    var imeVoiceKeyboardDelay: Long
        get() = prefs.getLong(KEY_IME_VOICE_KEYBOARD_DELAY, DEFAULT_IME_VOICE_KEYBOARD_DELAY_MS)
        set(value) = prefs.edit { putLong(KEY_IME_VOICE_KEYBOARD_DELAY, value) }

    // 配置监听器注册和注销
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * API 模块枚举 — 用于 resolveApiKey 统一分发
     */
    enum class ApiModule {
        AGENT,        // 主 Agent（PhoneAgent 执行操作）
        COORDINATOR,  // 智能协调器（SmartCoordinator）
        OPTIMIZER,    // 指令优化器（PromptOptimizer）
        INTENT        // 意图识别器（IntentRecognizer）
    }

    /**
     * 根据模型名称和模块，统一解析应使用的 API Key
     *
     * 业务目的: 消除 19 处重复的 when { startsWith("deepseek")... } 分发逻辑
     * 规则: 按模型名称前缀匹配 provider-specific key，未匹配时回退到模块默认 key
     *
     * @param modelName 模型名称（如 "deepseek-chat", "glm-4v" 等）
     * @param module    API 模块
     * @return 解析后的 API Key
     */
    fun resolveApiKey(modelName: String, module: ApiModule): String {
        return when {
            modelName.startsWith("deepseek") -> when (module) {
                ApiModule.AGENT -> apiKeyDeepseek
                ApiModule.COORDINATOR -> coordinatorApiKeyDeepseek
                ApiModule.OPTIMIZER -> optimizerApiKeyDeepseek
                ApiModule.INTENT -> intentApiKeyDeepseek
            }
            modelName.startsWith("glm-") -> when (module) {
                ApiModule.AGENT -> apiKeyBigmodel
                ApiModule.COORDINATOR -> coordinatorApiKeyBigmodel
                ApiModule.OPTIMIZER -> optimizerApiKeyBigmodel
                ApiModule.INTENT -> intentApiKeyBigmodel
            }
            modelName.startsWith("doubao") -> when (module) {
                ApiModule.AGENT -> apiKeyDoubao
                ApiModule.COORDINATOR -> coordinatorApiKeyDoubao
                ApiModule.OPTIMIZER -> optimizerApiKeyDoubao
                ApiModule.INTENT -> intentApiKeyDoubao
            }
            modelName.startsWith("qwen") -> when (module) {
                ApiModule.AGENT -> apiKeyQwen
                ApiModule.COORDINATOR -> coordinatorApiKey  // coordinator/optimizer/intent 暂无 qwen 独立 key
                ApiModule.OPTIMIZER -> optimizerApiKey
                ApiModule.INTENT -> intentApiKey
            }
            else -> when (module) {
                ApiModule.AGENT -> apiKey
                ApiModule.COORDINATOR -> coordinatorApiKey
                ApiModule.OPTIMIZER -> optimizerApiKey
                ApiModule.INTENT -> intentApiKey
            }
        }
    }

    /**
     * 根据模型名称和模块，统一保存 API Key 到对应的 provider-specific 字段
     *
     * @param modelName 模型名称
     * @param module    API 模块
     * @param key       要保存的 API Key 值
     */
    fun saveApiKey(modelName: String, module: ApiModule, key: String) {
        when {
            modelName.startsWith("deepseek") -> when (module) {
                ApiModule.AGENT -> apiKeyDeepseek = key
                ApiModule.COORDINATOR -> coordinatorApiKeyDeepseek = key
                ApiModule.OPTIMIZER -> optimizerApiKeyDeepseek = key
                ApiModule.INTENT -> intentApiKeyDeepseek = key
            }
            modelName.startsWith("glm-") -> when (module) {
                ApiModule.AGENT -> apiKeyBigmodel = key
                ApiModule.COORDINATOR -> coordinatorApiKeyBigmodel = key
                ApiModule.OPTIMIZER -> optimizerApiKeyBigmodel = key
                ApiModule.INTENT -> intentApiKeyBigmodel = key
            }
            modelName.startsWith("doubao") -> when (module) {
                ApiModule.AGENT -> apiKeyDoubao = key
                ApiModule.COORDINATOR -> coordinatorApiKeyDoubao = key
                ApiModule.OPTIMIZER -> optimizerApiKeyDoubao = key
                ApiModule.INTENT -> intentApiKeyDoubao = key
            }
            else -> when (module) {
                ApiModule.AGENT -> apiKey = key
                ApiModule.COORDINATOR -> coordinatorApiKey = key
                ApiModule.OPTIMIZER -> optimizerApiKey = key
                ApiModule.INTENT -> intentApiKey = key
            }
        }
    }

    /**
     * 根据模型名称推断默认的 API URL
     *
     * 业务目的: 消除 MainActivity 中 6+ 处重复的 URL 分发逻辑
     */
    fun resolveDefaultApiUrl(modelName: String): String {
        return when {
            modelName.startsWith("deepseek") -> "https://api.deepseek.com/v1"
            modelName.startsWith("glm-") || modelName.startsWith("autoglm") -> DEFAULT_API_URL
            modelName.startsWith("doubao") -> "https://ark.cn-beijing.volces.com/api/v3"
            modelName.startsWith("qwen") -> "https://dashscope.aliyuncs.com/compatible-mode/v1"
            else -> DEFAULT_API_URL
        }
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
