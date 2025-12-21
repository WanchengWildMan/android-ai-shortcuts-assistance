package com.autoglm.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.autoglm.assistant.util.PreferenceManager
import com.autoglm.assistant.util.ShellExecutor

class App : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "autoglm_service_channel"
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
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
