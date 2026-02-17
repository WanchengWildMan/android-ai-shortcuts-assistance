package com.autoglm.assistant.core.action

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
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
        ACCESSIBILITY,  // 使用无障碍服务（非 root）
        SHELL_INPUT,    // 使用 shell 输入命令（root/ADB）
        AUTO            // 自动选择最佳模式
    }

    var mode: Mode = Mode.AUTO

    /**
     * 控制 finish 操作时是否返回 AutoGLM 界面。
     * 协调器模式下设为 false，避免子步骤完成时频繁弹回 app。
     */
    var returnToAppOnFinish: Boolean = true

    companion object {
        // 延时配置（根据操作类型优化，单位：毫秒）
        private const val DELAY_TAP = 800L              // 点击后等待 UI 响应
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
            ActionType.FINISH -> executeFinish(action)
            ActionType.UNKNOWN -> ActionResult(success = false, message = "未知操作类型")
        }

        val elapsed = Logger.endTimer("action_${action.type}", Logger.ACTION)
        Logger.d(Logger.ACTION, "[RESULT] type=${action.type}, success=${result.success}, msg=${result.message}, elapsed=${elapsed}ms")
        return result
    }

    private suspend fun executeLaunch(action: ParsedAction): ActionResult {
        val appName = action.params["app"] as? String ?: return ActionResult(false, "未指定应用名称")

        // 使用动态搜索，支持从设备已安装应用中查找
        val packageName = AppDetector.getPackageFromAppName(context, appName) ?: appName

        return when (getEffectiveMode()) {
            Mode.SHELL_INPUT -> {
                // 在非 root 模式下，直接使用 Intent（shell 命令需要 root）
                if (!ShellExecutor.globalUseRoot) {
                    Logger.d(Logger.ACTION, "非 root 模式，使用 Intent 启动")
                    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        delay(DELAY_LAUNCH)
                        ActionResult(true, "通过 Intent 启动了 $appName")
                    } else {
                        ActionResult(false, "未找到应用：$appName")
                    }
                } else {
                    // Root 模式：优先用 shell 命令
                    val success = ShellExecutor.launchApp(packageName)
                    if (success) {
                        delay(DELAY_LAUNCH)
                        ActionResult(true, "已启动 $appName")
                    } else {
                        // Shell 失败，回退到 Intent
                        Logger.d(Logger.ACTION, "Shell 启动失败，回退到 Intent")
                        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            delay(DELAY_LAUNCH)
                            ActionResult(true, "通过 Intent 启动了 $appName")
                        } else {
                            ActionResult(false, "未找到应用：$appName")
                        }
                    }
                }
            }
            Mode.ACCESSIBILITY -> {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    delay(DELAY_LAUNCH)
                    ActionResult(true, "已启动 $appName")
                } else {
                    ActionResult(false, "未找到应用：$appName")
                }
            }
            else -> ActionResult(false, "无效模式")
        }
    }

    private suspend fun executeTap(action: ParsedAction): ActionResult {
        val relX = (action.params["x"] as? Float) ?: return ActionResult(false, "未指定 X 坐标")
        val relY = (action.params["y"] as? Float) ?: return ActionResult(false, "未指定 Y 坐标")

        val (x, y) = convertRelativeToAbsolute(relX, relY)
        Logger.d(Logger.ACTION, "Tap: rel($relX, $relY) -> screen($x, $y) [screen: ${screenWidth}x${screenHeight}]")

        // 检查敏感点击消息
        val message = action.params["message"] as? String
        if (message != null) {
            // 这是一个敏感操作 - 可以提示用户确认
            // 目前直接继续
        }

        return when (getEffectiveMode()) {
            Mode.SHELL_INPUT -> {
                val success = ShellExecutor.tap(x, y)
                delay(DELAY_TAP)
                ActionResult(success, if (success) "已点击 ($x, $y)" else "点击失败")
            }
            Mode.ACCESSIBILITY -> {
                val service = AutomationService.instance
                if (service != null) {
                    val success = service.performTap(x, y)
                    delay(DELAY_TAP)
                    ActionResult(success, if (success) "已点击 ($x, $y)" else "无障碍点击失败")
                } else {
                    ActionResult(false, "无障碍服务不可用")
                }
            }
            else -> ActionResult(false, "无效模式")
        }
    }

    private suspend fun executeSwipe(action: ParsedAction): ActionResult {
        val startX = (action.params["startX"] as? Float) ?: return ActionResult(false, "未指定起始 X")
        val startY = (action.params["startY"] as? Float) ?: return ActionResult(false, "未指定起始 Y")
        val endX = (action.params["endX"] as? Float) ?: return ActionResult(false, "未指定结束 X")
        val endY = (action.params["endY"] as? Float) ?: return ActionResult(false, "未指定结束 Y")

        val (sx, sy) = convertRelativeToAbsolute(startX, startY)
        val (ex, ey) = convertRelativeToAbsolute(endX, endY)
        Logger.d(Logger.ACTION, "Swipe: rel($startX,$startY)->($endX,$endY) -> screen($sx,$sy)->($ex,$ey)")

        // 计算滑动持续时间（与 Python 保持一致：dist_sq / 1000，范围 1000-2000ms）
        val distSq = (sx - ex) * (sx - ex) + (sy - ey) * (sy - ey)
        val duration = (distSq / 1000).coerceIn(1000, 2000)

        return when (getEffectiveMode()) {
            Mode.SHELL_INPUT -> {
                val success = ShellExecutor.swipe(sx, sy, ex, ey, duration)
                delay(DELAY_SWIPE)
                ActionResult(success, if (success) "已从 ($sx,$sy) 滑动到 ($ex,$ey)" else "滑动失败")
            }
            Mode.ACCESSIBILITY -> {
                val service = AutomationService.instance
                if (service != null) {
                    val success = service.performSwipe(sx, sy, ex, ey, duration.toLong())
                    delay(DELAY_SWIPE)
                    ActionResult(success, if (success) "已滑动" else "无障碍滑动失败")
                } else {
                    ActionResult(false, "无障碍服务不可用")
                }
            }
            else -> ActionResult(false, "无效模式")
        }
    }

    private suspend fun executeType(action: ParsedAction): ActionResult {
        val text = action.params["text"] as? String ?: return ActionResult(false, "未指定文本")

        // 根据模式调整策略：
        // - 无障碍模式(ACCESSIBILITY/AUTO): 优先剪贴板方案（清空→剪贴板→粘贴）- 最可靠
        // - Shell模式(SHELL_INPUT): 优先ADB Keyboard
        
        if (mode == Mode.ACCESSIBILITY || mode == Mode.AUTO) {
            // ========== 无障碍模式策略 ==========
            Logger.d(Logger.ACTION, "[TYPE] 当前模式: $mode，使用无障碍优先策略")
            
            // 优先1: 强制剪贴板方案（清空→剪贴板→粘贴，不依赖焦点验证）
            Logger.d(Logger.ACTION, "[TYPE] 优先1: 强制剪贴板方案 (清空→剪贴板→粘贴)")
            val directPasteSuccess = performDirectClipboardInput(text)
            if (directPasteSuccess) {
                Logger.d(Logger.ACTION, "[TYPE] ✅ 强制剪贴板输入成功")
                return ActionResult(true, "通过强制剪贴板方案输入了文本")
            }
            Logger.w(Logger.ACTION, "[TYPE] ❌ 强制剪贴板方案失败")
            
            // 优先2: Provider 无障碍服务（独立进程，兼容性好）
            Logger.d(Logger.ACTION, "[TYPE] 优先2: 尝试 Provider 无障碍服务")
            try {
                val nodeId = com.autoglm.assistant.accessibility.UIHierarchyManager.findFocusedNodeId(context)
                if (nodeId != null) {
                    Logger.d(Logger.ACTION, "[TYPE] 找到焦点节点 nodeId=$nodeId，调用 setTextOnNode()")
                    val success = com.autoglm.assistant.accessibility.UIHierarchyManager.setTextOnNode(context, nodeId, text)
                    if (success) {
                        Logger.d(Logger.ACTION, "[TYPE] ✅ Provider 无障碍服务输入成功")
                        return ActionResult(true, "通过 Provider 无障碍服务输入了文本")
                    } else {
                        Logger.w(Logger.ACTION, "[TYPE] ⚠️ setTextOnNode 返回 false")
                    }
                } else {
                    Logger.w(Logger.ACTION, "[TYPE] ⚠️ findFocusedNodeId 返回 null (未找到焦点节点)")
                }
            } catch (e: Exception) {
                Logger.e(Logger.ACTION, "[TYPE] ❌ Provider 输入异常: ${e.message}", e)
            }
            
            // 优先3: 本地无障碍服务 ACTION_SET_TEXT
            Logger.d(Logger.ACTION, "[TYPE] 优先3: 尝试本地无障碍 ACTION_SET_TEXT")
            val service = AutomationService.instance
            if (service != null) {
                val success = service.performTextInput(text)
                if (success) {
                    Logger.d(Logger.ACTION, "[TYPE] ✅ 本地无障碍输入成功")
                    return ActionResult(true, "通过本地无障碍服务输入了文本")
                } else {
                    Logger.w(Logger.ACTION, "[TYPE] ⚠️ 本地无障碍输入失败")
                }
            } else {
                Logger.w(Logger.ACTION, "[TYPE] ⚠️ AutomationService 实例不存在")
            }
            
            // 优先4: ADB Keyboard 降级方案
            Logger.d(Logger.ACTION, "[TYPE] 优先4: 尝试 ADB Keyboard 降级")
            val adbResult = ShellExecutor.typeTextViaAdbKeyboard(text, 300, context)
            if (adbResult.success) {
                Logger.d(Logger.ACTION, "[TYPE] ✅ ADB Keyboard 输入成功")
                return ActionResult(true, "通过 ADB Keyboard 输入了文本")
            }
            Logger.w(Logger.ACTION, "[TYPE] ❌ ADB Keyboard 失败: ${adbResult.output}")
            if (adbResult.output.contains("未安装", ignoreCase = true)) {
                showAdbKeyboardDownloadToast()
            }
            
            return ActionResult(false, "所有无障碍输入方式均失败: 强制剪贴板 / Provider / 本地无障碍 / ADB Keyboard")
            
        } else {
            // ========== Shell模式策略 ==========
            Logger.d(Logger.ACTION, "[TYPE] 当前模式: $mode，使用Shell优先策略")
            
            // 优先1: ADB Keyboard（shell 方式）
            Logger.d(Logger.ACTION, "[TYPE] 优先1: 尝试 ADB Keyboard (shell)，文本='$text'")
            val adbResult = ShellExecutor.typeTextViaAdbKeyboard(text, 300, context)
            if (adbResult.success) {
                Logger.d(Logger.ACTION, "[TYPE] ✅ ADB Keyboard 输入成功")
                return ActionResult(true, "通过 ADB Keyboard 输入了文本")
            }
            Logger.w(Logger.ACTION, "[TYPE] ❌ ADB Keyboard 失败: ${adbResult.output}")
            
            // 检查是否因为未安装而失败，如果是则提示用户下载
            if (adbResult.output.contains("未安装", ignoreCase = true)) {
                showAdbKeyboardDownloadToast()
            }
            
            return ActionResult(false, "Shell输入失败: ADB Keyboard(${adbResult.output})")
        }
    }
    
    /**
     * 强制剪贴板输入方案（不依赖焦点验证）
     * 业务目的: agent刚点击输入框后，焦点一定在那里，直接清空→剪贴板→粘贴
     * 操作实现: 1) 清空当前输入框 2) 写入剪贴板 3) 执行粘贴
     * 
     * 关键: 不验证bounds/isEditable等属性，只要有FOCUS_INPUT就操作
     */
    private suspend fun performDirectClipboardInput(text: String): Boolean {
        try {
            val service = AutomationService.instance
            if (service == null) {
                Logger.w(Logger.ACTION, "[DIRECT_PASTE] AutomationService不可用")
                return false
            }
            
            val rootNode = service.rootInActiveWindow
            if (rootNode == null) {
                Logger.w(Logger.ACTION, "[DIRECT_PASTE] 无法获取根节点")
                return false
            }
            
            // 步骤1: 查找焦点节点（不验证任何属性）
            var focusNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusNode == null) {
                // 备选: 尝试无障碍焦点
                focusNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            }
            
            if (focusNode == null) {
                Logger.w(Logger.ACTION, "[DIRECT_PASTE] 未找到任何焦点节点")
                return false
            }
            
            Logger.d(Logger.ACTION, "[DIRECT_PASTE] 找到焦点节点: class=${focusNode.className}")
            
            // 步骤2: 清空输入框
            try {
                Logger.d(Logger.ACTION, "[DIRECT_PASTE] 步骤1: 清空输入框")
                
                // 直接设置空文本清空
                val clearArgs = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
                delay(50)  // 使用delay替代Thread.sleep
                Logger.d(Logger.ACTION, "[DIRECT_PASTE] 清空完成")
            } catch (e: Exception) {
                Logger.w(Logger.ACTION, "[DIRECT_PASTE] 清空失败: ${e.message}，继续执行")
            }
            
            // 步骤3: 直接设置文本（不用剪贴板，更可靠）
            Logger.d(Logger.ACTION, "[DIRECT_PASTE] 步骤2: 直接设置文本")
            val setTextArgs = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val setTextResult = focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)
            
            if (setTextResult) {
                Logger.d(Logger.ACTION, "[DIRECT_PASTE] ✅ ACTION_SET_TEXT成功")
                focusNode.recycle()
                return true
            }
            
            // 步骤4: 降级方案 - 尝试剪贴板+粘贴
            Logger.w(Logger.ACTION, "[DIRECT_PASTE] ACTION_SET_TEXT失败，尝试剪贴板方案")
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("autoglm_input", text)
            clipboard.setPrimaryClip(clip)
            delay(100)
            
            val pasteResult = focusNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            focusNode.recycle()
            
            if (pasteResult) {
                Logger.d(Logger.ACTION, "[DIRECT_PASTE] ✅ 剪贴板粘贴成功")
                return true
            } else {
                Logger.w(Logger.ACTION, "[DIRECT_PASTE] ⚠️ 所有方式均失败")
                return false
            }
            
        } catch (e: Exception) {
            Logger.e(Logger.ACTION, "[DIRECT_PASTE] 异常: ${e.message}", e)
            return false
        }
    }
    
    /**
     * 显示ADB Keyboard下载提示
     * 包含可点击的下载链接
     */
    private fun showAdbKeyboardDownloadToast() {
        val downloadUrl = "https://github.com/senzhk/ADBKeyBoard/releases"
        
        Handler(Looper.getMainLooper()).post {
            // 显示Toast提示
            Toast.makeText(
                context,
                "⌨️ ADB Keyboard 未安装\n点击通知栏消息可下载",
                Toast.LENGTH_LONG
            ).show()
            
            // 尝试打开浏览器（方便用户直接下载）
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Logger.d(Logger.ACTION, "[TYPE] 已打开ADB Keyboard下载页面: $downloadUrl")
            } catch (e: Exception) {
                Logger.e(Logger.ACTION, "[TYPE] 无法打开下载页面: ${e.message}")
                // 再次Toast显示链接
                Toast.makeText(
                    context,
                    "请手动访问: $downloadUrl",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun executeLongPress(action: ParsedAction): ActionResult {
        val relX = (action.params["x"] as? Float) ?: return ActionResult(false, "未指定 X 坐标")
        val relY = (action.params["y"] as? Float) ?: return ActionResult(false, "未指定 Y 坐标")

        val (x, y) = convertRelativeToAbsolute(relX, relY)
        val duration = (action.params["duration"] as? Int) ?: 3000 // Python 默认 3000ms
        Logger.d(Logger.ACTION, "LongPress: rel($relX, $relY) -> screen($x, $y), duration=${duration}ms")

        return when (getEffectiveMode()) {
            Mode.SHELL_INPUT -> {
                val success = ShellExecutor.longPress(x, y, duration)
                delay(DELAY_LONG_PRESS)
                ActionResult(success, if (success) "已在 ($x, $y) 长按" else "长按失败")
            }
            Mode.ACCESSIBILITY -> {
                val service = AutomationService.instance
                if (service != null) {
                    val success = service.performLongPress(x, y, duration.toLong())
                    delay(DELAY_LONG_PRESS)
                    ActionResult(success)
                } else {
                    ActionResult(false, "无障碍服务不可用")
                }
            }
            else -> ActionResult(false, "无效模式")
        }
    }

    private suspend fun executeDoubleTap(action: ParsedAction): ActionResult {
        val relX = (action.params["x"] as? Float) ?: return ActionResult(false, "未指定 X 坐标")
        val relY = (action.params["y"] as? Float) ?: return ActionResult(false, "未指定 Y 坐标")

        val (x, y) = convertRelativeToAbsolute(relX, relY)
        Logger.d(Logger.ACTION, "DoubleTap: rel($relX, $relY) -> screen($x, $y)")

        return when (getEffectiveMode()) {
            Mode.SHELL_INPUT -> {
                ShellExecutor.tap(x, y)
                delay(DELAY_DOUBLE_TAP)
                val success = ShellExecutor.tap(x, y)
                delay(DELAY_DOUBLE_TAP_AFTER)
                ActionResult(success, if (success) "已在 ($x, $y) 双击" else "双击失败")
            }
            Mode.ACCESSIBILITY -> {
                val service = AutomationService.instance
                if (service != null) {
                    val success = service.performDoubleTap(x, y)
                    delay(DELAY_DOUBLE_TAP_AFTER)
                    ActionResult(success)
                } else {
                    ActionResult(false, "无障碍服务不可用")
                }
            }
            else -> ActionResult(false, "无效模式")
        }
    }

    private suspend fun executeBack(): ActionResult {
        return when (getEffectiveMode()) {
            Mode.SHELL_INPUT -> {
                val success = ShellExecutor.back()
                delay(DELAY_BACK)
                ActionResult(success, if (success) "已按返回键" else "返回失败")
            }
            Mode.ACCESSIBILITY -> {
                val service = AutomationService.instance
                if (service != null) {
                    val success = service.performBack()
                    delay(DELAY_BACK)
                    ActionResult(success)
                } else {
                    ActionResult(false, "无障碍服务不可用")
                }
            }
            else -> ActionResult(false, "无效模式")
        }
    }

    private suspend fun executeHome(): ActionResult {
        return when (getEffectiveMode()) {
            Mode.SHELL_INPUT -> {
                val success = ShellExecutor.home()
                delay(DELAY_HOME)
                ActionResult(success, if (success) "已按 Home 键" else "Home 键失败")
            }
            Mode.ACCESSIBILITY -> {
                val service = AutomationService.instance
                if (service != null) {
                    val success = service.performHome()
                    delay(DELAY_HOME)
                    ActionResult(success)
                } else {
                    ActionResult(false, "无障碍服务不可用")
                }
            }
            else -> ActionResult(false, "无效模式")
        }
    }

    private suspend fun executeWait(action: ParsedAction): ActionResult {
        val duration = (action.params["duration"] as? Int) ?: 1
        delay(duration * 1000L)
        return ActionResult(true, "已等待 $duration 秒")
    }

    private fun executeTakeOver(action: ParsedAction): ActionResult {
        val message = action.params["message"] as? String ?: "需要人工介入"
        return ActionResult(
            success = true,
            message = message,
            needsHumanIntervention = true
        )
    }

    private fun executeNote(action: ParsedAction): ActionResult {
        val message = action.params["message"] as? String ?: ""
        return ActionResult(true, "备注：$message")
    }

    private fun executeCallApi(action: ParsedAction): ActionResult {
        val instruction = action.params["message"] as? String ?: ""
        // 这通常会调用 API 进行内容总结
        return ActionResult(true, "调用 API：$instruction")
    }

    private suspend fun executeFinish(action: ParsedAction): ActionResult {
        val message = action.params["message"] as? String
        // 仅在非协调器模式下返回 app（协调器模式下子步骤 finish 不应弹回）
        if (returnToAppOnFinish) {
            returnToAutoGLM()
        }
        return ActionResult(success = true, message = message)
    }

    /**
     * 返回 AutoGLM app 界面
     * 可以在子任务完成、失败或需要用户确认时调用
     * 使用 REORDER_TO_FRONT 保持当前导航栈，不会跳转到主页
     */
    suspend fun returnToAutoGLM() {
        try {
            val intent = Intent(context, com.autoglm.assistant.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(intent)
            delay(500)
            Logger.d(Logger.ACTION, "已返回 AutoGLM 应用")
        } catch (e: Exception) {
            Logger.e(Logger.ACTION, "返回 AutoGLM 应用失败", e)
        }
    }

    private fun convertRelativeToAbsolute(relX: Float, relY: Float): Pair<Int, Int> {
        // 将 0-1000 的相对坐标转换为实际像素坐标
        val x = (relX / 1000f * screenWidth).toInt().coerceIn(0, screenWidth)
        val y = (relY / 1000f * screenHeight).toInt().coerceIn(0, screenHeight)
        return Pair(x, y)
    }

    private fun getEffectiveMode(): Mode {
        return when (mode) {
            Mode.AUTO -> {
                // 步骤1: 优先考虑 root 权限（更稳定可靠）
                if (ShellExecutor.globalUseRoot) {
                    Mode.SHELL_INPUT
                } else if (AutomationService.instance != null) {
                    // 步骤2: 无 root 时使用无障碍服务
                    Mode.ACCESSIBILITY
                } else {
                    // 步骤3: 都不可用时回退到 SHELL_INPUT（尝试非 root 命令）
                    Mode.SHELL_INPUT
                }
            }
            else -> mode
        }
    }
}
