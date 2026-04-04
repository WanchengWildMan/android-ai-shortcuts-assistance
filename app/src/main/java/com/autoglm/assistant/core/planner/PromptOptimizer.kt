package com.autoglm.assistant.core.planner

import com.autoglm.assistant.ai.Message
import com.autoglm.assistant.ai.ModelClient
import com.autoglm.assistant.ai.ModelConfig
import com.autoglm.assistant.util.Logger
import kotlinx.coroutines.withTimeout

/**
 * Prompt优化器
 * 将用户的简短指令扩展为更详细、具体的任务描述
 * 帮助UI Agent更好地理解和执行任务
 */
class PromptOptimizer(
    private val modelConfig: ModelConfig,
    private val customSystemPrompt: String = ""
) {
    private val client = ModelClient(modelConfig)

    // 回调
    var onOptimizing: (() -> Unit)? = null
    var onOptimized: ((String) -> Unit)? = null
    var onStreamToken: ((String) -> Unit)? = null
    var onSummarizing: (() -> Unit)? = null
    var onSummarized: ((String) -> Unit)? = null

    /**
     * 优化用户prompt
     * @param userPrompt 用户原始输入
     * @param language 语言 cn/en
     * @param conversationContext 对话上下文（可选），用于理解"继续"等指代性指令
     * @return 优化后的prompt，如果优化失败则返回原始prompt
     */
    suspend fun optimize(userPrompt: String, language: String = "cn", conversationContext: List<Pair<String, String>> = emptyList()): String {
        Logger.i(Logger.AGENT, "========== PROMPT OPTIMIZER: START ==========")
        Logger.i(Logger.AGENT, "Original prompt: $userPrompt")
        Logger.i(Logger.AGENT, "Context messages: ${conversationContext.size}")
        Logger.startTimer("prompt_optimization")

        onOptimizing?.invoke()

        try {
            // 步骤1: 选择系统提示词 — 优先使用自定义提示词，否则按语言选择内置默认
            val systemPrompt = if (customSystemPrompt.isNotBlank()) {
                customSystemPrompt
            } else if (language == "en") {
                SYSTEM_PROMPT_EN
            } else {
                SYSTEM_PROMPT_CN
            }
            val userMessage = buildUserPrompt(userPrompt, language, conversationContext)

            val messages = listOf(
                Message.System(systemPrompt),
                Message.User(userMessage)
            )

            Logger.i(Logger.AGENT, "Calling optimizer model: ${modelConfig.modelName}")

            val response = withTimeout(30000L) {
                client.chat(messages, object : ModelClient.StreamCallback {
                    override fun onToken(token: String) {
                        onStreamToken?.invoke(token)
                    }

                    override fun onThinkingComplete(thinking: String) {
                        Logger.d(Logger.AGENT, "[OPTIMIZER] Thinking: ${thinking.take(100)}...")
                    }

                    override fun onComplete(response: com.autoglm.assistant.ai.ModelResponse) {
                        Logger.i(Logger.AGENT, "[OPTIMIZER] Complete in ${response.totalTime}ms")
                    }

                    override fun onError(error: String) {
                        Logger.e(Logger.AGENT, "[OPTIMIZER] Error: $error")
                    }
                })
            }

            // 使用rawContent而不是action，因为优化器返回的是自然语言，不是Agent动作格式
            // 同时移除可能的思考标签（如DeepSeek的<think>标签）
            val rawContent = response.rawContent.trim()
            val optimizedPrompt = stripThinkingTags(rawContent)
            val time = Logger.endTimer("prompt_optimization", Logger.AGENT)
            Logger.i(Logger.AGENT, "Optimized prompt (${time}ms): $optimizedPrompt")
            Logger.i(Logger.AGENT, "========== PROMPT OPTIMIZER: END ==========")

            // 如果优化后的prompt为空或太短，使用原始prompt
            if (optimizedPrompt.isBlank() || optimizedPrompt.length < userPrompt.length / 2) {
                Logger.w(Logger.AGENT, "Optimized prompt too short or empty, using original")
                onOptimized?.invoke(userPrompt)
                return userPrompt
            }

            onOptimized?.invoke(optimizedPrompt)
            return optimizedPrompt

        } catch (e: Exception) {
            Logger.e(Logger.AGENT, "Prompt optimization failed, using original", e)
            Logger.endTimer("prompt_optimization", Logger.AGENT)
            return userPrompt
        }
    }

    private fun buildUserPrompt(userPrompt: String, language: String, conversationContext: List<Pair<String, String>>): String {
        val contextSection = if (conversationContext.isNotEmpty()) {
            val contextStr = conversationContext.takeLast(6).joinToString("\n") { (role, content) ->
                val roleLabel = if (language == "en") {
                    if (role == "user") "User" else "Assistant"
                } else {
                    if (role == "user") "用户" else "助手"
                }
                "$roleLabel: $content"
            }
            if (language == "en") {
                "\n\nRecent conversation context:\n$contextStr\n"
            } else {
                "\n\n最近的对话上下文：\n$contextStr\n"
            }
        } else ""

        val key = if (language == "en") com.autoglm.assistant.ai.PromptKey.OPTIMIZER_USER_EN
                  else com.autoglm.assistant.ai.PromptKey.OPTIMIZER_USER_CN
        return com.autoglm.assistant.ai.PromptRegistry.get(key, mapOf(
            "context_section" to contextSection,
            "user_prompt" to userPrompt
        ))
    }

    /**
     * 优化用户干预指令
     * 结合原始任务和已执行步骤上下文，将用户的干预纠正指令优化为清晰的任务调整描述
     * @param interventionInstruction 用户输入的干预指令
     * @param originalTask 原始任务描述
     * @param executionHistory 已执行步骤的对话历史（role, content）
     * @param language 语言 cn/en
     * @return 优化后的干预指令，失败则返回原始指令
     */
    suspend fun optimizeIntervention(
        interventionInstruction: String,
        originalTask: String,
        executionHistory: List<Pair<String, String>>,
        language: String = "cn"
    ): String {
        Logger.i(Logger.AGENT, "========== INTERVENTION OPTIMIZER: START ==========")
        Logger.i(Logger.AGENT, "Original task: $originalTask")
        Logger.i(Logger.AGENT, "Intervention: $interventionInstruction")
        Logger.i(Logger.AGENT, "Execution history size: ${executionHistory.size}")
        Logger.startTimer("intervention_optimization")

        onOptimizing?.invoke()

        try {
            // 步骤1: 选择系统提示词（复用任务优化器的系统提示词）
            val systemPrompt = if (customSystemPrompt.isNotBlank()) {
                customSystemPrompt
            } else if (language == "en") {
                SYSTEM_PROMPT_EN
            } else {
                SYSTEM_PROMPT_CN
            }

            // 步骤2: 从执行历史中提取摘要（取最近的assistant回复，提取action部分）
            val executionSummary = buildExecutionSummary(executionHistory, language)

            // 步骤3: 构建干预优化用户提示词
            val key = if (language == "en") com.autoglm.assistant.ai.PromptKey.INTERVENTION_USER_EN
                      else com.autoglm.assistant.ai.PromptKey.INTERVENTION_USER_CN
            val userMessage = com.autoglm.assistant.ai.PromptRegistry.get(key, mapOf(
                "original_task" to originalTask,
                "execution_summary" to executionSummary,
                "intervention_instruction" to interventionInstruction
            ))

            val messages = listOf(
                Message.System(systemPrompt),
                Message.User(userMessage)
            )

            Logger.i(Logger.AGENT, "Calling optimizer for intervention: ${modelConfig.modelName}")

            val response = withTimeout(30000L) {
                client.chat(messages, object : ModelClient.StreamCallback {
                    override fun onToken(token: String) {
                        onStreamToken?.invoke(token)
                    }
                    override fun onThinkingComplete(thinking: String) {
                        Logger.d(Logger.AGENT, "[INTERVENTION_OPT] Thinking: ${thinking.take(100)}...")
                    }
                    override fun onComplete(response: com.autoglm.assistant.ai.ModelResponse) {
                        Logger.i(Logger.AGENT, "[INTERVENTION_OPT] Complete in ${response.totalTime}ms")
                    }
                    override fun onError(error: String) {
                        Logger.e(Logger.AGENT, "[INTERVENTION_OPT] Error: $error")
                    }
                })
            }

            val rawContent = response.rawContent.trim()
            val optimized = stripThinkingTags(rawContent)
            val time = Logger.endTimer("intervention_optimization", Logger.AGENT)
            Logger.i(Logger.AGENT, "Optimized intervention (${time}ms): $optimized")
            Logger.i(Logger.AGENT, "========== INTERVENTION OPTIMIZER: END ==========")

            if (optimized.isBlank() || optimized.length < interventionInstruction.length / 2) {
                Logger.w(Logger.AGENT, "Optimized intervention too short, using original")
                onOptimized?.invoke(interventionInstruction)
                return interventionInstruction
            }

            onOptimized?.invoke(optimized)
            return optimized

        } catch (e: Exception) {
            Logger.e(Logger.AGENT, "Intervention optimization failed, using original", e)
            Logger.endTimer("intervention_optimization", Logger.AGENT)
            return interventionInstruction
        }
    }

    /**
     * 从执行历史中提取简要步骤摘要（供干预优化使用）
     * 提取最近的 assistant 回复中的 action 信息，避免摘要过长
     */
    private fun buildExecutionSummary(executionHistory: List<Pair<String, String>>, language: String): String {
        if (executionHistory.isEmpty()) {
            return if (language == "en") "No steps executed yet." else "尚未执行任何步骤。"
        }

        // 取最近8条 assistant 的回复，提取 <answer> 标签中的 action
        val recentActions = executionHistory
            .filter { it.first == "assistant" }
            .takeLast(8)
            .mapIndexedNotNull { index, (_, content) ->
                val answerMatch = Regex("<answer>(.*?)</answer>", RegexOption.DOT_MATCHES_ALL)
                    .find(content)
                val action = answerMatch?.groupValues?.get(1)?.trim() ?: content.take(100)
                "步骤${index + 1}: $action"
            }

        return if (recentActions.isEmpty()) {
            if (language == "en") "No steps executed yet." else "尚未执行任何步骤。"
        } else {
            recentActions.joinToString("\n")
        }
    }

    /**
     * 生成任务总结
     * @param originalTask 原始任务描述
     * @param conversationContext 对话上下文（包含执行过程）
     * @param language 语言 cn/en
     * @return 任务总结，如果生成失败则返回默认消息
     */
    suspend fun summarize(
        originalTask: String,
        conversationContext: List<Pair<String, String>>,
        language: String = "cn"
    ): String {
        Logger.i(Logger.AGENT, "========== TASK SUMMARIZER: START ==========")
        Logger.i(Logger.AGENT, "Original task: $originalTask")
        Logger.i(Logger.AGENT, "Context messages: ${conversationContext.size}")
        Logger.startTimer("task_summary")

        onSummarizing?.invoke()

        try {
            val systemPrompt = if (language == "en") SUMMARY_SYSTEM_PROMPT_EN else SUMMARY_SYSTEM_PROMPT_CN
            val userMessage = buildSummaryPrompt(originalTask, language, conversationContext)

            val messages = listOf(
                Message.System(systemPrompt),
                Message.User(userMessage)
            )

            Logger.i(Logger.AGENT, "Calling summarizer model: ${modelConfig.modelName}")

            val response = withTimeout(30000L) {
                client.chat(messages, object : ModelClient.StreamCallback {
                    override fun onToken(token: String) {
                        onStreamToken?.invoke(token)
                    }

                    override fun onThinkingComplete(thinking: String) {
                        Logger.d(Logger.AGENT, "[SUMMARIZER] Thinking: ${thinking.take(100)}...")
                    }

                    override fun onComplete(response: com.autoglm.assistant.ai.ModelResponse) {
                        Logger.i(Logger.AGENT, "[SUMMARIZER] Complete in ${response.totalTime}ms")
                    }

                    override fun onError(error: String) {
                        Logger.e(Logger.AGENT, "[SUMMARIZER] Error: $error")
                    }
                })
            }

            val rawContent = response.rawContent.trim()
            val summary = stripThinkingTags(rawContent)
            val time = Logger.endTimer("task_summary", Logger.AGENT)
            Logger.i(Logger.AGENT, "Task summary (${time}ms): $summary")
            Logger.i(Logger.AGENT, "========== TASK SUMMARIZER: END ==========")

            if (summary.isBlank()) {
                Logger.w(Logger.AGENT, "Summary is blank, using default message")
                val defaultMsg = if (language == "en") "Task completed" else "任务已完成"
                onSummarized?.invoke(defaultMsg)
                return defaultMsg
            }

            onSummarized?.invoke(summary)
            return summary

        } catch (e: Exception) {
            Logger.e(Logger.AGENT, "Task summary failed", e)
            Logger.endTimer("task_summary", Logger.AGENT)
            val defaultMsg = if (language == "en") "Task completed" else "任务已完成"
            onSummarized?.invoke(defaultMsg)
            return defaultMsg
        }
    }

    private fun buildSummaryPrompt(originalTask: String, language: String, conversationContext: List<Pair<String, String>>): String {
        val recentContext = conversationContext.takeLast(10).joinToString("\n\n") { (role, content) ->
            val roleLabel = if (language == "en") {
                if (role == "user") "User" else "Assistant"
            } else {
                if (role == "user") "用户" else "助手"
            }
            val shortContent = if (content.length > 500) content.take(500) + "..." else content
            "$roleLabel: $shortContent"
        }

        val key = if (language == "en") com.autoglm.assistant.ai.PromptKey.SUMMARY_USER_EN
                  else com.autoglm.assistant.ai.PromptKey.SUMMARY_USER_CN
        return com.autoglm.assistant.ai.PromptRegistry.get(key, mapOf(
            "original_task" to originalTask,
            "recent_context" to recentContext
        ))
    }

    /**
     * 移除模型可能输出的思考标签和思考内容
     * 支持 <think>...</think> 和 <thinking>...</thinking> 格式
     * 同时移除常见的思考性文字模式
     */
    private fun stripThinkingTags(content: String): String {
        var result = content

        // 移除 <think>...</think> 标签及其内容
        val thinkPattern = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        result = result.replace(thinkPattern, "")

        // 移除 <thinking>...</thinking> 标签及其内容
        val thinkingPattern = Regex("<thinking>.*?</thinking>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        result = result.replace(thinkingPattern, "")

        // 移除所有从开头到第一个"打开"、"在"、"使用"等动词之前的内容
        // 这能有效去掉大部分思考性前缀
        // 注意：如果思考内容中包含这些词（如"我应该打开..."），这个正则可能会失效，所以需要结合下面的行过滤
        val actionStartPattern = Regex("^[\\s\\S]*?(?=(?:打开|在|使用|进入|点击|搜索|查找|查看|发送|输入|选择))", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        val newResult = result.replace(actionStartPattern, "")
        if (newResult.isNotBlank() && newResult.length < result.length) {
            result = newResult
        }

        // 针对DeepSeek等模型可能输出的混合思考内容，进行行级过滤
        val lines = result.lines()
        val filteredLines = lines.filterNot { line ->
            val l = line.trim()
            // 过滤常见的思考性陈述
            (l.contains("这是一个", ignoreCase = true) && l.contains("需求", ignoreCase = true)) ||
            (l.contains("明确", ignoreCase = true) && l.contains("任务", ignoreCase = true)) ||
            l.contains("我应该", ignoreCase = true) ||
            l.startsWith("在是", ignoreCase = true) || // 例如 "在是晚上7点多"
            l.contains("选择合适的工具", ignoreCase = true) ||
            l.contains("选择一个合适", ignoreCase = true) ||
            l.contains("准备处理", ignoreCase = true) ||
            l.contains("异常情况", ignoreCase = true) ||
            // 过滤重复的引用
            (l.trim() == "\"查看今天的天气\"") ||
            l.startsWith("查看今天的天气，如果", ignoreCase = true)
        }
        result = filteredLines.joinToString("\n")

        // 移除常见的思考性前缀和分析内容
        val chineseThinkingPatterns = listOf(
            Regex("^[\\s\\S]*?(?=优化(?:为|后|指令)[：:：]?\\s*)", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)),
            Regex("^[\\s\\S]*?(?=最终(?:优化)?(?:指令|结果)[：:：]?\\s*)", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)),
            Regex("^(?:让我.*?[。\\n]|首先.*?[。\\n]|分析.*?[。\\n]|理解.*?[。\\n]|这个.*?[。\\n]|根据.*?[。\\n])+", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)),
        )

        val englishThinkingPatterns = listOf(
            Regex("^[\\s\\S]*?(?=(?:Open|Use|Enter|Click|Search|Find|View|Send|Input|Select))", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)),
            Regex("^[\\s\\S]*?(?=Optimized(?:\\s+instruction)?[：:：]?\\s*)", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)),
            Regex("^[\\s\\S]*?(?=Final(?:\\s+(?:optimized\\s+)?(?:instruction|result))[：:：]?\\s*)", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)),
            Regex("^(?:Let me.*?[.\\n]|First.*?[.\\n]|Analysis.*?[.\\n]|Understanding.*?[.\\n]|This.*?[.\\n]|Based on.*?[.\\n])+", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)),
        )

        // 先尝试中文模式
        for (pattern in chineseThinkingPatterns) {
            val newResult2 = result.replace(pattern, "")
            if (newResult2 != result && newResult2.isNotBlank()) {
                result = newResult2
                break
            }
        }

        // 如果还是包含思考性内容，尝试英文模式
        if (result.startsWith("Let me", ignoreCase = true) ||
            result.startsWith("First", ignoreCase = true) ||
            result.startsWith("Based", ignoreCase = true) ||
            result.startsWith("让我", ignoreCase = false) ||
            result.startsWith("首先", ignoreCase = false) ||
            result.startsWith("根据", ignoreCase = false)) {
            for (pattern in englishThinkingPatterns) {
                val newResult3 = result.replace(pattern, "")
                if (newResult3 != result && newResult3.isNotBlank()) {
                    result = newResult3
                    break
                }
            }
        }

        // 移除可能残留的各种前缀
        val prefixPatterns = listOf(
            "^优化(?:为|后|指令)[：:：]?\\s*",
            "^最终(?:优化)?(?:指令|结果)[：:：]?\\s*",
            "^指令[：:：]?\\s*",
            "^结果[：:：]?\\s*",
            "^以下是",
            "^这是"
        )

        for (prefixPattern in prefixPatterns) {
            result = result.replace(Regex(prefixPattern, RegexOption.MULTILINE), "")
        }

        result = result.replace(Regex("^Optimized(?:\\s+instruction)?[：:：]?\\s*", RegexOption.IGNORE_CASE), "")
        result = result.replace(Regex("^Final(?:\\s+(?:optimized\\s+)?(?:instruction|result))[：:：]?\\s*", RegexOption.IGNORE_CASE), "")
        result = result.replace(Regex("^Instruction[：:：]?\\s*", RegexOption.IGNORE_CASE), "")
        result = result.replace(Regex("^Result[：:：]?\\s*", RegexOption.IGNORE_CASE), "")

        // 移除开头和结尾的空白
        result = result.trim()

        return result.trim()
    }

    fun release() {
        // Clean up if needed
    }

    companion object {
        // 系统提示词统一通过 PromptRegistry 获取
        val SYSTEM_PROMPT_CN: String get() = com.autoglm.assistant.ai.PromptRegistry.get(com.autoglm.assistant.ai.PromptKey.OPTIMIZER_SYSTEM_CN)
        val SYSTEM_PROMPT_EN: String get() = com.autoglm.assistant.ai.PromptRegistry.get(com.autoglm.assistant.ai.PromptKey.OPTIMIZER_SYSTEM_EN)
        val SUMMARY_SYSTEM_PROMPT_CN: String get() = com.autoglm.assistant.ai.PromptRegistry.get(com.autoglm.assistant.ai.PromptKey.SUMMARY_SYSTEM_CN)
        val SUMMARY_SYSTEM_PROMPT_EN: String get() = com.autoglm.assistant.ai.PromptRegistry.get(com.autoglm.assistant.ai.PromptKey.SUMMARY_SYSTEM_EN)
    }
}
