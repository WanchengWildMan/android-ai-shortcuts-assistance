package com.autoglm.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.autoglm.assistant.util.PreferenceManager
import com.autoglm.assistant.util.ShellExecutor
import com.rokid.cxr.link.CXRLink

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

    /**
     * Rokid CXR-L 会话链路，进程内全局复用。
     * 业务目的：鉴权后由 GlassSessionManager 创建赋值，供自定义指令网关与状态推送共用同一实例。
     * 约束：setCXRLinkCbk 进程内只注册一次，子能力页不得 disconnect()。
     */
    @Volatile
    var sharedLink: CXRLink? = null
        internal set

    /** 会话结束或需要重建时清空链路引用 */
    fun resetGlassSession() {
        sharedLink = null
    }

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
