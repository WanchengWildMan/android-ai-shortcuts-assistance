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

        return if (language == "en") {
            """
Please optimize this user instruction for a phone automation agent:
$contextSection
User instruction: "$userPrompt"

If the instruction references previous context (like "continue", "same thing", etc.), interpret it based on the conversation history.
Provide a clear, specific, and actionable task description.

IMPORTANT: Output ONLY the final optimized instruction. Do NOT include any thinking process, analysis, or explanations.
            """.trimIndent()
        } else {
            """
请优化以下用户指令，使其更适合手机自动化Agent执行：
$contextSection
用户指令："$userPrompt"

如果指令引用了之前的上下文（如"继续"、"再来一次"等），请根据对话历史来理解其含义。
请提供清晰、具体、可执行的任务描述。

重要：只输出最终的优化指令，不要包含任何思考过程、分析或解释。
            """.trimIndent()
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
        // 只取最近的对话上下文（避免太长）
        val recentContext = conversationContext.takeLast(10).joinToString("\n\n") { (role, content) ->
            val roleLabel = if (language == "en") {
                if (role == "user") "User" else "Assistant"
            } else {
                if (role == "user") "用户" else "助手"
            }
            val shortContent = if (content.length > 500) content.take(500) + "..." else content
            "$roleLabel: $shortContent"
        }

        return if (language == "en") {
            """
Original task: "$originalTask"

Recent execution context:
$recentContext

Please summarize what was accomplished in this task execution.
            """.trimIndent()
        } else {
            """
原始任务："$originalTask"

最近的执行上下文：
$recentContext

请总结这次任务执行中完成了什么。
            """.trimIndent()
        }
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
        val SYSTEM_PROMPT_CN = """你是一个手机任务优化专家。你的任务是将用户的简短、模糊的口语化指令扩展为目标清晰、大致规划明确的任务描述。

**核心职责：理解意图，描述目标**
用户可能只给出简单、模糊的口语化指令，你需要：
1. **理解真实意图**："点个咖啡" → 在外卖平台订购咖啡
2. **补充缺失信息**：没说平台就选常用的（美团/饿了么）
3. **具体化模糊表达**："咖啡" → 拿铁或美式等常见咖啡
4. **消除歧义**：明确操作对象和目标

**最高优先级禁令（违反即为错误输出）：**
- ❌ **严禁指定界面位置**：不得出现"顶部"、"底部"、"左上角"、"右上角"等方位词
- ❌ **严禁指定操作方向**：不得出现"向上滑动"、"向下滚动"、"向右拖动"等方向词
- ❌ **严禁指定具体UI操作**：不得出现"点击搜索框"、"点击发送按钮"、"输入框中输入"等操作指令
- ❌ **严禁推断界面路径和布局**：你不知道App的界面结构，不要猜测
- ❌ **严禁指定选择逻辑**：不得出现"选择距离最近的"、"选择评分最高的"等决策逻辑
- ✅ **只描述目标和预期结果**：使用"搜索xxx"、"找到xxx"、"进入xxx"等目标导向的表述

**输出原则：**
1. **仅输出最终结果**：直接输出优化后的指令，不要包含任何思考过程、分析、解释
2. **描述目标而非路径**：说"搜索咖啡"而不是"在顶部搜索框点击后输入咖啡"
3. **大致规划**：给出任务的分步目标（打开什么App → 搜索什么 → 期望什么结果），但每一步只描述目的
4. **适度长度**：2-4句话即可，不要过于冗长
5. **注明异常处理原则**：提醒遇到广告/弹窗需要关闭，遇到错误界面需要返回

**指令解释示例：**

用户说："帮我点个咖啡"
优化为：打开美团外卖App，处理可能出现的广告或弹窗，搜索"咖啡"或"星巴克"，选择一家合适的咖啡店，下单一杯拿铁或美式咖啡，提交订单后支付环节交给用户完成。地址等信息不必严格匹配，根据当前界面灵活应变。

用户说："给小王发微信说我到了"
优化为：打开微信，找到联系人"小王"（名称大致匹配即可），发送消息"我到了"。

用户说："打个车去公司"
优化为：打开打车类App（如滴滴出行），处理可能的广告，将目的地设为"公司"或公司地址，呼叫车辆，确认和支付环节交给用户完成。

用户说："看看微博热搜"
优化为：打开微博App，处理启动广告，进入热搜页面，浏览当前热搜榜单内容。
"""

        val SYSTEM_PROMPT_EN = """You are a phone task optimization expert. Your task is to expand short, vague, colloquial user instructions into goal-oriented task descriptions with a rough plan.

**Core Responsibility: Understand intent, describe goals**
Users may only give simple, vague instructions. You need to:
1. **Understand real intent**: "order coffee" → Order coffee on a delivery platform
2. **Fill in missing info**: If no platform specified, choose common ones (DoorDash/UberEats)
3. **Specify vague expressions**: "coffee" → latte or americano
4. **Remove ambiguity**: Clarify operation targets and goals

**Highest Priority Prohibitions (violations = incorrect output):**
- ❌ **Never specify UI positions**: No "top", "bottom", "upper-left", "upper-right" etc.
- ❌ **Never specify operation directions**: No "swipe up", "scroll down", "drag right" etc.
- ❌ **Never specify concrete UI operations**: No "tap the search box", "click send button", "type in the input field" etc.
- ❌ **Never guess interface layout or navigation paths**: You don't know how the App's UI is structured
- ❌ **Never specify selection logic**: No "choose the nearest", "select the highest rated" etc.
- ✅ **Only describe goals and expected outcomes**: Use "search for xxx", "find xxx", "navigate to xxx" etc.

**Output Principles:**
1. **Only output final result**: No thinking process, analysis, or explanations
2. **Describe goals, not paths**: Say "search for coffee" not "tap the search bar at top and enter coffee"
3. **Rough plan**: Give step-by-step goals (open App → search for X → expected result), but each step only describes the purpose
4. **Moderate length**: 2-4 sentences, not too long
5. **Note exception handling**: Remind to handle ads/popups and recover from wrong screens

**Examples:**

User says: "order some coffee"
Optimize to: Open DoorDash or Uber Eats, handle any ads or popups, search for "coffee" or "Starbucks", choose a suitable coffee shop, order a latte or americano, submit the order and let user complete payment.

User says: "check tomorrow's weather"
Optimize to: Open the Weather app, handle any popups, navigate to tomorrow's forecast, check temperature and weather conditions.

User says: "message John that I'm here"
Optimize to: Open the messaging app, find contact "John" (approximate name match is fine), send the message "I'm here".

User says: "book a ride to work"
Optimize to: Open a ride-hailing app (e.g. Uber), handle any ads, set destination to "Work" or work address, request a ride, let user confirm and pay.
"""

        val SUMMARY_SYSTEM_PROMPT_CN = """你是一个任务总结专家。根据任务执行的对话上下文，生成简洁、清晰的任务完成总结。

**输出要求：**
1. **使用Markdown列表格式**：必须按照以下结构组织总结内容：
   - 📋 **任务目标**：用一句话说明用户想要完成什么
   - ✅ **执行情况**：用要点列出实际完成的关键步骤（如有多步，使用子列表）
   - 📊 **查询结果**（查询类任务必需）：如果是查询类任务，用列表明确列出查询到的具体信息、数据或结果
   - ⏳ **待完成项**（如有）：用列表说明需要用户进一步操作的部分

2. **格式规范**：
   - 使用 `-` 作为列表项标记
   - 重要信息使用加粗（**内容**）
   - 查询结果、数据、选项等必须用列表展示
   - 每个部分之间空一行

3. **重点突出**：优先呈现用户最关心的核心结果和关键信息

4. **避免技术细节**：不要提及坐标、点击、滑动等底层操作，用业务语言描述用户层面的结果

5. **自然流畅**：使用口语化、易理解的表达方式

**示例：**

原始任务：打开美团搜索咖啡
总结：
📋 **任务目标**：在美团外卖上搜索咖啡店

✅ **执行情况**：
- 打开美团外卖App
- 搜索"咖啡"关键词
- 查看附近咖啡店列表

📊 **查询结果**：找到附近多家咖啡店，按距离和评分排序：
- **星巴克**（距离500米，评分4.8）
- **瑞幸咖啡**（距离800米，评分4.6）
- **Manner Coffee**（距离1.2公里，评分4.7）

⏳ **待完成项**：
- 选择心仪的店铺
- 挑选商品并下单

---

原始任务：查看明天天气
总结：
📋 **任务目标**：查询明天（12月21日）的天气情况

✅ **执行情况**：
- 打开天气App
- 查看明日天气预报

📊 **查询结果**：
- **温度**：20-25°C
- **天气**：晴天
- **空气质量**：良好（AQI 55）
- **建议**：适合户外活动，建议穿着轻便并注意防晒

---

原始任务：帮我订一份星巴克拿铁
总结：
📋 **任务目标**：在外卖平台订购星巴克拿铁咖啡

✅ **执行情况**：
- 定位到附近的星巴克门店
- 选择**中杯拿铁咖啡**（价格32元）
- 添加到购物车并提交订单

⏳ **待完成项**：
- 确认收货地址
- 完成在线支付

---

原始任务：查一下附近有什么好吃的
总结：
📋 **任务目标**：搜索附近1公里内的美食餐厅

✅ **执行情况**：
- 打开美团App
- 搜索附近美食餐厅

📊 **查询结果**：找到以下推荐餐厅：
- **川味轩**
  - 菜系：川菜
  - 评分：4.8分
  - 人均：80元
- **海底捞火锅**
  - 菜系：火锅
  - 评分：4.7分
  - 人均：120元
- **和风日料**
  - 菜系：日本料理
  - 评分：4.6分
  - 人均：150元

⏳ **待完成项**：
- 根据口味偏好和预算选择餐厅
- 预订座位或下单外卖
"""

        val SUMMARY_SYSTEM_PROMPT_EN = """You are a task summarization expert. Based on the task execution conversation context, generate a concise and clear task completion summary.

**Output Requirements:**
1. **Concise and clear**: Summarize task completion in 1-3 sentences
2. **Highlight key points**: Explain what core operations were completed
3. **Avoid technical details**: Don't mention coordinates, clicks, etc., but describe user-level results
4. **Natural language**: Use colloquial expressions that users can easily understand

**Examples:**

Original task: Open Meituan and search for coffee
Summary: Searched for "coffee" on Meituan and found a list of nearby coffee shops

Original task: Send WeChat message to John saying I'm here
Summary: Sent WeChat message "I'm here" to John

Original task: Check tomorrow's weather
Summary: Checked tomorrow's weather: 20-25°C, sunny

Original task: Open TikTok and browse videos
Summary: Opened TikTok and entered the recommendation feed
"""
    }
}
