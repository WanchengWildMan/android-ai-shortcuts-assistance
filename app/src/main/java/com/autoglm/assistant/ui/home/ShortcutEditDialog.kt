package com.autoglm.assistant.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.autoglm.assistant.util.Logger
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.autoglm.assistant.App
import com.autoglm.assistant.ai.Message
import com.autoglm.assistant.ai.ModelClient
import com.autoglm.assistant.ai.ModelConfig
import com.autoglm.assistant.util.PreferenceManager
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ShortcutEditDialog(
    shortcut: ShortcutData? = null,  // null = create new
    onDismiss: () -> Unit,
    onSave: (ShortcutData) -> Unit,
    onDelete: ((String) -> Unit)? = null
) {
    val isEditing = shortcut != null
    val prefs = App.instance.preferenceManager
    var title by remember { mutableStateOf(shortcut?.title ?: "") }
    var promptValue by remember {
        mutableStateOf(TextFieldValue(
            text = shortcut?.prompt ?: "",
            selection = TextRange((shortcut?.prompt ?: "").length)
        ))
    }
    var selectedIcon by remember { mutableStateOf(shortcut?.iconName ?: "Star") }
    var selectedColor by remember { mutableStateOf(shortcut?.colorHex ?: 0xFF64B5F6) }
    // 新建快捷指令时以全局设置作为默认值，编辑时使用快捷指令自己的设置
    var enablePlanning by remember { mutableStateOf(shortcut?.enablePlanning ?: prefs.smartCoordinatorEnabled) }
    var enableOptimizer by remember { mutableStateOf(shortcut?.enableOptimizer ?: true) }
    var description by remember { mutableStateOf(shortcut?.description ?: "") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAddParamDialog by remember { mutableStateOf(false) }
    var showUnsavedConfirm by remember { mutableStateOf(false) }
    var newParamName by remember { mutableStateOf("") }
    var isOptimizing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 检查是否有未保存的修改
    val hasUnsavedChanges = remember(title, promptValue.text, selectedIcon, selectedColor, enablePlanning, enableOptimizer, description) {
        title != (shortcut?.title ?: "") ||
        promptValue.text != (shortcut?.prompt ?: "") ||
        selectedIcon != (shortcut?.iconName ?: "Star") ||
        selectedColor != (shortcut?.colorHex ?: 0xFF64B5F6) ||
        enablePlanning != (shortcut?.enablePlanning ?: prefs.smartCoordinatorEnabled) ||
        enableOptimizer != (shortcut?.enableOptimizer ?: true) ||
        description != (shortcut?.description ?: "")
    }

    Dialog(onDismissRequest = {
        // 如果有未保存的修改，显示确认对话框
        if (hasUnsavedChanges && (title.isNotBlank() || promptValue.text.isNotBlank())) {
            showUnsavedConfirm = true
        } else {
            onDismiss()
        }
    }) {
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
                    text = if (isEditing) "编辑快捷指令" else "新建快捷指令",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Title input
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    placeholder = { Text("例如: 查看天气") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Prompt input
                OutlinedTextField(
                    value = promptValue,
                    onValueChange = { promptValue = it },
                    label = { Text("指令内容") },
                    placeholder = { Text("例如: 帮我点一份{食物}") },
                    trailingIcon = {
                        if (isOptimizing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(
                                onClick = {
                                    if (promptValue.text.isNotBlank()) {
                                        isOptimizing = true
                                        scope.launch {
                                            try {
                                                    // 使用优化器专用的配置，而不是通用模型配置
                                                    val config = ModelConfig(
                                                        apiKey = run {
                                                            val key = prefs.resolveApiKey(prefs.optimizerModelName, PreferenceManager.ApiModule.OPTIMIZER)
                                                            if (key.isNotBlank()) key else prefs.apiKey
                                                        },
                                                        baseUrl = if (prefs.optimizerApiUrl.isNotBlank()) prefs.optimizerApiUrl else prefs.apiUrl,
                                                        modelName = prefs.optimizerModelName
                                                    )
                                                    val optimizer = com.autoglm.assistant.core.planner.PromptOptimizer(config)
                                                val optimized = optimizer.optimize(promptValue.text)
                                                promptValue = TextFieldValue(
                                                    text = optimized,
                                                    selection = TextRange(optimized.length)
                                                )
                                            } catch (e: Exception) {
                                                Logger.e(Logger.OPTIMIZER, "Prompt优化失败", e)
                                            } finally {
                                                isOptimizing = false
                                            }
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = "优化指令",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )

                Spacer(modifier = Modifier.height(8.dp))

                // Add parameter button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "点击右侧按钮添加可填参数",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledTonalButton(
                        onClick = { showAddParamDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("添加参数", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 备注/描述输入框（供意图识别参考，可选填）
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("备注（可选）") },
                    placeholder = { Text("描述指令用途，帮助语音识别更准确匹配") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 3,
                    supportingText = {
                        Text("供意图识别引擎参考，不影响指令执行")
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Enable planning toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "启用规划",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "让AI先规划步骤再执行（更准确但更慢）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enablePlanning,
                        onCheckedChange = { enablePlanning = it }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Enable optimizer toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "启用指令优化",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "执行前由AI重写优化指令内容（可能改变原文）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enableOptimizer,
                        onCheckedChange = { enableOptimizer = it }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Icon selection
                Text(
                    text = "图标",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(120.dp)
                ) {
                    items(ShortcutManager.AVAILABLE_ICONS) { (name, icon) ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selectedIcon == name)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    width = if (selectedIcon == name) 2.dp else 0.dp,
                                    color = if (selectedIcon == name)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { selectedIcon = name },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = name,
                                tint = if (selectedIcon == name)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Color selection
                Text(
                    text = "颜色",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ShortcutManager.AVAILABLE_COLORS.forEach { colorHex ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(colorHex))
                                .border(
                                    width = if (selectedColor == colorHex) 3.dp else 0.dp,
                                    color = if (selectedColor == colorHex)
                                        MaterialTheme.colorScheme.onSurface
                                    else
                                        Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = colorHex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedColor == colorHex) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Spacer(modifier = Modifier.height(8.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isEditing && onDelete != null) {
                        TextButton(
                            onClick = { showDeleteConfirm = true },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("删除")
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }

                    Button(
                        onClick = {
                            if (title.isNotBlank() && promptValue.text.isNotBlank()) {
                                onSave(
                                    ShortcutData(
                                        id = shortcut?.id ?: UUID.randomUUID().toString(),
                                        title = title,
                                        prompt = promptValue.text,
                                        iconName = selectedIcon,
                                        colorHex = selectedColor,
                                        enablePlanning = enablePlanning,
                                        enableOptimizer = enableOptimizer,
                                        description = description.trim()
                                    )
                                )
                            }
                        },
                        enabled = title.isNotBlank() && promptValue.text.isNotBlank()
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteConfirm && shortcut != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除快捷指令") },
            text = { Text("确定要删除「${shortcut.title}」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete?.invoke(shortcut.id)
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Add parameter dialog
    if (showAddParamDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddParamDialog = false
                newParamName = ""
            },
            title = { Text("添加参数") },
            text = {
                Column {
                    Text(
                        text = "参数名将以 {参数名} 的形式添加到指令末尾",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newParamName,
                        onValueChange = { newParamName = it },
                        label = { Text("参数名") },
                        placeholder = { Text("如: 食物、时间、地点") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newParamName.isNotBlank()) {
                            val paramText = "{$newParamName}"
                            val currentText = promptValue.text
                            val cursorPos = promptValue.selection.start

                            // Insert parameter at cursor position
                            val newText = currentText.substring(0, cursorPos) +
                                         paramText +
                                         currentText.substring(cursorPos)

                            // Update text field value with cursor after inserted parameter
                            promptValue = TextFieldValue(
                                text = newText,
                                selection = TextRange(cursorPos + paramText.length)
                            )

                            newParamName = ""
                            showAddParamDialog = false
                        }
                    },
                    enabled = newParamName.isNotBlank()
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddParamDialog = false
                    newParamName = ""
                }) {
                    Text("取消")
                }
            }
        )
    }

    // Unsaved changes confirmation dialog
    if (showUnsavedConfirm) {
        AlertDialog(
            onDismissRequest = { showUnsavedConfirm = false },
            title = { Text("保存修改") },
            text = { Text("是否保存对快捷指令的修改？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (title.isNotBlank() && promptValue.text.isNotBlank()) {
                            onSave(
                                ShortcutData(
                                    id = shortcut?.id ?: UUID.randomUUID().toString(),
                                    title = title,
                                    prompt = promptValue.text,
                                    iconName = selectedIcon,
                                    colorHex = selectedColor,
                                    enablePlanning = enablePlanning
                                )
                            )
                        }
                        showUnsavedConfirm = false
                    },
                    enabled = title.isNotBlank() && promptValue.text.isNotBlank()
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsavedConfirm = false
                    onDismiss()
                }) {
                    Text("放弃")
                }
            }
        )
    }
}
