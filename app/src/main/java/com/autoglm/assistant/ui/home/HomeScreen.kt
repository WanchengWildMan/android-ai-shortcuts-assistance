package com.autoglm.assistant.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onShortcutClick: (String) -> Unit,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val shortcutManager = remember { ShortcutManager(context) }
    var shortcuts by remember { mutableStateOf(shortcutManager.loadShortcuts()) }
    var inputText by remember { mutableStateOf("") }
    var showEditDialog by remember { mutableStateOf<ShortcutData?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showParameterDialog by remember { mutableStateOf<ShortcutData?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = getGreeting(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "我能为您做些什么?",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "添加",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                IconButton(
                    onClick = onHistoryClick,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                ) {
                    Icon(Icons.Default.History, contentDescription = "历史")
                }
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "快速操作",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "长按编辑",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(shortcuts, key = { it.id }) { shortcut ->
                ShortcutCard(
                    shortcut = shortcut,
                    onClick = {
                        if (shortcut.hasParameters()) {
                            showParameterDialog = shortcut
                        } else {
                            onShortcutClick(shortcut.prompt)
                        }
                    },
                    onLongClick = { showEditDialog = shortcut }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            placeholder = { Text("输入命令...") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            ),
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = {
                if (inputText.isNotEmpty()) {
                    IconButton(onClick = {
                        onShortcutClick(inputText)
                        inputText = ""
                    }) {
                        Icon(Icons.Default.Send, contentDescription = "发送", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (inputText.isNotEmpty()) {
                    onShortcutClick(inputText)
                    inputText = ""
                }
            }),
            singleLine = true
        )
    }

    // Create dialog
    if (showCreateDialog) {
        ShortcutEditDialog(
            shortcut = null,
            onDismiss = { showCreateDialog = false },
            onSave = { newShortcut ->
                shortcuts = shortcutManager.addShortcut(newShortcut)
                showCreateDialog = false
            }
        )
    }

    // Edit dialog
    showEditDialog?.let { shortcut ->
        ShortcutEditDialog(
            shortcut = shortcut,
            onDismiss = { showEditDialog = null },
            onSave = { updatedShortcut ->
                shortcuts = shortcutManager.updateShortcut(updatedShortcut)
                showEditDialog = null
            },
            onDelete = { id ->
                shortcuts = shortcutManager.deleteShortcut(id)
                showEditDialog = null
            }
        )
    }

    // Parameter input dialog for template shortcuts
    showParameterDialog?.let { shortcut ->
        ParameterInputDialog(
            shortcut = shortcut,
            onDismiss = { showParameterDialog = null },
            onConfirm = { filledPrompt ->
                showParameterDialog = null
                onShortcutClick(filledPrompt)
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortcutCard(
    shortcut: ShortcutData,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val icon = ShortcutManager.getIcon(shortcut.iconName)
    val color = Color(shortcut.colorHex)

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .aspectRatio(1f)
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(color.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }

            Text(
                text = shortcut.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

fun getGreeting(): String {
    val c = Calendar.getInstance()
    val timeOfDay = c.get(Calendar.HOUR_OF_DAY)

    return when (timeOfDay) {
        in 0..11 -> "早上好"
        in 12..15 -> "下午好"
        in 16..20 -> "晚上好"
        else -> "晚安"
    }
}
