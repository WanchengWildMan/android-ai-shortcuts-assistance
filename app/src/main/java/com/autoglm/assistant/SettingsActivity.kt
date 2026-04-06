package com.autoglm.assistant

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoglm.assistant.service.WakeWordService
import com.autoglm.assistant.ui.theme.AutoGLMAssistantTheme
import com.autoglm.assistant.util.PreferenceManager
import kotlinx.coroutines.launch
import com.autoglm.assistant.util.Logger

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
    val originalImeVoiceKeyboardDelay = remember { prefs.imeVoiceKeyboardDelay.toString() }
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
        prefs.resolveApiKey(prefs.coordinatorModelName, PreferenceManager.ApiModule.COORDINATOR)
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
        prefs.resolveApiKey(prefs.optimizerModelName, PreferenceManager.ApiModule.OPTIMIZER)
    }
    val originalOptimizerModelName = remember { prefs.optimizerModelName }
    val originalTaskSummaryEnabled = remember { prefs.taskSummaryEnabled }
    val originalOptimizerSystemPrompt = remember { prefs.optimizerSystemPrompt }
    // Intent Recognizer originals
    val originalIntentRecognizerEnabled = remember { prefs.intentRecognizerEnabled }
    val originalIntentApiUrl = remember { prefs.intentApiUrl }
    val originalIntentApiKey = remember {
        prefs.resolveApiKey(prefs.intentModelName, PreferenceManager.ApiModule.INTENT)
    }
    val originalIntentModelName = remember { prefs.intentModelName }

    var apiUrl by remember { mutableStateOf(TextFieldValue(prefs.apiUrl)) }
    var apiKey by remember { mutableStateOf(TextFieldValue(prefs.apiKey)) }
    var modelName by remember { mutableStateOf(TextFieldValue(prefs.modelName)) }

    var agentSystemPrompt by remember { mutableStateOf(TextFieldValue(prefs.agentSystemPrompt)) }
    var porcupineKey by remember { mutableStateOf(TextFieldValue(prefs.porcupineAccessKey)) }
    var wakeWordKeyword by remember { mutableStateOf(prefs.wakeWordKeyword) }
    var wakeWordDropdownExpanded by remember { mutableStateOf(false) }
    var wakeEngineType by remember { mutableStateOf(prefs.wakeEngineType) }
    var wakeEngineDropdownExpanded by remember { mutableStateOf(false) }
    // 自定义 ppn 模型文件状态
    var customPpnName by remember { mutableStateOf(prefs.customPpnName) }
    var customPpnPath by remember { mutableStateOf(prefs.customPpnPath) }
    val originalCustomPpnPath = remember { prefs.customPpnPath }
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

                // 步骤3: 立即写入 prefs 并更新 UI 状态（ppn 文件只在此处变更，不经过 saveSettings 弹窗）
                customPpnName = fileName
                customPpnPath = destFile.absolutePath
                wakeWordKeyword = "CUSTOM"  // 自动切换到自定义模型
                prefs.customPpnName = fileName
                prefs.customPpnPath = destFile.absolutePath
                prefs.wakeWordKeyword = "CUSTOM"

                Logger.i(Logger.SETTINGS, "自定义 ppn 模型已导入: $fileName -> ${destFile.absolutePath}")
                android.widget.Toast.makeText(context, "模型文件已导入: $fileName", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.e(Logger.SETTINGS, "导入 ppn 文件失败: ${e.message}", e)
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
                Logger.e(Logger.SETTINGS, "导出配置失败: ${e.message}", e)
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
                Logger.e(Logger.SETTINGS, "导入配置失败: ${e.message}", e)
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
    // IME 语音输入坐标及键盘就绪延迟配置
    var imeVoiceSpaceX by remember { mutableStateOf(TextFieldValue(prefs.imeVoiceSpaceX.toString())) }
    var imeVoiceSpaceY by remember { mutableStateOf(TextFieldValue(prefs.imeVoiceSpaceY.toString())) }
    var imeVoiceKeyboardDelay by remember { mutableStateOf(TextFieldValue(prefs.imeVoiceKeyboardDelay.toString())) }
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
        mutableStateOf(
            TextFieldValue(
                prefs.resolveApiKey(prefs.coordinatorModelName, PreferenceManager.ApiModule.COORDINATOR)
            )
        )
    }
    var coordinatorModelName by remember { mutableStateOf(TextFieldValue(prefs.coordinatorModelName)) }
    var coordinatorSystemPrompt by remember { mutableStateOf(TextFieldValue(prefs.coordinatorSystemPrompt)) }

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
        mutableStateOf(
            TextFieldValue(
                prefs.resolveApiKey(prefs.optimizerModelName, PreferenceManager.ApiModule.OPTIMIZER)
            )
        )
    }
    var optimizerModelName by remember { mutableStateOf(TextFieldValue(prefs.optimizerModelName)) }

    var taskSummaryEnabled by remember { mutableStateOf(prefs.taskSummaryEnabled) }
    var optimizerSystemPrompt by remember { mutableStateOf(TextFieldValue(prefs.optimizerSystemPrompt)) }
    // Intent Recognizer states
    var intentRecognizerEnabled by remember { mutableStateOf(prefs.intentRecognizerEnabled) }
    var intentApiUrl by remember { mutableStateOf(TextFieldValue(prefs.intentApiUrl)) }
    var intentApiKey by remember {
        mutableStateOf(
            TextFieldValue(
                prefs.resolveApiKey(prefs.intentModelName, PreferenceManager.ApiModule.INTENT)
            )
        )
    }
    var intentModelName by remember { mutableStateOf(TextFieldValue(prefs.intentModelName)) }

    var showExitDialog by remember { mutableStateOf(false) }

    // Check if any setting has changed
    val hasChanges = apiUrl.text != originalApiUrl ||
            apiKey.text != originalApiKey ||
            modelName.text != originalModelName ||
            agentSystemPrompt.text != originalAgentSystemPrompt ||
            porcupineKey.text != originalPorcupineKey ||
            wakeWordKeyword != originalWakeWordKeyword ||
            wakeEngineType != originalWakeEngineType ||
            customPpnPath != originalCustomPpnPath ||
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
            imeVoiceKeyboardDelay.text != originalImeVoiceKeyboardDelay ||
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
        // IME 语音坐标及键盘就绪延迟
        prefs.imeVoiceSpaceX = imeVoiceSpaceX.text.toIntOrNull() ?: PreferenceManager.DEFAULT_IME_VOICE_SPACE_X
        prefs.imeVoiceSpaceY = imeVoiceSpaceY.text.toIntOrNull() ?: PreferenceManager.DEFAULT_IME_VOICE_SPACE_Y
        prefs.imeVoiceKeyboardDelay = imeVoiceKeyboardDelay.text.toLongOrNull() ?: PreferenceManager.DEFAULT_IME_VOICE_KEYBOARD_DELAY_MS
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
        // 保存协调器 API Key 到对应厂商槽位
        prefs.saveApiKey(coordinatorModelName.text, PreferenceManager.ApiModule.COORDINATOR, coordinatorApiKey.text)
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
        // 保存优化器 API Key 到对应厂商槽位
        prefs.saveApiKey(optimizerModelName.text, PreferenceManager.ApiModule.OPTIMIZER, optimizerApiKey.text)
        prefs.optimizerModelName = optimizerModelName.text
        prefs.taskSummaryEnabled = taskSummaryEnabled
        prefs.optimizerSystemPrompt = optimizerSystemPrompt.text
        // Intent Recognizer settings
        prefs.intentRecognizerEnabled = intentRecognizerEnabled
        prefs.intentApiUrl = intentApiUrl.text
        // 保存意图识别器 API Key 到对应厂商槽位
        prefs.saveApiKey(intentModelName.text, PreferenceManager.ApiModule.INTENT, intentApiKey.text)
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

    // Handle system back button — 始终弹出确认框，防止意外丢失修改
    androidx.activity.compose.BackHandler(enabled = true) {
        showExitDialog = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.title) },
                navigationIcon = {
                    IconButton(onClick = {
                        showExitDialog = true
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

            // Agent 模型 API 配置（URL + Key + 模型选择 + 获取模型列表）
            com.autoglm.assistant.ui.settings.ModelApiConfigSection(
                apiUrl = apiUrl,
                onApiUrlChange = { apiUrl = it },
                apiKey = apiKey,
                onApiKeyChange = { apiKey = it },
                modelName = modelName,
                onModelNameChange = { modelName = it },
                module = PreferenceManager.ApiModule.AGENT,
                prefs = prefs,
                isChinese = isChinese,
                apiUrlLabel = strings.apiUrlLabel,
                apiKeyLabel = strings.apiKeyLabel,
                modelLabel = strings.modelNameLabel
            )

            // Agent 自定义系统提示词
            com.autoglm.assistant.ui.settings.PromptEditorSection(
                title = if (isChinese) "自定义 Agent 提示词" else "Custom Agent Prompt",
                prompt = agentSystemPrompt,
                onPromptChange = { agentSystemPrompt = it },
                defaultPrompt = if (isChinese)
                    com.autoglm.assistant.ai.MessageBuilder.DEFAULT_SYSTEM_PROMPT_CN
                else
                    com.autoglm.assistant.ai.MessageBuilder.DEFAULT_SYSTEM_PROMPT_EN,
                isChinese = isChinese,
                hintText = if (isChinese) "自定义 Agent 的系统角色设定，留空则使用内置的默认提示词"
                    else "Customize Agent system role. Leave empty to use built-in default",
                showViewDefault = false
            )

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
                    OutlinedTextField(
                        value = imeVoiceKeyboardDelay,
                        onValueChange = { imeVoiceKeyboardDelay = it },
                        label = { Text(if (isChinese) "键盘就绪延迟（毫秒）" else "Keyboard Ready Delay (ms)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(if (isChinese) "弹出键盘后等待多久再点击语音按钮，过短会导致点击无效（默认 1200）" else "Wait time after keyboard appears before tapping voice button (default 1200)") }
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

            com.autoglm.assistant.ui.settings.SwitchSettingRow(
                title = strings.showProcess,
                description = strings.showProcessDesc,
                checked = showAgentProcess,
                onCheckedChange = { showAgentProcess = it }
            )

            Divider()

            // SmartCoordinator Settings
            Text(strings.smartCoordinatorSettings, style = MaterialTheme.typography.titleMedium)

            com.autoglm.assistant.ui.settings.SwitchSettingRow(
                title = if (isChinese) "启用智能协调器 (全局)" else "Enable Smart Coordinator (Global)",
                description = strings.smartCoordinatorDesc,
                checked = smartCoordinatorEnabled,
                onCheckedChange = { smartCoordinatorEnabled = it }
            )

            com.autoglm.assistant.ui.settings.SwitchSettingRow(
                title = strings.showCoordinatorThinking,
                description = strings.showCoordinatorThinkingDesc,
                checked = showCoordinatorThinking,
                onCheckedChange = { showCoordinatorThinking = it }
            )

            // 协调器模型 API 配置
            val coordinatorPresetModels = listOf(
                "deepseek-chat" to "DeepSeek Chat",
                "glm-4-plus" to "\u667a\u8c31 GLM-4 Plus",
                "glm-4" to "\u667a\u8c31 GLM-4",
                "doubao-seed-1-6-251015" to "\u8c46\u5305 Seed 1.6"
            )
            com.autoglm.assistant.ui.settings.ModelApiConfigSection(
                apiUrl = coordinatorApiUrl,
                onApiUrlChange = { coordinatorApiUrl = it },
                apiKey = coordinatorApiKey,
                onApiKeyChange = { coordinatorApiKey = it },
                modelName = coordinatorModelName,
                onModelNameChange = { coordinatorModelName = it },
                module = PreferenceManager.ApiModule.COORDINATOR,
                prefs = prefs,
                isChinese = isChinese,
                apiUrlLabel = strings.coordinatorApiUrlLabel,
                apiKeyLabel = strings.coordinatorApiKeyLabel,
                modelLabel = strings.coordinatorModelLabel,
                presetModels = coordinatorPresetModels
            )

            // 启用模型思考
            com.autoglm.assistant.ui.settings.SwitchSettingRow(
                title = strings.coordinatorEnableThinkingLabel,
                description = strings.coordinatorEnableThinkingDesc,
                checked = coordinatorEnableThinking,
                onCheckedChange = { coordinatorEnableThinking = it }
            )

            // 模型是否支持图像（Vision）
            com.autoglm.assistant.ui.settings.SwitchSettingRow(
                title = if (isChinese) "\u6a21\u578b\u652f\u6301\u56fe\u50cf (Vision)" else "Model Supports Vision",
                description = if (isChinese) "\u542f\u7528\u540e\u534f\u8c03\u5668\u4f1a\u53d1\u9001\u622a\u56fe\u8f85\u52a9\u51b3\u7b56" else "When enabled, coordinator sends screenshots for decision making",
                checked = coordinatorEnableVision,
                onCheckedChange = { coordinatorEnableVision = it }
            )

            // 协调器自定义系统提示词
            com.autoglm.assistant.ui.settings.PromptEditorSection(
                title = if (isChinese) "\u81ea\u5b9a\u4e49\u89c4\u5212\u5668\u63d0\u793a\u8bcd" else "Custom Planner Prompt",
                prompt = coordinatorSystemPrompt,
                onPromptChange = { coordinatorSystemPrompt = it },
                defaultPrompt = if (isChinese)
                    com.autoglm.assistant.core.planner.SmartCoordinator.DECISION_SYSTEM_PROMPT_CN
                else
                    com.autoglm.assistant.core.planner.SmartCoordinator.DECISION_SYSTEM_PROMPT_EN,
                isChinese = isChinese,
                hintText = if (isChinese)
                    "\u534f\u8c03\u5668\u8d1f\u8d23\u89c4\u5212\u6bcf\u4e00\u6b65\u7684\u76ee\u6807\uff0c\u4e0d\u8d1f\u8d23\u5177\u4f53\u64cd\u4f5c\u3002\u7559\u7a7a\u4f7f\u7528\u5185\u7f6e\u9ed8\u8ba4\u63d0\u793a\u8bcd\u3002"
                else
                    "Coordinator plans each step's goal, not specific operations. Leave empty to use built-in default.",
                showViewDefault = true
            )

            // 执行监督开关
            com.autoglm.assistant.ui.settings.SwitchSettingRow(
                title = strings.enableSupervision,
                description = strings.supervisionDesc,
                checked = supervisionEnabled,
                onCheckedChange = { supervisionEnabled = it }
            )

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

            com.autoglm.assistant.ui.settings.SwitchSettingRow(
                title = strings.enablePromptOptimizer,
                description = strings.promptOptimizerDesc,
                checked = promptOptimizerEnabled,
                onCheckedChange = { promptOptimizerEnabled = it }
            )

            if (promptOptimizerEnabled) {
                // 优化器模型 API 配置
                val optimizerPresetModels = listOf(
                    "deepseek-chat" to "DeepSeek Chat",
                    "glm-4-plus" to "\u667a\u8c31 GLM-4 Plus",
                    "glm-4" to "\u667a\u8c31 GLM-4",
                    "doubao-seed-1-6-251015" to "\u8c46\u5305 Seed 1.6"
                )
                com.autoglm.assistant.ui.settings.ModelApiConfigSection(
                    apiUrl = optimizerApiUrl,
                    onApiUrlChange = { optimizerApiUrl = it },
                    apiKey = optimizerApiKey,
                    onApiKeyChange = { optimizerApiKey = it },
                    modelName = optimizerModelName,
                    onModelNameChange = { optimizerModelName = it },
                    module = PreferenceManager.ApiModule.OPTIMIZER,
                    prefs = prefs,
                    isChinese = isChinese,
                    apiUrlLabel = strings.optimizerApiUrlLabel,
                    apiKeyLabel = strings.optimizerApiKeyLabel,
                    modelLabel = strings.optimizerModelLabel,
                    presetModels = optimizerPresetModels
                )

                // 任务完成后自动总结
                com.autoglm.assistant.ui.settings.SwitchSettingRow(
                    title = if (isChinese) "\u4efb\u52a1\u5b8c\u6210\u540e\u81ea\u52a8\u603b\u7ed3" else "Auto-summarize on task completion",
                    description = if (isChinese) "\u4efb\u52a1\u5b8c\u6210\u6216\u7ec8\u6b62\u540e\u81ea\u52a8\u751f\u6210\u603b\u7ed3\u8bf4\u660e" else "Automatically generate summary when task completes or stops",
                    checked = taskSummaryEnabled,
                    onCheckedChange = { taskSummaryEnabled = it }
                )

                // 优化器自定义系统提示词
                com.autoglm.assistant.ui.settings.PromptEditorSection(
                    title = if (isChinese) "\u81ea\u5b9a\u4e49\u4f18\u5316\u5668\u63d0\u793a\u8bcd" else "Custom Optimizer Prompt",
                    prompt = optimizerSystemPrompt,
                    onPromptChange = { optimizerSystemPrompt = it },
                    defaultPrompt = if (isChinese)
                        com.autoglm.assistant.core.planner.PromptOptimizer.SYSTEM_PROMPT_CN
                    else
                        com.autoglm.assistant.core.planner.PromptOptimizer.SYSTEM_PROMPT_EN,
                    isChinese = isChinese,
                    hintText = if (isChinese)
                        "\u4f18\u5316\u5668\u8d1f\u8d23\u5c06\u7528\u6237\u6307\u4ee4\u8f6c\u6362\u4e3a\u7ed3\u6784\u5316\u4efb\u52a1\u63cf\u8ff0\u3002\u7559\u7a7a\u4f7f\u7528\u5185\u7f6e\u9ed8\u8ba4\u63d0\u793a\u8bcd\u3002"
                    else
                        "Optimizer converts user instructions to structured task descriptions. Leave empty to use built-in default.",
                    showViewDefault = true
                )
            }

            Divider()

            // ===== 意图识别设置 =====
            Text(
                strings.intentRecognizerSettings,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )

            // 意图识别开关
            com.autoglm.assistant.ui.settings.SwitchSettingRow(
                title = strings.enableIntentRecognizer,
                description = strings.intentRecognizerDesc,
                checked = intentRecognizerEnabled,
                onCheckedChange = { intentRecognizerEnabled = it }
            )

            if (intentRecognizerEnabled) {
                // 意图识别模型 API 配置
                val intentPresetModels = listOf(
                    "deepseek-chat" to "DeepSeek Chat",
                    "glm-4-plus" to "\u667a\u8c31 GLM-4 Plus",
                    "glm-4" to "\u667a\u8c31 GLM-4",
                    "doubao-seed-1-6-251015" to "\u8c46\u5305 Seed 1.6"
                )
                com.autoglm.assistant.ui.settings.ModelApiConfigSection(
                    apiUrl = intentApiUrl,
                    onApiUrlChange = { intentApiUrl = it },
                    apiKey = intentApiKey,
                    onApiKeyChange = { intentApiKey = it },
                    modelName = intentModelName,
                    onModelNameChange = { intentModelName = it },
                    module = PreferenceManager.ApiModule.INTENT,
                    prefs = prefs,
                    isChinese = isChinese,
                    apiUrlLabel = strings.intentApiUrlLabel,
                    apiKeyLabel = strings.intentApiKeyLabel,
                    modelLabel = strings.intentModelLabel,
                    presetModels = intentPresetModels
                )
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
            if (imeVoiceKeyboardDelay.text != originalImeVoiceKeyboardDelay) add(if (isChinese) "IME 键盘就绪延迟" else "IME Keyboard Delay")
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
            title = { Text(if (isChinese) (if (hasChanges) "以下设置已修改" else "是否保存设置？") else (if (hasChanges) "Settings Changed" else "Save Settings?")) },
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
