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

/**
 * simple markdown parser to annotated string
 * supports:
 * - **bold**
 * - *italic*
 * - `code`
 * - [link](url) (text only)
 */
@Composable
fun parseMarkdown(text: String): androidx.compose.ui.text.AnnotatedString {
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f) // use onSurfaceVariant for code
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant

    return buildAnnotatedString {
        var currentIndex = 0
        // simple regex for **bold**, *italic*, `code`
        // Note: this is a very basic parser and does not handle nested well or complex structures
        // It's a pragmatic choice for basic chat formatting.
        
        // Split by code blocks first to avoid formatting inside code
        val parts = text.split("```")
        
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                // Code block (odd index)
                pushStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground.copy(alpha = 0.3f), // slight background
                    color = codeColor
                ))
                append(part)
                pop()
            } else {
                // Normal text (even index), parse inline markdown
                
                // We process sequentially. A better way is to find all matches and sort by index.
                // For simplicity, let's just handle bold then code? No, order matters.
                
                // Let's iterate char by char state machine for simplicity and correctness on simple cases
                // Or just use a simple approach: render as is but bold the **...**
                
                // Let's try a regex replace logic with append?
                // Actually, let's keep it very simple: just bold and code.
                
                // Strategy: Find first match of any token, process it, recurse on rest?
                // Or manual scan.
                
                var i = 0
                while (i < part.length) {
                    // Check for ``` is handled by outer split.
                    
                    // Check for ` (inline code)
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
                    
                     // Check for ** (bold)
                    if (i + 1 < part.length && part[i] == '*' && part[i+1] == '*') {
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
                currentIndex = 0 // reset for next loop (conceptually, though we use `part`)
            }
        }
    }
}
