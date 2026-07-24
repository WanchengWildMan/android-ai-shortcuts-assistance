package com.autoglm.assistant.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autoglm.assistant.App
import com.autoglm.assistant.glass.GlassAuthHelper
import com.autoglm.assistant.service.WakeWordService
import com.autoglm.assistant.util.Logger
import com.autoglm.assistant.util.ServiceHelper

/**
 * Rokid 眼镜通道设置区块。
 *
 * 业务目的：提供眼镜通道总开关、眼镜端 App 目标包名、鉴权入口、会话状态提示。
 * 鉴权流程：
 *   1) 点击"授权眼镜" → GlassAuthHelper.requestAuth()
 *   2) 若 SDK 即时返回结果（用户此前已授权）→ 直接 parseResult
 *   3) 否则用返回的 Intent 启动 StartActivityForResult → 回调中 parseResult
 *   4) 拿到 token 持久化 → 触发 WakeWordService.initGlassChannel()
 */
@Composable
fun GlassChannelSection(isChinese: Boolean) {
    val context = LocalContext.current
    val prefs = App.instance.preferenceManager

    var enabled by remember { mutableStateOf(prefs.glassChannelEnabled) }
    var targetPackage by remember { mutableStateOf(prefs.glassTargetPackage) }
    var autoStart by remember { mutableStateOf(prefs.glassAutoStartApp) }
    var tokenSet by remember { mutableStateOf(prefs.glassAuthToken.isNotEmpty()) }
    var feishuWebhookUrl by remember { mutableStateOf(prefs.glassFeishuWebhookUrl) }

    val authHelper = remember { GlassAuthHelper(context as android.app.Activity) }

    /** 确保服务已启动并触发眼镜会话建立 */
    fun startGlassSession() {
        // 服务未启动时先拉起（不启动唤醒监听），再 initGlassChannel
        if (WakeWordService.instance == null) {
            ServiceHelper.startWakeWordService(context, false)
            // 服务 onCreate 异步执行，稍后重试 initGlassChannel
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                WakeWordService.instance?.initGlassChannel()
            }, 800L)
        } else {
            WakeWordService.instance?.initGlassChannel()
        }
    }

    // 鉴权结果 launcher：用于 SDK 拉起外部授权 Activity 后接收结果
    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val token = authHelper.parseResult(result.resultCode, result.data)
        if (token != null) {
            prefs.glassAuthToken = token
            tokenSet = true
            Toast.makeText(context, if (isChinese) "授权成功" else "Authorized", Toast.LENGTH_SHORT).show()
            // 授权成功后触发眼镜会话建立
            startGlassSession()
        } else {
            Toast.makeText(context, if (isChinese) "授权失败或已取消" else "Authorization failed or cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                if (isChinese) "Rokid 眼镜通道" else "Rokid Glass Channel",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (isChinese) "眼镜作为语音输入与显示输出通道，Agent 仍在手机操作手机 App"
                else "Glass as voice input & display output; agent still runs on phone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            // 总开关
            SwitchSettingRow(
                title = if (isChinese) "启用眼镜通道" else "Enable Glass Channel",
                description = if (isChinese) "需先安装 Rokid AI App ≥1.7.14 并授权" else "Requires Rokid AI App ≥1.7.14 & authorization",
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.glassChannelEnabled = it
                    if (it) {
                        // 开启时若已鉴权则尝试建立会话
                        if (prefs.glassAuthToken.isNotEmpty()) {
                            startGlassSession()
                        }
                    }
                }
            )

            // 目标包名输入
            OutlinedTextField(
                value = targetPackage,
                onValueChange = {
                    targetPackage = it
                    prefs.glassTargetPackage = it
                },
                label = { Text(if (isChinese) "眼镜端 App 包名" else "Glass App Package") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled
            )

            // 自动启动开关
            SwitchSettingRow(
                title = if (isChinese) "链路就绪后自动启动眼镜App" else "Auto-start Glass App on link ready",
                checked = autoStart,
                onCheckedChange = {
                    autoStart = it
                    prefs.glassAutoStartApp = it
                }
            )

            Spacer(Modifier.height(8.dp))

            // 飞书 webhook 中转地址：本机作为眼镜桥接端时，识别文本经此 webhook 发到群里，电脑侧长连接收到后转发 PhoneAgent
            OutlinedTextField(
                value = feishuWebhookUrl,
                onValueChange = {
                    feishuWebhookUrl = it
                    prefs.glassFeishuWebhookUrl = it
                },
                label = { Text(if (isChinese) "飞书 Webhook 地址" else "Feishu Webhook URL") },
                placeholder = { Text("https://open.feishu.cn/open-apis/bot/v2/hook/xxx") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled
            )

            Spacer(Modifier.height(8.dp))

            // 鉴权状态与按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (tokenSet) (if (isChinese) "已授权" else "Authorized")
                    else (if (isChinese) "未授权" else "Not authorized"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tokenSet) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Button(
                    onClick = {
                        if (!authHelper.isRequiredAppInstalled()) {
                            Toast.makeText(
                                context,
                                if (isChinese) "未安装 Rokid AI App，请先安装" else "Rokid AI App not installed",
                                Toast.LENGTH_LONG
                            ).show()
                            Logger.w(Logger.GLASS, "Rokid AI App 未安装，无法鉴权")
                            return@Button
                        }
                        // requestAuth 返回 Pair：
                        //   - first 为有效 resultCode 且 second 含结果 → 用户此前已授权，即时解析
                        //   - second 为待启动 Intent → 走 launcher 拉起授权界面
                        //   - 返回 null → SDK 已自行拉起授权界面，等待 onActivityResult
                        val immediate = authHelper.requestAuth()
                        if (immediate != null && immediate.first != null) {
                            // 即时结果：直接解析
                            val token = authHelper.parseResult(immediate.first, immediate.second)
                            if (token != null) {
                                prefs.glassAuthToken = token
                                tokenSet = true
                                Toast.makeText(context, if (isChinese) "授权成功" else "Authorized", Toast.LENGTH_SHORT).show()
                                startGlassSession()
                            } else if (immediate.second != null) {
                                // 即时解析未拿到 token，但有待启动 Intent，走 launcher
                                authLauncher.launch(immediate.second)
                            }
                        } else if (immediate?.second != null) {
                            authLauncher.launch(immediate.second)
                        }
                    },
                    enabled = enabled
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isChinese) "授权眼镜" else "Authorize")
                }
            }
        }
    }
}
