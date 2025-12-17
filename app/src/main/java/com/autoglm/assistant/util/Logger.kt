package com.autoglm.assistant.util

import android.util.Log
import com.autoglm.assistant.BuildConfig

/**
 * 统一日志工具类
 * 使用: adb logcat -s AutoGLM
 */
object Logger {
    private const val TAG = "AutoGLM"

    // 子模块标签
    const val AGENT = "Agent"
    const val ACTION = "Action"
    const val MODEL = "Model"
    const val SCREEN = "Screen"
    const val VOICE = "Voice"
    const val SHELL = "Shell"
    const val SERVICE = "Service"

    private val isDebug: Boolean = BuildConfig.DEBUG

    fun d(module: String, message: String) {
        if (isDebug) {
            Log.d(TAG, "=== [$module] $message")
        }
    }

    fun i(module: String, message: String) {
        Log.i(TAG, "=== [$module] $message")
    }

    fun w(module: String, message: String) {
        Log.w(TAG, "=== [$module] $message")
    }

    fun e(module: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, "=== [$module] $message", throwable)
        } else {
            Log.e(TAG, "=== [$module] $message")
        }
    }

    // 便捷方法
    fun agent(message: String) = d(AGENT, message)
    fun action(message: String) = d(ACTION, message)
    fun model(message: String) = d(MODEL, message)
    fun screen(message: String) = d(SCREEN, message)
    fun voice(message: String) = d(VOICE, message)
    fun shell(message: String) = d(SHELL, message)
    fun service(message: String) = d(SERVICE, message)

    // 性能计时
    private val timers = mutableMapOf<String, Long>()

    fun startTimer(name: String) {
        timers[name] = System.currentTimeMillis()
    }

    fun endTimer(name: String, module: String = AGENT): Long {
        val start = timers.remove(name) ?: return 0
        val elapsed = System.currentTimeMillis() - start
        d(module, "$name took ${elapsed}ms")
        return elapsed
    }
}
