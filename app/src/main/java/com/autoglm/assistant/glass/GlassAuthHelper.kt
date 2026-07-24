package com.autoglm.assistant.glass

import android.app.Activity
import android.util.Log
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission

/**
 * Rokid 鉴权封装。
 *
 * 业务目的：检测手机是否安装必要 App（Rokid AI App≥1.7.14 / Hi Rokid），
 * 发起授权并预申请眼镜侧麦克风权限，解析授权结果取得 token。
 *
 * 关键事实（反编译 SDK 确认）：
 *   - AuthorizationHelper 为实例方法（非静态），需 new AuthorizationHelper()
 *   - requestAuthorization(activity, GlassPermission[], code) 返回 Pair<Integer, Intent>，
 *     可能为已授权的即时结果（resultCode+Intent），需手动 startActivityForResult 或直接解析
 *   - parseAuthorizationResult(resultCode, data) 返回 AuthResult，AuthSuccess.getToken() 取 token
 */
class GlassAuthHelper(private val activity: Activity) {

    /** 是否已安装 Rokid AI App（≥1.7.14）或 Hi Rokid */
    fun isRequiredAppInstalled(): Boolean =
        AuthorizationHelper.isRequiredRokidAppInstalled(activity) ||
            AuthorizationHelper.isRequiredHiRokidInstalled(activity)

    /**
     * 发起授权，预申请眼镜侧麦克风权限。
     * 返回值非 null 表示 SDK 即时给出了结果（resultCode+Intent），调用方应直接 parseResult；
     * 返回值 null 表示 SDK 已拉起必要 App 的授权界面，等待 onActivityResult 回调。
     */
    fun requestAuth(): android.util.Pair<Int, android.content.Intent>? {
        val result = AuthorizationHelper.requestAuthorization(
            activity,
            arrayOf(GlassPermission.MICROPHONE),
            AUTH_REQUEST_CODE
        )
        // SDK 返回的 Pair 若包含有效 resultCode，说明无需等待外部 Activity 回调（用户此前已授权）
        return result?.takeIf { pair -> pair.first != null }
    }

    /**
     * 在 Activity.onActivityResult 中调用，解析授权结果。
     * 返回 token 或 null（失败/取消）。
     */
    fun parseResult(resultCode: Int, data: android.content.Intent?): String? {
        val r = AuthorizationHelper.parseAuthorizationResult(resultCode, data)
        return when (r) {
            is AuthResult.AuthSuccess -> {
                val token = r.token
                Log.i(LogTAG, "鉴权成功 token.length=${token?.length ?: 0}")
                token
            }
            is AuthResult.AuthFail -> {
                Log.w(LogTAG, "鉴权失败")
                null
            }
            is AuthResult.AuthCancel -> {
                Log.i(LogTAG, "用户取消鉴权")
                null
            }
            else -> null
        }
    }

    companion object {
        const val AUTH_REQUEST_CODE = 10091
        private const val LogTAG = "AutoGLM"
    }
}
