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

        // Default values - BigModel API
        const val DEFAULT_WAKE_WORD = "XIAOAI"  // 小爱 custom wake word
        const val DEFAULT_API_URL = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_MODEL_NAME = "autoglm-phone"
        const val DEFAULT_LANGUAGE = "cn"
        const val DEFAULT_MAX_STEPS = 100
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

    // true = show all process messages, false = show only final result
    var showAgentProcess: Boolean
        get() = prefs.getBoolean(KEY_SHOW_AGENT_PROCESS, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_AGENT_PROCESS, value) }
}
