package com.autoglm.assistant.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isAgentRunning: Boolean,
    onSendMessage: (String, Boolean, Boolean) -> Unit,
    onStopTask: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs = com.autoglm.assistant.App.instance.preferenceManager
    var inputText by remember { mutableStateOf("") }
    // 使用全局设置作为初始值，并监听配置变化实时同步
    var enablePlanning by remember { mutableStateOf(prefs.smartCoordinatorEnabled) }
    var enableOptimizer by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    var previousMessageCount by remember { mutableIntStateOf(0) }
    var userScrolledUp by remember { mutableStateOf(false) }
    var isAutoScrolling by remember { mutableStateOf(false) }

    // 监听全局配置变化，实时同步到界面
    DisposableEffect(Unit) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "smart_coordinator_enabled") {
                enablePlanning = prefs.smartCoordinatorEnabled
            }
        }
        prefs.registerListener(listener)
        onDispose {
            prefs.unregisterListener(listener)
        }
    }

    // 判断是否在底部（带容差，允许最后2项范围内）
    // 注意：reverseLayout=true时，index 0 是底部
    fun isNearBottom(): Boolean {
        val layoutInfo = listState.layoutInfo
        val firstVisibleIndex = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: return true
        return firstVisibleIndex <= 1
    }

    // 监听用户主动滚动
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { isScrolling ->
                // 只在非自动滚动时更新状态
                if (isScrolling && !isAutoScrolling) {
                    // 用户滚动中，检查是否离开底部
                    if (!isNearBottom()) {
                        userScrolledUp = true
                        android.util.Log.d("ChatScroll", "User scrolled up")
                    }
                }
                // 滚动停止时，如果回到底部，清除标记
                if (!isScrolling && userScrolledUp && isNearBottom()) {
                    userScrolledUp = false
                    android.util.Log.d("ChatScroll", "User back to bottom")
                }
            }
    }

    // 自动滚动：收到消息或内容更新就滚到底
    // 使用 reverseLayout=true，新消息在 index 0
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isEmpty()) return@LaunchedEffect

        val lastMessage = messages.lastOrNull() ?: return@LaunchedEffect
        val isUserMessage = lastMessage.isUser
        
        // 如果是用户消息，或者用户没有向上滚动，就自动滚动到底部
        if (isUserMessage || !userScrolledUp) {
            try {
                isAutoScrolling = true
                // 滚动到底部 (index 0)
                listState.animateScrollToItem(0)
            } catch (e: Exception) {
                // Ignore scroll errors
            } finally {
                isAutoScrolling = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .imePadding()
    ) {
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无消息",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = true, // 关键修改：反向布局，底部为起点
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 注意：reverseLayout=true 时，列表顺序需要反转
                items(
                    items = messages.reversed(),
                    key = { it.timestamp }
                ) { message ->
                    AnimatedMessageItem(message = message)
                }
            }
        }

        Surface(
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Planning Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (enablePlanning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clickable {
                                enablePlanning = !enablePlanning
                                prefs.smartCoordinatorEnabled = enablePlanning  // 同步到全局配置
                            }
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
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "启用协调器",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (enablePlanning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
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
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "启用优化",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (enableOptimizer) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("输入任务指令...") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(28.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank()) {
                                    onSendMessage(inputText, enablePlanning, enableOptimizer)
                                    inputText = ""
                                    userScrolledUp = false
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        ),
                        maxLines = 3
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (isAgentRunning) {
                                onStopTask()
                            } else if (inputText.isNotBlank()) {
                                onSendMessage(inputText, enablePlanning, enableOptimizer)
                                inputText = ""
                                userScrolledUp = false
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = when {
                                    isAgentRunning -> MaterialTheme.colorScheme.error
                                    inputText.isNotBlank() -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isAgentRunning) Icons.Default.Stop else Icons.Default.Send,
                            contentDescription = if (isAgentRunning) "停止" else "发送",
                            tint = when {
                                isAgentRunning -> MaterialTheme.colorScheme.onError
                                inputText.isNotBlank() -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AnimatedMessageItem(message: ChatMessage) {
    // Use rememberSaveable to keep state across scrolls, so animation only plays once
    var visible by rememberSaveable(message.timestamp) { mutableStateOf(false) }

    LaunchedEffect(message.timestamp) {
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            expandFrom = Alignment.Bottom,
            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)
        ) + fadeIn(
            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)
        ) + slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)
        )
    ) {
        ChatBubble(message = message)
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.isUser
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val backgroundColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = if (isUser) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            if (!isUser) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = "Bot",
                        modifier = Modifier.padding(6.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Surface(
                color = backgroundColor,
                shape = shape,
                modifier = Modifier.widthIn(min = 100.dp, max = 320.dp),
                shadowElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    SelectionContainer {
                        MarkdownText(
                            markdown = message.content,
                            style = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatTime(message.timestamp),
                        color = textColor.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }

            if (isUser) {
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
