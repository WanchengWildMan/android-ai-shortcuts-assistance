package com.autoglm.assistant.ui.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import java.util.Calendar

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onShortcutClick: (String, Boolean, Boolean) -> Unit,  // (prompt, enablePlanning, enableOptimizer)
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val shortcutManager = remember { ShortcutManager(context) }
    val prefs = com.autoglm.assistant.App.instance.preferenceManager
    var shortcuts by remember { mutableStateOf(shortcutManager.loadShortcuts()) }
    var inputText by remember { mutableStateOf("") }
    var showEditDialog by remember { mutableStateOf<ShortcutData?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showParameterDialog by remember { mutableStateOf<ShortcutData?>(null) }
    var isEditMode by remember { mutableStateOf(false) }
    // 使用全局设置作为默认值：全局开关控制手动输入任务的默认规划开关状态
    var enablePlanning by remember { mutableStateOf(prefs.smartCoordinatorEnabled) }
    var enableOptimizer by remember { mutableStateOf(true) }
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .combinedClickable(
                onClick = { if (isEditMode) isEditMode = false },
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            )
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
            if (isEditMode) {
                TextButton(onClick = { isEditMode = false }) {
                    Text("完成")
                }
            } else {
                Text(
                    text = "长按编辑排序",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val lazyGridState = rememberLazyGridState()
        val reorderableLazyGridState = rememberReorderableLazyGridState(lazyGridState) { from, to ->
            shortcuts = shortcuts.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            shortcutManager.saveShortcuts(shortcuts)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = lazyGridState,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(shortcuts, key = { it.id }) { shortcut ->
                ReorderableItem(reorderableLazyGridState, key = shortcut.id) { isDragging ->
                    ShortcutCard(
                        shortcut = shortcut,
                        isDragging = isDragging,
                        isEditMode = isEditMode,
                        onClick = {
                            if (isEditMode) {
                                // In edit mode, click does nothing on the card itself, 
                                // edit button handles edit
                            } else {
                                if (shortcut.hasParameters()) {
                                    showParameterDialog = shortcut
                                } else {
                                    onShortcutClick(shortcut.prompt, shortcut.enablePlanning, true) // Default optimizer to true for shortcuts? Or false?
                                }
                            }
                        },
                        onLongPress = {
                            isEditMode = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onEditClick = { showEditDialog = shortcut },
                        draggableModifier = if (isEditMode) {
                            Modifier.draggableHandle()
                        } else {
                            Modifier
                        }
                    )
                }
            }
        }

        // Planning toggle above input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Start
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
                IconButton(onClick = {
                    if (inputText.isNotEmpty()) {
                        onShortcutClick(inputText, enablePlanning, enableOptimizer)
                        inputText = ""
                    }
                }) {
                    Icon(
                        Icons.Default.Send, 
                        contentDescription = "发送", 
                        tint = if (inputText.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (inputText.isNotEmpty()) {
                    onShortcutClick(inputText, enablePlanning, enableOptimizer)
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
            onConfirm = { filledPrompt, enablePlanning, enableOptimizer ->
                showParameterDialog = null
                onShortcutClick(filledPrompt, enablePlanning, enableOptimizer)
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortcutCard(
    shortcut: ShortcutData,
    isDragging: Boolean,
    isEditMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onEditClick: () -> Unit,
    draggableModifier: Modifier = Modifier
) {
    val icon = ShortcutManager.getIcon(shortcut.iconName)
    val color = Color(shortcut.colorHex)
    val elevation by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 0.dp,
        label = "elevation"
    )
    
    // Jiggle animation for edit mode
    val infiniteTransition = rememberInfiniteTransition(label = "jiggle")
    val rotation by if (isEditMode) {
        infiniteTransition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(150, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "rotation"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .graphicsLayer {
                rotationZ = rotation
            }
            .then(draggableModifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(color.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = shortcut.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start
                )
            }
            
            if (isEditMode) {
                IconButton(
                    onClick = onEditClick,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
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
