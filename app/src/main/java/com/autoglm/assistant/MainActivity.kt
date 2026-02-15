package com.autoglm.assistant

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.autoglm.assistant.App
import com.autoglm.assistant.service.WakeWordService
import com.autoglm.assistant.ui.theme.AutoGLMAssistantTheme
import com.autoglm.assistant.ui.chat.ChatScreen
import com.autoglm.assistant.ui.chat.ChatMessage
import com.autoglm.assistant.ui.home.HomeScreen
import com.autoglm.assistant.ui.chat.MessageManager
import com.autoglm.assistant.ui.chat.Conversation
import com.autoglm.assistant.ui.chat.ConversationListScreen
import androidx.compose.runtime.collectAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.autoglm.assistant.core.agent.SerializableMessage
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

class MainActivity : ComponentActivity() {

    private var wakeWordService: WakeWordService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WakeWordService.LocalBinder
            wakeWordService = binder.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            wakeWordService = null
            serviceBound = false
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startWakeWordService()
        } else {
            Toast.makeText(this, "需要必要权限才能运行", Toast.LENGTH_SHORT).show()
        }
    }

    private val screenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            wakeWordService?.setScreenCapturePermission(result.resultCode, result.data!!)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AutoGLMAssistantTheme {
                MainScreen(
                    onStartService = { startServiceWithPermissions() },
                    onStopService = { stopWakeWordService() },
                    onOpenSettings = { openSettings() },
                    onRequestScreenCapture = { requestScreenCapturePermission() },
                    onExecuteTask = { task, enablePlanning, enableOptimizer, messages -> executeTask(task, enablePlanning, enableOptimizer, messages) },
                    onStopTask = { stopCurrentTask() },
                    getServiceState = { wakeWordService?.serviceState },
                    getLastRecognizedText = { wakeWordService?.lastRecognizedText },
                    getAgentMessage = { wakeWordService?.agentMessage },
                    getCoordinatorMessage = { wakeWordService?.coordinatorMessage }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, WakeWordService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        android.util.Log.d("AutoGLM", "=== MainActivity.onStop() called - activity going to background ===")
        super.onStop()
        if (serviceBound) {
            android.util.Log.d("AutoGLM", "=== MainActivity: unbinding service (but NOT stopping task) ===")
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun startServiceWithPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startWakeWordService()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startWakeWordService() {
        // 检查无障碍服务
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "请启用无障碍服务", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }

        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        // 启动服务
        val serviceIntent = Intent(this, WakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopWakeWordService() {
        stopService(Intent(this, WakeWordService::class.java))
    }

    // 步骤3: 执行任务方法 - 默认参数仅作为兜底，实际调用都会传入明确的值
    private fun executeTask(task: String, enablePlanning: Boolean = true, enableOptimizer: Boolean = true, messages: List<ChatMessage> = emptyList()) {
        // 确保服务作为前台服务启动，这样即使 Activity 进入后台也不会被销毁
        val serviceIntent = Intent(this, WakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val context = messages.map {
            SerializableMessage(
                role = if (it.isUser) "user" else "assistant",
                content = it.content
            )
        }
        wakeWordService?.executeTask(task, enablePlanning, enableOptimizer, context)
    }

    private fun stopCurrentTask() {
        android.util.Log.w("AutoGLM", "=== MainActivity.stopCurrentTask() 被调用 - UI 请求停止 ===")
        Exception("MainActivity stopCurrentTask trace").printStackTrace()
        wakeWordService?.stopCurrentTask()
        Toast.makeText(this, "任务已停止", Toast.LENGTH_SHORT).show()
    }

    private fun requestScreenCapturePermission() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCapturePermissionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/${packageName}.service.AutomationService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(serviceName) == true
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openSettings() {
        // 跳转到设置界面
        startActivity(Intent(this, SettingsActivity::class.java))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestScreenCapture: () -> Unit,
        onExecuteTask: (String, Boolean, Boolean, List<ChatMessage>) -> Unit,
    onStopTask: () -> Unit,
    getServiceState: () -> kotlinx.coroutines.flow.StateFlow<WakeWordService.ServiceState>?,
    getLastRecognizedText: () -> kotlinx.coroutines.flow.StateFlow<String>?,
    getAgentMessage: () -> kotlinx.coroutines.flow.StateFlow<WakeWordService.AgentMessage?>?,
    getCoordinatorMessage: () -> kotlinx.coroutines.flow.StateFlow<WakeWordService.CoordinatorMessage?>?
) {
    var isServiceRunning by remember { mutableStateOf(false) }
    val conversations = remember { mutableStateListOf<Conversation>() }
    var currentConversation by remember { mutableStateOf<Conversation?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }

    val serviceState = getServiceState()?.collectAsState()
    val lastRecognizedText = getLastRecognizedText()?.collectAsState()
    val agentMessage = getAgentMessage()?.collectAsState()
    val coordinatorMessage = getCoordinatorMessage()?.collectAsState()

    // Coordinator消息状态
    var lastCoordinatorContent by remember { mutableStateOf<String?>(null) }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val context = LocalContext.current
    val messageManager = remember { MessageManager(context) }
    val scope = rememberCoroutineScope()

    // 启动时加载对话
    LaunchedEffect(Unit) {
        val loaded = messageManager.loadConversations()
        conversations.clear()
        conversations.addAll(loaded)
        // 自动选择最近的对话或创建新对话
        if (loaded.isNotEmpty()) {
            currentConversation = loaded.first()
            messages.clear()
            messages.addAll(loaded.first().messages)
        }
    }

    fun selectConversation(conversation: Conversation) {
        currentConversation = conversation
        messages.clear()
        messages.addAll(conversation.messages)
    }

    fun createNewConversation(): Conversation {
        val newConv = Conversation()
        conversations.add(0, newConv)
        currentConversation = newConv
        messages.clear()
        return newConv
    }

    fun deleteConversation(id: String) {
        scope.launch {
            messageManager.deleteConversation(id)
            conversations.removeAll { it.id == id }
            if (currentConversation?.id == id) {
                currentConversation = conversations.firstOrNull()
                messages.clear()
                currentConversation?.messages?.let { messages.addAll(it) }
            }
        }
    }

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        // 更新当前对话
        val conv = currentConversation ?: createNewConversation()
        conv.messages.add(message)
        conv.timestamp = System.currentTimeMillis()
        // 从第一条用户消息自动生成标题
        if (conv.title == "新对话" && message.isUser) {
            conv.title = message.content.take(30) + if (message.content.length > 30) "..." else ""
        }
        // 更新列表顺序
        conversations.remove(conv)
        conversations.add(0, conv)
        currentConversation = conv
        scope.launch {
            messageManager.saveConversation(conv)
        }
    }

    fun updateMessage(index: Int, message: ChatMessage) {
        if (index >= 0 && index < messages.size) {
            messages[index] = message
            // 更新当前对话
            val conv = currentConversation
            if (conv != null && index < conv.messages.size) {
                conv.messages[index] = message
                conv.timestamp = System.currentTimeMillis()
                scope.launch {
                    messageManager.saveConversation(conv)
                }
            }
        }
    }

    LaunchedEffect(lastRecognizedText?.value) {
        lastRecognizedText?.value?.let { text ->
            if (text.isNotBlank() && currentRoute == "chat") {
                addMessage(ChatMessage(content = text, isUser = true))
            }
        }
    }

    // 观察Coordinator消息 - 基于消息类型处理
    // 跟踪当前正在流式更新的消息索引（优化器、规划器、总结器各自独立）
    var optimizerMessageIndex by remember { mutableIntStateOf(-1) }
    var plannerMessageIndex by remember { mutableIntStateOf(-1) }
    var summaryMessageIndex by remember { mutableIntStateOf(-1) }
    var stepMessageIndex by remember { mutableIntStateOf(-1) }
    // 跟踪是否已经显示了subtask卡片
    var hasShownSubtaskCards by remember { mutableStateOf(false) }

    // 当切换对话时，重置流式索引
    LaunchedEffect(currentConversation?.id) {
        optimizerMessageIndex = -1
        plannerMessageIndex = -1
        summaryMessageIndex = -1
        stepMessageIndex = -1
        hasShownSubtaskCards = false
        lastCoordinatorContent = null
    }

    LaunchedEffect(coordinatorMessage?.value) {
        val msg = coordinatorMessage?.value ?: return@LaunchedEffect
        val showProcess = App.instance.preferenceManager.showAgentProcess

        // CLEAR类型：重置索引
        if (msg.type == WakeWordService.CoordinatorMessageType.CLEAR) {
            lastCoordinatorContent = null
            optimizerMessageIndex = -1
            plannerMessageIndex = -1
            summaryMessageIndex = -1
            stepMessageIndex = -1
            hasShownSubtaskCards = false
            return@LaunchedEffect
        }

        // 只有在 chat 页面时才处理消息
        if (currentRoute != "chat") return@LaunchedEffect

        if (!showProcess) return@LaunchedEffect

        // 根据类型格式化显示内容
        val displayContent = when (msg.type) {
            WakeWordService.CoordinatorMessageType.OPTIMIZER_STREAMING -> {
                if (msg.content.isBlank()) "✨ 正在优化指令..."
                else "✨ 优化中：${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.OPTIMIZER_COMPLETE -> {
                "✨ **优化后的指令：**\n\n${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.PLANNING_STREAMING -> {
                if (msg.content.isBlank()) "🤔 正在规划任务..."
                else "🤔 正在规划...\n\n${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.PLAN_COMPLETE -> {
                msg.content  // 已经包含格式化内容
            }
            WakeWordService.CoordinatorMessageType.SUBTASK_CARD -> {
                // 移除前缀，直接显示Markdown内容
                msg.content
            }
            WakeWordService.CoordinatorMessageType.SUBTASK_START -> {
                // 移除前缀，直接显示Markdown内容
                msg.content
            }
            WakeWordService.CoordinatorMessageType.SUPERVISION_RESULT -> {
                // content格式: "STATUS|内容"
                val parts = msg.content.split("|", limit = 2)
                val status = parts.getOrNull(0) ?: ""
                val content = parts.getOrNull(1) ?: msg.content
                val emoji = when (status) {
                    "SUCCESS" -> "✅"
                    "NEEDS_CORRECTION" -> "⚠️"
                    "FAILED" -> "❌"
                    "UNCERTAIN" -> "❓"
                    else -> "📊"
                }
                "$emoji 监督结果\n\n$content"
            }
            WakeWordService.CoordinatorMessageType.COORDINATOR_THINKING -> {
                "💭 协调器思考：\n${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.COORDINATOR_STEP -> {
                // content 格式: "currentStep|maxSteps"
                val parts = msg.content.split("|", limit = 2)
                val current = parts.getOrNull(0) ?: "?"
                val max = parts.getOrNull(1) ?: "?"
                "🔄 **协调器执行中** [$current/$max]"
            }
            WakeWordService.CoordinatorMessageType.SUMMARY_STREAMING -> {
                if (msg.content.isBlank()) "📝 正在生成任务总结..."
                else "📝 正在总结...\n\n${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.SUMMARY_COMPLETE -> {
                "📝 **任务总结：**\n\n${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.CLEAR -> ""
        }

        if (displayContent.isBlank()) return@LaunchedEffect

        // 根据类型决定是更新还是新增消息
        when (msg.type) {
            WakeWordService.CoordinatorMessageType.OPTIMIZER_STREAMING,
            WakeWordService.CoordinatorMessageType.OPTIMIZER_COMPLETE -> {
                // 优化器消息：流式更新同一条
                if (optimizerMessageIndex >= 0 && optimizerMessageIndex < messages.size) {
                    val oldMsg = messages[optimizerMessageIndex]
                    updateMessage(optimizerMessageIndex, ChatMessage(
                        id = oldMsg.id,
                        content = displayContent,
                        isUser = false,
                        timestamp = oldMsg.timestamp
                    ))
                } else {
                    addMessage(ChatMessage(content = displayContent, isUser = false))
                    optimizerMessageIndex = messages.size - 1
                }
            }
            WakeWordService.CoordinatorMessageType.PLANNING_STREAMING,
            WakeWordService.CoordinatorMessageType.PLAN_COMPLETE -> {
                // 规划器消息：流式更新同一条（与优化器消息分开）
                // 如果是PLAN_COMPLETE且已经显示了subtask卡片，则只显示简单的完成提示
                val content = if (msg.type == WakeWordService.CoordinatorMessageType.PLAN_COMPLETE && hasShownSubtaskCards) {
                    "✅ 任务规划完成，开始执行"
                } else {
                    displayContent
                }

                if (plannerMessageIndex >= 0 && plannerMessageIndex < messages.size) {
                    val oldMsg = messages[plannerMessageIndex]
                    updateMessage(plannerMessageIndex, ChatMessage(
                        id = oldMsg.id,
                        content = content,
                        isUser = false,
                        timestamp = oldMsg.timestamp
                    ))
                } else {
                    addMessage(ChatMessage(content = content, isUser = false))
                    plannerMessageIndex = messages.size - 1
                }
            }
            WakeWordService.CoordinatorMessageType.SUBTASK_CARD -> {
                // 子任务卡片：每个都是独立的新消息
                hasShownSubtaskCards = true
                addMessage(ChatMessage(content = displayContent, isUser = false))
            }
            WakeWordService.CoordinatorMessageType.SUMMARY_STREAMING,
            WakeWordService.CoordinatorMessageType.SUMMARY_COMPLETE -> {
                // 总结消息：流式更新同一条（与优化器和规划器消息分开）
                if (summaryMessageIndex >= 0 && summaryMessageIndex < messages.size) {
                    val oldMsg = messages[summaryMessageIndex]
                    updateMessage(summaryMessageIndex, ChatMessage(
                        id = oldMsg.id,
                        content = displayContent,
                        isUser = false,
                        timestamp = oldMsg.timestamp
                    ))
                } else {
                    addMessage(ChatMessage(content = displayContent, isUser = false))
                    summaryMessageIndex = messages.size - 1
                }
            }
            WakeWordService.CoordinatorMessageType.COORDINATOR_STEP -> {
                // 步骤计数器：原地更新同一条消息
                if (stepMessageIndex >= 0 && stepMessageIndex < messages.size) {
                    val oldMsg = messages[stepMessageIndex]
                    updateMessage(stepMessageIndex, ChatMessage(
                        id = oldMsg.id,
                        content = displayContent,
                        isUser = false,
                        timestamp = oldMsg.timestamp
                    ))
                } else {
                    addMessage(ChatMessage(content = displayContent, isUser = false))
                    stepMessageIndex = messages.size - 1
                }
            }
            else -> {
                // 其他消息：添加新消息
                if (displayContent != lastCoordinatorContent) {
                    lastCoordinatorContent = displayContent
                    addMessage(ChatMessage(content = displayContent, isUser = false))
                }
            }
        }
    }

    // Observe agent messages and add to chat based on setting
    // 缓存最后一条thinking消息，与action合并显示
    var lastThinkingContent by remember { mutableStateOf<String?>(null) }
    var lastThinkingMessageIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(agentMessage?.value) {
        agentMessage?.value?.let { msg ->
            // 只有在 chat 页面时才处理消息
            if (currentRoute != "chat") return@LaunchedEffect

            // Read setting dynamically each time
            val showProcess = App.instance.preferenceManager.showAgentProcess
            val shouldShow = when (msg.type) {
                WakeWordService.AgentMessageType.RESULT -> true  // Always show final result
                WakeWordService.AgentMessageType.THINKING,
                WakeWordService.AgentMessageType.ACTION -> showProcess  // Only show if setting enabled
            }

            when (msg.type) {
                WakeWordService.AgentMessageType.THINKING -> {
                    // 始终缓存thinking内容（即使是空的），以便与action合并
                    lastThinkingContent = msg.content
                    // 只有在应该显示且内容不为空时才添加临时消息
                    if (shouldShow && msg.content.isNotBlank()) {
                        addMessage(ChatMessage(content = "**思考：**\n${msg.content}", isUser = false))
                        lastThinkingMessageIndex = messages.size - 1
                    } else {
                        // 内容为空或不应显示，但仍需标记索引为null
                        lastThinkingMessageIndex = null
                    }
                }
                WakeWordService.AgentMessageType.ACTION -> {
                    if (shouldShow && msg.content.isNotBlank()) {
                        // 如果有缓存的thinking，合并显示
                        val content = if (!lastThinkingContent.isNullOrBlank()) {
                            buildString {
                                append("**思考：**\n")
                                append(lastThinkingContent)
                                append("\n\n**操作：**\n")
                                append(msg.content)
                            }
                        } else {
                            "**操作：**\n${msg.content}"
                        }

                        // 如果之前添加了thinking消息，替换它；否则添加新消息
                        if (lastThinkingMessageIndex != null && lastThinkingMessageIndex!! < messages.size) {
                            val oldMsg = messages[lastThinkingMessageIndex!!]
                            updateMessage(lastThinkingMessageIndex!!, ChatMessage(
                                id = oldMsg.id,
                                content = content,
                                isUser = false,
                                timestamp = oldMsg.timestamp
                            ))
                        } else {
                            addMessage(ChatMessage(content = content, isUser = false))
                        }
                    }

                    // 清空缓存
                    lastThinkingContent = null
                    lastThinkingMessageIndex = null
                }
                WakeWordService.AgentMessageType.RESULT -> {
                    if (shouldShow || msg.type == WakeWordService.AgentMessageType.RESULT) {
                        addMessage(ChatMessage(content = msg.content, isUser = false))
                    }
                    // 清空thinking缓存
                    lastThinkingContent = null
                    lastThinkingMessageIndex = null
                }
            }
        }
    }

    Scaffold(
        topBar = {
            when (currentRoute) {
                "chat" -> {
                    TopAppBar(
                        title = {
                            Text(
                                currentConversation?.title ?: "新对话",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1
                            )
                        },
                        actions = {
                            IconButton(onClick = { navController.navigate("conversations") }) {
                                Icon(Icons.Default.List, contentDescription = "对话列表")
                            }
                            IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Default.Settings, contentDescription = "设置")
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                            }
                        }
                    )
                }
                "conversations" -> {
                    TopAppBar(
                        title = { Text("对话历史", style = MaterialTheme.typography.titleLarge) },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                            }
                        },
                        actions = {
                            IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Default.Settings, contentDescription = "设置")
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Status & Controls Bar
            Surface(
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 状态指示器
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (icon, color, text) = when (serviceState?.value) {
                            WakeWordService.ServiceState.LISTENING_WAKE_WORD -> Triple(Icons.Default.Mic, MaterialTheme.colorScheme.primary, "正在监听唤醒词")
                            WakeWordService.ServiceState.LISTENING_COMMAND -> Triple(Icons.Default.RecordVoiceOver, MaterialTheme.colorScheme.tertiary, "正在监听指令")
                            WakeWordService.ServiceState.EXECUTING_TASK -> Triple(Icons.Default.PlayArrow, MaterialTheme.colorScheme.secondary, "正在执行")
                            WakeWordService.ServiceState.PROCESSING -> Triple(Icons.Default.Pending, MaterialTheme.colorScheme.primary, "正在处理")
                            else -> Triple(Icons.Default.PowerSettingsNew, MaterialTheme.colorScheme.outline, "空闲")
                        }
                        
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
                    }

                    // 控制按钮
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = onRequestScreenCapture) {
                            Icon(
                                Icons.Default.ScreenShare,
                                contentDescription = "屏幕权限",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        FilledTonalIconButton(
                            onClick = {
                        if (isServiceRunning) {
                            onStopService()
                            isServiceRunning = false
                        } else {
                            onStartService()
                            isServiceRunning = true
                        }
                            },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (isServiceRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                                contentColor = if (isServiceRunning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Icon(
                                imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (isServiceRunning) "停止" else "启动"
                            )
                        }
                    }
                }
            }

            NavHost(navController = navController, startDestination = "home", modifier = Modifier.weight(1f)) {
                composable(
                    route = "home",
                    enterTransition = { slideInHorizontally(initialOffsetX = { -it }) + fadeIn() },
                    exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() },
                    popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }) + fadeIn() },
                    popExitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() }
                ) {
                    HomeScreen(
                        onShortcutClick = { prompt, enablePlanning, enableOptimizer ->
                            // Create new conversation for new task
                            android.util.Log.d("AutoGLM", "Shortcut clicked: $prompt, enablePlanning=$enablePlanning, enableOptimizer=$enableOptimizer")
                            createNewConversation()
                            addMessage(ChatMessage(content = prompt, isUser = true))
                            // Navigate first, then execute task
                            navController.navigate("chat")
                            // Execute task after navigation to ensure UI is ready
                            onExecuteTask(prompt, enablePlanning, enableOptimizer, emptyList())
                        },
                        onHistoryClick = {
                            navController.navigate("conversations")
                        },
                        onSettingsClick = onOpenSettings,
                        onRunningTaskClick = {
                            navController.navigate("chat")
                        }
                    )
                }
                composable(
                    route = "chat",
                    enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                    exitTransition = { slideOutHorizontally(targetOffsetX = { it }) + fadeOut() },
                    popEnterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                    popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) + fadeOut() }
                ) {
                    // Use mutableState + LaunchedEffect to manually collect StateFlow
                    var isAgentRunning by remember { mutableStateOf(false) }
                    val stateFlow = getServiceState()

                    // Debug: log when composable enters
                    LaunchedEffect(Unit) {
                        android.util.Log.e("AutoGLM", "=== ChatScreen entered, stateFlow = $stateFlow")
                    }

                    // Collect StateFlow and update isAgentRunning
                    LaunchedEffect(stateFlow) {
                        if (stateFlow != null) {
                            stateFlow.collect { state ->
                                android.util.Log.e("AutoGLM", "=== ChatScreen collected state: $state")
                                isAgentRunning = state == WakeWordService.ServiceState.EXECUTING_TASK
                            }
                        } else {
                            android.util.Log.e("AutoGLM", "=== ChatScreen: stateFlow is NULL!")
                        }
                    }

                    ChatScreen(
                        messages = messages,
                        isAgentRunning = isAgentRunning,
                        onSendMessage = { text, enablePlanning, enableOptimizer ->
                            onExecuteTask(text, enablePlanning, enableOptimizer, messages)
                            addMessage(ChatMessage(content = text, isUser = true))
                        },
                        onStopTask = onStopTask,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                composable(
                    route = "conversations",
                    enterTransition = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
                    exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() },
                    popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }) + fadeIn() },
                    popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) + fadeOut() }
                ) {
                    ConversationListScreen(
                        conversations = conversations,
                        currentConversationId = currentConversation?.id,
                        onSelectConversation = { conv ->
                            selectConversation(conv)
                            navController.navigate("chat")
                        },
                        onNewConversation = {
                            createNewConversation()
                            navController.navigate("chat")
                        },
                        onDeleteConversation = { id -> deleteConversation(id) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

// Placeholder for SettingsActivity
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoGLMAssistantTheme {
                SettingsScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = App.instance.preferenceManager

    // Original values for change detection
    val originalApiUrl = remember { prefs.apiUrl }
    val originalApiKey = remember { prefs.apiKey }
    val originalModelName = remember { prefs.modelName }
    val originalPorcupineKey = remember { prefs.porcupineAccessKey }
    val originalWakeWordKeyword = remember { prefs.wakeWordKeyword }
    val originalMaxSteps = remember { prefs.maxSteps.toString() }
    val originalLanguage = remember { prefs.language }
    val originalShowAgentProcess = remember { prefs.showAgentProcess }
    val originalSmartCoordinatorEnabled = remember { prefs.smartCoordinatorEnabled }
    val originalCoordinatorApiUrl = remember { prefs.coordinatorApiUrl }
    val originalCoordinatorApiKey = remember {
        val model = prefs.coordinatorModelName
        when {
            model.startsWith("deepseek") -> prefs.coordinatorApiKeyDeepseek
            model.startsWith("glm-") -> prefs.coordinatorApiKeyBigmodel
            model.startsWith("doubao") -> prefs.coordinatorApiKeyDoubao
            else -> prefs.coordinatorApiKey
        }
    }
    val originalCoordinatorModelName = remember { prefs.coordinatorModelName }
    val originalSupervisionEnabled = remember { prefs.supervisionEnabled }
    val originalMaxCorrections = remember { prefs.maxCorrections.toString() }
    val originalMaxCoordinatorSteps = remember { prefs.maxCoordinatorSteps.toString() }
    // Prompt Optimizer originals
    val originalPromptOptimizerEnabled = remember { prefs.promptOptimizerEnabled }
    val originalOptimizerApiUrl = remember { prefs.optimizerApiUrl }
    val originalOptimizerApiKey = remember {
        val model = prefs.optimizerModelName
        when {
            model.startsWith("deepseek") -> prefs.optimizerApiKeyDeepseek
            model.startsWith("glm-") -> prefs.optimizerApiKeyBigmodel
            model.startsWith("doubao") -> prefs.optimizerApiKeyDoubao
            else -> prefs.optimizerApiKey
        }
    }
    val originalOptimizerModelName = remember { prefs.optimizerModelName }
    val originalTaskSummaryEnabled = remember { prefs.taskSummaryEnabled }
    val originalOptimizerSystemPrompt = remember { prefs.optimizerSystemPrompt }

    var apiUrl by remember { mutableStateOf(prefs.apiUrl) }
    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    var modelName by remember { mutableStateOf(prefs.modelName) }
    var porcupineKey by remember { mutableStateOf(prefs.porcupineAccessKey) }
    var wakeWordKeyword by remember { mutableStateOf(prefs.wakeWordKeyword) }
    var wakeWordDropdownExpanded by remember { mutableStateOf(false) }
    var maxSteps by remember { mutableStateOf(prefs.maxSteps.toString()) }
    var language by remember { mutableStateOf(prefs.language) }
    var showAgentProcess by remember { mutableStateOf(prefs.showAgentProcess) }
    var smartCoordinatorEnabled by remember { mutableStateOf(prefs.smartCoordinatorEnabled) }
    var coordinatorApiUrl by remember { mutableStateOf(prefs.coordinatorApiUrl) }
    var coordinatorApiKey by remember {
        val model = prefs.coordinatorModelName
        mutableStateOf(
            when {
                model.startsWith("deepseek") -> prefs.coordinatorApiKeyDeepseek
                model.startsWith("glm-") -> prefs.coordinatorApiKeyBigmodel
                model.startsWith("doubao") -> prefs.coordinatorApiKeyDoubao
                else -> prefs.coordinatorApiKey
            }
        )
    }
    var coordinatorModelName by remember { mutableStateOf(prefs.coordinatorModelName) }
    var coordinatorModelDropdownExpanded by remember { mutableStateOf(false) }
    var supervisionEnabled by remember { mutableStateOf(prefs.supervisionEnabled) }
    var maxCorrections by remember { mutableStateOf(prefs.maxCorrections.toString()) }
    var maxCoordinatorSteps by remember { mutableStateOf(prefs.maxCoordinatorSteps.toString()) }
    // Prompt Optimizer states
    var promptOptimizerEnabled by remember { mutableStateOf(prefs.promptOptimizerEnabled) }
    var optimizerApiUrl by remember { mutableStateOf(prefs.optimizerApiUrl) }
    var optimizerApiKey by remember {
        val model = prefs.optimizerModelName
        mutableStateOf(
            when {
                model.startsWith("deepseek") -> prefs.optimizerApiKeyDeepseek
                model.startsWith("glm-") -> prefs.optimizerApiKeyBigmodel
                model.startsWith("doubao") -> prefs.optimizerApiKeyDoubao
                else -> prefs.optimizerApiKey
            }
        )
    }
    var optimizerModelName by remember { mutableStateOf(prefs.optimizerModelName) }
    var optimizerModelDropdownExpanded by remember { mutableStateOf(false) }
    var taskSummaryEnabled by remember { mutableStateOf(prefs.taskSummaryEnabled) }
    var optimizerSystemPrompt by remember { mutableStateOf(prefs.optimizerSystemPrompt) }
    var showExitDialog by remember { mutableStateOf(false) }

    // Check if any setting has changed
    val hasChanges = apiUrl != originalApiUrl ||
            apiKey != originalApiKey ||
            modelName != originalModelName ||
            porcupineKey != originalPorcupineKey ||
            wakeWordKeyword != originalWakeWordKeyword ||
            maxSteps != originalMaxSteps ||
            language != originalLanguage ||
            showAgentProcess != originalShowAgentProcess ||
            smartCoordinatorEnabled != originalSmartCoordinatorEnabled ||
            coordinatorApiUrl != originalCoordinatorApiUrl ||
            coordinatorApiKey != originalCoordinatorApiKey ||
            coordinatorModelName != originalCoordinatorModelName ||
            supervisionEnabled != originalSupervisionEnabled ||
            maxCorrections != originalMaxCorrections ||
            maxCoordinatorSteps != originalMaxCoordinatorSteps ||
            promptOptimizerEnabled != originalPromptOptimizerEnabled ||
            optimizerApiUrl != originalOptimizerApiUrl ||
            optimizerApiKey != originalOptimizerApiKey ||
            optimizerModelName != originalOptimizerModelName ||
            taskSummaryEnabled != originalTaskSummaryEnabled ||
            optimizerSystemPrompt != originalOptimizerSystemPrompt

    // Available wake words
    val availableWakeWords = listOf(
        "XIAOAI" to "小爱",
        "PORCUPINE" to "Porcupine",
        "ALEXA" to "Alexa",
        "AMERICANO" to "Americano",
        "BLUEBERRY" to "Blueberry",
        "BUMBLEBEE" to "Bumblebee",
        "COMPUTER" to "Computer",
        "GRAPEFRUIT" to "Grapefruit",
        "GRASSHOPPER" to "Grasshopper",
        "HEY_GOOGLE" to "Hey Google",
        "HEY_SIRI" to "Hey Siri",
        "JARVIS" to "Jarvis",
        "OK_GOOGLE" to "Ok Google",
        "PICOVOICE" to "Picovoice",
        "TERMINATOR" to "Terminator"
    )

    // Localized strings based on selected language
    val isChinese = language == "cn"
    val strings = object {
        val title = if (isChinese) "设置" else "Settings"
        val save = if (isChinese) "保存" else "Save"
        val saved = if (isChinese) "设置已保存" else "Settings saved"
        val modelSettings = if (isChinese) "AutoGLM模型设置" else "AutoGLM Model Settings"
        val apiUrlLabel = "API URL"
        val apiKeyLabel = "API Key"
        val modelNameLabel = if (isChinese) "模型名称" else "Model Name"
        val voiceSettings = if (isChinese) "语音设置" else "Voice Settings"
        val porcupineLabel = "Porcupine Access Key"
        val porcupineHint = if (isChinese) "从 picovoice.ai 获取" else "Get key from picovoice.ai"
        val wakeWordLabel = if (isChinese) "唤醒词" else "Wake Word"
        val wakeWordHint = if (isChinese) "说出唤醒词来激活助手" else "Say the wake word to activate assistant"
        val agentSettings = if (isChinese) "Agent 设置" else "Agent Settings"
        val maxStepsLabel = if (isChinese) "最大步数" else "Max Steps"
        val languageLabel = if (isChinese) "语言: " else "Language: "
        val chinese = "中文"
        val english = "English"
        val showProcess = if (isChinese) "显示执行过程" else "Show Agent Process"
        val showProcessDesc = if (isChinese) "在对话中显示思考和操作步骤" else "Display thinking and action steps in chat"
        val smartCoordinatorSettings = if (isChinese) "智能协调器设置" else "Smart Coordinator Settings"
        val enableSmartCoordinator = if (isChinese) "默认启用规划" else "Enable Planning by Default"
        val smartCoordinatorDesc = if (isChinese) "新建快捷指令和手动输入任务时默认启用规划（各任务可独立控制）" else "Enable planning by default for new shortcuts and manual tasks (each task can be controlled independently)"
        val coordinatorApiUrlLabel = if (isChinese) "协调器 API URL" else "Coordinator API URL"
        val coordinatorApiKeyLabel = if (isChinese) "协调器 API Key" else "Coordinator API Key"
        val coordinatorModelLabel = if (isChinese) "协调器模型" else "Coordinator Model"
        val enableSupervision = if (isChinese) "启用执行监督" else "Enable Supervision"
        val supervisionDesc = if (isChinese) "检查每个子任务的执行结果" else "Check execution result of each subtask"
        val maxCorrectionsLabel = if (isChinese) "最大纠正次数" else "Max Corrections"
        val maxCoordinatorStepsLabel = if (isChinese) "协调器最大执行步数" else "Max Coordinator Steps"
        // Prompt Optimizer strings
        val promptOptimizerSettings = if (isChinese) "指令优化器设置" else "Prompt Optimizer Settings"
        val enablePromptOptimizer = if (isChinese) "启用指令优化器" else "Enable Prompt Optimizer"
        val promptOptimizerDesc = if (isChinese) "将简短指令扩展为详细任务描述" else "Expand short instructions into detailed task descriptions"
        val optimizerApiUrlLabel = if (isChinese) "优化器 API URL" else "Optimizer API URL"
        val optimizerApiKeyLabel = if (isChinese) "优化器 API Key" else "Optimizer API Key"
        val optimizerModelLabel = if (isChinese) "优化器模型" else "Optimizer Model"
        val unsavedChanges = if (isChinese) "未保存的更改" else "Unsaved Changes"
        val unsavedChangesMsg = if (isChinese) "是否保存更改？" else "Do you want to save changes?"
        val saveBtn = if (isChinese) "保存" else "Save"
        val discardBtn = if (isChinese) "不保存" else "Discard"
        val cancelBtn = if (isChinese) "取消" else "Cancel"
    }

    // Handle system back button
    androidx.activity.compose.BackHandler(enabled = true) {
        if (hasChanges) {
            showExitDialog = true
        } else {
            onBack()
        }
    }

    // Save function
    val saveSettings = {
        // 检测关键配置是否变化（影响 Agent/Coordinator/Optimizer）
        val needsRestart = apiUrl != originalApiUrl ||
                apiKey != originalApiKey ||
                modelName != originalModelName ||
                language != originalLanguage ||
                maxSteps != originalMaxSteps ||
                smartCoordinatorEnabled != originalSmartCoordinatorEnabled ||
                coordinatorApiUrl != originalCoordinatorApiUrl ||
                coordinatorApiKey != originalCoordinatorApiKey ||
                coordinatorModelName != originalCoordinatorModelName ||
                supervisionEnabled != originalSupervisionEnabled ||
                maxCorrections != originalMaxCorrections ||
                maxCoordinatorSteps != originalMaxCoordinatorSteps ||
                promptOptimizerEnabled != originalPromptOptimizerEnabled ||
                optimizerApiUrl != originalOptimizerApiUrl ||
                optimizerApiKey != originalOptimizerApiKey ||
                optimizerModelName != originalOptimizerModelName ||
                taskSummaryEnabled != originalTaskSummaryEnabled ||
                optimizerSystemPrompt != originalOptimizerSystemPrompt

        // 保存所有设置
        prefs.apiUrl = apiUrl
        prefs.apiKey = apiKey
        prefs.modelName = modelName
        prefs.porcupineAccessKey = porcupineKey
        prefs.wakeWordKeyword = wakeWordKeyword
        prefs.maxSteps = maxSteps.toIntOrNull() ?: 100
        prefs.language = language
        prefs.showAgentProcess = showAgentProcess
        prefs.smartCoordinatorEnabled = smartCoordinatorEnabled
        prefs.coordinatorApiUrl = coordinatorApiUrl
        // Save coordinator API key to provider-specific slot
        when {
            coordinatorModelName.startsWith("deepseek") -> prefs.coordinatorApiKeyDeepseek = coordinatorApiKey
            coordinatorModelName.startsWith("glm-") -> prefs.coordinatorApiKeyBigmodel = coordinatorApiKey
            coordinatorModelName.startsWith("doubao") -> prefs.coordinatorApiKeyDoubao = coordinatorApiKey
            else -> prefs.coordinatorApiKey = coordinatorApiKey
        }
        prefs.coordinatorModelName = coordinatorModelName
        prefs.supervisionEnabled = supervisionEnabled
        prefs.maxCorrections = maxCorrections.toIntOrNull() ?: 2
        prefs.maxCoordinatorSteps = maxCoordinatorSteps.toIntOrNull() ?: 20
        // Prompt Optimizer settings
        prefs.promptOptimizerEnabled = promptOptimizerEnabled
        prefs.optimizerApiUrl = optimizerApiUrl
        // Save optimizer API key to provider-specific slot
        when {
            optimizerModelName.startsWith("deepseek") -> prefs.optimizerApiKeyDeepseek = optimizerApiKey
            optimizerModelName.startsWith("glm-") -> prefs.optimizerApiKeyBigmodel = optimizerApiKey
            optimizerModelName.startsWith("doubao") -> prefs.optimizerApiKeyDoubao = optimizerApiKey
            else -> prefs.optimizerApiKey = optimizerApiKey
        }
        prefs.optimizerModelName = optimizerModelName
        prefs.taskSummaryEnabled = taskSummaryEnabled
        prefs.optimizerSystemPrompt = optimizerSystemPrompt

        // 如果关键配置变化，重启服务使其生效
        if (needsRestart) {
            // 停止服务
            context.stopService(Intent(context, WakeWordService::class.java))
            // 延迟后重启（给服务时间完全停止）
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val serviceIntent = Intent(context, WakeWordService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }, 500)
            Toast.makeText(context, if (language == "cn") "设置已保存，服务重启中..." else "Settings saved, restarting service...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, strings.saved, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.title) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasChanges) {
                            showExitDialog = true
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(strings.modelSettings, style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = apiUrl,
                onValueChange = { apiUrl = it },
                label = { Text(strings.apiUrlLabel) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(strings.apiKeyLabel) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                label = { Text(strings.modelNameLabel) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Divider()

            Text(strings.voiceSettings, style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = porcupineKey,
                onValueChange = { porcupineKey = it },
                label = { Text(strings.porcupineLabel) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text(strings.porcupineHint) }
            )

            // Wake word dropdown
            ExposedDropdownMenuBox(
                expanded = wakeWordDropdownExpanded,
                onExpandedChange = { wakeWordDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = availableWakeWords.find { it.first == wakeWordKeyword }?.second ?: "Porcupine",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(strings.wakeWordLabel) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = wakeWordDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    supportingText = { Text(strings.wakeWordHint) }
                )
                ExposedDropdownMenu(
                    expanded = wakeWordDropdownExpanded,
                    onDismissRequest = { wakeWordDropdownExpanded = false }
                ) {
                    availableWakeWords.forEach { (key, displayName) ->
                        DropdownMenuItem(
                            text = { Text(displayName) },
                            onClick = {
                                wakeWordKeyword = key
                                wakeWordDropdownExpanded = false
                            },
                            leadingIcon = if (wakeWordKeyword == key) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null
                        )
                    }
                }
            }

            Divider()

            Text(strings.agentSettings, style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = maxSteps,
                onValueChange = { maxSteps = it },
                label = { Text(strings.maxStepsLabel) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(strings.languageLabel, style = MaterialTheme.typography.bodyMedium)
                FilterChip(
                    selected = language == "cn",
                    onClick = { language = "cn" },
                    label = { Text(strings.chinese) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = language == "en",
                    onClick = { language = "en" },
                    label = { Text(strings.english) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.showProcess, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        strings.showProcessDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = showAgentProcess,
                    onCheckedChange = { showAgentProcess = it }
                )
            }

            Divider()

            // SmartCoordinator Settings
            Text(strings.smartCoordinatorSettings, style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (isChinese) "启用智能协调器 (全局)" else "Enable Smart Coordinator (Global)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        strings.smartCoordinatorDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = smartCoordinatorEnabled,
                    onCheckedChange = { smartCoordinatorEnabled = it }
                )
            }

            OutlinedTextField(
                value = coordinatorApiUrl,
                onValueChange = { coordinatorApiUrl = it },
                label = { Text(strings.coordinatorApiUrlLabel) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("DeepSeek: https://api.deepseek.com/v1   ·   Doubao(豆包): https://ark.cn-beijing.volces.com/api/v3") }
            )

            OutlinedTextField(
                value = coordinatorApiKey,
                onValueChange = { coordinatorApiKey = it },
                label = { Text(strings.coordinatorApiKeyLabel) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Model selection dropdown
            val coordinatorModels = listOf(
                "deepseek-chat" to "DeepSeek Chat",
                "glm-4-plus" to "智谱 GLM-4 Plus",
                "glm-4" to "智谱 GLM-4",
                "doubao-seed-1-6-251015" to "豆包 Seed 1.6"
            )

            ExposedDropdownMenuBox(
                expanded = coordinatorModelDropdownExpanded,
                onExpandedChange = { coordinatorModelDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = coordinatorModels.find { it.first == coordinatorModelName }?.second ?: coordinatorModelName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(strings.coordinatorModelLabel) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = coordinatorModelDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = coordinatorModelDropdownExpanded,
                    onDismissRequest = { coordinatorModelDropdownExpanded = false }
                ) {
                    coordinatorModels.forEach { (model, displayName) ->
                        DropdownMenuItem(
                            text = { Text(displayName) },
                            onClick = {
                                coordinatorModelName = model
                                // Auto-switch Coordinator API URL based on selected model
                                coordinatorApiUrl = when {
                                    model.startsWith("deepseek") -> "https://api.deepseek.com/v1"
                                    model.startsWith("glm-") -> "https://open.bigmodel.cn/api/paas/v4"
                                    model.startsWith("doubao") -> "https://ark.cn-beijing.volces.com/api/v3"
                                    else -> coordinatorApiUrl
                                }
                                // Auto-switch Coordinator API Key based on selected model
                                coordinatorApiKey = when {
                                    model.startsWith("deepseek") -> prefs.coordinatorApiKeyDeepseek
                                    model.startsWith("glm-") -> prefs.coordinatorApiKeyBigmodel
                                    model.startsWith("doubao") -> prefs.coordinatorApiKeyDoubao
                                    else -> coordinatorApiKey
                                }
                                coordinatorModelDropdownExpanded = false
                            },
                            leadingIcon = if (coordinatorModelName == model) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.enableSupervision, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        strings.supervisionDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = supervisionEnabled,
                    onCheckedChange = { supervisionEnabled = it }
                )
            }

            if (supervisionEnabled) {
                OutlinedTextField(
                    value = maxCorrections,
                    onValueChange = { maxCorrections = it },
                    label = { Text(strings.maxCorrectionsLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // 2. 协调器最大执行步数
            OutlinedTextField(
                value = maxCoordinatorSteps,
                onValueChange = { maxCoordinatorSteps = it },
                label = { Text(strings.maxCoordinatorStepsLabel) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Divider()

            // Prompt Optimizer Settings
            Text(strings.promptOptimizerSettings, style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.enablePromptOptimizer, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        strings.promptOptimizerDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = promptOptimizerEnabled,
                    onCheckedChange = { promptOptimizerEnabled = it }
                )
            }

            if (promptOptimizerEnabled) {
                OutlinedTextField(
                    value = optimizerApiUrl,
                    onValueChange = { optimizerApiUrl = it },
                    label = { Text(strings.optimizerApiUrlLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("DeepSeek: https://api.deepseek.com/v1   ·   Doubao(豆包): https://ark.cn-beijing.volces.com/api/v3") }
                )

                OutlinedTextField(
                    value = optimizerApiKey,
                    onValueChange = { optimizerApiKey = it },
                    label = { Text(strings.optimizerApiKeyLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Model selection dropdown
                val optimizerModels = listOf(
                    "deepseek-chat" to "DeepSeek Chat",
                    "glm-4-plus" to "智谱 GLM-4 Plus",
                    "glm-4" to "智谱 GLM-4",
                    "doubao-seed-1-6-251015" to "豆包 Seed 1.6"
                )

                ExposedDropdownMenuBox(
                    expanded = optimizerModelDropdownExpanded,
                    onExpandedChange = { optimizerModelDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = optimizerModels.find { it.first == optimizerModelName }?.second ?: optimizerModelName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(strings.optimizerModelLabel) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = optimizerModelDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = optimizerModelDropdownExpanded,
                        onDismissRequest = { optimizerModelDropdownExpanded = false }
                    ) {
                        optimizerModels.forEach { (model, displayName) ->
                            DropdownMenuItem(
                                text = { Text(displayName) },
                                onClick = {
                                    optimizerModelName = model
                                    // Auto-switch Optimizer API URL based on selected model
                                    optimizerApiUrl = when {
                                        model.startsWith("deepseek") -> "https://api.deepseek.com/v1"
                                        model.startsWith("glm-") -> "https://open.bigmodel.cn/api/paas/v4"
                                        model.startsWith("doubao") -> "https://ark.cn-beijing.volces.com/api/v3"
                                        else -> optimizerApiUrl
                                    }
                                    // Auto-switch Optimizer API Key based on selected model
                                    optimizerApiKey = when {
                                        model.startsWith("deepseek") -> prefs.optimizerApiKeyDeepseek
                                        model.startsWith("glm-") -> prefs.optimizerApiKeyBigmodel
                                        model.startsWith("doubao") -> prefs.optimizerApiKeyDoubao
                                        else -> optimizerApiKey
                                    }
                                    optimizerModelDropdownExpanded = false
                                },
                                leadingIcon = if (optimizerModelName == model) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )
                        }
                    }
                }

                // Task Summary Toggle (只在启用优化器时显示)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (isChinese) "任务完成后自动总结" else "Auto-summarize on task completion",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (isChinese) "任务完成或终止后自动生成总结说明" else "Automatically generate summary when task completes or stops",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = taskSummaryEnabled,
                        onCheckedChange = { taskSummaryEnabled = it }
                    )
                }

                // 自定义优化器系统提示词
                var showPromptEditor by remember { mutableStateOf(false) }
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
                            Text(
                                if (isChinese) "自定义优化器提示词" else "Custom Optimizer Prompt",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                if (optimizerSystemPrompt.isBlank()) {
                                    if (isChinese) "当前使用内置默认提示词" else "Currently using built-in default prompt"
                                } else {
                                    if (isChinese) "已配置自定义提示词（${optimizerSystemPrompt.length}字）" else "Custom prompt configured (${optimizerSystemPrompt.length} chars)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row {
                            if (optimizerSystemPrompt.isNotBlank()) {
                                TextButton(onClick = { optimizerSystemPrompt = "" }) {
                                    Text(if (isChinese) "恢复默认" else "Reset")
                                }
                            }
                            TextButton(onClick = { showPromptEditor = !showPromptEditor }) {
                                Text(if (showPromptEditor) {
                                    if (isChinese) "收起" else "Collapse"
                                } else {
                                    if (isChinese) "编辑" else "Edit"
                                })
                            }
                        }
                    }
                    if (showPromptEditor) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = optimizerSystemPrompt,
                            onValueChange = { optimizerSystemPrompt = it },
                            label = { Text(if (isChinese) "系统提示词（留空使用默认）" else "System Prompt (empty = default)") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 150.dp, max = 400.dp),
                            maxLines = 20,
                            supportingText = {
                                Text(
                                    if (isChinese) "自定义指令优化器的行为规则，留空则使用内置的默认提示词"
                                    else "Customize optimizer behavior rules. Leave empty to use built-in default prompt"
                                )
                            }
                        )
                    }
                }
            }

            Divider()

            // Root Mode Toggle
            var useRootMode by remember { mutableStateOf(prefs.useRootMode) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (isChinese) "Root 模式" else "Root Mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (isChinese) "使用 Root 权限执行命令（需要设备已 Root）" else "Execute commands with root (requires rooted device)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = useRootMode,
                    onCheckedChange = {
                        useRootMode = it
                        prefs.useRootMode = it
                        com.autoglm.assistant.util.ShellExecutor.globalUseRoot = it
                    }
                )
            }

            // 非 Root 模式下的辅助功能提示
            if (!useRootMode) {
                var accessibilityEnabled by remember { mutableStateOf(
                    com.autoglm.assistant.util.PermissionHelper.isAccessibilityServiceEnabled(context)
                ) }
                // 实时检测辅助功能状态
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(1000)
                        accessibilityEnabled = com.autoglm.assistant.util.PermissionHelper.isAccessibilityServiceEnabled(context)
                    }
                }
                if (!accessibilityEnabled) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (isChinese) "需要辅助功能权限" else "Accessibility Service Required",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    if (isChinese) "非 Root 模式需要启用辅助功能才能执行点击、滑动等操作" else "Non-root mode requires accessibility service for tap/swipe actions",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            ) {
                                Text(if (isChinese) "去开启" else "Enable")
                            }
                        }
                    }
                }
            }

            Divider()

            // System Tools
            Text(if (isChinese) "系统工具" else "System Tools", style = MaterialTheme.typography.titleMedium)

            val scope = rememberCoroutineScope()

            OutlinedButton(
                onClick = {
                    scope.launch {
                        // 尝试多种方式重启 input 服务
                        var result = com.autoglm.assistant.util.ShellExecutor.execute("setprop ctl.restart inputflinger", useRoot = true)
                        if (!result.success) {
                            result = com.autoglm.assistant.util.ShellExecutor.execute("killall inputflinger", useRoot = true)
                        }
                        Toast.makeText(context, if (result.success) "Input 服务已重启" else "失败: ${result.stderr}", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isChinese) "重启 Input 服务" else "Restart Input Service")
            }

            var showZygoteConfirm by remember { mutableStateOf(false) }

            OutlinedButton(
                onClick = { showZygoteConfirm = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isChinese) "重启 Zygote" else "Restart Zygote")
            }

            if (showZygoteConfirm) {
                AlertDialog(
                    onDismissRequest = { showZygoteConfirm = false },
                    title = { Text(if (isChinese) "确认重启" else "Confirm Restart") },
                    text = { Text(if (isChinese) "这会重启所有应用，你需要重新打开本应用。确定继续？" else "This will restart all apps. You need to reopen this app. Continue?") },
                    confirmButton = {
                        TextButton(onClick = {
                            showZygoteConfirm = false
                            scope.launch {
                                val result = com.autoglm.assistant.util.ShellExecutor.execute("setprop ctl.restart zygote", useRoot = true)
                                if (!result.success) {
                                    Toast.makeText(context, "失败: ${result.stderr}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) {
                            Text(if (isChinese) "确定" else "OK")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showZygoteConfirm = false }) {
                            Text(if (isChinese) "取消" else "Cancel")
                        }
                    }
                )
            }
                }
            }
        }
    }

    // Exit confirmation dialog
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(strings.unsavedChanges) },
            text = { Text(strings.unsavedChangesMsg) },
            confirmButton = {
                TextButton(onClick = {
                    saveSettings()
                    showExitDialog = false
                    onBack()
                }) {
                    Text(strings.saveBtn)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showExitDialog = false
                        onBack()
                    }) {
                        Text(strings.discardBtn)
                    }
                    TextButton(onClick = { showExitDialog = false }) {
                        Text(strings.cancelBtn)
                    }
                }
            }
        )
    }
}
