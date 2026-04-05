package com.autoglm.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.autoglm.assistant.util.PreferenceManager
import com.autoglm.assistant.util.ShellExecutor

class App : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "autoglm_service_channel_v2"
        /** 旧版通知渠道ID，用于升级时删除 */
        private const val OLD_CHANNEL_ID = "autoglm_service_channel"
        const val NOTIFICATION_ID = 1001

        lateinit var instance: App
            private set
    }

    lateinit var preferenceManager: PreferenceManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferenceManager = PreferenceManager(this)

        // 初始化 Shell 全局设置
        ShellExecutor.globalUseRoot = preferenceManager.useRootMode

        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // 步骤1: 删除旧版 IMPORTANCE_LOW 渠道（Android 缓存渠道设置，不删无法升级重要性）
            notificationManager.deleteNotificationChannel(OLD_CHANNEL_ID)

            // 步骤2: 创建新版渠道（IMPORTANCE_DEFAULT 确保 MIUI 上可见）
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(channel)
        }
    }
}
