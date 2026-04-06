package com.autoglm.assistant.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.autoglm.assistant.util.Logger

@Composable
fun ParameterInputDialog(
    shortcut: ShortcutData,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean, Boolean) -> Unit  // Returns (filled prompt, enablePlanning, enableOptimizer)
) {
    val parameterNames = shortcut.getParameterNames()
    val parameterValues = remember { mutableStateMapOf<String, String>().apply {
        parameterNames.forEach { put(it, "") }
    }}
    var enablePlanning by remember { mutableStateOf(shortcut.enablePlanning) }
    // 步骤: 以快捷指令的配置作为初始值，用户可在参数输入弹窗中临时覆盖
    var enableOptimizer by remember { mutableStateOf(shortcut.enableOptimizer) }

    // 调试日志：显示快捷指令的规划设置
    Logger.d(Logger.SETTINGS, "ParameterInputDialog: shortcut.enablePlanning=${shortcut.enablePlanning}, initial enablePlanning=$enablePlanning")

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                // Title
                Text(
                    text = shortcut.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Show prompt template hint
                Text(
                    text = "请填写以下信息:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Parameter inputs
                parameterNames.forEach { paramName ->
                    OutlinedTextField(
                        value = parameterValues[paramName] ?: "",
                        onValueChange = { parameterValues[paramName] = it },
                        label = { Text(paramName) },
                        placeholder = { Text("请输入$paramName") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Planning switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (enablePlanning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clickable { enablePlanning = !enablePlanning }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountTree,
                                contentDescription = null,
                                tint = if (enablePlanning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "启用协调器",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (enablePlanning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (enableOptimizer) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clickable { enableOptimizer = !enableOptimizer }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoFixHigh,
                                contentDescription = null,
                                tint = if (enableOptimizer) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "启用优化",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (enableOptimizer) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))

                // Preview
                val filledPrompt = shortcut.fillParameters(parameterValues)
                if (parameterValues.values.any { it.isNotBlank() }) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "预览:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = filledPrompt,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val result = shortcut.fillParameters(parameterValues)
                            Logger.d(Logger.SETTINGS, "ParameterInputDialog confirm: enablePlanning=$enablePlanning, enableOptimizer=$enableOptimizer")
                            onConfirm(result, enablePlanning, enableOptimizer)
                        },
                        enabled = parameterValues.values.all { it.isNotBlank() }
                    ) {
                        Text("执行")
                    }
                }
            }
        }
    }
}
