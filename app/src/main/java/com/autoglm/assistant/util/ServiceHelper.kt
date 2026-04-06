package com.autoglm.assistant.util

import android.content.Context
import android.content.Intent
import android.os.Build
import com.autoglm.assistant.service.WakeWordService

/**
 * 服务启动辅助工具
 *
 * 业务目的：统一 WakeWordService 的启动方式，消除分散在 MainActivity、
 * SettingsActivity、FloatingWindowService 中的重复版本适配代码。
 */
object ServiceHelper {

    /**
     * 启动 WakeWordService 前台服务
     *
     * 流程：
     * 1. 构造携带 START_WAKE_WORD extra 的 Intent
     * 2. 根据 Android 版本选择 startForegroundService / startService
     * 3. 捕获异常并通过 Logger 记录
     *
     * @param context 调用方上下文
     * @param startWakeWord 是否同时启动语音唤醒监听
     * @return 启动是否成功
     */
    fun startWakeWordService(context: Context, startWakeWord: Boolean = false): Boolean {
        val serviceIntent = Intent(context, WakeWordService::class.java).apply {
            putExtra("START_WAKE_WORD", startWakeWord)
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            true
        } catch (e: Exception) {
            Logger.e(Logger.SERVICE, "启动 WakeWordService 失败: ${e.message}", e)
            false
        }
    }
}
