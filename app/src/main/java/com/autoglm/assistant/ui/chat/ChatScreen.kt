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
    // 使用全局设置作为默认值：全局开关控制聊天任务的默认规划开关状态
    var enablePlanning by remember { mutableStateOf(prefs.smartCoordinatorEnabled) }
    var enableOptimizer by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    var previousMessageCount by remember { mutableIntStateOf(0) }
    var isInitialLoad by remember { mutableStateOf(true) }
    var userScrolledUp by remember { mutableStateOf(false) }
    var isAutoScrolling by remember { mutableStateOf(false) }
    val currentMessages by rememberUpdatedState(messages)
    val scope = rememberCoroutineScope()

    // 检查是否在底部的函数
    fun isAtBottom(): Boolean {
        val layoutInfo = listState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        if (visibleItems.isEmpty() || currentMessages.isEmpty()) return true

        val lastVisibleItem = visibleItems.lastOrNull() ?: return false
        val totalItems = layoutInfo.totalItemsCount

        // 最后一个可见项是否是最后一项，且完全可见
        return lastVisibleItem.index >= totalItems - 1
    }

    // 监听用户主动滚动行为
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.isScrollInProgress to listState.firstVisibleItemIndex
        }.collect { (isScrolling, _) ->
            // 只在非自动滚动时更新状态
            if (isScrolling && !isAutoScrolling) {
                // 如果在底部，说明用户滚回来了 -> false
                // 如果不在底部，说明用户滚上去了 -> true
                userScrolledUp = !isAtBottom()
            }
        }
    }

    // 自动滚动逻辑：像 QQ/微信 一样
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isEmpty()) return@LaunchedEffect

        val currentCount = messages.size
        val lastIndex = messages.size - 1
        val lastMessage = messages.last()
        val isUserMessage = lastMessage.isUser

        // 初始加载：直接滚到底部
        if (isInitialLoad) {
            delay(50)  // 等待布局完成
            listState.scrollToItem(lastIndex)
            isInitialLoad = false
            previousMessageCount = currentCount
            userScrolledUp = false
            return@LaunchedEffect
        }

        val isNewMessage = currentCount > previousMessageCount

        // 决定是否自动滚动
        val shouldAutoScroll = when {
            isUserMessage -> true  // 用户发送的消息：总是滚到底部
            !userScrolledUp -> true  // 用户没有上滑：自动滚到底部
            else -> false  // 用户正在查看历史：不打扰
        }

        if (shouldAutoScroll) {
            isAutoScrolling = true
            try {
                if (isNewMessage) {
                    // 新消息：使用动画滚动
                    userScrolledUp = false
                    listState.animateScrollToItem(lastIndex)
                } else {
                    // 流式更新：平滑滚动（避免频繁动画）
                    listState.scrollToItem(lastIndex)
                }
            } finally {
                isAutoScrolling = false
            }
        }

        previousMessageCount = currentCount
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
                        text = "No messages yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(
                    items = messages,
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
                        placeholder = { Text("Type a message...") },
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
                            contentDescription = if (isAgentRunning) "Stop" else "Send",
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
