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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import com.autoglm.assistant.util.PreferenceManager
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
import com.autoglm.assistant.ui.components.PermissionGuideDialog
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

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
import com.autoglm.assistant.ai.MessageBuilder
import com.autoglm.assistant.core.planner.SmartCoordinator
import com.autoglm.assistant.core.planner.PromptOptimizer

class MainActivity : ComponentActivity() {

    private var wakeWordService: WakeWordService? = null
    private var serviceBound = false
    // 步骤: 用 Compose State 追踪服务绑定状态，确保绑定完成后触发重组
    private val _serviceConnected = mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            android.util.Log.d("AutoGLM", "=== MainActivity.onServiceConnected ===")
            val binder = service as WakeWordService.LocalBinder
            wakeWordService = binder.getService()
            serviceBound = true
            _serviceConnected.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            android.util.Log.d("AutoGLM", "=== MainActivity.onServiceDisconnected ===")
            wakeWordService = null
            serviceBound = false
            _serviceConnected.value = false
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
            // 读取 _serviceConnected 以便服务绑定后触发 recomposition
            val isConnected = _serviceConnected.value
            android.util.Log.d("AutoGLM", "=== MainActivity.setContent recomposing, isConnected=$isConnected ===")
            val currentService = if (isConnected) wakeWordService else null
            AutoGLMAssistantTheme {
                MainScreen(
                    onStartService = { startServiceWithPermissions() },
                    onStopService = { stopWakeWordService() },
                    onOpenSettings = { openSettings() },
                    onRequestScreenCapture = { requestScreenCapturePermission() },
                    onExecuteTask = { task, enablePlanning, enableOptimizer, messages -> executeTask(task, enablePlanning, enableOptimizer, messages) },
                    onStopTask = { stopCurrentTask() },
                    getServiceState = { currentService?.serviceState },
                    getLastRecognizedText = { currentService?.lastRecognizedText },
                    getAgentMessage = { currentService?.agentMessage },
                    getCoordinatorMessage = { currentService?.coordinatorMessage },
                    getLastWakeWordError = { currentService?.lastWakeWordError },
                    getActiveEngineType = { currentService?.activeEngineType }
                )
            }
        }

        // 步骤A: 应用启动时主动请求核心权限（麦克风 + 通知）
        requestCorePermissionsOnLaunch()

        // 步骤B: 如果语音唤醒已启用且权限已授予，自动启动服务
        autoStartServiceIfEnabled()
    }

    /**
     * 应用启动时主动检查并请求核心权限
     * 业务目的：确保用户在进入功能之前就授予了麦克风等关键权限，避免后续流程中静默失败
     */
    private fun requestCorePermissionsOnLaunch() {
        val needed = mutableListOf<String>()

        // 1. 麦克风权限 — 唤醒词引擎和语音识别都依赖
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        // 2. 通知权限 — Android 13+ 前台服务通知必需
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isNotEmpty()) {
            android.util.Log.i("AutoGLM_START", "应用启动，主动申请权限: $needed")
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    /**
     * 自动启动语音唤醒服务
     * 条件：设置中已开启语音唤醒 + 已拥有麦克风权限 + 有悬浮窗权限
     */
    private fun autoStartServiceIfEnabled() {
        val prefs = App.instance.preferenceManager
        if (!prefs.wakeWordEnabled) return

        // 检查必要权限是否已授予（不弹窗，静默检查）
        val hasMicPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasOverlayPermission = Settings.canDrawOverlays(this)

        if (hasMicPermission && hasOverlayPermission) {
            android.util.Log.i("AutoGLM", "=== 语音唤醒已开启，自动启动服务 ===")
            val serviceIntent = Intent(this, WakeWordService::class.java).apply {
                putExtra("START_WAKE_WORD", true)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (e: Exception) {
                android.util.Log.e("AutoGLM", "自动启动服务失败: ${e.message}", e)
            }
        } else {
            android.util.Log.w("AutoGLM", "=== 语音唤醒已开启但权限不足（mic=$hasMicPermission, overlay=$hasOverlayPermission），跳过自动启动 ===")
        }
    }

    override fun onStart() {
        super.onStart()
        // 步骤: 仅在尚未绑定时绑定服务
        // 任务执行期间 onStop 会跳过解绑，此时 serviceBound 仍为 true，无需重复绑定
        if (!serviceBound) {
            android.util.Log.d("AutoGLM", "=== MainActivity.onStart: Binding service... ===")
            bindService(
                Intent(this, WakeWordService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        } else {
            android.util.Log.d("AutoGLM", "=== MainActivity.onStart: Service already bound. ===")
        }
    }

    override fun onStop() {
        android.util.Log.d("AutoGLM", "=== MainActivity.onStop() called - activity going to background ===")
        super.onStop()
        if (serviceBound) {
            // 步骤: 任务执行期间保持绑定，作为双重保险
            // 主保护由 ensureServiceStartedAsForeground 提供（前台服务不会因解绑销毁）
            // 此处额外保留绑定，避免极端情况（如前台服务启动失败）下服务被销毁
            val isTaskRunning = wakeWordService?.serviceState?.value == WakeWordService.ServiceState.EXECUTING_TASK
            if (isTaskRunning) {
                android.util.Log.d("AutoGLM", "=== MainActivity.onStop(): task running, keeping service bound ===")
            } else {
                android.util.Log.d("AutoGLM", "=== MainActivity: unbinding service (no task running) ===")
                unbindService(serviceConnection)
                serviceBound = false
            }
        }
    }

    private fun startServiceWithPermissions() {
        android.util.Log.d("AutoGLM_START", "startServiceWithPermissions called")
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
        android.util.Log.d("AutoGLM_START", "startWakeWordService called")
        // 步骤1: 如果有悬浮窗权限则允许展示原有外层窗体，这里不再强制 return 阻塞
        if (!Settings.canDrawOverlays(this)) {
            android.util.Log.w("AutoGLM_START", "No overlay permission. The app can still listen inside, but floating UI will fallback or not show outside.")
        }

        // 步骤2: 检查并请求电池优化豁免
        // 业务目的：防止系统在后台杀死服务，确保任务持续执行
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                android.util.Log.w("AutoGLM", "=== 应用未在电池优化白名单中，请求加入 ===")
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    Toast.makeText(this, "请允许应用在后台运行，以确保任务不被中断", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    android.util.Log.e("AutoGLM", "请求电池优化豁免失败", e)
                }
            }
        }

        // 步骤3: 启动语音唤醒服务（需要麦克风权限）
        val wakeWordEnabled = App.instance.preferenceManager.wakeWordEnabled
        android.util.Log.d("AutoGLM_START", "startWakeWordService preparing intent, wakeWordEnabled=$wakeWordEnabled")
        val serviceIntent = Intent(this, WakeWordService::class.java).apply {
            putExtra("START_WAKE_WORD", wakeWordEnabled)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "启动服务失败: ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("MainActivity", "启动服务失败", e)
        }
    }

    private fun stopWakeWordService() {
        stopService(Intent(this, WakeWordService::class.java))
    }

    // 步骤3: 执行任务方法 - 默认参数仅作为兜底，实际调用都会传入明确的值
    private fun executeTask(task: String, enablePlanning: Boolean = true, enableOptimizer: Boolean = true, messages: List<ChatMessage> = emptyList()) {
        // 步骤3.0: 检查并请求通知权限（Android 13+）
        // 业务目的：确保后台执行任务时通知栏可见，让用户知道服务在运行
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }

        // 步骤3.1: 检查电池优化设置
        // 业务目的：在执行任务前提醒用户关闭电池优化，避免任务被系统中断
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                android.util.Log.w("AutoGLM", "=== 警告：应用未在电池优化白名单中，任务可能被中断 ===")
                Toast.makeText(this, "建议关闭电池优化，避免任务被中断", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 步骤3.2: 确保服务以前台服务方式运行
        // 业务目的：防止 Activity 切后台（onStop→unbindService）时服务因仅通过 BIND_AUTO_CREATE 创建而被销毁
        // 仅通过 bindService 创建的服务，在所有客户端解绑后会被系统销毁；
        // 通过 startForegroundService 启动的服务，即使解绑也会继续运行
        ensureServiceStartedAsForeground()

        if (!serviceBound) {
            bindService(
                Intent(this, WakeWordService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            // 等待绑定完成
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                executeTaskInternal(task, enablePlanning, enableOptimizer, messages)
            }, 100)
        } else {
            executeTaskInternal(task, enablePlanning, enableOptimizer, messages)
        }
    }

    /**
     * 确保 WakeWordService 以前台服务方式运行
     * 业务目的：前台服务在 unbindService 后不会被销毁，确保任务在后台持续执行
     */
    private fun ensureServiceStartedAsForeground() {
        val serviceIntent = Intent(this, WakeWordService::class.java).apply {
            putExtra("START_WAKE_WORD", false)  // 不启动语音唤醒，仅确保前台服务运行
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "⚠️ 无法启动前台服务: ${e.message}")
        }
    }
    
    private fun executeTaskInternal(task: String, enablePlanning: Boolean, enableOptimizer: Boolean, messages: List<ChatMessage>) {
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
        // 步骤1: 确保服务已启动（作为MediaProjection类型的前台服务）
        if (!serviceBound) {
            // 启动服务（不需要语音唤醒，但需要MediaProjection类型）
            val serviceIntent = Intent(this, WakeWordService::class.java).apply {
                putExtra("START_WAKE_WORD", false)  // 不启动语音唤醒
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                
                // 绑定服务以获取引用
                bindService(
                    Intent(this, WakeWordService::class.java),
                    serviceConnection,
                    0  // 不使用BIND_AUTO_CREATE，因为已经启动了
                )
                
                // 等待绑定完成后再请求权限
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    screenCapturePermissionLauncher.launch(projectionManager.createScreenCaptureIntent())
                }, 200)
                return
            } catch (e: Exception) {
                Toast.makeText(this, "启动服务失败: ${e.message}", Toast.LENGTH_LONG).show()
                android.util.Log.e("MainActivity", "启动服务失败", e)
                return
            }
        }
        
        // 步骤2: 服务已启动，直接请求权限
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
    getCoordinatorMessage: () -> kotlinx.coroutines.flow.StateFlow<WakeWordService.CoordinatorMessage?>?,
    getLastWakeWordError: () -> kotlinx.coroutines.flow.StateFlow<String?>? = { null },
    getActiveEngineType: () -> kotlinx.coroutines.flow.StateFlow<com.autoglm.assistant.voice.wake.WakeEngine.EngineType?>? = { null }
) {
    val conversations = remember { mutableStateListOf<Conversation>() }
    var currentConversation by remember { mutableStateOf<Conversation?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }

    val serviceState = getServiceState()?.collectAsState()
    val lastRecognizedText = getLastRecognizedText()?.collectAsState()
    val agentMessage = getAgentMessage()?.collectAsState()
    val coordinatorMessage = getCoordinatorMessage()?.collectAsState()

    // 从实际服务状态派生运行状态，避免UI与服务不同步
    val isServiceRunning = serviceState?.value != null &&
        serviceState?.value != WakeWordService.ServiceState.IDLE
        
    android.util.Log.d("AutoGLM_START", "MainScreen recompose: serviceState=${serviceState?.value}, isServiceRunning=$isServiceRunning")
    
    val wakeWordError = getLastWakeWordError()?.collectAsState()
    val activeEngine = getActiveEngineType()?.collectAsState()

    // Coordinator消息状态
    var lastCoordinatorContent by remember { mutableStateOf<String?>(null) }
    var latestExecutorHint by remember { mutableStateOf<String?>(null) }
    var latestCoordinatorHint by remember { mutableStateOf<String?>(null) }
    var coordinatorThinkingActive by remember { mutableStateOf(false) }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val context = LocalContext.current
    val messageManager = remember { MessageManager(context) }
    val scope = rememberCoroutineScope()

    var showPermissionGuide by remember { mutableStateOf(false) }

    if (showPermissionGuide) {
        PermissionGuideDialog(
            onDismissRequest = { showPermissionGuide = false },
            onGoToSettings = {
                showPermissionGuide = false
                try {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            },
            isChinese = App.instance.preferenceManager.language == "cn"
        )
    }

    val checkAndExecute: (String, Boolean, Boolean, List<ChatMessage>) -> Unit = { task, plan, opt, msgs ->
        val prefs = App.instance.preferenceManager
        if (!prefs.useRootMode && !com.autoglm.assistant.util.PermissionHelper.isAccessibilityServiceEnabled(context)) {
            showPermissionGuide = true
        } else {
            onExecuteTask(task, plan, opt, msgs)
        }
    }

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
        // 自动生成标题：新对话收到第一条用户消息时截取内容作为标题
        if (conv.title == Conversation.DEFAULT_CONVERSATION_TITLE && message.isUser) {
            conv.title = Conversation.generateTitle(message.content)
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

    fun compactStatusText(raw: String): String {
        return raw
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    fun keepFirstLines(raw: String, maxLines: Int): String {
        if (maxLines <= 0) return ""
        val normalized = compactStatusText(raw)
        if (normalized.isBlank()) return ""
        return normalized
            .lineSequence()
            .take(maxLines)
            .joinToString("\n")
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
        val showCoordinatorThinking = App.instance.preferenceManager.showCoordinatorThinking

        // CLEAR类型：重置索引
        if (msg.type == WakeWordService.CoordinatorMessageType.CLEAR) {
            lastCoordinatorContent = null
            optimizerMessageIndex = -1
            plannerMessageIndex = -1
            summaryMessageIndex = -1
            stepMessageIndex = -1
            hasShownSubtaskCards = false
            latestCoordinatorHint = null
            coordinatorThinkingActive = false
            return@LaunchedEffect
        }

        coordinatorThinkingActive = msg.type == WakeWordService.CoordinatorMessageType.COORDINATOR_THINKING
        latestCoordinatorHint = when (msg.type) {
            WakeWordService.CoordinatorMessageType.OPTIMIZER_STREAMING -> {
                if (msg.content.isBlank()) "协调器正在优化指令..." else "协调器优化中：${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.OPTIMIZER_COMPLETE -> "协调器已完成指令优化"
            WakeWordService.CoordinatorMessageType.PLANNING_STREAMING -> {
                if (msg.content.isBlank()) "协调器正在规划任务..." else "协调器规划中：${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.PLAN_COMPLETE -> "协调器规划完成"
            WakeWordService.CoordinatorMessageType.SUBTASK_CARD -> "协调器已生成子任务"
            WakeWordService.CoordinatorMessageType.SUBTASK_START -> "协调器正在执行子任务"
            WakeWordService.CoordinatorMessageType.SUPERVISION_RESULT -> "协调器已完成执行监督"
            WakeWordService.CoordinatorMessageType.COORDINATOR_THINKING -> "协调器正在思考..."
            WakeWordService.CoordinatorMessageType.SUMMARY_STREAMING -> {
                if (msg.content.isBlank()) "协调器正在生成总结..." else "协调器总结中：${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.SUMMARY_COMPLETE -> "协调器任务总结完成"
            WakeWordService.CoordinatorMessageType.COORDINATOR_STEP -> "协调器第 ${msg.content} 步"
            WakeWordService.CoordinatorMessageType.CLEAR -> null
        }

        if (msg.type == WakeWordService.CoordinatorMessageType.COORDINATOR_THINKING && !showCoordinatorThinking) {
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
                "🤔 **协调器分析：**\n\n${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.COORDINATOR_STEP -> {
                // content 格式: "currentStep|maxSteps"
                val parts = msg.content.split("|", limit = 2)
                val current = parts.getOrNull(0) ?: "?"
                val max = parts.getOrNull(1) ?: "?"
                "🔄 **协调器决策中** [$current/$max]"
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
            latestExecutorHint = when (msg.type) {
                WakeWordService.AgentMessageType.THINKING -> {
                    if (msg.content.isBlank()) "执行器正在思考..." else "执行器思考：${msg.content}"
                }
                WakeWordService.AgentMessageType.ACTION -> {
                    if (msg.content.isBlank()) "执行器正在执行操作..." else "执行器操作：${msg.content}"
                }
                WakeWordService.AgentMessageType.RESULT -> null
            }

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

    val isExecutingTask = serviceState?.value == WakeWordService.ServiceState.EXECUTING_TASK
    val coordinatorBannerSource = when {
        coordinatorThinkingActive -> "协调器正在思考..."
        !latestCoordinatorHint.isNullOrBlank() -> latestCoordinatorHint!!
        else -> ""
    }
    val executorBannerLines = if (coordinatorBannerSource.isNotBlank()) 2 else 3
    val coordinatorBannerLines = if (!latestExecutorHint.isNullOrBlank()) 1 else 2
    val executorBannerText = keepFirstLines(latestExecutorHint ?: "", executorBannerLines)
    val coordinatorBannerText = keepFirstLines(coordinatorBannerSource, coordinatorBannerLines)
    val bottomStatusText = buildString {
        if (executorBannerText.isNotBlank()) {
            append(executorBannerText)
        }
        if (coordinatorBannerText.isNotBlank()) {
            if (isNotEmpty()) append("\n")
            append("协调中：")
            append(coordinatorBannerText)
        }
    }
    val shouldShowBottomBanner = currentRoute == "home" || currentRoute == "chat"

    Scaffold(
        topBar = {
            when (currentRoute) {
                "chat" -> {
                    TopAppBar(
                        title = {
                            Text(
                                currentConversation?.title ?: Conversation.DEFAULT_CONVERSATION_TITLE,
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
            // 唤醒状态卡片 — 醒目展示当前唤醒引擎状态
            val errorText = wakeWordError?.value
            val engineLabel = when (activeEngine?.value) {
                com.autoglm.assistant.voice.wake.WakeEngine.EngineType.PORCUPINE -> "Porcupine"
                com.autoglm.assistant.voice.wake.WakeEngine.EngineType.STT_SYSTEM -> "系统 STT"
                else -> null
            }

            Surface(
                tonalElevation = 2.dp,
                color = when {
                    errorText != null -> MaterialTheme.colorScheme.errorContainer
                    serviceState?.value == WakeWordService.ServiceState.LISTENING_WAKE_WORD -> MaterialTheme.colorScheme.primaryContainer
                    serviceState?.value == WakeWordService.ServiceState.EXECUTING_TASK -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surface
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 左侧：状态图标 + 主状态文字
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            val (icon, color, statusText) = when {
                                errorText != null -> Triple(Icons.Default.Warning, MaterialTheme.colorScheme.error, "唤醒异常")
                                serviceState?.value == WakeWordService.ServiceState.LISTENING_WAKE_WORD ->
                                    Triple(Icons.Default.Mic, MaterialTheme.colorScheme.primary, "正在监听")
                                serviceState?.value == WakeWordService.ServiceState.LISTENING_COMMAND ->
                                    Triple(Icons.Default.RecordVoiceOver, MaterialTheme.colorScheme.tertiary, "监听指令")
                                serviceState?.value == WakeWordService.ServiceState.EXECUTING_TASK ->
                                    Triple(Icons.Default.PlayArrow, MaterialTheme.colorScheme.secondary, "执行中")
                                serviceState?.value == WakeWordService.ServiceState.PROCESSING ->
                                    Triple(Icons.Default.Pending, MaterialTheme.colorScheme.primary, "处理中")
                                else -> Triple(Icons.Default.PowerSettingsNew, MaterialTheme.colorScheme.outline, "语音唤醒未启动")
                            }

                            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    statusText,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = color
                                )
                                if (engineLabel != null && errorText == null) {
                                    Text(
                                        "引擎: $engineLabel",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // 右侧：控制按钮
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalIconButton(
                                onClick = {
                                    android.util.Log.d("AutoGLM_START", "Button clicked! isServiceRunning=$isServiceRunning")
                                    if (isServiceRunning) onStopService() else onStartService()
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

                    // 错误信息详情（有错误时展示完整内容）
                    if (errorText != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            errorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
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
                            checkAndExecute(prompt, enablePlanning, enableOptimizer, emptyList())
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
                            checkAndExecute(text, enablePlanning, enableOptimizer, messages)
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

            if (shouldShowBottomBanner && isExecutingTask && bottomStatusText.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Text(
                        text = bottomStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
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
    val originalAgentSystemPrompt = remember { prefs.agentSystemPrompt }
    val originalPorcupineKey = remember { prefs.porcupineAccessKey }
    val originalWakeWordKeyword = remember { prefs.wakeWordKeyword }
    val originalWakeEngineType = remember { prefs.wakeEngineType }
    val originalSttWakePhrase = remember { prefs.sttWakePhrase }
    val originalCommandSttMode = remember { prefs.commandSttMode }
    val originalSttApiType = remember { prefs.sttApiType }
    val originalSttApiUrl = remember { prefs.sttApiUrl }
    val originalSttApiKey = remember { prefs.sttApiKey }
    val originalSttModelName = remember { prefs.sttModelName }
    val originalAliNlsAkId = remember { prefs.aliNlsAkId }
    val originalAliNlsAkSecret = remember { prefs.aliNlsAkSecret }
    val originalAliNlsAppKey = remember { prefs.aliNlsAppKey }
    val originalImeVoiceSpaceX = remember { prefs.imeVoiceSpaceX.toString() }
    val originalImeVoiceSpaceY = remember { prefs.imeVoiceSpaceY.toString() }
    val originalLockScreenPassword = remember {
        com.autoglm.assistant.util.SecureStorage.getDecrypted(
            context, com.autoglm.assistant.util.ScreenUnlocker.SECURE_KEY_LOCK_PASSWORD
        )
    }
    val originalMaxSteps = remember { prefs.maxSteps.toString() }
    val originalLanguage = remember { prefs.language }
    val originalShowAgentProcess = remember { prefs.showAgentProcess }
    val originalSmartCoordinatorEnabled = remember { prefs.smartCoordinatorEnabled }
    val originalShowCoordinatorThinking = remember { prefs.showCoordinatorThinking }
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
    val originalCoordinatorEnableVision = remember { prefs.coordinatorEnableVision }
    val originalCoordinatorEnableThinking = remember { prefs.coordinatorEnableThinking }
    val originalCoordinatorSystemPrompt = remember { prefs.coordinatorSystemPrompt }
    val originalSupervisionEnabled = remember { prefs.supervisionEnabled }
    val originalMaxCorrections = remember { prefs.maxCorrections.toString() }
    val originalMaxCoordinatorSteps = remember { prefs.maxCoordinatorSteps.toString() }
    val originalMaxAgentStepsPerCoordinatorStep = remember { prefs.maxAgentStepsPerCoordinatorStep.toString() }
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
    // Intent Recognizer originals
    val originalIntentRecognizerEnabled = remember { prefs.intentRecognizerEnabled }
    val originalIntentApiUrl = remember { prefs.intentApiUrl }
    val originalIntentApiKey = remember {
        val model = prefs.intentModelName
        when {
            model.startsWith("deepseek") -> prefs.intentApiKeyDeepseek
            model.startsWith("glm-") -> prefs.intentApiKeyBigmodel
            model.startsWith("doubao") -> prefs.intentApiKeyDoubao
            else -> prefs.intentApiKey
        }
    }
    val originalIntentModelName = remember { prefs.intentModelName }

    var apiUrl by remember { mutableStateOf(TextFieldValue(prefs.apiUrl)) }
    var apiKey by remember { mutableStateOf(TextFieldValue(prefs.apiKey)) }
    var modelName by remember { mutableStateOf(TextFieldValue(prefs.modelName)) }
    // Agent API URL 下拉选择状态
    var apiUrlDropdownExpanded by remember { mutableStateOf(false) }
    // Agent /models 获取模型列表状态
    var agentFetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var agentFetchingModels by remember { mutableStateOf(false) }
    var agentSystemPrompt by remember { mutableStateOf(TextFieldValue(prefs.agentSystemPrompt)) }
    var porcupineKey by remember { mutableStateOf(TextFieldValue(prefs.porcupineAccessKey)) }
    var wakeWordKeyword by remember { mutableStateOf(prefs.wakeWordKeyword) }
    var wakeWordDropdownExpanded by remember { mutableStateOf(false) }
    var wakeEngineType by remember { mutableStateOf(prefs.wakeEngineType) }
    var wakeEngineDropdownExpanded by remember { mutableStateOf(false) }
    // 自定义 ppn 模型文件状态
    var customPpnName by remember { mutableStateOf(prefs.customPpnName) }
    var customPpnPath by remember { mutableStateOf(prefs.customPpnPath) }
    // ppn 文件选择器
    val ppnFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            try {
                // 步骤1: 读取用户选择的 ppn 文件
                val inputStream = context.contentResolver.openInputStream(uri)
                val fileName = run {
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) c.getString(nameIndex) else null
                        } else null
                    }
                } ?: "custom_wake_word.ppn"

                // 步骤2: 复制到应用内部存储
                val destFile = java.io.File(context.filesDir, "custom_ppn/$fileName")
                destFile.parentFile?.mkdirs()
                inputStream?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // 步骤3: 保存路径到 preferences
                customPpnName = fileName
                customPpnPath = destFile.absolutePath
                wakeWordKeyword = "CUSTOM"  // 自动切换到自定义模型

                android.util.Log.i("AutoGLM", "自定义 ppn 模型已导入: $fileName -> ${destFile.absolutePath}")
                android.widget.Toast.makeText(context, "模型文件已导入: $fileName", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("AutoGLM", "导入 ppn 文件失败: ${e.message}", e)
                android.widget.Toast.makeText(context, "导入失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== 配置导入/导出文件选择器 =====
    // 导出：将所有 SharedPreferences 序列化为 JSON 写入用户选择的文件
    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            try {
                val jsonString = prefs.exportToJson()
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(jsonString.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, if (prefs.language == "cn") "配置已导出" else "Config exported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("AutoGLM", "导出配置失败: ${e.message}", e)
                Toast.makeText(context, if (prefs.language == "cn") "导出失败: ${e.message}" else "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // 导入：从用户选择的 JSON 文件读取配置并写入 SharedPreferences，然后重建页面
    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            try {
                val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                } ?: throw IllegalStateException("无法读取文件")
                val count = prefs.importFromJson(jsonString)
                Toast.makeText(
                    context,
                    if (prefs.language == "cn") "已导入 $count 项配置，页面即将刷新" else "Imported $count items, refreshing...",
                    Toast.LENGTH_SHORT
                ).show()
                // 重建 Activity 以刷新所有 remember 状态
                (context as? Activity)?.recreate()
            } catch (e: Exception) {
                android.util.Log.e("AutoGLM", "导入配置失败: ${e.message}", e)
                Toast.makeText(context, if (prefs.language == "cn") "导入失败: ${e.message}" else "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var sttWakePhrase by remember { mutableStateOf(TextFieldValue(prefs.sttWakePhrase)) }
    // 唤醒后语音识别模式配置
    var commandSttMode by remember { mutableStateOf(prefs.commandSttMode) }
    var commandSttModeDropdownExpanded by remember { mutableStateOf(false) }
    var sttApiType by remember { mutableStateOf(prefs.sttApiType) }
    var sttApiTypeDropdownExpanded by remember { mutableStateOf(false) }
    var sttApiUrl by remember { mutableStateOf(TextFieldValue(prefs.sttApiUrl)) }
    var sttApiKey by remember { mutableStateOf(TextFieldValue(prefs.sttApiKey)) }
    var sttModelName by remember { mutableStateOf(TextFieldValue(prefs.sttModelName)) }
    // 阿里 NLS 配置
    var aliNlsAkId by remember { mutableStateOf(TextFieldValue(prefs.aliNlsAkId)) }
    var aliNlsAkSecret by remember { mutableStateOf(TextFieldValue(prefs.aliNlsAkSecret)) }
    var aliNlsAppKey by remember { mutableStateOf(TextFieldValue(prefs.aliNlsAppKey)) }
    // IME 语音输入坐标配置
    var imeVoiceSpaceX by remember { mutableStateOf(TextFieldValue(prefs.imeVoiceSpaceX.toString())) }
    var imeVoiceSpaceY by remember { mutableStateOf(TextFieldValue(prefs.imeVoiceSpaceY.toString())) }
    // 息屏唤醒 — 锁屏密码（加密存储，初始化时解密读取）
    var lockScreenPassword by remember {
        mutableStateOf(TextFieldValue(
            com.autoglm.assistant.util.SecureStorage.getDecrypted(
                context, com.autoglm.assistant.util.ScreenUnlocker.SECURE_KEY_LOCK_PASSWORD
            )
        ))
    }
    var lockPasswordVisible by remember { mutableStateOf(false) }
    var maxSteps by remember { mutableStateOf(TextFieldValue(prefs.maxSteps.toString())) }
    var language by remember { mutableStateOf(prefs.language) }
    var showAgentProcess by remember { mutableStateOf(prefs.showAgentProcess) }
    var smartCoordinatorEnabled by remember { mutableStateOf(prefs.smartCoordinatorEnabled) }
    var showCoordinatorThinking by remember { mutableStateOf(prefs.showCoordinatorThinking) }
    var coordinatorApiUrl by remember { mutableStateOf(TextFieldValue(prefs.coordinatorApiUrl)) }
    var coordinatorApiKey by remember {
        val model = prefs.coordinatorModelName
        mutableStateOf(
            TextFieldValue(
                when {
                    model.startsWith("deepseek") -> prefs.coordinatorApiKeyDeepseek
                    model.startsWith("glm-") -> prefs.coordinatorApiKeyBigmodel
                    model.startsWith("doubao") -> prefs.coordinatorApiKeyDoubao
                    else -> prefs.coordinatorApiKey
                }
            )
        )
    }
    var coordinatorModelName by remember { mutableStateOf(TextFieldValue(prefs.coordinatorModelName)) }
    var coordinatorSystemPrompt by remember { mutableStateOf(TextFieldValue(prefs.coordinatorSystemPrompt)) }
    var coordinatorModelDropdownExpanded by remember { mutableStateOf(false) }
    var coordinatorApiUrlDropdownExpanded by remember { mutableStateOf(false) }
    var coordinatorFetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var coordinatorFetchingModels by remember { mutableStateOf(false) }
    var coordinatorEnableVision by remember { mutableStateOf(prefs.coordinatorEnableVision) }
    var coordinatorEnableThinking by remember { mutableStateOf(prefs.coordinatorEnableThinking) }
    var supervisionEnabled by remember { mutableStateOf(prefs.supervisionEnabled) }
    var maxCorrections by remember { mutableStateOf(TextFieldValue(prefs.maxCorrections.toString())) }
    var maxCoordinatorSteps by remember { mutableStateOf(TextFieldValue(prefs.maxCoordinatorSteps.toString())) }
    var maxAgentStepsPerCoordinatorStep by remember {
        mutableStateOf(TextFieldValue(prefs.maxAgentStepsPerCoordinatorStep.toString()))
    }
    // Prompt Optimizer states
    var promptOptimizerEnabled by remember { mutableStateOf(prefs.promptOptimizerEnabled) }
    var optimizerApiUrl by remember { mutableStateOf(TextFieldValue(prefs.optimizerApiUrl)) }
    var optimizerApiKey by remember {
        val model = prefs.optimizerModelName
        mutableStateOf(
            TextFieldValue(
                when {
                    model.startsWith("deepseek") -> prefs.optimizerApiKeyDeepseek
                    model.startsWith("glm-") -> prefs.optimizerApiKeyBigmodel
                    model.startsWith("doubao") -> prefs.optimizerApiKeyDoubao
                    else -> prefs.optimizerApiKey
                }
            )
        )
    }
    var optimizerModelName by remember { mutableStateOf(TextFieldValue(prefs.optimizerModelName)) }
    var optimizerModelDropdownExpanded by remember { mutableStateOf(false) }
    var optimizerApiUrlDropdownExpanded by remember { mutableStateOf(false) }
    var optimizerFetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var optimizerFetchingModels by remember { mutableStateOf(false) }
    var taskSummaryEnabled by remember { mutableStateOf(prefs.taskSummaryEnabled) }
    var optimizerSystemPrompt by remember { mutableStateOf(TextFieldValue(prefs.optimizerSystemPrompt)) }
    // Intent Recognizer states
    var intentRecognizerEnabled by remember { mutableStateOf(prefs.intentRecognizerEnabled) }
    var intentApiUrl by remember { mutableStateOf(TextFieldValue(prefs.intentApiUrl)) }
    var intentApiKey by remember {
        val model = prefs.intentModelName
        mutableStateOf(
            TextFieldValue(
                when {
                    model.startsWith("deepseek") -> prefs.intentApiKeyDeepseek
                    model.startsWith("glm-") -> prefs.intentApiKeyBigmodel
                    model.startsWith("doubao") -> prefs.intentApiKeyDoubao
                    else -> prefs.intentApiKey
                }
            )
        )
    }
    var intentModelName by remember { mutableStateOf(TextFieldValue(prefs.intentModelName)) }
    var intentModelDropdownExpanded by remember { mutableStateOf(false) }
    var intentApiUrlDropdownExpanded by remember { mutableStateOf(false) }
    // /models API 获取的模型列表
    var intentFetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var intentFetchingModels by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }

    // Check if any setting has changed
    val hasChanges = apiUrl.text != originalApiUrl ||
            apiKey.text != originalApiKey ||
            modelName.text != originalModelName ||
            agentSystemPrompt.text != originalAgentSystemPrompt ||
            porcupineKey.text != originalPorcupineKey ||
            wakeWordKeyword != originalWakeWordKeyword ||
            wakeEngineType != originalWakeEngineType ||
            sttWakePhrase.text != originalSttWakePhrase ||
            commandSttMode != originalCommandSttMode ||
            sttApiType != originalSttApiType ||
            sttApiUrl.text != originalSttApiUrl ||
            sttApiKey.text != originalSttApiKey ||
            sttModelName.text != originalSttModelName ||
            aliNlsAkId.text != originalAliNlsAkId ||
            aliNlsAkSecret.text != originalAliNlsAkSecret ||
            aliNlsAppKey.text != originalAliNlsAppKey ||
            imeVoiceSpaceX.text != originalImeVoiceSpaceX ||
            imeVoiceSpaceY.text != originalImeVoiceSpaceY ||
            lockScreenPassword.text != originalLockScreenPassword ||
            maxSteps.text != originalMaxSteps ||
            language != originalLanguage ||
            showAgentProcess != originalShowAgentProcess ||
            smartCoordinatorEnabled != originalSmartCoordinatorEnabled ||
            showCoordinatorThinking != originalShowCoordinatorThinking ||
            coordinatorApiUrl.text != originalCoordinatorApiUrl ||
            coordinatorApiKey.text != originalCoordinatorApiKey ||
            coordinatorModelName.text != originalCoordinatorModelName ||
            coordinatorEnableVision != originalCoordinatorEnableVision ||
            coordinatorEnableThinking != originalCoordinatorEnableThinking ||
            coordinatorSystemPrompt.text != originalCoordinatorSystemPrompt ||
            supervisionEnabled != originalSupervisionEnabled ||
            maxCorrections.text != originalMaxCorrections ||
            maxCoordinatorSteps.text != originalMaxCoordinatorSteps ||
            maxAgentStepsPerCoordinatorStep.text != originalMaxAgentStepsPerCoordinatorStep ||
            promptOptimizerEnabled != originalPromptOptimizerEnabled ||
            optimizerApiUrl.text != originalOptimizerApiUrl ||
            optimizerApiKey.text != originalOptimizerApiKey ||
            optimizerModelName.text != originalOptimizerModelName ||
            taskSummaryEnabled != originalTaskSummaryEnabled ||
            optimizerSystemPrompt.text != originalOptimizerSystemPrompt ||
            intentRecognizerEnabled != originalIntentRecognizerEnabled ||
            intentApiUrl.text != originalIntentApiUrl ||
            intentApiKey.text != originalIntentApiKey ||
            intentModelName.text != originalIntentModelName

    // Available wake words（含自定义模型选项）
    val availableWakeWords = listOf(
        "XIAOAI" to "小爱",
        "CUSTOM" to "自定义模型",
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
        val maxStepsLabel = if (isChinese) "Agent 总操作步数上限" else "Agent Max Operation Steps"
        val languageLabel = if (isChinese) "语言: " else "Language: "
        val chinese = "中文"
        val english = "English"
        val showProcess = if (isChinese) "显示执行过程" else "Show Agent Process"
        val showProcessDesc = if (isChinese) "在对话中显示思考和操作步骤" else "Display thinking and action steps in chat"
        val smartCoordinatorSettings = if (isChinese) "智能协调器设置" else "Smart Coordinator Settings"
        val enableSmartCoordinator = if (isChinese) "默认启用规划" else "Enable Planning by Default"
        val smartCoordinatorDesc = if (isChinese) "新建快捷指令和手动输入任务时默认启用规划（各任务可独立控制）" else "Enable planning by default for new shortcuts and manual tasks (each task can be controlled independently)"
        val showCoordinatorThinking = if (isChinese) "显示协调器思考正文" else "Show Coordinator Thinking Details"
        val showCoordinatorThinkingDesc = if (isChinese) "关闭后不在对话里显示协调器思考全文，仅保留下方状态提示" else "Hide coordinator thinking text in chat and keep only bottom status hint"
        val coordinatorApiUrlLabel = if (isChinese) "协调器 API URL" else "Coordinator API URL"
        val coordinatorApiKeyLabel = if (isChinese) "协调器 API Key" else "Coordinator API Key"
        val coordinatorModelLabel = if (isChinese) "协调器模型" else "Coordinator Model"
        val coordinatorEnableThinkingLabel = if (isChinese) "启用模型思考/推理" else "Enable Model Thinking/Reasoning"
        val coordinatorEnableThinkingDesc = if (isChinese) {
            "仅对部分模型生效（如 DeepSeek/Qwen/豆包），关闭可减少推理输出"
        } else {
            "Only works for some models (e.g. DeepSeek/Qwen/Doubao). Disable to reduce reasoning output"
        }
        val enableSupervision = if (isChinese) "启用执行监督" else "Enable Supervision"
        val supervisionDesc = if (isChinese) "检查每个子任务的执行结果" else "Check execution result of each subtask"
        val maxCorrectionsLabel = if (isChinese) "最大纠正次数（已废弃）" else "Max Corrections (Deprecated)"
        val maxCoordinatorStepsLabel = if (isChinese) "协调器最大决策轮次" else "Max Coordinator Decision Rounds"
        val maxAgentStepsPerCoordinatorStepLabel = if (isChinese) {
            "单轮协调内 Agent 最大决策轮次"
        } else {
            "Max Agent Rounds Per Coordinator Step"
        }
        // Prompt Optimizer strings
        val promptOptimizerSettings = if (isChinese) "指令优化器设置" else "Prompt Optimizer Settings"
        val enablePromptOptimizer = if (isChinese) "启用指令优化器" else "Enable Prompt Optimizer"
        val promptOptimizerDesc = if (isChinese) "将简短指令扩展为详细任务描述" else "Expand short instructions into detailed task descriptions"
        val optimizerApiUrlLabel = if (isChinese) "优化器 API URL" else "Optimizer API URL"
        val optimizerApiKeyLabel = if (isChinese) "优化器 API Key" else "Optimizer API Key"
        val optimizerModelLabel = if (isChinese) "优化器模型" else "Optimizer Model"
        val unsavedChanges = if (isChinese) "未保存的更改" else "Unsaved Changes"
        val intentRecognizerSettings = if (isChinese) "意图识别设置" else "Intent Recognition Settings"
        val enableIntentRecognizer = if (isChinese) "启用意图识别" else "Enable Intent Recognition"
        val intentRecognizerDesc = if (isChinese) "自动将自然语言匹配到快捷指令" else "Auto-match natural language to shortcut commands"
        val intentApiUrlLabel = if (isChinese) "识别器 API URL" else "Recognizer API URL"
        val intentApiKeyLabel = if (isChinese) "识别器 API Key" else "Recognizer API Key"
        val intentModelLabel = if (isChinese) "识别器模型" else "Recognizer Model"
        val fetchModelsLabel = if (isChinese) "获取模型列表" else "Fetch Models"
        val fetchingModelsLabel = if (isChinese) "获取中..." else "Fetching..."
        val unsavedChangesMsg = if (isChinese) "是否保存更改？" else "Do you want to save changes?"
        val saveBtn = if (isChinese) "保存" else "Save"
        val discardBtn = if (isChinese) "不保存" else "Discard"
        val cancelBtn = if (isChinese) "取消" else "Cancel"
    }

    // Save function
    val saveSettings = {
        // 检测关键配置是否变化（影响 Agent/Coordinator/Optimizer）
        val needsRestart = apiUrl.text != originalApiUrl ||
                apiKey.text != originalApiKey ||
                modelName.text != originalModelName ||
                agentSystemPrompt.text != originalAgentSystemPrompt ||
                language != originalLanguage ||
                maxSteps.text != originalMaxSteps ||
                smartCoordinatorEnabled != originalSmartCoordinatorEnabled ||
                coordinatorApiUrl.text != originalCoordinatorApiUrl ||
                coordinatorApiKey.text != originalCoordinatorApiKey ||
                coordinatorModelName.text != originalCoordinatorModelName ||
            coordinatorEnableVision != originalCoordinatorEnableVision ||
                coordinatorEnableThinking != originalCoordinatorEnableThinking ||
                coordinatorSystemPrompt.text != originalCoordinatorSystemPrompt ||
                supervisionEnabled != originalSupervisionEnabled ||
                maxCorrections.text != originalMaxCorrections ||
                maxCoordinatorSteps.text != originalMaxCoordinatorSteps ||
                maxAgentStepsPerCoordinatorStep.text != originalMaxAgentStepsPerCoordinatorStep ||
                promptOptimizerEnabled != originalPromptOptimizerEnabled ||
                optimizerApiUrl.text != originalOptimizerApiUrl ||
                optimizerApiKey.text != originalOptimizerApiKey ||
                optimizerModelName.text != originalOptimizerModelName ||
                taskSummaryEnabled != originalTaskSummaryEnabled ||
                optimizerSystemPrompt.text != originalOptimizerSystemPrompt ||
                intentRecognizerEnabled != originalIntentRecognizerEnabled ||
                intentApiUrl.text != originalIntentApiUrl ||
                intentApiKey.text != originalIntentApiKey ||
                intentModelName.text != originalIntentModelName

        // 保存所有设置
        prefs.apiUrl = apiUrl.text
        prefs.apiKey = apiKey.text
        prefs.modelName = modelName.text
        prefs.agentSystemPrompt = agentSystemPrompt.text
        prefs.wakeEngineType = wakeEngineType
        prefs.porcupineAccessKey = porcupineKey.text
        prefs.wakeWordKeyword = wakeWordKeyword
        prefs.customPpnPath = customPpnPath
        prefs.customPpnName = customPpnName
        prefs.sttWakePhrase = sttWakePhrase.text
        // 唤醒后语音识别模式配置
        prefs.commandSttMode = commandSttMode
        prefs.sttApiType = sttApiType
        prefs.sttApiUrl = sttApiUrl.text
        prefs.sttApiKey = sttApiKey.text
        prefs.sttModelName = sttModelName.text
        // 阿里 NLS 配置
        prefs.aliNlsAkId = aliNlsAkId.text
        prefs.aliNlsAkSecret = aliNlsAkSecret.text
        prefs.aliNlsAppKey = aliNlsAppKey.text
        // IME 语音坐标
        prefs.imeVoiceSpaceX = imeVoiceSpaceX.text.toIntOrNull() ?: PreferenceManager.DEFAULT_IME_VOICE_SPACE_X
        prefs.imeVoiceSpaceY = imeVoiceSpaceY.text.toIntOrNull() ?: PreferenceManager.DEFAULT_IME_VOICE_SPACE_Y
        // 锁屏密码 → 加密存储（不经过普通 SharedPreferences）
        com.autoglm.assistant.util.SecureStorage.putEncrypted(
            context, com.autoglm.assistant.util.ScreenUnlocker.SECURE_KEY_LOCK_PASSWORD,
            lockScreenPassword.text
        )
        prefs.maxSteps = maxSteps.text.toIntOrNull() ?: 100
        prefs.language = language
        prefs.showAgentProcess = showAgentProcess
        prefs.smartCoordinatorEnabled = smartCoordinatorEnabled
        prefs.showCoordinatorThinking = showCoordinatorThinking
        prefs.coordinatorApiUrl = coordinatorApiUrl.text
        // Save coordinator API key to provider-specific slot
        when {
            coordinatorModelName.text.startsWith("deepseek") -> prefs.coordinatorApiKeyDeepseek = coordinatorApiKey.text
            coordinatorModelName.text.startsWith("glm-") -> prefs.coordinatorApiKeyBigmodel = coordinatorApiKey.text
            coordinatorModelName.text.startsWith("doubao") -> prefs.coordinatorApiKeyDoubao = coordinatorApiKey.text
            else -> prefs.coordinatorApiKey = coordinatorApiKey.text
        }
        prefs.coordinatorSystemPrompt = coordinatorSystemPrompt.text
        prefs.coordinatorModelName = coordinatorModelName.text
        prefs.coordinatorEnableVision = coordinatorEnableVision
        prefs.coordinatorEnableThinking = coordinatorEnableThinking
        prefs.supervisionEnabled = supervisionEnabled
        prefs.maxCorrections = maxCorrections.text.toIntOrNull() ?: 2
        prefs.maxCoordinatorSteps = maxCoordinatorSteps.text.toIntOrNull() ?: 20
        prefs.maxAgentStepsPerCoordinatorStep =
            maxAgentStepsPerCoordinatorStep.text.toIntOrNull() ?: 10
        // Prompt Optimizer settings
        prefs.promptOptimizerEnabled = promptOptimizerEnabled
        prefs.optimizerApiUrl = optimizerApiUrl.text
        // Save optimizer API key to provider-specific slot
        when {
            optimizerModelName.text.startsWith("deepseek") -> prefs.optimizerApiKeyDeepseek = optimizerApiKey.text
            optimizerModelName.text.startsWith("glm-") -> prefs.optimizerApiKeyBigmodel = optimizerApiKey.text
            optimizerModelName.text.startsWith("doubao") -> prefs.optimizerApiKeyDoubao = optimizerApiKey.text
            else -> prefs.optimizerApiKey = optimizerApiKey.text
        }
        prefs.optimizerModelName = optimizerModelName.text
        prefs.taskSummaryEnabled = taskSummaryEnabled
        prefs.optimizerSystemPrompt = optimizerSystemPrompt.text
        // Intent Recognizer settings
        prefs.intentRecognizerEnabled = intentRecognizerEnabled
        prefs.intentApiUrl = intentApiUrl.text
        // Save intent API key to provider-specific slot
        when {
            intentModelName.text.startsWith("deepseek") -> prefs.intentApiKeyDeepseek = intentApiKey.text
            intentModelName.text.startsWith("glm-") -> prefs.intentApiKeyBigmodel = intentApiKey.text
            intentModelName.text.startsWith("doubao") -> prefs.intentApiKeyDoubao = intentApiKey.text
            else -> prefs.intentApiKey = intentApiKey.text
        }
        prefs.intentModelName = intentModelName.text

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

    // Handle system back button — 有变更时弹出确认+变更列表
    androidx.activity.compose.BackHandler(enabled = true) {
        if (hasChanges) {
            showExitDialog = true
        } else {
            onBack()
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
                },
                actions = {
                    // 导入配置：从 JSON 文件加载配置
                    TextButton(onClick = {
                        importFileLauncher.launch("application/json")
                    }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isChinese) "导入" else "Import", fontSize = 12.sp)
                    }
                    // 导出配置：保存当前配置为 JSON 文件
                    TextButton(onClick = {
                        // 先将当前界面编辑保存到 SharedPreferences，再触发文件选择
                        saveSettings()
                        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                            .format(java.util.Date())
                        exportFileLauncher.launch("autoglm_config_$timestamp.json")
                    }) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isChinese) "导出" else "Export", fontSize = 12.sp)
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

            // Agent API URL 下拉选择 + 自定义输入
            val presetApiUrls = listOf(
                "https://api.deepseek.com/v1" to "DeepSeek",
                "https://open.bigmodel.cn/api/paas/v4" to "智谱 BigModel",
                "https://ark.cn-beijing.volces.com/api/v3" to "豆包 Doubao",
                "https://api.openai.com/v1" to "OpenAI",
                "https://dashscope.aliyuncs.com/compatible-mode/v1" to "通义千问"
            )
            ExposedDropdownMenuBox(
                expanded = apiUrlDropdownExpanded,
                onExpandedChange = { apiUrlDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = apiUrl,
                    onValueChange = { apiUrl = it },
                    label = { Text(strings.apiUrlLabel) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = apiUrlDropdownExpanded) }
                )
                ExposedDropdownMenu(
                    expanded = apiUrlDropdownExpanded,
                    onDismissRequest = { apiUrlDropdownExpanded = false }
                ) {
                    presetApiUrls.forEach { (url, label) ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                    Text(url, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                apiUrl = TextFieldValue(url)
                                apiUrlDropdownExpanded = false
                            }
                        )
                    }
                }
            }

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

            // Agent 从 /models API 获取模型列表
            val agentCoroutineScope = rememberCoroutineScope()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        agentFetchingModels = true
                        agentCoroutineScope.launch {
                            val models = com.autoglm.assistant.ai.ModelApiHelper.fetchModels(
                                apiUrl.text, apiKey.text
                            )
                            agentFetchedModels = models
                            agentFetchingModels = false
                        }
                    },
                    enabled = !agentFetchingModels && apiUrl.text.isNotBlank() && apiKey.text.isNotBlank()
                ) {
                    if (agentFetchingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (agentFetchingModels) strings.fetchingModelsLabel else strings.fetchModelsLabel)
                }
                if (agentFetchedModels.isNotEmpty()) {
                    Text(
                        "${agentFetchedModels.size} ${if (isChinese) "个模型" else "models"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 显示从 API 获取的 Agent 模型列表（可选择）
            if (agentFetchedModels.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    agentFetchedModels.forEach { model ->
                        FilterChip(
                            selected = modelName.text == model,
                            onClick = { modelName = TextFieldValue(model) },
                            label = { Text(model, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            // 自定义 Agent 系统提示词
            var showAgentPromptEditor by remember { mutableStateOf(false) }
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
                            if (isChinese) "自定义 Agent 提示词" else "Custom Agent Prompt",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (agentSystemPrompt.text.isBlank()) {
                                if (isChinese) "当前使用内置默认提示词" else "Using built-in default"
                            } else {
                                if (isChinese) "已配置（${agentSystemPrompt.text.length}字）" else "Configured (${agentSystemPrompt.text.length} chars)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        if (agentSystemPrompt.text.isNotBlank()) {
                            TextButton(onClick = { agentSystemPrompt = TextFieldValue("") }) {
                                Text(if (isChinese) "清空重置" else "Clear")
                            }
                        }
                        TextButton(onClick = {
                            val defaultPrompt = if (isChinese) com.autoglm.assistant.ai.MessageBuilder.DEFAULT_SYSTEM_PROMPT_CN else com.autoglm.assistant.ai.MessageBuilder.DEFAULT_SYSTEM_PROMPT_EN
                            agentSystemPrompt = TextFieldValue(defaultPrompt)
                            showAgentPromptEditor = true
                        }) {
                            Text(if (isChinese) "加载默认模板" else "Load Template")
                        }
                        TextButton(onClick = { showAgentPromptEditor = !showAgentPromptEditor }) {
                            Text(if (showAgentPromptEditor) {
                                if (isChinese) "收起" else "Collapse"
                            } else {
                                if (isChinese) "编辑" else "Edit"
                            })
                        }
                    }
                }
                if (showAgentPromptEditor) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = agentSystemPrompt,
                        onValueChange = { agentSystemPrompt = it },
                        label = { Text(if (isChinese) "系统提示词（留空使用默认）" else "System Prompt (empty = default)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 150.dp, max = 400.dp),
                        maxLines = 20,
                        supportingText = {
                            Text(
                                if (isChinese) "自定义 Agent 的系统角色设定，留空则使用内置的默认提示词"
                                else "Customize Agent system role. Leave empty to use built-in default"
                            )
                        }
                    )
                }
            }

            Divider()

            Text(strings.voiceSettings, style = MaterialTheme.typography.titleMedium)

            // 唤醒引擎选择
            ExposedDropdownMenuBox(
                expanded = wakeEngineDropdownExpanded,
                onExpandedChange = { wakeEngineDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = when (wakeEngineType) {
                        "PORCUPINE" -> "Porcupine (需要 API Key)"
                        "STT_SYSTEM" -> "系统 STT (免费)"
                        else -> wakeEngineType
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(if (isChinese) "唤醒引擎" else "Wake Engine") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = wakeEngineDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    supportingText = { Text(if (isChinese) "选择语音唤醒引擎" else "Select wake engine") }
                )
                ExposedDropdownMenu(
                    expanded = wakeEngineDropdownExpanded,
                    onDismissRequest = { wakeEngineDropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Porcupine (需要 API Key)") },
                        onClick = {
                            wakeEngineType = "PORCUPINE"
                            wakeEngineDropdownExpanded = false
                        },
                        leadingIcon = if (wakeEngineType == "PORCUPINE") {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null
                    )
                    DropdownMenuItem(
                        text = { Text("系统 STT (免费)") },
                        onClick = {
                            wakeEngineType = "STT_SYSTEM"
                            wakeEngineDropdownExpanded = false
                        },
                        leadingIcon = if (wakeEngineType == "STT_SYSTEM") {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null
                    )
                }
            }

            // Porcupine 配置（仅在选择 Porcupine 时显示）
            if (wakeEngineType == "PORCUPINE") {
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
            }

            // 自定义模型上传区域（当选择"自定义模型"时显示）
            if (wakeEngineType == "PORCUPINE" && wakeWordKeyword == "CUSTOM") {
                // 显示当前自定义模型文件信息
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            if (isChinese) "自定义唤醒词模型" else "Custom Wake Word Model",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (customPpnName.isNotBlank()) {
                            Text(
                                "${if (isChinese) "当前模型" else "Current"}: $customPpnName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Button(
                            onClick = { ppnFileLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isChinese) "选择 .ppn 模型文件" else "Select .ppn Model File")
                        }
                        Text(
                            if (isChinese) "从 Picovoice Console 训练并下载的 .ppn 文件" else "Upload .ppn file trained from Picovoice Console",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // STT 配置（仅在选择 STT_SYSTEM 时显示）
            if (wakeEngineType == "STT_SYSTEM") {
                OutlinedTextField(
                    value = sttWakePhrase,
                    onValueChange = { sttWakePhrase = it },
                    label = { Text(if (isChinese) "STT 唤醒词" else "STT Wake Phrase") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text(if (isChinese) "使用系统 STT 时的唤醒词（免费，无需 API Key）" else "Wake phrase for System STT (free, no API key required)") }
                )
            }

            Divider()

            // ─── 唤醒后语音识别配置 ───
            Text(
                if (isChinese) "语音识别设置" else "Speech Recognition Settings",
                style = MaterialTheme.typography.titleMedium
            )

            // STT 模式下拉选择
            ExposedDropdownMenuBox(
                expanded = commandSttModeDropdownExpanded,
                onExpandedChange = { commandSttModeDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = when (commandSttMode) {
                        "API" -> if (isChinese) "API 模式 (AudioRecord + Whisper)" else "API Mode (AudioRecord + Whisper)"
                        "SYSTEM" -> if (isChinese) "系统模式 (SpeechRecognizer)" else "System Mode (SpeechRecognizer)"
                        else -> commandSttMode
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(if (isChinese) "语音识别模式" else "STT Mode") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = commandSttModeDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    supportingText = { Text(if (isChinese) "唤醒后用哪种方式进行语音识别" else "How to recognize speech after wake word") }
                )
                ExposedDropdownMenu(
                    expanded = commandSttModeDropdownExpanded,
                    onDismissRequest = { commandSttModeDropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(if (isChinese) "API 模式" else "API Mode")
                                Text(
                                    if (isChinese) "直接录音 + OpenAI Whisper 兼容 API，MIUI 等定制 ROM 推荐" else "Direct recording + OpenAI Whisper API, recommended for MIUI",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            commandSttMode = "API"
                            commandSttModeDropdownExpanded = false
                        },
                        leadingIcon = if (commandSttMode == "API") {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(if (isChinese) "系统模式" else "System Mode")
                                Text(
                                    if (isChinese) "使用系统 SpeechRecognizer，免费但部分 ROM 受限" else "Uses system SpeechRecognizer, free but limited on some ROMs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            commandSttMode = "SYSTEM"
                            commandSttModeDropdownExpanded = false
                        },
                        leadingIcon = if (commandSttMode == "SYSTEM") {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null
                    )
                }
            }

            // API 模式配置（仅在选择 API 时显示）
            if (commandSttMode == "API") {

                // STT API 类型选择
                ExposedDropdownMenuBox(
                    expanded = sttApiTypeDropdownExpanded,
                    onExpandedChange = { sttApiTypeDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = when (sttApiType) {
                            "ALI_NLS" -> if (isChinese) "阿里云 NLS 一句话识别" else "Alibaba Cloud NLS"
                            "OPENAI" -> if (isChinese) "OpenAI Whisper 兼容" else "OpenAI Whisper Compatible"
                            "IME_VOICE" -> if (isChinese) "输入法语音（豆包等）" else "IME Voice (Doubao etc.)"
                            else -> sttApiType
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(if (isChinese) "STT 服务商" else "STT Provider") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sttApiTypeDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        supportingText = { Text(if (isChinese) "选择语音识别 API 服务商" else "Select STT API provider") }
                    )
                    ExposedDropdownMenu(
                        expanded = sttApiTypeDropdownExpanded,
                        onDismissRequest = { sttApiTypeDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(if (isChinese) "阿里云 NLS" else "Alibaba Cloud NLS")
                                    Text(
                                        if (isChinese) "一句话识别，国内网络推荐" else "One-sentence recognition, recommended in China",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                sttApiType = "ALI_NLS"
                                sttApiTypeDropdownExpanded = false
                            },
                            leadingIcon = if (sttApiType == "ALI_NLS") {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(if (isChinese) "OpenAI Whisper 兼容" else "OpenAI Whisper Compatible")
                                    Text(
                                        if (isChinese) "支持 Whisper、SenseVoice 等兼容端点" else "Supports Whisper, SenseVoice compatible endpoints",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                sttApiType = "OPENAI"
                                sttApiTypeDropdownExpanded = false
                            },
                            leadingIcon = if (sttApiType == "OPENAI") {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(if (isChinese) "输入法语音（豆包等）" else "IME Voice (Doubao etc.)")
                                    Text(
                                        if (isChinese) "通过模拟长按空格键触发输入法语音，免费" else "Triggers IME voice via simulated long-press on space bar, free",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                sttApiType = "IME_VOICE"
                                sttApiTypeDropdownExpanded = false
                            },
                            leadingIcon = if (sttApiType == "IME_VOICE") {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null
                        )
                    }
                }

                // IME 语音配置（仅在选择 IME_VOICE 时显示）
                if (sttApiType == "IME_VOICE") {
                    OutlinedTextField(
                        value = imeVoiceSpaceX,
                        onValueChange = { imeVoiceSpaceX = it },
                        label = { Text(if (isChinese) "空格键 X 坐标" else "Space Bar X") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(if (isChinese) "输入法空格键中心的屏幕 X 坐标（像素）" else "Screen X coordinate of space bar center (px)") }
                    )
                    OutlinedTextField(
                        value = imeVoiceSpaceY,
                        onValueChange = { imeVoiceSpaceY = it },
                        label = { Text(if (isChinese) "空格键 Y 坐标" else "Space Bar Y") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(if (isChinese) "输入法空格键中心的屏幕 Y 坐标（像素）" else "Screen Y coordinate of space bar center (px)") }
                    )
                }

                // 阿里 NLS 配置（仅在选择 ALI_NLS 时显示）
                if (sttApiType == "ALI_NLS") {
                    OutlinedTextField(
                        value = aliNlsAkId,
                        onValueChange = { aliNlsAkId = it },
                        label = { Text("AccessKey ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(if (isChinese) "阿里云 AccessKey ID" else "Alibaba Cloud AccessKey ID") }
                    )
                    OutlinedTextField(
                        value = aliNlsAkSecret,
                        onValueChange = { aliNlsAkSecret = it },
                        label = { Text("AccessKey Secret") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (aliNlsAkSecret.text.length > 8)
                            PasswordVisualTransformation() else VisualTransformation.None,
                        supportingText = { Text(if (isChinese) "阿里云 AccessKey Secret" else "Alibaba Cloud AccessKey Secret") }
                    )
                    OutlinedTextField(
                        value = aliNlsAppKey,
                        onValueChange = { aliNlsAppKey = it },
                        label = { Text("AppKey") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(if (isChinese) "NLS 应用 AppKey（从控制台获取）" else "NLS Application AppKey (from console)") }
                    )
                }

                // OpenAI 兼容 API 配置（仅在选择 OPENAI 时显示）
                if (sttApiType == "OPENAI") {
                    OutlinedTextField(
                        value = sttApiUrl,
                        onValueChange = { sttApiUrl = it },
                        label = { Text(if (isChinese) "STT API 地址" else "STT API URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(if (isChinese) "OpenAI 兼容的语音识别 API 基础地址" else "OpenAI-compatible STT API base URL") }
                    )
                    OutlinedTextField(
                        value = sttApiKey,
                        onValueChange = { sttApiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (sttApiKey.text.length > 8)
                            PasswordVisualTransformation() else VisualTransformation.None,
                        supportingText = { Text(if (isChinese) "语音识别服务的 API Key" else "API Key for STT service") }
                    )
                    OutlinedTextField(
                        value = sttModelName,
                        onValueChange = { sttModelName = it },
                        label = { Text(if (isChinese) "STT 模型名称" else "STT Model Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(if (isChinese) "如 whisper-1、FunAudioLLM/SenseVoiceSmall 等" else "e.g. whisper-1, FunAudioLLM/SenseVoiceSmall") }
                    )
                }
            }

            Divider()

            // ─── 息屏唤醒设置 ───
            Text(
                if (isChinese) "息屏唤醒设置" else "Screen-off Wake Settings",
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedTextField(
                value = lockScreenPassword,
                onValueChange = { lockScreenPassword = it },
                label = { Text(if (isChinese) "锁屏密码/PIN" else "Lock Screen Password/PIN") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (lockPasswordVisible)
                    VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { lockPasswordVisible = !lockPasswordVisible }) {
                        Icon(
                            if (lockPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                },
                supportingText = {
                    Text(
                        if (isChinese)
                            "息屏唤醒时自动解锁用，使用 Android Keystore 硬件加密存储，其他应用无法读取"
                        else
                            "Used for auto-unlock on screen-off wake, encrypted with Android Keystore"
                    )
                }
            )

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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.showCoordinatorThinking, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        strings.showCoordinatorThinkingDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = showCoordinatorThinking,
                    onCheckedChange = { showCoordinatorThinking = it }
                )
            }

            // 协调器 API URL 下拉选择 + 自定义输入
            val coordinatorPresetApiUrls = listOf(
                "https://api.deepseek.com/v1" to "DeepSeek",
                "https://open.bigmodel.cn/api/paas/v4" to "智谱 BigModel",
                "https://ark.cn-beijing.volces.com/api/v3" to "豆包 Doubao",
                "https://api.openai.com/v1" to "OpenAI",
                "https://dashscope.aliyuncs.com/compatible-mode/v1" to "通义千问"
            )
            ExposedDropdownMenuBox(
                expanded = coordinatorApiUrlDropdownExpanded,
                onExpandedChange = { coordinatorApiUrlDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = coordinatorApiUrl,
                    onValueChange = { coordinatorApiUrl = it },
                    label = { Text(strings.coordinatorApiUrlLabel) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = coordinatorApiUrlDropdownExpanded) }
                )
                ExposedDropdownMenu(
                    expanded = coordinatorApiUrlDropdownExpanded,
                    onDismissRequest = { coordinatorApiUrlDropdownExpanded = false }
                ) {
                    coordinatorPresetApiUrls.forEach { (url, label) ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                    Text(url, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                coordinatorApiUrl = TextFieldValue(url)
                                coordinatorApiUrlDropdownExpanded = false
                            }
                        )
                    }
                }
            }

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

            // 可编辑的模型名称输入框
            OutlinedTextField(
                value = coordinatorModelName,
                onValueChange = { coordinatorModelName = it },
                label = { Text(strings.coordinatorModelLabel) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text(if (isChinese) "可输入任意模型名称" else "Enter any model name") }
            )

            // 快捷选择按钮
            Text(
                text = if (isChinese) "快捷选择：" else "Quick select:",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                coordinatorModels.forEach { (model, displayName) ->
                    FilterChip(
                        selected = coordinatorModelName.text == model,
                        onClick = {
                            coordinatorModelName = TextFieldValue(model)
                            // Auto-switch Coordinator API URL based on selected model
                            coordinatorApiUrl = when {
                                model.startsWith("deepseek") -> TextFieldValue("https://api.deepseek.com/v1")
                                model.startsWith("glm-") -> TextFieldValue("https://open.bigmodel.cn/api/paas/v4")
                                model.startsWith("doubao") -> TextFieldValue("https://ark.cn-beijing.volces.com/api/v3")
                                else -> coordinatorApiUrl
                            }
                            // Auto-switch Coordinator API Key based on selected model
                            coordinatorApiKey = when {
                                model.startsWith("deepseek") -> TextFieldValue(prefs.coordinatorApiKeyDeepseek)
                                model.startsWith("glm-") -> TextFieldValue(prefs.coordinatorApiKeyBigmodel)
                                model.startsWith("doubao") -> TextFieldValue(prefs.coordinatorApiKeyDoubao)
                                else -> coordinatorApiKey
                            }
                        },
                        label = { Text(displayName, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            // 协调器：从 /models API 获取模型列表
            val coordinatorCoroutineScope = rememberCoroutineScope()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        coordinatorFetchingModels = true
                        coordinatorCoroutineScope.launch {
                            val models = com.autoglm.assistant.ai.ModelApiHelper.fetchModels(
                                coordinatorApiUrl.text, coordinatorApiKey.text
                            )
                            coordinatorFetchedModels = models
                            coordinatorFetchingModels = false
                        }
                    },
                    enabled = !coordinatorFetchingModels && coordinatorApiUrl.text.isNotBlank() && coordinatorApiKey.text.isNotBlank()
                ) {
                    if (coordinatorFetchingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (coordinatorFetchingModels) strings.fetchingModelsLabel else strings.fetchModelsLabel)
                }
                if (coordinatorFetchedModels.isNotEmpty()) {
                    Text(
                        "${coordinatorFetchedModels.size} ${if (isChinese) "个模型" else "models"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 显示从 API 获取的协调器模型列表（可选择）
            if (coordinatorFetchedModels.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    coordinatorFetchedModels.forEach { model ->
                        FilterChip(
                            selected = coordinatorModelName.text == model,
                            onClick = { coordinatorModelName = TextFieldValue(model) },
                            label = { Text(model, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            Row(
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        strings.coordinatorEnableThinkingLabel,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        strings.coordinatorEnableThinkingDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = coordinatorEnableThinking,
                    onCheckedChange = { coordinatorEnableThinking = it }
                )
            }

            // 模型是否支持图像（Vision）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isChinese) "模型支持图像 (Vision)" else "Model Supports Vision",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (isChinese) "启用后协调器会发送截图辅助决策" else "When enabled, coordinator sends screenshots for decision making",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = coordinatorEnableVision,
                    onCheckedChange = { coordinatorEnableVision = it }
                )
            }

            // 自定义 Coordinator 系统提示词
            var showCoordinatorPromptEditor by remember { mutableStateOf(false) }
            var showCoordinatorDefaultPreview by remember { mutableStateOf(false) }
            
            // 预览默认提示词 Dialog
            if (showCoordinatorDefaultPreview) {
                AlertDialog(
                    onDismissRequest = { showCoordinatorDefaultPreview = false },
                    title = { Text(if (isChinese) "默认协调器提示词" else "Default Coordinator Prompt") },
                    text = {
                        val defaultPrompt = if (isChinese) 
                            com.autoglm.assistant.core.planner.SmartCoordinator.DECISION_SYSTEM_PROMPT_CN 
                        else 
                            com.autoglm.assistant.core.planner.SmartCoordinator.DECISION_SYSTEM_PROMPT_EN
                        
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                text = if (isChinese) 
                                    "这是内置的默认提示词内容。您可以基于此模板修改，或直接留空使用默认。" 
                                else 
                                    "This is the built-in default prompt. You can modify based on this template, or leave empty to use default.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = defaultPrompt,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val defaultPrompt = if (isChinese) 
                                com.autoglm.assistant.core.planner.SmartCoordinator.DECISION_SYSTEM_PROMPT_CN 
                            else 
                                com.autoglm.assistant.core.planner.SmartCoordinator.DECISION_SYSTEM_PROMPT_EN
                            coordinatorSystemPrompt = TextFieldValue(defaultPrompt)
                            showCoordinatorDefaultPreview = false
                            showCoordinatorPromptEditor = true
                        }) {
                            Text(if (isChinese) "加载到编辑器" else "Load to Editor")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCoordinatorDefaultPreview = false }) {
                            Text(if (isChinese) "关闭" else "Close")
                        }
                    }
                )
            }
            
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
                            if (isChinese) "自定义规划器提示词" else "Custom Planner Prompt",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (coordinatorSystemPrompt.text.isBlank()) {
                                if (isChinese) "当前使用内置默认提示词" else "Using built-in default"
                            } else {
                                if (isChinese) "已配置（${coordinatorSystemPrompt.text.length}字）" else "Configured (${coordinatorSystemPrompt.text.length} chars)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        if (coordinatorSystemPrompt.text.isNotBlank()) {
                            TextButton(onClick = { coordinatorSystemPrompt = TextFieldValue("") }) {
                                Text(if (isChinese) "清空" else "Clear")
                            }
                        }
                        TextButton(onClick = { showCoordinatorDefaultPreview = true }) {
                            Text(if (isChinese) "查看默认" else "View Default")
                        }
                        TextButton(onClick = { showCoordinatorPromptEditor = !showCoordinatorPromptEditor }) {
                            Text(if (showCoordinatorPromptEditor) {
                                if (isChinese) "收起" else "Collapse"
                            } else {
                                if (isChinese) "编辑" else "Edit"
                            })
                        }
                    }
                }
                if (showCoordinatorPromptEditor) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = coordinatorSystemPrompt,
                        onValueChange = { coordinatorSystemPrompt = it },
                        label = { Text(if (isChinese) "系统提示词（留空使用默认）" else "System Prompt (empty = default)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 150.dp, max = 400.dp),
                        maxLines = 20,
                        supportingText = {
                            Text(
                                if (isChinese) 
                                    "协调器负责规划每一步的目标（如\"打开微信找到张三\"），不负责具体操作。留空使用内置默认提示词，自定义后将覆盖默认。点击上方【查看默认】按钮可预览内置提示词内容。"
                                else 
                                    "Coordinator plans each step's goal (e.g. \"Open WeChat and find John\"), not specific operations. Leave empty to use built-in default. Custom prompt overrides default. Click [View Default] above to preview built-in prompt."
                            )
                        }
                    )
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

            OutlinedTextField(
                value = maxAgentStepsPerCoordinatorStep,
                onValueChange = { maxAgentStepsPerCoordinatorStep = it },
                label = { Text(strings.maxAgentStepsPerCoordinatorStepLabel) },
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
                // 优化器 API URL 下拉选择 + 自定义输入
                val optimizerPresetApiUrls = listOf(
                    "https://api.deepseek.com/v1" to "DeepSeek",
                    "https://open.bigmodel.cn/api/paas/v4" to "智谱 BigModel",
                    "https://ark.cn-beijing.volces.com/api/v3" to "豆包 Doubao",
                    "https://api.openai.com/v1" to "OpenAI",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1" to "通义千问"
                )
                ExposedDropdownMenuBox(
                    expanded = optimizerApiUrlDropdownExpanded,
                    onExpandedChange = { optimizerApiUrlDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = optimizerApiUrl,
                        onValueChange = { optimizerApiUrl = it },
                        label = { Text(strings.optimizerApiUrlLabel) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = optimizerApiUrlDropdownExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = optimizerApiUrlDropdownExpanded,
                        onDismissRequest = { optimizerApiUrlDropdownExpanded = false }
                    ) {
                        optimizerPresetApiUrls.forEach { (url, label) ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(label, style = MaterialTheme.typography.bodyMedium)
                                        Text(url, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    optimizerApiUrl = TextFieldValue(url)
                                    optimizerApiUrlDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

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

                // 可编辑的模型名称输入框
                OutlinedTextField(
                    value = optimizerModelName,
                    onValueChange = { optimizerModelName = it },
                    label = { Text(strings.optimizerModelLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text(if (isChinese) "可输入任意模型名称" else "Enter any model name") }
                )

                // 快捷选择按钮
                Text(
                    text = if (isChinese) "快捷选择：" else "Quick select:",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    optimizerModels.forEach { (model, displayName) ->
                        FilterChip(
                            selected = optimizerModelName.text == model,
                            onClick = {
                                optimizerModelName = TextFieldValue(model)
                                // Auto-switch Optimizer API URL based on selected model
                                optimizerApiUrl = when {
                                    model.startsWith("deepseek") -> TextFieldValue("https://api.deepseek.com/v1")
                                    model.startsWith("glm-") -> TextFieldValue("https://open.bigmodel.cn/api/paas/v4")
                                    model.startsWith("doubao") -> TextFieldValue("https://ark.cn-beijing.volces.com/api/v3")
                                    else -> optimizerApiUrl
                                }
                                // Auto-switch Optimizer API Key based on selected model
                                optimizerApiKey = when {
                                    model.startsWith("deepseek") -> TextFieldValue(prefs.optimizerApiKeyDeepseek)
                                    model.startsWith("glm-") -> TextFieldValue(prefs.optimizerApiKeyBigmodel)
                                    model.startsWith("doubao") -> TextFieldValue(prefs.optimizerApiKeyDoubao)
                                    else -> optimizerApiKey
                                }
                            },
                            label = { Text(displayName, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                // 优化器：从 /models API 获取模型列表
                val optimizerCoroutineScope = rememberCoroutineScope()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            optimizerFetchingModels = true
                            optimizerCoroutineScope.launch {
                                val models = com.autoglm.assistant.ai.ModelApiHelper.fetchModels(
                                    optimizerApiUrl.text, optimizerApiKey.text
                                )
                                optimizerFetchedModels = models
                                optimizerFetchingModels = false
                            }
                        },
                        enabled = !optimizerFetchingModels && optimizerApiUrl.text.isNotBlank() && optimizerApiKey.text.isNotBlank()
                    ) {
                        if (optimizerFetchingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (optimizerFetchingModels) strings.fetchingModelsLabel else strings.fetchModelsLabel)
                    }
                    if (optimizerFetchedModels.isNotEmpty()) {
                        Text(
                            "${optimizerFetchedModels.size} ${if (isChinese) "个模型" else "models"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 显示从 API 获取的优化器模型列表（可选择）
                if (optimizerFetchedModels.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        optimizerFetchedModels.forEach { model ->
                            FilterChip(
                                selected = optimizerModelName.text == model,
                                onClick = { optimizerModelName = TextFieldValue(model) },
                                label = { Text(model, style = MaterialTheme.typography.labelSmall) }
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
                var showOptimizerDefaultPreview by remember { mutableStateOf(false) }
                
                // 预览默认优化器提示词 Dialog
                if (showOptimizerDefaultPreview) {
                    AlertDialog(
                        onDismissRequest = { showOptimizerDefaultPreview = false },
                        title = { Text(if (isChinese) "默认优化器提示词" else "Default Optimizer Prompt") },
                        text = {
                            val defaultPrompt = if (isChinese) 
                                com.autoglm.assistant.core.planner.PromptOptimizer.SYSTEM_PROMPT_CN 
                            else 
                                com.autoglm.assistant.core.planner.PromptOptimizer.SYSTEM_PROMPT_EN
                            
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                Text(
                                    text = if (isChinese) 
                                        "这是内置的默认优化器提示词。您可以基于此模板修改，或直接留空使用默认。" 
                                    else 
                                        "This is the built-in default optimizer prompt. You can modify based on this template, or leave empty to use default.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = defaultPrompt,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val defaultPrompt = if (isChinese) 
                                    com.autoglm.assistant.core.planner.PromptOptimizer.SYSTEM_PROMPT_CN 
                                else 
                                    com.autoglm.assistant.core.planner.PromptOptimizer.SYSTEM_PROMPT_EN
                                optimizerSystemPrompt = TextFieldValue(defaultPrompt)
                                showOptimizerDefaultPreview = false
                                showPromptEditor = true
                            }) {
                                Text(if (isChinese) "加载到编辑器" else "Load to Editor")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showOptimizerDefaultPreview = false }) {
                                Text(if (isChinese) "关闭" else "Close")
                            }
                        }
                    )
                }
                
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
                                if (optimizerSystemPrompt.text.isBlank()) {
                                    if (isChinese) "当前使用内置默认提示词" else "Currently using built-in default prompt"
                                } else {
                                    if (isChinese) "已配置自定义提示词（${optimizerSystemPrompt.text.length}字）" else "Custom prompt configured (${optimizerSystemPrompt.text.length} chars)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row {
                            if (optimizerSystemPrompt.text.isNotBlank()) {
                                TextButton(onClick = { optimizerSystemPrompt = TextFieldValue("") }) {
                                    Text(if (isChinese) "清空" else "Clear")
                                }
                            }
                            TextButton(onClick = { showOptimizerDefaultPreview = true }) {
                                Text(if (isChinese) "查看默认" else "View Default")
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
                                    if (isChinese) 
                                        "优化器负责将用户指令转换为结构化任务描述。留空使用内置默认提示词，自定义后将覆盖默认。点击上方【查看默认】按钮可预览内置提示词内容。"
                                    else 
                                        "Optimizer converts user instructions to structured task descriptions. Leave empty to use built-in default. Custom prompt overrides default. Click [View Default] above to preview built-in prompt."
                                )
                            }
                        )
                    }
                }
            }

            Divider()

            // ===== 意图识别设置 =====
            Text(
                strings.intentRecognizerSettings,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.enableIntentRecognizer, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        strings.intentRecognizerDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = intentRecognizerEnabled,
                    onCheckedChange = { intentRecognizerEnabled = it }
                )
            }

            if (intentRecognizerEnabled) {
                // 意图识别器 API URL 下拉选择 + 自定义输入
                val intentPresetApiUrls = listOf(
                    "https://api.deepseek.com/v1" to "DeepSeek",
                    "https://open.bigmodel.cn/api/paas/v4" to "智谱 BigModel",
                    "https://ark.cn-beijing.volces.com/api/v3" to "豆包 Doubao",
                    "https://api.openai.com/v1" to "OpenAI",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1" to "通义千问"
                )
                ExposedDropdownMenuBox(
                    expanded = intentApiUrlDropdownExpanded,
                    onExpandedChange = { intentApiUrlDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = intentApiUrl,
                        onValueChange = { intentApiUrl = it },
                        label = { Text(strings.intentApiUrlLabel) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = intentApiUrlDropdownExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = intentApiUrlDropdownExpanded,
                        onDismissRequest = { intentApiUrlDropdownExpanded = false }
                    ) {
                        intentPresetApiUrls.forEach { (url, label) ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(label, style = MaterialTheme.typography.bodyMedium)
                                        Text(url, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    intentApiUrl = TextFieldValue(url)
                                    intentApiUrlDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = intentApiKey,
                    onValueChange = { intentApiKey = it },
                    label = { Text(strings.intentApiKeyLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // 可编辑的模型名称输入框
                OutlinedTextField(
                    value = intentModelName,
                    onValueChange = { intentModelName = it },
                    label = { Text(strings.intentModelLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text(if (isChinese) "可输入任意模型名称，或点击下方获取可用列表" else "Enter any model name, or fetch available list below") }
                )

                // 快捷选择按钮 + 获取模型列表
                val intentModels = listOf(
                    "deepseek-chat" to "DeepSeek Chat",
                    "glm-4-plus" to "智谱 GLM-4 Plus",
                    "glm-4" to "智谱 GLM-4",
                    "doubao-seed-1-6-251015" to "豆包 Seed 1.6"
                )

                Text(
                    text = if (isChinese) "快捷选择：" else "Quick select:",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    intentModels.forEach { (model, displayName) ->
                        FilterChip(
                            selected = intentModelName.text == model,
                            onClick = {
                                intentModelName = TextFieldValue(model)
                                intentApiUrl = when {
                                    model.startsWith("deepseek") -> TextFieldValue("https://api.deepseek.com/v1")
                                    model.startsWith("glm-") -> TextFieldValue("https://open.bigmodel.cn/api/paas/v4")
                                    model.startsWith("doubao") -> TextFieldValue("https://ark.cn-beijing.volces.com/api/v3")
                                    else -> intentApiUrl
                                }
                                intentApiKey = when {
                                    model.startsWith("deepseek") -> TextFieldValue(prefs.intentApiKeyDeepseek)
                                    model.startsWith("glm-") -> TextFieldValue(prefs.intentApiKeyBigmodel)
                                    model.startsWith("doubao") -> TextFieldValue(prefs.intentApiKeyDoubao)
                                    else -> intentApiKey
                                }
                            },
                            label = { Text(displayName, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                // 从 /models API 获取模型列表按钮
                val coroutineScope = rememberCoroutineScope()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            intentFetchingModels = true
                            coroutineScope.launch {
                                val models = com.autoglm.assistant.ai.ModelApiHelper.fetchModels(
                                    intentApiUrl.text, intentApiKey.text
                                )
                                intentFetchedModels = models
                                intentFetchingModels = false
                            }
                        },
                        enabled = !intentFetchingModels && intentApiUrl.text.isNotBlank() && intentApiKey.text.isNotBlank()
                    ) {
                        if (intentFetchingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (intentFetchingModels) strings.fetchingModelsLabel else strings.fetchModelsLabel)
                    }
                    if (intentFetchedModels.isNotEmpty()) {
                        Text(
                            "${intentFetchedModels.size} ${if (isChinese) "个模型" else "models"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 显示从 API 获取的模型列表（可选择）
                if (intentFetchedModels.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        intentFetchedModels.forEach { model ->
                            FilterChip(
                                selected = intentModelName.text == model,
                                onClick = { intentModelName = TextFieldValue(model) },
                                label = { Text(model, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
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

    // Exit confirmation dialog — 显示具体变更项
    if (showExitDialog) {
        // 构建变更列表
        val changedItems = buildList {
            if (apiUrl.text != originalApiUrl) add("API URL")
            if (apiKey.text != originalApiKey) add("API Key")
            if (modelName.text != originalModelName) add(if (isChinese) "模型名称" else "Model Name")
            if (agentSystemPrompt.text != originalAgentSystemPrompt) add(if (isChinese) "Agent 系统提示词" else "Agent System Prompt")
            if (wakeEngineType != originalWakeEngineType) add(if (isChinese) "唤醒引擎" else "Wake Engine")
            if (porcupineKey.text != originalPorcupineKey) add("Porcupine Key")
            if (wakeWordKeyword != originalWakeWordKeyword) add(if (isChinese) "唤醒词" else "Wake Word")
            if (sttWakePhrase.text != originalSttWakePhrase) add(if (isChinese) "STT 唤醒词" else "STT Wake Phrase")
            if (commandSttMode != originalCommandSttMode) add(if (isChinese) "语音识别模式" else "STT Mode")
            if (sttApiType != originalSttApiType) add(if (isChinese) "STT 服务商" else "STT Provider")
            if (sttApiUrl.text != originalSttApiUrl) add("STT API URL")
            if (sttApiKey.text != originalSttApiKey) add("STT API Key")
            if (sttModelName.text != originalSttModelName) add(if (isChinese) "STT 模型" else "STT Model")
            if (aliNlsAkId.text != originalAliNlsAkId) add("NLS AccessKey ID")
            if (aliNlsAkSecret.text != originalAliNlsAkSecret) add("NLS AccessKey Secret")
            if (aliNlsAppKey.text != originalAliNlsAppKey) add("NLS AppKey")
            if (imeVoiceSpaceX.text != originalImeVoiceSpaceX) add(if (isChinese) "IME 语音坐标X" else "IME Voice X")
            if (imeVoiceSpaceY.text != originalImeVoiceSpaceY) add(if (isChinese) "IME 语音坐标Y" else "IME Voice Y")
            if (lockScreenPassword.text != originalLockScreenPassword) add(if (isChinese) "锁屏密码" else "Lock Password")
            if (maxSteps.text != originalMaxSteps) add(if (isChinese) "最大步数" else "Max Steps")
            if (language != originalLanguage) add(if (isChinese) "语言" else "Language")
            if (showAgentProcess != originalShowAgentProcess) add(if (isChinese) "显示执行过程" else "Show Process")
            if (smartCoordinatorEnabled != originalSmartCoordinatorEnabled) add(if (isChinese) "智能协调器" else "Smart Coordinator")
            if (coordinatorApiUrl.text != originalCoordinatorApiUrl) add(if (isChinese) "协调器 API URL" else "Coordinator API URL")
            if (coordinatorApiKey.text != originalCoordinatorApiKey) add(if (isChinese) "协调器 API Key" else "Coordinator API Key")
            if (coordinatorModelName.text != originalCoordinatorModelName) add(if (isChinese) "协调器模型" else "Coordinator Model")
            if (coordinatorEnableVision != originalCoordinatorEnableVision) add("Vision")
            if (coordinatorEnableThinking != originalCoordinatorEnableThinking) add(if (isChinese) "模型思考" else "Thinking")
            if (coordinatorSystemPrompt.text != originalCoordinatorSystemPrompt) add(if (isChinese) "协调器提示词" else "Coordinator Prompt")
            if (supervisionEnabled != originalSupervisionEnabled) add(if (isChinese) "执行监督" else "Supervision")
            if (promptOptimizerEnabled != originalPromptOptimizerEnabled) add(if (isChinese) "提示词优化器" else "Prompt Optimizer")
            if (optimizerApiUrl.text != originalOptimizerApiUrl) add(if (isChinese) "优化器 API URL" else "Optimizer API URL")
            if (optimizerApiKey.text != originalOptimizerApiKey) add(if (isChinese) "优化器 API Key" else "Optimizer API Key")
            if (optimizerModelName.text != originalOptimizerModelName) add(if (isChinese) "优化器模型" else "Optimizer Model")
            if (intentRecognizerEnabled != originalIntentRecognizerEnabled) add(if (isChinese) "意图识别" else "Intent Recognition")
            if (intentApiUrl.text != originalIntentApiUrl) add(if (isChinese) "识别器 API URL" else "Recognizer API URL")
            if (intentApiKey.text != originalIntentApiKey) add(if (isChinese) "识别器 API Key" else "Recognizer API Key")
            if (intentModelName.text != originalIntentModelName) add(if (isChinese) "识别器模型" else "Recognizer Model")
        }
        val changesSummary = if (changedItems.isNotEmpty()) {
            changedItems.joinToString("\n") { "• $it" }
        } else {
            if (isChinese) "（未检测到具体变更）" else "(No specific changes detected)"
        }

        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(if (isChinese) "以下设置已修改" else "Settings Changed") },
            text = {
                Column {
                    Text(changesSummary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (isChinese) "是否保存？" else "Save changes?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
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
