package com.autoglm.assistant.core.action

import android.content.Context
import android.content.Intent
import com.autoglm.assistant.core.screen.AppDetector
import com.autoglm.assistant.service.AutomationService
import com.autoglm.assistant.util.Logger
import com.autoglm.assistant.util.ShellExecutor
import kotlinx.coroutines.delay

data class ActionResult(
    val success: Boolean,
    val message: String? = null,
    val needsHumanIntervention: Boolean = false
)

class ActionExecutor(
    private val context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int
) {

    enum class Mode {
        ACCESSIBILITY,  // Use AccessibilityService (non-root)
        SHELL_INPUT,    // Use shell input commands (root/ADB)
        AUTO            // Automatically choose best mode
    }

    var mode: Mode = Mode.AUTO

    companion object {
        // 延时配置（根据操作类型优化，单位：毫秒）
        private const val DELAY_TAP = 800L              // 点击后等待UI响应
        private const val DELAY_SWIPE = 600L            // 滑动后等待界面稳定
        private const val DELAY_BACK = 500L             // 返回后等待
        private const val DELAY_HOME = 800L             // 回到桌面后等待
        private const val DELAY_LAUNCH = 1500L          // 启动应用需要较长时间
        private const val DELAY_LONG_PRESS = 500L       // 长按后等待
        private const val DELAY_DOUBLE_TAP = 100L       // 双击间隔
        private const val DELAY_DOUBLE_TAP_AFTER = 500L // 双击完成后等待
        private const val DELAY_IME_SWITCH = 1000L      // 输入法切换等待（合并命令内部使用）
    }

    suspend fun execute(action: ParsedAction): ActionResult {
        Logger.d(Logger.ACTION, "[EXEC] type=${action.type}, params=${action.params}")
        Logger.startTimer("action_${action.type}")

        val result = when (action.type) {
            ActionType.LAUNCH -> executeLaunch(action)
            ActionType.TAP -> executeTap(action)
            ActionType.SWIPE -> executeSwipe(action)
            ActionType.TYPE, ActionType.TYPE_NAME -> executeType(action)
            ActionType.LONG_PRESS -> executeLongPress(action)
            ActionType.DOUBLE_TAP -> executeDoubleTap(action)
            ActionType.BACK -> executeBack()
            ActionType.HOME -> executeHome()
            ActionType.WAIT -> executeWait(action)
            ActionType.TAKE_OVER -> executeTakeOver(action)
            ActionType.NOTE -> executeNote(action)
            ActionType.CALL_API -> executeCallApi(action)
            ActionType.FINISH -> ActionResult(success = true, message = action.params["message"] as? String)
            ActionType.UNKNOWN -> ActionResult(success = false, message = "Unknown action type")
        }

        val elapsed = Logger.endTimer("action_${action.type}", Logger.ACTION)
        Logger.d(Logger.ACTION, "[RESULT] type=${action.type}, success=${result.success}, msg=${result.message}, elapsed=${elapsed}ms")
        return result
    }

    private suspend fun executeLaunch(action: ParsedAction): ActionResult {
        val appName = action.params["app"] as? String ?: return ActionResult(false, "App name not specified")

        // 使用动态搜索，支持从设备已安装应用中查找
        val packageName = AppDetector.getPackageFromAppName(context, appName) ?: appName

        return when (getEffectiveMode()) {
            Mode.SHELL_INPUT -> {
                val success = ShellExecutor.launchApp(packageName)
                delay(DELAY_LAUNCH)
                ActionResult(success, if (success) "Launched $appName" else "Failed to launch $appName")
            }
            Mode.ACCESSIBILITY -> {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    delay(DELAY_LAUNCH)
                    ActionResult(true, "Launched $appName")
                } else {
                    ActionResult(false, "App not found: $appName")
                }
            }
            else -> ActionResult(false, "Invalid mode")
        }
    }

    private suspend fun executeTap(action: ParsedAction): ActionResult {
        val relX = (action.params["x"] as? Float) ?: return ActionResult(false, "X coordinate not specified")
        val relY = (action.params["y"] as? Float) ?: return ActionResult(false, "Y coordinate not specified")

        val (x, y) = convertRelativeToAbsolute(relX, relY)
        Logger.d(Logger.ACTION, "Tap: rel($relX, $relY) -> screen($x, $y) [screen: ${screenWidth}x${screenHeight}]")

        // Check for sensitive tap message
        val message = action.params["message"] as? String
        if (message != null) {
            // This is a sensitive operation - could prompt user for confirmation
            // For now, just proceed
        }

        return when (getEffectiveMode()) {
            Mode.SHELL_INPUT -> {
                val success = ShellExecutor.tap(x, y)
                delay(DELAY_TAP)
                ActionResult(success, if (success) "Tapped at ($x, $y)" else "Tap failed")
            }
            Mode.ACCESSIBILITY -> {
                val service = AutomationService.instance
                if (service != null) {
                    val success = service.performTap(x, y)
                    delay(DELAY_TAP)
                    ActionResult(success, if (success) "Tapped at ($x, $y)" else "Accessibility tap failed")
                } else {
                    ActionResult(false, "Accessibility service not available")
                }
            }
            else -> ActionResult(false, "Invalid mode")
        }
    }

    private suspend fun executeSwipe(action: ParsedAction): ActionResult {
        val startX = (action.params["startX"] as? Float) ?: return ActionResult(false, "Start X not specified")
        val startY = (action.params["startY"] as? Float) ?: return ActionResult(false, "Start Y not specified")
        val endX = (action.params["endX"] as? Float) ?: return ActionResult(false, "End X not specified")
        val endY = (action.params["endY"] as? Float) ?: return ActionResult(false, "End Y not specified")

        val (sx, sy) = convertRelativeToAbsolute(startX, startY)
        val (ex, ey) = convertRelativeToAbsolute(endX, endY)
        Logger.d(Logger.ACTION, "Swipe: rel($startX,$startY)->($endX,$endY) -> screen($sx,$sy)->($ex,$ey)")

        // 计算滑动持续时间（与Python保持一致：dist_sq / 1000，范围1000-2000ms）
        val distSq = (sx - ex) * (sx - ex) + (sy - ey) * (sy - ey)
        val duration = (distSq / 1000).coerceIn(1000, 2000)

        return when (getEffectiveMode()) {
            Mode.SHELL_INPUT -> {
                val success = ShellExecutor.swipe(sx, sy, ex, ey, duration)
                delay(DELAY_SWIPE)
                ActionResult(success, if (success) "Swiped from ($sx,$sy) to ($ex,$ey)" else "Swipe failed")
            }
            Mode.ACCESSIBILITY -> {
                val service = AutomationService.instance
                if (service != null) {
                    val success = service.performSwipe(sx, sy, ex, ey, duration.toLong())
                    delay(DELAY_SWIPE)
                    ActionResult(success, if (success) "Swiped" else "Accessibility swipe failed")
                } else {
                    ActionResult(false, "Accessibility service not available")
                }
            }
            else -> ActionResult(false, "Invalid mode")
        }
    }

    private suspend fun executeType(action: ParsedAction): ActionResult {
        val text = action.params["text"] as? String ?: return ActionResult(false, "Text not specified")

        // 优先使用 Accessibility ACTION_SET_TEXT（最可靠，不需要剪贴板）
        val service = AutomationService.instance
        if (service != null) {
            Logger.d(Logger.ACTION, "[TYPE] Using Accessibility ACTION_SET_TEXT")
            val success = service.performTextInput(text)
            if (success) {
                return ActionResult(true, "Typed text via Accessibility")
            }
            Logger.d(Logger.ACTION, "[TYPE] Accessibility failed, trying ADB Keyboard")
        }

        // 回退：如果已是 ADB Keyboard 则直接发送，否则失败
        Logger.d(Logger.ACTION, "[TYPE] Using ADB Keyboard")
        val result = ShellExecutor.typeTextViaAdbKeyboard(text, 300)
        return ActionResult(result.success, if (result.success) "Typed text via ADB Keyboard" else result.output)
    }

    private suspend fun executeLongPress(action: ParsedAction): ActionResult {
        val relX = (action.params["x"] as? Float) ?: return ActionResult(false, "X coordinate not specified")
        val relY = (action.params["y"] as? Float) ?: return ActionResult(false, "Y coordinate not specified")

        val (x, y) = convertRelativeToAbsolute(relX, relY)
        val duration = (action.params["duration"] as? Int) ?: 3000 // Python默认3000ms
        Logger.d(Logger.ACTION, "LongPress: rel($relX, $relY) -> screen($x, $y), duration=${duration}ms")

        return when (getEffectiveMode()) {
            Mode.SHELL_INPUT -> {
                val success = ShellExecutor.longPress(x, y, duration)
                delay(DELAY_LONG_PRESS)
                ActionResult(success, if (success) "Long pressed at ($x, $y)" else "Long press failed")
            }
            Mode.ACCESSIBILITY -> {
                val service = AutomationService.instance
                if (service != null) {
                    val success = service.performLongPress(x, y, duration.toLong())
                    delay(DELAY_LONG_PRESS)
                    ActionResult(success)
                } else {
                    ActionResult(false, "Accessibility service not available")
                }
            }
            else -> ActionResult(false, "Invalid mode")
        }
    }

    private suspend fun executeDoubleTap(action: ParsedAction): ActionResult {
        val relX = (action.params["x"] as? Float) ?: return ActionResult(false, "X coordinate not specified")
        val relY = (action.params["y"] as? Float) ?: return ActionResult(false, "Y coordinate not specified")

        val (x, y) = convertRelativeToAbsolute(relX, relY)
        Logger.d(Logger.ACTION, "DoubleTap: rel($relX, $relY) -> screen($x, $y)")

        return when (getEffectiveMode()) {
            Mode.SHELL_INPUT -> {
                ShellExecutor.tap(x, y)
                delay(DELAY_DOUBLE_TAP)
                val success = ShellExecutor.tap(x, y)
                delay(DELAY_DOUBLE_TAP_AFTER)
                ActionResult(success, if (success) "Double tapped at ($x, $y)" else "Double tap failed")
            }
            Mode.ACCESSIBILITY -> {
                val service = AutomationService.instance
                if (service != null) {
                    val success = service.performDoubleTap(x, y)
                    delay(DELAY_DOUBLE_TAP_AFTER)
                    ActionResult(success)
                } else {
                    ActionResult(false, "Accessibility service not available")
                }
            }
            else -> ActionResult(false, "Invalid mode")
        }
    }

    private suspend fun executeBack(): ActionResult {
        return when (getEffectiveMode()) {
            Mode.SHELL_INPUT -> {
                val success = ShellExecutor.back()
                delay(DELAY_BACK)
                ActionResult(success, if (success) "Pressed back" else "Back failed")
            }
            Mode.ACCESSIBILITY -> {
                val service = AutomationService.instance
                if (service != null) {
                    val success = service.performBack()
                    delay(DELAY_BACK)
                    ActionResult(success)
                } else {
                    ActionResult(false, "Accessibility service not available")
                }
            }
            else -> ActionResult(false, "Invalid mode")
        }
    }

    private suspend fun executeHome(): ActionResult {
        return when (getEffectiveMode()) {
            Mode.SHELL_INPUT -> {
                val success = ShellExecutor.home()
                delay(DELAY_HOME)
                ActionResult(success, if (success) "Pressed home" else "Home failed")
            }
            Mode.ACCESSIBILITY -> {
                val service = AutomationService.instance
                if (service != null) {
                    val success = service.performHome()
                    delay(DELAY_HOME)
                    ActionResult(success)
                } else {
                    ActionResult(false, "Accessibility service not available")
                }
            }
            else -> ActionResult(false, "Invalid mode")
        }
    }

    private suspend fun executeWait(action: ParsedAction): ActionResult {
        val duration = (action.params["duration"] as? Int) ?: 1
        delay(duration * 1000L)
        return ActionResult(true, "Waited for $duration seconds")
    }

    private fun executeTakeOver(action: ParsedAction): ActionResult {
        val message = action.params["message"] as? String ?: "Human intervention required"
        return ActionResult(
            success = true,
            message = message,
            needsHumanIntervention = true
        )
    }

    private fun executeNote(action: ParsedAction): ActionResult {
        val message = action.params["message"] as? String ?: ""
        return ActionResult(true, "Note: $message")
    }

    private fun executeCallApi(action: ParsedAction): ActionResult {
        val instruction = action.params["message"] as? String ?: ""
        // This would typically call an API for content summarization
        return ActionResult(true, "Call API: $instruction")
    }

    private fun convertRelativeToAbsolute(relX: Float, relY: Float): Pair<Int, Int> {
        // Convert 0-1000 relative coordinates to actual pixel coordinates
        val x = (relX / 1000f * screenWidth).toInt().coerceIn(0, screenWidth)
        val y = (relY / 1000f * screenHeight).toInt().coerceIn(0, screenHeight)
        return Pair(x, y)
    }

    private fun getEffectiveMode(): Mode {
        return when (mode) {
            Mode.AUTO -> {
                // Check if accessibility service is available
                if (AutomationService.instance != null) {
                    Mode.ACCESSIBILITY
                } else {
                    Mode.SHELL_INPUT
                }
            }
            else -> mode
        }
    }
}
