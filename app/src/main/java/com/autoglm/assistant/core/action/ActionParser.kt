package com.autoglm.assistant.core.action

import java.util.regex.Pattern

data class ParsedAction(
    val type: ActionType,
    val params: Map<String, Any>,
    val isFinish: Boolean = false,
    val rawString: String = ""
)

enum class ActionType {
    LAUNCH,
    TAP,
    SWIPE,
    TYPE,
    TYPE_NAME,
    LONG_PRESS,
    DOUBLE_TAP,
    BACK,
    HOME,
    WAIT,
    TAKE_OVER,
    INTERACT,
    NOTE,
    CALL_API,
    CLEAR_INPUT,
    FINISH,
    UNKNOWN
}

object ActionParser {

    fun parse(actionString: String): ParsedAction {
        val trimmed = actionString.trim()

        // Check for finish action
        if (trimmed.startsWith("finish(")) {
            return parseFinishAction(trimmed)
        }

        // Check for do() wrapper
        if (trimmed.startsWith("do(")) {
            return parseDoAction(trimmed)
        }

        // Try to parse as raw action
        return parseRawAction(trimmed)
    }

    private fun parseFinishAction(actionString: String): ParsedAction {
        val messagePattern = Pattern.compile("finish\\(message=[\"'](.+?)[\"']\\)", Pattern.DOTALL)
        val matcher = messagePattern.matcher(actionString)

        val message = if (matcher.find()) {
            matcher.group(1) ?: ""
        } else {
            // Try without quotes
            val simplePattern = Pattern.compile("finish\\(message=(.+?)\\)")
            val simpleMatcher = simplePattern.matcher(actionString)
            if (simpleMatcher.find()) simpleMatcher.group(1) ?: "" else ""
        }

        return ParsedAction(
            type = ActionType.FINISH,
            params = mapOf("message" to message),
            isFinish = true,
            rawString = actionString
        )
    }

    private fun parseDoAction(actionString: String): ParsedAction {
        // Extract action type
        val actionPattern = Pattern.compile("action=[\"']?(\\w+)[\"']?")
        val actionMatcher = actionPattern.matcher(actionString)

        val actionTypeStr = if (actionMatcher.find()) {
            actionMatcher.group(1) ?: "UNKNOWN"
        } else {
            "UNKNOWN"
        }

        val actionType = parseActionType(actionTypeStr)
        val params = extractParams(actionString, actionType)

        return ParsedAction(
            type = actionType,
            params = params,
            isFinish = false,
            rawString = actionString
        )
    }

    private fun parseRawAction(actionString: String): ParsedAction {
        // Try common action patterns
        val patterns = listOf(
            "Tap" to ActionType.TAP,
            "Swipe" to ActionType.SWIPE,
            "Type" to ActionType.TYPE,
            "Launch" to ActionType.LAUNCH,
            "Long_Press" to ActionType.LONG_PRESS,
            "Double_Tap" to ActionType.DOUBLE_TAP,
            "Back" to ActionType.BACK,
            "Home" to ActionType.HOME,
            "Wait" to ActionType.WAIT
        )

        for ((prefix, type) in patterns) {
            if (actionString.startsWith(prefix, ignoreCase = true)) {
                val params = extractParams(actionString, type)
                return ParsedAction(
                    type = type,
                    params = params,
                    rawString = actionString
                )
            }
        }

        return ParsedAction(
            type = ActionType.UNKNOWN,
            params = emptyMap(),
            rawString = actionString
        )
    }

    private fun parseActionType(typeStr: String): ActionType {
        return when (typeStr.uppercase().replace("-", "_")) {
            "LAUNCH" -> ActionType.LAUNCH
            "TAP" -> ActionType.TAP
            "SWIPE" -> ActionType.SWIPE
            "TYPE" -> ActionType.TYPE
            "TYPE_NAME" -> ActionType.TYPE_NAME
            "LONG_PRESS", "LONGPRESS" -> ActionType.LONG_PRESS
            "DOUBLE_TAP", "DOUBLETAP" -> ActionType.DOUBLE_TAP
            "BACK" -> ActionType.BACK
            "HOME" -> ActionType.HOME
            "WAIT" -> ActionType.WAIT
            "TAKE_OVER", "TAKEOVER" -> ActionType.TAKE_OVER
            "INTERACT" -> ActionType.INTERACT
            "NOTE" -> ActionType.NOTE
            "CALL_API", "CALLAPI" -> ActionType.CALL_API
            "CLEAR_INPUT", "CLEARINPUT", "CLEAR" -> ActionType.CLEAR_INPUT
            "FINISH" -> ActionType.FINISH
            else -> ActionType.UNKNOWN
        }
    }

