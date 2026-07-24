package com.autoglm.assistant.glass

import android.content.Context
import com.autoglm.assistant.App
import com.autoglm.assistant.util.Logger
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo

/**
 * Rokid CXR-L 会话管理器。
 *
 * 业务目的：创建 CXRLink、配置 CUSTOMAPP 会话、connect、判定链路就绪、推装并启动眼镜端目标 App。
 * 进程内复用 App.sharedLink 单例；setCXRLinkCbk 进程内只注册一次。
 *
 * 关键事实（反编译 SDK 确认）：
 *   - configCXRSession(CxrDefs.CXRSession(CXRSessionType.CUSTOMAPP, targetPackage))：双参构造，第二参为 customAppPackageName
 *   - connect(token): Boolean，最终连通状态以回调为准
 *   - 链路就绪 = onCXRLConnected(true) && onGlassBtConnected(true)
 *   - 会话构建完成 = onOpenAppResult(true) 或 onGlassAppResume(true)
 *   - ICXRLinkCbk 7 个方法全为 abstract，需全部实现
 */
class GlassSessionManager(private val context: Context) {

    @Volatile private var cxrConnected = false
    @Volatile private var btConnected = false

    /** 链路就绪：CXR 服务连通 && 眼镜蓝牙就绪 */
    val isLinkReady: Boolean get() = cxrConnected && btConnected

    /** 会话构建完成：眼镜端目标 App 已启动 */
    @Volatile var sessionBuilt = false
        private set

    /** 眼镜端目标 App 入口 Activity 全名（须与 :glass 模块 Manifest 一致） */
    private var targetPackage: String = "com.autoglm.glass"
    private val glassEntryActivity: String
        get() = "$targetPackage.activities.MainActivity"

    var onLinkReady: (() -> Unit)? = null
    var onSessionBuilt: (() -> Unit)? = null
    var onLinkLost: (() -> Unit)? = null
    var onAiInterrupt: (() -> Unit)? = null

