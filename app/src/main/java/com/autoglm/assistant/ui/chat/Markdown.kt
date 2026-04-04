package com.autoglm.assistant.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import java.util.regex.Pattern

// --- Markdown 列表匹配正则 ---
private val UNORDERED_LIST_REGEX = Regex("""^(\s*)[-*+]\s+(.*)""")
private val ORDERED_LIST_REGEX = Regex("""^(\s*)(\d+)\.\s+(.*)""")

/**
 * 简易 Markdown 解析器，将文本转为 AnnotatedString。
 * 支持：**bold**、*italic*、`code`、```代码块```、无序列表、有序列表
 */
@Composable
fun parseMarkdown(text: String): androidx.compose.ui.text.AnnotatedString {
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant

    // 步骤1: 预处理列表语法 → 替换为可视化格式
    val preprocessed = preprocessLists(text)

    return buildAnnotatedString {
        // 步骤2: 按代码块分割，避免对代码块内容做格式化
        val parts = preprocessed.split("```")

        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                // 代码块（奇数索引）
                pushStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground.copy(alpha = 0.3f),
                    color = codeColor
                ))
                append(part)
                pop()
            } else {
                // 普通文本，解析行内 Markdown
                parseInlineMarkdown(part, codeColor, codeBackground)
            }
        }
    }
}

/**
 * 预处理列表语法：将 `- item` 转为 `  • item`，保留 `1. item` 格式并缩进。
 * 在代码块外逐行处理。
 */
private fun preprocessLists(text: String): String {
    val lines = text.lines()
    val result = StringBuilder()
    var inCodeBlock = false

    for ((i, line) in lines.withIndex()) {
        if (line.trimStart().startsWith("```")) {
            inCodeBlock = !inCodeBlock
            result.append(line)
        } else if (!inCodeBlock) {
            // 无序列表: `- item` / `* item` / `+ item`
            val unorderedMatch = UNORDERED_LIST_REGEX.matchEntire(line)
            if (unorderedMatch != null) {
                val indent = unorderedMatch.groupValues[1]
                val content = unorderedMatch.groupValues[2]
                result.append("${indent}  • $content")
            } else {
                // 有序列表: `1. item`
                val orderedMatch = ORDERED_LIST_REGEX.matchEntire(line)
                if (orderedMatch != null) {
                    val indent = orderedMatch.groupValues[1]
                    val number = orderedMatch.groupValues[2]
                    val content = orderedMatch.groupValues[3]
                    result.append("${indent}  $number. $content")
                } else {
                    result.append(line)
                }
            }
        } else {
            result.append(line)
        }
        if (i < lines.lastIndex) result.append('\n')
    }
    return result.toString()
}

/**
 * 解析行内 Markdown：**bold**、`code`
 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.parseInlineMarkdown(
    part: String,
    codeColor: Color,
    codeBackground: Color
) {
    var currentIndex = 0
    var i = 0
    while (i < part.length) {
        // 行内代码 `...`
        if (part[i] == '`') {
            val end = part.indexOf('`', i + 1)
            if (end != -1) {
                append(part.substring(currentIndex, i))
                pushStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground.copy(alpha = 0.2f),
                    color = codeColor
                ))
                append(part.substring(i + 1, end))
                pop()
                currentIndex = end + 1
                i = end + 1
                continue
            }
        }

        // 加粗 **...**
        if (i + 1 < part.length && part[i] == '*' && part[i + 1] == '*') {
            val end = part.indexOf("**", i + 2)
            if (end != -1) {
                append(part.substring(currentIndex, i))
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(part.substring(i + 2, end))
                pop()
                currentIndex = end + 2
                i = end + 2
                continue
            }
        }

        i++
    }
    if (currentIndex < part.length) {
        append(part.substring(currentIndex))
    }
}