    private fun extractParams(actionString: String, actionType: ActionType): Map<String, Any> {
        val params = mutableMapOf<String, Any>()

        when (actionType) {
            ActionType.LAUNCH -> {
                // 支持多种参数名：app=, 参数=, name= 等
                val appPatterns = listOf(
                    Pattern.compile("(?:app|参数|name)=[\"']?([^\"',\\)]+)[\"']?"),
                    // 如果只有一个参数值（无参数名），也尝试提取
                    Pattern.compile("Launch[\"']?,\\s*[\"']?([^\"',\\)]+)[\"']?", Pattern.CASE_INSENSITIVE)
                )
                for (pattern in appPatterns) {
                    val matcher = pattern.matcher(actionString)
                    if (matcher.find()) {
                        val appName = matcher.group(1)?.trim() ?: ""
                        if (appName.isNotEmpty()) {
                            params["app"] = appName
                            break
                        }
                    }
                }
            }

            ActionType.TAP, ActionType.LONG_PRESS, ActionType.DOUBLE_TAP -> {
                val elementPattern = Pattern.compile("element=\\[([\\d.]+),\\s*([\\d.]+)\\]")
                val matcher = elementPattern.matcher(actionString)
                if (matcher.find()) {
                    params["x"] = matcher.group(1)?.toFloatOrNull() ?: 0f
                    params["y"] = matcher.group(2)?.toFloatOrNull() ?: 0f
                }

                // Check for message (sensitive tap)
                val msgPattern = Pattern.compile("message=[\"'](.+?)[\"']")
                val msgMatcher = msgPattern.matcher(actionString)
                if (msgMatcher.find()) {
                    params["message"] = msgMatcher.group(1) ?: ""
                }
            }

            ActionType.SWIPE -> {
                val startPattern = Pattern.compile("start=\\[([\\d.]+),\\s*([\\d.]+)\\]")
                val endPattern = Pattern.compile("end=\\[([\\d.]+),\\s*([\\d.]+)\\]")

                val startMatcher = startPattern.matcher(actionString)
                if (startMatcher.find()) {
                    params["startX"] = startMatcher.group(1)?.toFloatOrNull() ?: 0f
                    params["startY"] = startMatcher.group(2)?.toFloatOrNull() ?: 0f
                }

                val endMatcher = endPattern.matcher(actionString)
                if (endMatcher.find()) {
                    params["endX"] = endMatcher.group(1)?.toFloatOrNull() ?: 0f
                    params["endY"] = endMatcher.group(2)?.toFloatOrNull() ?: 0f
                }
            }

            ActionType.TYPE, ActionType.TYPE_NAME -> {
                // 支持多种参数名：text=, 文本=, 内容=, content= 等
                val textPatterns = listOf(
                    Pattern.compile("(?:text|文本|内容|content)=[\"'](.+?)[\"']", Pattern.DOTALL),
                    Pattern.compile("(?:text|文本|内容|content)=([^,\\)]+)", Pattern.DOTALL)
                )
                for (pattern in textPatterns) {
                    val matcher = pattern.matcher(actionString)
                    if (matcher.find()) {
                        val text = matcher.group(1)?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            params["text"] = text
                            break
                        }
                    }
                }
            }

            ActionType.WAIT -> {
                val durationPattern = Pattern.compile("duration=[\"']?(\\d+)")
                val matcher = durationPattern.matcher(actionString)
                if (matcher.find()) {
                    params["duration"] = matcher.group(1)?.toIntOrNull() ?: 1
                } else {
                    params["duration"] = 1
                }
            }

            ActionType.TAKE_OVER, ActionType.INTERACT, ActionType.NOTE, ActionType.CALL_API -> {
                val msgPattern = Pattern.compile("(message|instruction)=[\"'](.+?)[\"']", Pattern.DOTALL)
                val matcher = msgPattern.matcher(actionString)
                if (matcher.find()) {
                    params["message"] = matcher.group(2) ?: ""
                }
            }

            else -> {}
        }

        return params
    }
}