    /**
     * 创建并连接 CXRLink。
     * 步骤：1)复用既有 sharedLink 2)创建+配置 CUSTOMAPP 3)注册链路回调 4)connect(token)
     */
    fun start(token: String, targetPackage: String) {
        this.targetPackage = targetPackage
        if (App.instance.sharedLink != null) {
            Logger.i(Logger.GLASS, "sharedLink 已存在，复用，不重复 connect")
            return
        }
        if (token.isBlank()) {
            Logger.w(Logger.GLASS, "token 为空，无法 connect")
            return
        }
        Logger.i(Logger.GLASS, "创建 CXRLink，targetPackage=$targetPackage")
        val link = CXRLink(context).apply {
            configCXRSession(CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, targetPackage))
            setCXRLinkCbk(linkCallback)
        }
        App.instance.sharedLink = link
        link.connect(token)
    }

    private val linkCallback = object : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) {
            cxrConnected = connected
            Logger.i(Logger.GLASS, "onCXRLConnected=$connected")
            evaluateLinkState()
        }

        override fun onGlassBtConnected(connected: Boolean) {
            btConnected = connected
            Logger.i(Logger.GLASS, "onGlassBtConnected=$connected")
            evaluateLinkState()
        }

        override fun onGlassDeviceInfo(info: GlassInfo) {
            Logger.i(Logger.GLASS, "onGlassDeviceInfo=$info")
        }

        override fun onGlassWearingStatus(wearing: Boolean) {
            Logger.d(Logger.GLASS, "onGlassWearingStatus=$wearing")
        }

        override fun onGlassAiAssistStart() {
            Logger.i(Logger.GLASS, "onGlassAiAssistStart 眼镜侧 AI 助手激活")
        }

        override fun onGlassAiAssistStop() {
            Logger.i(Logger.GLASS, "onGlassAiAssistStop 眼镜侧 AI 助手停止")
        }

        override fun onGlassAiInterrupt(interrupt: Boolean) {
            Logger.i(Logger.GLASS, "onGlassAiInterrupt=$interrupt")
            onAiInterrupt?.invoke()
        }
    }

    /** 评估链路状态：就绪则触发 onLinkReady 并尝试装启眼镜 App；断开则触发 onLinkLost */
    private fun evaluateLinkState() {
        if (isLinkReady) {
            Logger.i(Logger.GLASS, "链路就绪")
            onLinkReady?.invoke()
            ensureGlassAppInstalledAndStarted()
        } else {
            Logger.w(Logger.GLASS, "链路未就绪 cxr=$cxrConnected bt=$btConnected")
            onLinkLost?.invoke()
        }
    }

    private val appCallback = object : IGlassAppCbk {
        override fun onQueryAppResult(installed: Boolean) {
            Logger.i(Logger.GLASS, "appIsInstalled=$installed")
            if (!installed) uploadAndInstall() else startGlassApp()
        }

        override fun onInstallAppResult(success: Boolean) {
            Logger.i(Logger.GLASS, "installApp=$success")
            if (success) startGlassApp()
        }

        override fun onOpenAppResult(success: Boolean) {
            Logger.i(Logger.GLASS, "onOpenAppResult=$success 会话构建${if (success) "完成" else "失败"}")
            if (success) {
                sessionBuilt = true
                onSessionBuilt?.invoke()
            }
        }

        override fun onGlassAppResume(resumed: Boolean) {
            Logger.i(Logger.GLASS, "onGlassAppResume=$resumed")
            if (resumed && !sessionBuilt) {
                sessionBuilt = true
                onSessionBuilt?.invoke()
            }
        }

        override fun onStopAppResult(success: Boolean) {
            Logger.i(Logger.GLASS, "onStopAppResult=$success")
        }

        override fun onUnInstallAppResult(success: Boolean) {
            Logger.i(Logger.GLASS, "onUnInstallAppResult=$success")
        }
    }

    /** 链路就绪后：查安装 → 缺则推装 → 启动入口 */
    fun ensureGlassAppInstalledAndStarted() {
        val link = App.instance.sharedLink ?: run {
            Logger.w(Logger.GLASS, "sharedLink 为空，无法查装眼镜 App")
            return
        }
        link.appIsInstalled(appCallback)
    }

    /** 推装眼镜端 APK（APK 须已置于应用专属目录，免存储权限） */
    private fun uploadAndInstall() {
        val link = App.instance.sharedLink ?: return
        val apkFile = context.getExternalFilesDir("DCIM/Rokid")?.resolve("autoglm-glass.apk")
        if (apkFile == null || !apkFile.exists() || !apkFile.canRead()) {
            Logger.w(Logger.GLASS, "眼镜端 APK 不可读: ${apkFile?.absolutePath}，请在设置中重新推装")
            return
        }
        Logger.i(Logger.GLASS, "推装眼镜端 APK: ${apkFile.absolutePath}")
        runCatching {
            link.appUploadAndInstall(apkFile.absolutePath, appCallback)
        }.onFailure { Logger.e(Logger.GLASS, "appUploadAndInstall 异常", it) }
    }

    /** 启动眼镜端入口 Activity */
    private fun startGlassApp() {
        val link = App.instance.sharedLink ?: return
        Logger.i(Logger.GLASS, "appStart $glassEntryActivity")
        runCatching {
            link.appStart(glassEntryActivity, appCallback)
        }.onFailure { Logger.e(Logger.GLASS, "appStart 异常", it) }
    }

    /** 把手机端打包好的 :glass 模块 APK 拷到应用专属目录，供 appUploadAndInstall 使用 */
    fun prepareGlassApk(apkSourcePath: String): Boolean {
        val target = context.getExternalFilesDir("DCIM/Rokid")?.resolve("autoglm-glass.apk") ?: return false
        return runCatching {
            java.io.File(apkSourcePath).copyTo(target, overwrite = true)
            Logger.i(Logger.GLASS, "已拷贝眼镜端 APK 到 ${target.absolutePath}")
            true
        }.onFailure { Logger.e(Logger.GLASS, "拷贝眼镜端 APK 失败", it) }.getOrDefault(false)
    }

    /** 停止眼镜端 App（会话结束/重连时调用） */
    fun stopGlassApp() {
        App.instance.sharedLink?.appStop(appCallback)
    }
}
