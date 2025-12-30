package com.autoglm.assistant.ai

/**
 * 模型配置信息
 */
data class ModelConfig(
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val apiKey: String = "",
    val modelName: String = "autoglm-phone",
    val maxTokens: Int = 3000,
    val temperature: Float = 0.0f,
    val topP: Float = 0.85f,
    val frequencyPenalty: Float = 0.2f
) {
    val chatCompletionsUrl: String
        get() = "${baseUrl.trimEnd('/')}/chat/completions"
}

/**
 * 模型响应结果
 */
data class ModelResponse(
    val thinking: String,
    val action: String,
    val rawContent: String,
    val timeToFirstToken: Long? = null,
    val totalTime: Long? = null
)

/**
 * 消息基类
 */
sealed class Message {
    abstract val role: String
    abstract fun toJsonMap(): Map<String, Any>

    /**
     * 系统消息
     */
    data class System(val content: String) : Message() {
        override val role = "system"
        override fun toJsonMap() = mapOf(
            "role" to role,
            "content" to content
        )
    }

    /**
     * 用户消息，支持文本和图片
     */
    data class User(
        val text: String,
        val imageBase64: String? = null
    ) : Message() {
        override val role = "user"
        override fun toJsonMap(): Map<String, Any> {
            return if (imageBase64 != null) {
                mapOf(
                    "role" to role,
                    "content" to listOf(
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf(
                                "url" to "data:image/png;base64,$imageBase64"
                            )
                        ),
                        mapOf(
                            "type" to "text",
                            "text" to text
                        )
                    )
                )
            } else {
                mapOf(
                    "role" to role,
                    "content" to text
                )
            }
        }
    }

    /**
     * 助手消息
     */
    data class Assistant(val content: String) : Message() {
        override val role = "assistant"
        override fun toJsonMap() = mapOf(
            "role" to role,
            "content" to content
        )
    }
}
