package com.autoglm.assistant.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * 可复用的系统 Prompt 编辑器组件
 *
 * 【目的】Agent / 协调器 / 优化器三处都有"查看默认 -> 编辑 -> 清空"的提示词编辑 UI。
 *          抽取为通用组件消除约 200 行重复代码。
 *
 * @param title          编辑器标题，如"自定义 Agent 提示词"
 * @param prompt         当前提示词状态
 * @param onPromptChange 提示词变更回调
 * @param defaultPrompt  内置默认提示词内容（用于"加载默认"和"查看默认"）
 * @param isChinese      是否中文
 * @param hintText       编辑框下方的辅助说明
 * @param showViewDefault 是否显示"查看默认"按钮（Agent 没有预览，协调器/优化器有）
 */
@Composable
fun PromptEditorSection(
    title: String,
    prompt: TextFieldValue,
    onPromptChange: (TextFieldValue) -> Unit,
    defaultPrompt: String,
    isChinese: Boolean,
    hintText: String = "",
    showViewDefault: Boolean = true
) {
    var showEditor by remember { mutableStateOf(false) }
    var showDefaultPreview by remember { mutableStateOf(false) }

    // ---- 1. 预览默认提示词的 Dialog ----
    if (showViewDefault && showDefaultPreview) {
        AlertDialog(
            onDismissRequest = { showDefaultPreview = false },
            title = { Text(if (isChinese) "\u9ed8\u8ba4\u63d0\u793a\u8bcd" else "Default Prompt") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = if (isChinese)
                            "\u8fd9\u662f\u5185\u7f6e\u7684\u9ed8\u8ba4\u63d0\u793a\u8bcd\u5185\u5bb9\u3002\u60a8\u53ef\u4ee5\u57fa\u4e8e\u6b64\u6a21\u677f\u4fee\u6539\uff0c\u6216\u76f4\u63a5\u7559\u7a7a\u4f7f\u7528\u9ed8\u8ba4\u3002"
                        else
                            "This is the built-in default prompt. You can modify based on this template, or leave empty to use default.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                            .padding(12.dp)
                    ) {
                        Text(
                            text = defaultPrompt,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onPromptChange(TextFieldValue(defaultPrompt))
                    showDefaultPreview = false
                    showEditor = true
                }) {
                    Text(if (isChinese) "\u52a0\u8f7d\u5230\u7f16\u8f91\u5668" else "Load to Editor")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDefaultPreview = false }) {
                    Text(if (isChinese) "\u5173\u95ed" else "Close")
                }
            }
        )
    }

    // ---- 2. 编辑器主体 ----
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (prompt.text.isBlank()) {
                        if (isChinese) "\u5f53\u524d\u4f7f\u7528\u5185\u7f6e\u9ed8\u8ba4\u63d0\u793a\u8bcd" else "Using built-in default"
                    } else {
                        if (isChinese) "\u5df2\u914d\u7f6e\uff08${prompt.text.length}\u5b57\uff09" else "Configured (${prompt.text.length} chars)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                // 清空按钮（仅在有自定义内容时显示）
                if (prompt.text.isNotBlank()) {
                    TextButton(onClick = { onPromptChange(TextFieldValue("")) }) {
                        Text(if (isChinese) "\u6e05\u7a7a" else "Clear")
                    }
                }
                // 查看默认（可选）
                if (showViewDefault) {
                    TextButton(onClick = { showDefaultPreview = true }) {
                        Text(if (isChinese) "\u67e5\u770b\u9ed8\u8ba4" else "View Default")
                    }
                } else {
                    // Agent 模式：加载默认模板按钮
                    TextButton(onClick = {
                        onPromptChange(TextFieldValue(defaultPrompt))
                        showEditor = true
                    }) {
                        Text(if (isChinese) "\u52a0\u8f7d\u9ed8\u8ba4\u6a21\u677f" else "Load Template")
                    }
                }
                // 编辑/收起
                TextButton(onClick = { showEditor = !showEditor }) {
                    Text(
                        if (showEditor) {
                            if (isChinese) "\u6536\u8d77" else "Collapse"
                        } else {
                            if (isChinese) "\u7f16\u8f91" else "Edit"
                        }
                    )
                }
            }
        }
        if (showEditor) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                label = {
                    Text(
                        if (isChinese) "\u7cfb\u7edf\u63d0\u793a\u8bcd\uff08\u7559\u7a7a\u4f7f\u7528\u9ed8\u8ba4\uff09"
                        else "System Prompt (empty = default)"
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp, max = 400.dp),
                maxLines = 20,
                supportingText = if (hintText.isNotBlank()) {
                    { Text(hintText) }
                } else null
            )
        }
    }
}
