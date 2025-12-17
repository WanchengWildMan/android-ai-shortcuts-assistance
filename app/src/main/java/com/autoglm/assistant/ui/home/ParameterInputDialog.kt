package com.autoglm.assistant.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun ParameterInputDialog(
    shortcut: ShortcutData,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit  // Returns the filled prompt
) {
    val parameterNames = shortcut.getParameterNames()
    val parameterValues = remember { mutableStateMapOf<String, String>().apply {
        parameterNames.forEach { put(it, "") }
    }}

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
                            onConfirm(result)
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
