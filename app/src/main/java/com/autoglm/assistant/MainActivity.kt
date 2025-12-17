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
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
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
                    onExecuteTask = { task, resetHistory, messages -> executeTask(task, resetHistory, messages) },
                    onStopTask = { stopCurrentTask() },
                    getServiceState = { wakeWordService?.serviceState },
                    getLastRecognizedText = { wakeWordService?.lastRecognizedText },
                    getAgentMessage = { wakeWordService?.agentMessage }
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
        // Check accessibility service
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable accessibility service", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }

        // Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        // Start service
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

    private fun executeTask(task: String, resetHistory: Boolean = true, messages: List<ChatMessage> = emptyList()) {
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
        wakeWordService?.executeTask(task, resetHistory, context)
    }

    private fun stopCurrentTask() {
        android.util.Log.w("AutoGLM", "=== MainActivity.stopCurrentTask() called - UI requested stop ===")
        Exception("MainActivity stopCurrentTask trace").printStackTrace()
        wakeWordService?.stopCurrentTask()
        Toast.makeText(this, "Task stopped", Toast.LENGTH_SHORT).show()
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
        // Navigate to settings screen
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
    onExecuteTask: (String, Boolean, List<ChatMessage>) -> Unit,
    onStopTask: () -> Unit,
    getServiceState: () -> kotlinx.coroutines.flow.StateFlow<WakeWordService.ServiceState>?,
    getLastRecognizedText: () -> kotlinx.coroutines.flow.StateFlow<String>?,
    getAgentMessage: () -> kotlinx.coroutines.flow.StateFlow<WakeWordService.AgentMessage?>?
) {
    var isServiceRunning by remember { mutableStateOf(false) }
    val conversations = remember { mutableStateListOf<Conversation>() }
    var currentConversation by remember { mutableStateOf<Conversation?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }

    val serviceState = getServiceState()?.collectAsState()
    val lastRecognizedText = getLastRecognizedText()?.collectAsState()
    val agentMessage = getAgentMessage()?.collectAsState()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val context = LocalContext.current
    val messageManager = remember { MessageManager(context) }
    val scope = rememberCoroutineScope()

    // Load conversations on start
    LaunchedEffect(Unit) {
        val loaded = messageManager.loadConversations()
        conversations.clear()
        conversations.addAll(loaded)
        // Auto-select the most recent conversation or create new one
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
        // Update current conversation
        val conv = currentConversation ?: createNewConversation()
        conv.messages.add(message)
        conv.timestamp = System.currentTimeMillis()
        // Auto-generate title from first user message
        if (conv.title == "New Chat" && message.isUser) {
            conv.title = message.content.take(30) + if (message.content.length > 30) "..." else ""
        }
        // Update list order
        conversations.remove(conv)
        conversations.add(0, conv)
        currentConversation = conv
        scope.launch {
            messageManager.saveConversation(conv)
        }
    }

    LaunchedEffect(lastRecognizedText?.value) {
        lastRecognizedText?.value?.let { text ->
            if (text.isNotBlank()) {
                addMessage(ChatMessage(content = text, isUser = true))
                // If we receive a voice command, make sure we are on the chat screen
                if (navController.currentDestination?.route != "chat") {
                    navController.navigate("chat")
                }
            }
        }
    }

    // Observe agent messages and add to chat based on setting
    LaunchedEffect(agentMessage?.value) {
        agentMessage?.value?.let { msg ->
            // Read setting dynamically each time
            val showProcess = App.instance.preferenceManager.showAgentProcess
            val shouldShow = when (msg.type) {
                WakeWordService.AgentMessageType.RESULT -> true  // Always show final result
                WakeWordService.AgentMessageType.THINKING,
                WakeWordService.AgentMessageType.ACTION -> showProcess  // Only show if setting enabled
            }
            if (shouldShow && msg.content.isNotBlank()) {
                val prefix = when (msg.type) {
                    WakeWordService.AgentMessageType.THINKING -> "[Thinking] "
                    WakeWordService.AgentMessageType.ACTION -> "[Action] "
                    WakeWordService.AgentMessageType.RESULT -> ""
                }
                addMessage(ChatMessage(content = prefix + msg.content, isUser = false))
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
                                currentConversation?.title ?: "New Chat",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1
                            )
                        },
                        actions = {
                            IconButton(onClick = { navController.navigate("conversations") }) {
                                Icon(Icons.Default.List, contentDescription = "Conversations")
                            }
                            IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        }
                    )
                }
                "conversations" -> {
                    TopAppBar(
                        title = { Text("Conversations", style = MaterialTheme.typography.titleLarge) },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                    // Status Indicator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (icon, color, text) = when (serviceState?.value) {
                            WakeWordService.ServiceState.LISTENING_WAKE_WORD -> Triple(Icons.Default.Mic, MaterialTheme.colorScheme.primary, "Listening")
                            WakeWordService.ServiceState.LISTENING_COMMAND -> Triple(Icons.Default.RecordVoiceOver, MaterialTheme.colorScheme.tertiary, "Listening Command")
                            WakeWordService.ServiceState.EXECUTING_TASK -> Triple(Icons.Default.PlayArrow, MaterialTheme.colorScheme.secondary, "Executing")
                            WakeWordService.ServiceState.PROCESSING -> Triple(Icons.Default.Pending, MaterialTheme.colorScheme.primary, "Processing")
                            else -> Triple(Icons.Default.PowerSettingsNew, MaterialTheme.colorScheme.outline, "Idle")
                        }
                        
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
                    }

                    // Controls
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = onRequestScreenCapture) {
                            Icon(
                                Icons.Default.ScreenShare,
                                contentDescription = "Screen Permission",
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
                                contentDescription = if (isServiceRunning) "Stop" else "Start"
                            )
                        }
                    }
                }
            }

            NavHost(navController = navController, startDestination = "home", modifier = Modifier.weight(1f)) {
                composable("home") {
                    HomeScreen(
                        onShortcutClick = { prompt ->
                            // Create new conversation for new task
                            android.util.Log.d("AutoGLM", "Shortcut clicked: $prompt, current state = ${serviceState?.value}")
                            createNewConversation()
                            addMessage(ChatMessage(content = prompt, isUser = true))
                            // Navigate first, then execute task
                            navController.navigate("chat")
                            // Execute task after navigation to ensure UI is ready
                            onExecuteTask(prompt, true, emptyList())
                            android.util.Log.d("AutoGLM", "After executeTask: state = ${serviceState?.value}")
                        },
                        onHistoryClick = {
                            navController.navigate("conversations")
                        },
                        onSettingsClick = onOpenSettings
                    )
                }
                composable("chat") {
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
                        onSendMessage = { text ->
                            onExecuteTask(text, false, messages)
                            addMessage(ChatMessage(content = text, isUser = true))
                        },
                        onStopTask = onStopTask,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                composable("conversations") {
                    ConversationListScreen(
                        conversations = conversations,
                        currentConversationId = currentConversation?.id,
                        onSelectConversation = { conv ->
                            selectConversation(conv)
                            navController.navigate("chat") {
                                popUpTo("conversations") { inclusive = true }
                            }
                        },
                        onNewConversation = {
                            createNewConversation()
                            navController.navigate("chat") {
                                popUpTo("conversations") { inclusive = true }
                            }
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
    val originalCoordinatorApiKey = remember { prefs.coordinatorApiKey }
    val originalCoordinatorModelName = remember { prefs.coordinatorModelName }
    val originalSupervisionEnabled = remember { prefs.supervisionEnabled }
    val originalMaxCorrections = remember { prefs.maxCorrections.toString() }

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
    var coordinatorApiKey by remember { mutableStateOf(prefs.coordinatorApiKey) }
    var coordinatorModelName by remember { mutableStateOf(prefs.coordinatorModelName) }
    var coordinatorModelDropdownExpanded by remember { mutableStateOf(false) }
    var supervisionEnabled by remember { mutableStateOf(prefs.supervisionEnabled) }
    var maxCorrections by remember { mutableStateOf(prefs.maxCorrections.toString()) }
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
            maxCorrections != originalMaxCorrections

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
        val modelSettings = if (isChinese) "模型设置" else "Model Settings"
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
        val enableSmartCoordinator = if (isChinese) "启用智能协调器" else "Enable Smart Coordinator"
        val smartCoordinatorDesc = if (isChinese) "使用更强模型解释指令和监督执行" else "Use stronger model to interpret instructions and supervise execution"
        val coordinatorApiUrlLabel = if (isChinese) "协调器 API URL" else "Coordinator API URL"
        val coordinatorApiKeyLabel = if (isChinese) "协调器 API Key" else "Coordinator API Key"
        val coordinatorModelLabel = if (isChinese) "协调器模型" else "Coordinator Model"
        val enableSupervision = if (isChinese) "启用执行监督" else "Enable Supervision"
        val supervisionDesc = if (isChinese) "检查每个子任务的执行结果" else "Check execution result of each subtask"
        val maxCorrectionsLabel = if (isChinese) "最大纠正次数" else "Max Corrections"
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
        prefs.coordinatorApiKey = coordinatorApiKey
        prefs.coordinatorModelName = coordinatorModelName
        prefs.supervisionEnabled = supervisionEnabled
        prefs.maxCorrections = maxCorrections.toIntOrNull() ?: 2
        Toast.makeText(context, strings.saved, Toast.LENGTH_SHORT).show()
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
                    Text(strings.enableSmartCoordinator, style = MaterialTheme.typography.bodyMedium)
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

            if (smartCoordinatorEnabled) {
                OutlinedTextField(
                    value = coordinatorApiUrl,
                    onValueChange = { coordinatorApiUrl = it },
                    label = { Text(strings.coordinatorApiUrlLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("DeepSeek: https://api.deepseek.com/v1") }
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
                    "glm-4" to "智谱 GLM-4"
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
