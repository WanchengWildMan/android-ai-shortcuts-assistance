package com.autoglm.assistant.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * # 电池限制状态卡片
 *
 * ## 业务目的
 * 检测应用是否被系统电池优化限制后台运行（MIUI SmartPower 等），
 * 引导用户开启「无限制」模式，防止操作过程中被冻结。
 *
 * ## 实现逻辑
 * - 步骤1: 定时检查 isIgnoringBatteryOptimizations 状态
 * - 步骤2: 受限时显示橙色警告卡片，不受限时隐藏
 * - 步骤3: 点击优先弹系统白名单对话框，失败则跳转应用详情页
 */
@Composable
fun BatteryRestrictionCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isIgnoringOptimization by remember { mutableStateOf(true) }

    // 步骤1: 定时检查电池优化白名单状态
    LaunchedEffect(Unit) {
        while (true) {
            isIgnoringOptimization = checkBatteryOptimization(context)
            delay(3000) // 每3秒检查一次（用户可能从设置页返回）
        }
    }

    // 步骤2: 仅在受限时显示警告卡片
    if (isIgnoringOptimization) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { requestIgnoreBatteryOptimization(context) },
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFF9800).copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：警告图标和文字
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.BatteryAlert,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "后台运行受限",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "⚠️ 电池优化可能导致操作中断，点击开启「无限制」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 右侧：设置按钮
            IconButton(onClick = { requestIgnoreBatteryOptimization(context) }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "打开设置"
                )
            }
        }
    }
}

/**
 * 检查应用是否在电池优化白名单中
 * @return true=不受限（无限制模式），false=受限（优化/受限模式）
 */
private fun checkBatteryOptimization(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        ?: return true // 无法获取服务时默认不显示警告
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * 请求加入电池优化白名单
 * 优先使用系统对话框（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS），
 * 失败则跳转应用详情页让用户手动设置。
 */
private fun requestIgnoreBatteryOptimization(context: Context) {
    try {
        // 优先尝试: 弹出系统对话框直接请求白名单
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // 降级: 跳转到应用详情页（用户手动找到电池设置）
        try {
            val detailIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(detailIntent)
        } catch (e2: Exception) {
            // 兜底: 跳转到电池优化全局设置
            val batteryIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(batteryIntent)
        }
    }
}
