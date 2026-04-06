package com.autoglm.assistant.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.autoglm.assistant.ai.ModelApiHelper
import com.autoglm.assistant.util.PreferenceManager
import kotlinx.coroutines.launch

/**
 * 预设 API Provider 信息
 * 包含 URL、显示名、模型名前缀（用于切换时自动解析 API Key）
 */
data class ApiPreset(
    val url: String,
    val label: String,
    /** 该 provider 的默认模型名前缀，用于触发 resolveApiKey 自动填充 */
    val modelPrefix: String
)

/** 所有模块共用的预设 API Provider 列表 */
val PRESET_API_PROVIDERS = listOf(
    ApiPreset("https://api.deepseek.com/v1", "DeepSeek", "deepseek"),
    ApiPreset("https://open.bigmodel.cn/api/paas/v4", "\u667a\u8c31 BigModel", "glm-"),
    ApiPreset("https://ark.cn-beijing.volces.com/api/v3", "\u8c46\u5305 Doubao", "doubao"),
    ApiPreset("https://api.openai.com/v1", "OpenAI", ""),
    ApiPreset("https://dashscope.aliyuncs.com/compatible-mode/v1", "\u901a\u4e49\u5343\u95ee", "qwen")
)

/**
 * 可复用的模型 API 配置区块
 *
 * 【目的】四个模块（Agent / 协调器 / 优化器 / 意图识别）共享相同的
 *          API URL + API Key + 模型名 + 获取模型列表 的 UI 交互模式。
 *          提取为统一组件消除重复代码。
 *
 * 【API Key 联动机制】
 *   当用户从下拉列表选择新的 API URL 时：
 *   1. 根据 URL 匹配到 ApiPreset.modelPrefix
 *   2. 调用 PreferenceManager.resolveApiKey(prefix, module) 获取存储的 key
 *   3. 自动填充 API Key 输入框
 *   用户也可以手动修改 key，不会被覆盖（只有选择 preset 时才触发）
 *
 * @param apiUrl        当前 API URL 状态
 * @param onApiUrlChange URL 变更回调
 * @param apiKey        当前 API Key 状态
 * @param onApiKeyChange Key 变更回调
 * @param modelName     当前模型名称状态
 * @param onModelNameChange 模型名变更回调
 * @param module        API 模块标识（用于 resolveApiKey）
 * @param prefs         PreferenceManager 实例
 * @param isChinese     是否中文
 * @param apiUrlLabel   API URL 输入框标签
 * @param apiKeyLabel   API Key 输入框标签
 * @param modelLabel    模型名输入框标签
 * @param presetModels  可选的快捷模型选择列表 (modelId to displayName)
 * @param extraContent  额外内容（如 Prompt 编辑器），插在模型选择下方
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelApiConfigSection(
    apiUrl: TextFieldValue,
    onApiUrlChange: (TextFieldValue) -> Unit,
    apiKey: TextFieldValue,
    onApiKeyChange: (TextFieldValue) -> Unit,
    modelName: TextFieldValue,
    onModelNameChange: (TextFieldValue) -> Unit,
    module: PreferenceManager.ApiModule,
    prefs: PreferenceManager,
    isChinese: Boolean,
    apiUrlLabel: String = "API URL",
    apiKeyLabel: String = "API Key",
    modelLabel: String = if (isChinese) "\u6a21\u578b\u540d\u79f0" else "Model Name",
    presetModels: List<Pair<String, String>> = emptyList(),
    extraContent: @Composable () -> Unit = {}
) {
    // ---- 1. 获取模型列表的本地状态 ----
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchingModels by remember { mutableStateOf(false) }
    var urlDropdownExpanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // ---- 2. API URL 下拉选择 + 自定义输入 ----
    ExposedDropdownMenuBox(
        expanded = urlDropdownExpanded,
        onExpandedChange = { urlDropdownExpanded = it }
    ) {
        OutlinedTextField(
            value = apiUrl,
            onValueChange = onApiUrlChange,
            label = { Text(apiUrlLabel) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            singleLine = true,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = urlDropdownExpanded)
            }
        )
        ExposedDropdownMenu(
            expanded = urlDropdownExpanded,
            onDismissRequest = { urlDropdownExpanded = false }
        ) {
            PRESET_API_PROVIDERS.forEach { preset ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(preset.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                preset.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        // 步骤 A: 更新 URL
                        onApiUrlChange(TextFieldValue(preset.url))
                        urlDropdownExpanded = false
                        // 步骤 B: 联动 API Key -- 根据 provider 前缀自动填充已存储的 key
                        if (preset.modelPrefix.isNotEmpty()) {
                            val resolvedKey = prefs.resolveApiKey(preset.modelPrefix, module)
                            if (resolvedKey.isNotBlank()) {
                                onApiKeyChange(TextFieldValue(resolvedKey))
                            }
                        }
                    }
                )
            }
        }
    }

    // ---- 3. API Key 输入框 ----
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text(apiKeyLabel) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )

    // ---- 4. 模型名称输入框（可编辑） ----
    OutlinedTextField(
        value = modelName,
        onValueChange = onModelNameChange,
        label = { Text(modelLabel) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        supportingText = {
            Text(if (isChinese) "\u53ef\u8f93\u5165\u4efb\u610f\u6a21\u578b\u540d\u79f0" else "Enter any model name")
        }
    )

    // ---- 5. 从 /models API 获取模型列表 ----
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = {
                fetchingModels = true
                coroutineScope.launch {
                    val models = ModelApiHelper.fetchModels(apiUrl.text, apiKey.text)
                    fetchedModels = models
                    fetchingModels = false
                }
            },
            enabled = !fetchingModels && apiUrl.text.isNotBlank() && apiKey.text.isNotBlank()
        ) {
            if (fetchingModels) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                if (fetchingModels) {
                    if (isChinese) "\u83b7\u53d6\u4e2d..." else "Fetching..."
                } else {
                    if (isChinese) "\u83b7\u53d6\u6a21\u578b\u5217\u8868" else "Fetch Models"
                }
            )
        }
        if (fetchedModels.isNotEmpty()) {
            Text(
                "${fetchedModels.size} ${if (isChinese) "\u4e2a\u6a21\u578b" else "models"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    // 从 API 获取的模型列表（可选择）
    if (fetchedModels.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            fetchedModels.forEach { model ->
                FilterChip(
                    selected = modelName.text == model,
                    onClick = { onModelNameChange(TextFieldValue(model)) },
                    label = { Text(model, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }

    // ---- 6. 快捷选择预设模型 ----
    if (presetModels.isNotEmpty()) {
        Text(
            text = if (isChinese) "\u5feb\u6377\u9009\u62e9\uff1a" else "Quick select:",
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
            presetModels.forEach { (model, displayName) ->
                FilterChip(
                    selected = modelName.text == model,
                    onClick = {
                        onModelNameChange(TextFieldValue(model))
                        // 切换预设模型时也联动 API Key
                        val resolvedKey = prefs.resolveApiKey(model, module)
                        if (resolvedKey.isNotBlank()) {
                            onApiKeyChange(TextFieldValue(resolvedKey))
                        }
                        // 联动 API URL
                        val resolvedUrl = prefs.resolveDefaultApiUrl(model)
                        if (resolvedUrl.isNotBlank()) {
                            onApiUrlChange(TextFieldValue(resolvedUrl))
                        }
                    },
                    label = { Text(displayName, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }

    // ---- 7. 额外内容插槽 ----
    extraContent()
}
