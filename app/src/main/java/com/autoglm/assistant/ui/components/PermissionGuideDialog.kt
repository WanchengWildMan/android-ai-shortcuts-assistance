package com.autoglm.assistant.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun PermissionGuideDialog(
    onDismissRequest: () -> Unit,
    onGoToSettings: () -> Unit,
    isChinese: Boolean = true
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                Icons.Default.AccessibilityNew,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(if (isChinese) "需要无障碍服务权限" else "Accessibility Permission Required")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (isChinese) "非 Root 模式需要无障碍服务来模拟点击和输入。请按照以下步骤开启："
                    else "Non-Root mode requires Accessibility Service to simulate taps and input. Please follow these steps to enable:"
                )
                
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StepItem(
                            number = 1, 
                            text = if (isChinese) "点击“前往设置”按钮" else "Click 'Go to Settings'"
                        )
                        StepItem(
                            number = 2, 
                            text = if (isChinese) "在列表中找到“AutoGLM助手”" else "Find 'AutoGLM Assistant' in the list"
                        )
                        StepItem(
                            number = 3, 
                            text = if (isChinese) "开启无障碍服务开关" else "Enable the Accessibility Service toggle"
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onGoToSettings) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isChinese) "前往设置" else "Go to Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(if (isChinese) "取消" else "Cancel")
            }
        }
    )
}

@Composable
private fun StepItem(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Badge(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(20.dp)
        ) {
            Text(number.toString(), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
