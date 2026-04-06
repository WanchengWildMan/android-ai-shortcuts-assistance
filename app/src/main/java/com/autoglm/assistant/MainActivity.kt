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
import com.autoglm.assistant.util.Logger

class MainActivity : ComponentActivity() {

    private var wakeWordService: WakeWordService? = null
    private var serviceBound = false
    // 步骤: 用 Compose State 追踪服务绑定状态，确保绑定完成后触发重组
    private val _serviceConnected = mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Logger.d(Logger.SERVICE, "=== MainActivity.onServiceConnected ===")
            val binder = service as WakeWordService.LocalBinder
            wakeWordService = binder.getService()
            serviceBound = true
            _serviceConnected.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Logger.d(Logger.SERVICE, "=== MainActivity.onServiceDisconnected ===")
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
            Logger.d(Logger.SERVICE, "=== MainActivity.setContent recomposing, isConnected=$isConnected ===")
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
            Logger.i(Logger.SERVICE, "应用启动，主动申请权限: $needed")
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
            Logger.i(Logger.SERVICE, "=== 语音唤醒已开启，自动启动服务 ===")
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
                Logger.e(Logger.SERVICE, "自动启动服务失败: ${e.message}", e)
            }
        } else {
            Logger.w(Logger.SERVICE, "=== 语音唤醒已开启但权限不足（mic=$hasMicPermission, overlay=$hasOverlayPermission），跳过自动启动 ===")
        }
    }

    override fun onStart() {
        super.onStart()
        // 步骤: 仅在尚未绑定时绑定服务
        // 任务执行期间 onStop 会跳过解绑，此时 serviceBound 仍为 true，无需重复绑定
        if (!serviceBound) {
            Logger.d(Logger.SERVICE, "=== MainActivity.onStart: Binding service... ===")
            bindService(
                Intent(this, WakeWordService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        } else {
            Logger.d(Logger.SERVICE, "=== MainActivity.onStart: Service already bound. ===")
        }
    }

    override fun onStop() {
        Logger.d(Logger.SERVICE, "=== MainActivity.onStop() called - activity going to background ===")
        super.onStop()
        if (serviceBound) {
            // 步骤: 任务执行期间保持绑定，作为双重保险
            // 主保护由 ensureServiceStartedAsForeground 提供（前台服务不会因解绑销毁）
            // 此处额外保留绑定，避免极端情况（如前台服务启动失败）下服务被销毁
            val isTaskRunning = wakeWordService?.serviceState?.value == WakeWordService.ServiceState.EXECUTING_TASK
            if (isTaskRunning) {
                Logger.d(Logger.SERVICE, "=== MainActivity.onStop(): task running, keeping service bound ===")
            } else {
                Logger.d(Logger.SERVICE, "=== MainActivity: unbinding service (no task running) ===")
                unbindService(serviceConnection)
                serviceBound = false
            }
        }
    }

    private fun startServiceWithPermissions() {
        Logger.d(Logger.SERVICE, "startServiceWithPermissions called")
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
        Logger.d(Logger.SERVICE, "startWakeWordService called")
        // 步骤1: 如果有悬浮窗权限则允许展示原有外层窗体，这里不再强制 return 阻塞
        if (!Settings.canDrawOverlays(this)) {
            Logger.w(Logger.SERVICE, "No overlay permission. The app can still listen inside, but floating UI will fallback or not show outside.")
        }

        // 步骤2: 检查并请求电池优化豁免
        // 业务目的：防止系统在后台杀死服务，确保任务持续执行
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Logger.w(Logger.SERVICE, "=== 应用未在电池优化白名单中，请求加入 ===")
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    Toast.makeText(this, "请允许应用在后台运行，以确保任务不被中断", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Logger.e(Logger.SERVICE, "请求电池优化豁免失败", e)
                }
            }
        }

        // 步骤3: 启动语音唤醒服务（需要麦克风权限）
        val wakeWordEnabled = App.instance.preferenceManager.wakeWordEnabled
        Logger.d(Logger.SERVICE, "startWakeWordService preparing intent, wakeWordEnabled=$wakeWordEnabled")
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
            Logger.e(Logger.SERVICE, "启动服务失败", e)
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

        // 步骤3.1: 记录电池优化警告（已有首页 BatteryRestrictionCard 引导，不再重复弹 Toast）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Logger.w(Logger.SERVICE, "=== 警告：应用未在电池优化白名单中，任务可能被中断 ===")
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
            Logger.w(Logger.SERVICE, "⚠️ 无法启动前台服务: ${e.message}")
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
        Logger.w(Logger.SERVICE, "=== MainActivity.stopCurrentTask() 被调用 - UI 请求停止 ===", Exception("stopCurrentTask trace"))
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
                Logger.e(Logger.SERVICE, "启动服务失败", e)
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

    // 步骤: 服务是否运行 — 用 serviceState != null 判断（而非 != IDLE）
    // 原因：引擎初始化失败时 serviceState = IDLE 但服务仍在前台运行，
    //       若用 != IDLE 会导致右上角显示"启动"按钮，用户无法停止服务
    val isServiceRunning = serviceState?.value != null
        
    Logger.d(Logger.SERVICE, "MainScreen recompose: serviceState=${serviceState?.value}, isServiceRunning=$isServiceRunning")
    
    val wakeWordError = getLastWakeWordError()?.collectAsState()
    val activeEngine = getActiveEngineType()?.collectAsState()

    // 任务发起时锁定的对话 ID，用于将 agent/coordinator 消息路由到正确的对话
    // 防止用户切换对话后，老任务消息错误写入新的当前对话
    var taskOwnerConversationId by remember { mutableStateOf<String?>(null) }

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

    // 步骤: 将 agent/coordinator 消息写入任务所属对话
    // 若任务对话 == 当前对话，走正常 addMessage 逻辑（更新显示）
    // 若用户已切换对话，只写后台对话（不更新 messages 显示），避免消息串屏
    fun addMessageToTask(message: ChatMessage) {
        val ownerId = taskOwnerConversationId
        if (ownerId == null || ownerId == currentConversation?.id) {
            addMessage(message)
            return
        }
        val ownerConv = conversations.firstOrNull { it.id == ownerId } ?: run {
            addMessage(message) // 后备：找不到任务对话则写当前
            return
        }
        ownerConv.messages.add(message)
        ownerConv.timestamp = System.currentTimeMillis()
        conversations.remove(ownerConv)
        conversations.add(0, ownerConv)
        scope.launch { messageManager.saveConversation(ownerConv) }
    }

    // 步骤: streaming 原地更新任务对话消息
    // 仅当任务对话是当前对话时生效；已切走则跳过（后台对话无需 streaming 更新）
    fun updateMessageInTask(index: Int, message: ChatMessage) {
        val ownerId = taskOwnerConversationId
        if (ownerId == null || ownerId == currentConversation?.id) {
            updateMessage(index, message)
        }
        // 已切换对话时 streaming update 无意义，跳过
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
            if (text.isNotBlank()) {
                // 步骤1: 每次语音唤醒都新建对话，避免消息写入旧的历史对话
                createNewConversation()
                addMessage(ChatMessage(content = text, isUser = true))
                // 步骤2: 锁定任务所属对话，防止后续消息写入用户切换后的新对话
                taskOwnerConversationId = currentConversation?.id
                // 步骤3: 若不在 chat 页面（如 home 页语音唤醒），自动导航过去展示执行过程
                if (currentRoute != "chat") {
                    navController.navigate("chat")
                }
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

    // 当切换对话时，重置 UI 标志位（不重置流式消息索引）
    // 注意：optimizerMessageIndex/plannerMessageIndex/summaryMessageIndex/stepMessageIndex
    // 不在此处重置，因为语音唤醒时 _lastRecognizedText 触发 createNewConversation()，
    // 对话 ID 变化可能与 optimizer 流式输出并发，导致索引被错误清零后重复新建 bubble。
    // 这些索引由 CoordinatorMessageType.CLEAR 消息统一重置（任务完成/出错时发送）。
    LaunchedEffect(currentConversation?.id) {
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

        // 步骤: showProcess 关闭时不显示协调器过程消息（RESULT 在 agent channel 发送，不受影响）
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
                    updateMessageInTask(optimizerMessageIndex, ChatMessage(
                        id = oldMsg.id,
                        content = displayContent,
                        isUser = false,
                        timestamp = oldMsg.timestamp
                    ))
                } else {
                    addMessageToTask(ChatMessage(content = displayContent, isUser = false))
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
                    updateMessageInTask(plannerMessageIndex, ChatMessage(
                        id = oldMsg.id,
                        content = content,
                        isUser = false,
                        timestamp = oldMsg.timestamp
                    ))
                } else {
                    addMessageToTask(ChatMessage(content = content, isUser = false))
                    plannerMessageIndex = messages.size - 1
                }
            }
            WakeWordService.CoordinatorMessageType.SUBTASK_CARD -> {
                // 子任务卡片：每个都是独立的新消息
                hasShownSubtaskCards = true
                addMessageToTask(ChatMessage(content = displayContent, isUser = false))
            }
            WakeWordService.CoordinatorMessageType.SUMMARY_STREAMING,
            WakeWordService.CoordinatorMessageType.SUMMARY_COMPLETE -> {
                // 总结消息：流式更新同一条（与优化器和规划器消息分开）
                if (summaryMessageIndex >= 0 && summaryMessageIndex < messages.size) {
                    val oldMsg = messages[summaryMessageIndex]
                    updateMessageInTask(summaryMessageIndex, ChatMessage(
                        id = oldMsg.id,
                        content = displayContent,
                        isUser = false,
                        timestamp = oldMsg.timestamp
                    ))
                } else {
                    addMessageToTask(ChatMessage(content = displayContent, isUser = false))
                    summaryMessageIndex = messages.size - 1
                }
            }
            WakeWordService.CoordinatorMessageType.COORDINATOR_STEP -> {
                // 步骤计数器：原地更新同一条消息
                if (stepMessageIndex >= 0 && stepMessageIndex < messages.size) {
                    val oldMsg = messages[stepMessageIndex]
                    updateMessageInTask(stepMessageIndex, ChatMessage(
                        id = oldMsg.id,
                        content = displayContent,
                        isUser = false,
                        timestamp = oldMsg.timestamp
                    ))
                } else {
                    addMessageToTask(ChatMessage(content = displayContent, isUser = false))
                    stepMessageIndex = messages.size - 1
                }
            }
            else -> {
                // 其他消息：添加新消息
                if (displayContent != lastCoordinatorContent) {
                    lastCoordinatorContent = displayContent
                    addMessageToTask(ChatMessage(content = displayContent, isUser = false))
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
                        addMessageToTask(ChatMessage(content = "**思考：**\n${msg.content}", isUser = false))
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
                            updateMessageInTask(lastThinkingMessageIndex!!, ChatMessage(
                                id = oldMsg.id,
                                content = content,
                                isUser = false,
                                timestamp = oldMsg.timestamp
                            ))
                        } else {
                            addMessageToTask(ChatMessage(content = content, isUser = false))
                        }
                    }

                    // 清空缓存
                    lastThinkingContent = null
                    lastThinkingMessageIndex = null
                }
                WakeWordService.AgentMessageType.RESULT -> {
                    if (shouldShow || msg.type == WakeWordService.AgentMessageType.RESULT) {
                        addMessageToTask(ChatMessage(content = msg.content, isUser = false))
                    }
                    // 清空thinking缓存
                    lastThinkingContent = null
                    lastThinkingMessageIndex = null
                    // 任务完成，解除任务对话锁定
                    taskOwnerConversationId = null
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
                                    Logger.d(Logger.SERVICE, "Button clicked! isServiceRunning=$isServiceRunning")
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

                    // 错误信息详情（有错误时展示完整内容，最多6行覆盖多引擎失败的4行 errorMsg）
                    if (errorText != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            errorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 6,
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
                            Logger.d(Logger.SERVICE, "Shortcut clicked: $prompt, enablePlanning=$enablePlanning, enableOptimizer=$enableOptimizer")
                            createNewConversation()
                            addMessage(ChatMessage(content = prompt, isUser = true))
                            // 步骤: 锁定任务所属对话
                            taskOwnerConversationId = currentConversation?.id
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
                        Logger.e(Logger.SERVICE, "=== ChatScreen entered, stateFlow = $stateFlow")
                    }

                    // Collect StateFlow and update isAgentRunning
                    LaunchedEffect(stateFlow) {
                        if (stateFlow != null) {
                            stateFlow.collect { state ->
                                Logger.e(Logger.SERVICE, "=== ChatScreen collected state: $state")
                                isAgentRunning = state == WakeWordService.ServiceState.EXECUTING_TASK
                            }
                        } else {
                            Logger.e(Logger.SERVICE, "=== ChatScreen: stateFlow is NULL!")
                        }
                    }

                    ChatScreen(
                        messages = messages,
                        isAgentRunning = isAgentRunning,
                        onSendMessage = { text, enablePlanning, enableOptimizer ->
                            checkAndExecute(text, enablePlanning, enableOptimizer, messages)
                            addMessage(ChatMessage(content = text, isUser = true))
                            // 步骤: 锁定任务所属对话
                            taskOwnerConversationId = currentConversation?.id
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

