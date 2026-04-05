package com.autoglm.assistant.ai

import com.autoglm.assistant.ui.home.ShortcutData
import com.autoglm.assistant.util.Logger
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.withTimeout

/**
 * 意图识别器
 * 使用 LLM 将用户自然语言输入匹配到已配置的快捷指令，
 * 自动提取参数并生成结构化的 Agent 指令。
 *
 * 流程：
 * 1. 将用户输入 + 可用快捷指令列表发送给 LLM
 * 2. LLM 返回 JSON：{matched, shortcutId, parameters}
 * 3. 如果匹配成功，用提取的参数填充快捷指令模板
 * 4. 返回 IntentResult（包含填充后的 prompt 和是否启用规划）
 */
class IntentRecognizer(
    private val modelConfig: ModelConfig
) {
    private val client = ModelClient(modelConfig)
    private val gson = Gson()

    // 回调：通知 UI 正在识别
    var onRecognizing: (() -> Unit)? = null
    var onRecognized: ((IntentResult) -> Unit)? = null

    /**
     * 识别用户输入的意图，尝试匹配快捷指令
     * @param userInput 用户原始输入
     * @param shortcuts 可用的快捷指令列表
     * @param language 语言 cn/en
     * @return IntentResult，matched=true 时包含填充后的 prompt
     */
    suspend fun recognize(
        userInput: String,
        shortcuts: List<ShortcutData>,
        language: String = "cn"
    ): IntentResult {
        if (shortcuts.isEmpty()) {
            Logger.i(Logger.AGENT, "IntentRecognizer: no shortcuts available, skipping")
            return IntentResult.notMatched()
        }

        Logger.i(Logger.AGENT, "========== INTENT RECOGNIZER: START ==========")
        Logger.i(Logger.AGENT, "User input: $userInput")
        Logger.i(Logger.AGENT, "Available shortcuts: ${shortcuts.size}")
        Logger.startTimer("intent_recognition")

        onRecognizing?.invoke()

        return try {
            val result = withTimeout(RECOGNITION_TIMEOUT_MS) {
                performRecognition(userInput, shortcuts, language)
            }
            Logger.endTimer("intent_recognition", Logger.AGENT)
            Logger.i(Logger.AGENT, "IntentRecognizer result: matched=${result.matched}, shortcut=${result.matchedShortcutTitle}")
            onRecognized?.invoke(result)
            result
        } catch (e: Exception) {
            Logger.e(Logger.AGENT, "IntentRecognizer failed: ${e.message}")
            // 识别失败不阻塞流程，返回未匹配
            IntentResult.notMatched()
        }
    }

    /**
     * 执行 LLM 意图识别
     */
    private suspend fun performRecognition(
        userInput: String,
        shortcuts: List<ShortcutData>,
        language: String
    ): IntentResult {
        // 步骤1: 构建快捷指令描述列表（含备注信息供识别参考）
        val shortcutDescriptions = shortcuts.mapIndexed { index, shortcut ->
            val params = if (shortcut.hasParameters()) {
                "，参数: ${shortcut.getParameterNames().joinToString(", ") { "{$it}" }}"
            } else {
                ""
            }
            val desc = if (shortcut.description.isNotBlank()) {
                "（${shortcut.description}）"
            } else {
                ""
            }
            "${index + 1}. [id=${shortcut.id}] ${shortcut.title}$desc → 模板: \"${shortcut.prompt}\"$params"
        }.joinToString("\n")

        // 步骤2: 构建系统提示词
        val systemPrompt = if (language == "cn") {
            SYSTEM_PROMPT_CN
        } else {
            SYSTEM_PROMPT_EN
        }

        // 步骤3: 构建用户消息
        val userMessage = buildString {
            append("可用快捷指令：\n")
            append(shortcutDescriptions)
            append("\n\n用户输入：\"$userInput\"")
        }

        // 步骤4: 调用 LLM
        val messages = listOf(
            Message.System(systemPrompt),
            Message.User(userMessage)
        )
        val response = client.chatSync(messages)

        // 步骤5: 解析 LLM 返回的 JSON
        return parseRecognitionResponse(response.rawContent, shortcuts)
    }

    /**
     * 解析 LLM 返回的意图识别 JSON 结果
     */
    private fun parseRecognitionResponse(
        rawContent: String,
        shortcuts: List<ShortcutData>
    ): IntentResult {
        // 移除可能的 markdown 代码块标记
        val jsonStr = rawContent
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()

        return try {
            val json = JsonParser.parseString(jsonStr).asJsonObject
            val matched = json.get("matched")?.asBoolean ?: false

            if (!matched) {
                return IntentResult.notMatched()
            }

            val shortcutId = json.get("shortcutId")?.asString ?: return IntentResult.notMatched()
            val matchedShortcut = shortcuts.find { it.id == shortcutId } ?: return IntentResult.notMatched()

            // 提取参数
            val parameters = mutableMapOf<String, String>()
            json.getAsJsonObject("parameters")?.entrySet()?.forEach { (key, value) ->
                parameters[key] = value.asString
            }

            // 用参数填充模板
            val filledPrompt = matchedShortcut.fillParameters(parameters)

            IntentResult(
                matched = true,
                matchedShortcutId = shortcutId,
                matchedShortcutTitle = matchedShortcut.title,
                parameters = parameters,
                filledPrompt = filledPrompt,
                enablePlanning = matchedShortcut.enablePlanning
            )
        } catch (e: Exception) {
            Logger.e(Logger.AGENT, "Failed to parse intent JSON: $jsonStr, error: ${e.message}")
            IntentResult.notMatched()
        }
    }

    companion object {
        // 意图识别超时时间（不需要太长，只做分类）
        private const val RECOGNITION_TIMEOUT_MS = 15_000L

        private val SYSTEM_PROMPT_CN: String get() = PromptRegistry.get(PromptKey.INTENT_SYSTEM_CN)

        private val SYSTEM_PROMPT_EN: String get() = PromptRegistry.get(PromptKey.INTENT_SYSTEM_EN)

        /**
         * 从 OpenAI 兼容的 /models 端点获取可用模型列表
         * @param baseUrl API 基础地址
         * @param apiKey API Key
         * @return 模型 ID 列表，失败返回空列表
         */
        suspend fun fetchAvailableModels(baseUrl: String, apiKey: String): List<String> {
            return ModelApiHelper.fetchModels(baseUrl, apiKey)
        }
    }
}

/**
 * 意图识别结果
 */
data class IntentResult(
    val matched: Boolean,
    val matchedShortcutId: String? = null,
    val matchedShortcutTitle: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val filledPrompt: String? = null,
    val enablePlanning: Boolean = true
) {
    companion object {
        fun notMatched() = IntentResult(matched = false)
    }
}
