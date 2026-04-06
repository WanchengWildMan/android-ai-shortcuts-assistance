package com.autoglm.assistant.ui.chat

import com.autoglm.assistant.util.Logger
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*
// dev.jeziellago.compose.markdowntext.MarkdownText removed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isAgentRunning: Boolean,
    onSendMessage: (String, Boolean, Boolean) -> Unit,
    onStopTask: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs = com.autoglm.assistant.App.instance.preferenceManager
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    // 使用全局设置作为初始值，并监听配置变化实时同步
    var enablePlanning by remember { mutableStateOf(prefs.smartCoordinatorEnabled) }
    var enableOptimizer by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

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
                        Logger.d(Logger.SERVICE, "User scrolled up")
                    }
                }
                // 滚动停止时，如果回到底部，清除标记
                if (!isScrolling && userScrolledUp && isNearBottom()) {
                    userScrolledUp = false
                    Logger.d(Logger.SERVICE, "User back to bottom")
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
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
                        onValueChange = { textFieldValue ->
                            val textChanged = textFieldValue.text != inputText.text
                            inputText = textFieldValue
                            // Scroll to bottom when typing
                            if (textChanged && messages.isNotEmpty() && !userScrolledUp) {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(0)
                                }
                            }
                        },
                        placeholder = { Text("输入任务指令...") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(28.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.text.isNotBlank()) {
                                    onSendMessage(inputText.text, enablePlanning, enableOptimizer)
                                    inputText = TextFieldValue("")
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
                            } else if (inputText.text.isNotBlank()) {
                                onSendMessage(inputText.text, enablePlanning, enableOptimizer)
                                inputText = TextFieldValue("")
                                userScrolledUp = false
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = when {
                                    isAgentRunning -> MaterialTheme.colorScheme.error
                                    inputText.text.isNotBlank() -> MaterialTheme.colorScheme.primary
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
                                inputText.text.isNotBlank() -> MaterialTheme.colorScheme.onPrimary
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
    // 步骤1: 判断是否为近期消息（2秒内），仅对新消息播放动画，滚动到旧消息时直接显示
    val isRecentMessage = remember(message.timestamp) {
        System.currentTimeMillis() - message.timestamp < 2000L
    }

    if (!isRecentMessage) {
        // 旧消息直接显示，不播放动画，避免滚动时的视觉干扰
        ChatBubble(message = message)
        return
    }

    // 步骤2: 仅使用fadeIn动画，避免expandVertically导致的LazyColumn布局跳动
    var visible by rememberSaveable(message.timestamp) { mutableStateOf(false) }

    LaunchedEffect(message.timestamp) {
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
        )
    ) {
        ChatBubble(message = message)
    }
}

@OptIn(ExperimentalFoundationApi::class)
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

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val selectionColors = TextSelectionColors(
        handleColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        backgroundColor = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    )

    CompositionLocalProvider(
        LocalTextSelectionColors provides selectionColors
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = alignment
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
            ) {
                if (!isUser) {
                    // 根据消息来源使用不同图标和颜色
                    val (icon, iconTint, bgColor) = when (message.source) {
                        MessageSource.COORDINATOR -> Triple(
                            Icons.Default.AccountTree,
                            MaterialTheme.colorScheme.tertiary,
                            MaterialTheme.colorScheme.tertiaryContainer
                        )
                        MessageSource.OPTIMIZER -> Triple(
                            Icons.Default.AutoFixHigh,
                            MaterialTheme.colorScheme.secondary,
                            MaterialTheme.colorScheme.secondaryContainer
                        )
                        MessageSource.SUMMARY -> Triple(
                            Icons.Default.AutoFixHigh,
                            MaterialTheme.colorScheme.secondary,
                            MaterialTheme.colorScheme.secondaryContainer
                        )
                        else -> Triple(
                            Icons.Default.SmartToy,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                            MaterialTheme.colorScheme.primaryContainer
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = bgColor,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = message.source.name,
                            modifier = Modifier.padding(6.dp),
                            tint = iconTint
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Surface(
                    color = backgroundColor,
                    shape = shape,
                    modifier = Modifier
                        .widthIn(min = 60.dp, max = 320.dp)
                        .combinedClickable(
                            onClick = { },
                            onLongClick = {
                                clipboardManager.setText(AnnotatedString(message.content))
                                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            }
                        ),
                    shadowElevation = 1.dp
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // 解析思考内容：检测 "**思考：**" 前缀，分离思考与正文部分
                        val thinkingParts = if (!isUser) parseThinkingContent(message.content) else null
                        val hasThinking = thinkingParts != null && thinkingParts.first.isNotBlank()

                        if (hasThinking) {
                            // 思考内容折叠区域（默认收起）
                            var thinkingExpanded by remember { mutableStateOf(false) }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { thinkingExpanded = !thinkingExpanded }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "💭 思考过程",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = textColor.copy(alpha = 0.6f)
                                )
                                Icon(
                                    imageVector = if (thinkingExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (thinkingExpanded) "收起" else "展开",
                                    tint = textColor.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            AnimatedVisibility(visible = thinkingExpanded) {
                                SelectionContainer {
                                    Text(
                                        text = parseMarkdown(thinkingParts!!.first),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = textColor.copy(alpha = 0.7f)
                                        ),
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                            }

                            // 正文部分（操作/结果等）
                            val remainingContent = thinkingParts!!.second
                            if (remainingContent.isNotBlank()) {
                                SelectionContainer {
                                    Text(
                                        text = parseMarkdown(remainingContent),
                                        style = MaterialTheme.typography.bodyLarge.copy(color = textColor)
                                    )
                                }
                            }
                        } else {
                            // 无思考内容，正常渲染
                            SelectionContainer {
                                if (isUser) {
                                    Text(
                                        text = message.content,
                                        style = MaterialTheme.typography.bodyLarge.copy(color = textColor)
                                    )
                                } else {
                                    Text(
                                        text = parseMarkdown(message.content),
                                        style = MaterialTheme.typography.bodyLarge.copy(color = textColor)
                                    )
                                }
                            }
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
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/**
 * 解析消息中的思考内容，将其与正文部分分离。
 * 支持格式:
 *   1) "**思考：**\n内容\n\n**操作：**\n操作内容" → (思考内容, 操作内容)
 *   2) "**思考：**\n内容" → (思考内容, "")
 *   3) "<think>内容</think>正文" → (内容, 正文)
 * @return Pair(思考内容, 剩余正文) 或 null（无思考内容）
 */
private fun parseThinkingContent(content: String): Pair<String, String>? {
    // 格式1: **思考：** 前缀，后续可能有 **操作：** 分隔
    if (content.startsWith("**思考：**") || content.startsWith("**思考:**")) {
        val thinkPrefix = if (content.startsWith("**思考：**")) "**思考：**" else "**思考:**"
        val afterThink = content.removePrefix(thinkPrefix).trimStart('\n')

        // 查找 **操作：** 分隔符
        val actionSeparator = Regex("""\*\*操作[：:]\*\*""")
        val match = actionSeparator.find(afterThink)
        return if (match != null) {
            val thinking = afterThink.substring(0, match.range.first).trim()
            val remaining = afterThink.substring(match.range.first).trim()
            Pair(thinking, remaining)
        } else {
            Pair(afterThink.trim(), "")
        }
    }

    // 格式2: <think>...</think> 标签
    val thinkTagPattern = Regex("""<think>(.*?)</think>(.*)""", RegexOption.DOT_MATCHES_ALL)
    val tagMatch = thinkTagPattern.find(content)
    if (tagMatch != null) {
        return Pair(tagMatch.groupValues[1].trim(), tagMatch.groupValues[2].trim())
    }

    return null
}
