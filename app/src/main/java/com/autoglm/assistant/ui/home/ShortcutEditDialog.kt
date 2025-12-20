package com.autoglm.assistant.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.autoglm.assistant.App
import com.autoglm.assistant.ai.Message
import com.autoglm.assistant.ai.ModelClient
import com.autoglm.assistant.ai.ModelConfig
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
    var title by remember { mutableStateOf(shortcut?.title ?: "") }
    var prompt by remember { mutableStateOf(shortcut?.prompt ?: "") }
    var selectedIcon by remember { mutableStateOf(shortcut?.iconName ?: "Star") }
    var selectedColor by remember { mutableStateOf(shortcut?.colorHex ?: 0xFF64B5F6) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAddParamDialog by remember { mutableStateOf(false) }
    var showUnsavedConfirm by remember { mutableStateOf(false) }
    var newParamName by remember { mutableStateOf("") }
    var isOptimizing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val prefs = App.instance.preferenceManager

    // 检查是否有未保存的修改
    val hasUnsavedChanges = remember(title, prompt, selectedIcon, selectedColor) {
        title != (shortcut?.title ?: "") ||
        prompt != (shortcut?.prompt ?: "") ||
        selectedIcon != (shortcut?.iconName ?: "Star") ||
        selectedColor != (shortcut?.colorHex ?: 0xFF64B5F6)
    }

    Dialog(onDismissRequest = {
        // 如果有未保存的修改，显示确认对话框
        if (hasUnsavedChanges && (title.isNotBlank() || prompt.isNotBlank())) {
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
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("指令内容") },
                    placeholder = { Text("例如: 帮我点一份{食物}") },
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

                Spacer(modifier = Modifier.height(24.dp))

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
                            if (title.isNotBlank() && prompt.isNotBlank()) {
                                onSave(
                                    ShortcutData(
                                        id = shortcut?.id ?: UUID.randomUUID().toString(),
                                        title = title,
                                        prompt = prompt,
                                        iconName = selectedIcon,
                                        colorHex = selectedColor
                                    )
                                )
                            }
                        },
                        enabled = title.isNotBlank() && prompt.isNotBlank()
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
                            prompt = if (prompt.isEmpty()) {
                                "{$newParamName}"
                            } else {
                                "$prompt{$newParamName}"
                            }
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
                        if (title.isNotBlank() && prompt.isNotBlank()) {
                            onSave(
                                ShortcutData(
                                    id = shortcut?.id ?: UUID.randomUUID().toString(),
                                    title = title,
                                    prompt = prompt,
                                    iconName = selectedIcon,
                                    colorHex = selectedColor
                                )
                            )
                        }
                        showUnsavedConfirm = false
                    },
                    enabled = title.isNotBlank() && prompt.isNotBlank()
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
