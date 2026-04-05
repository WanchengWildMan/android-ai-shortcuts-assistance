package com.autoglm.assistant.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * Shell命令执行器
 * 使用 libsu 库（by topjohnwu）执行 Root 命令，提供更可靠的 Root Shell 支持
 */
object ShellExecutor {
    private const val TAG = Logger.SHELL
    private const val ADB_KEYBOARD_IME_ID = "com.android.adbkeyboard/.AdbIME"

    // 缓存 Root 可用性检查结果
    @Volatile
    private var rootAvailable: Boolean? = null

    // SELinux 是否已关闭
    @Volatile
    private var selinuxDisabled: Boolean = false

    // 全局 Root 模式开关（可通过设置控制）
    @Volatile
    var globalUseRoot: Boolean = true

    // 任务级输入法会话状态
    @Volatile
    private var imeSessionActive: Boolean = false

    @Volatile
    private var imeSessionOriginalIme: String? = null

    // 静态初始化 libsu Shell 配置
    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
        )
        Logger.d(TAG, "libsu Shell initialized")
    }

    /**
     * 确保 SELinux 已关闭（仅执行一次）
     * 某些设备上 input 命令需要关闭 SELinux 才能正常工作
     */
    private fun ensureSELinuxDisabled() {
        if (selinuxDisabled) return

        try {
            // 检查当前 SELinux 状态
            val checkResult = Shell.cmd("getenforce").exec()
            val currentStatus = checkResult.out.joinToString("").trim()
            Logger.d(TAG, "Current SELinux status: $currentStatus")

            if (currentStatus.equals("Enforcing", ignoreCase = true)) {
                // 关闭 SELinux
                val disableResult = Shell.cmd("setenforce 0").exec()
                if (disableResult.code == 0) {
                    Logger.i(TAG, "SELinux disabled successfully (setenforce 0)")
                    selinuxDisabled = true
                } else {
                    Logger.w(TAG, "Failed to disable SELinux: ${disableResult.err.joinToString()}")
                }
            } else {
                Logger.d(TAG, "SELinux already in Permissive mode")
                selinuxDisabled = true
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error disabling SELinux", e)
        }
    }

    /**
     * 命令执行结果
     * @param success 命令是否成功执行（exitCode == 0）
     * @param stdout 标准输出
     * @param stderr 标准错误
     * @param exitCode 退出码
     */
    data class Result(
        val success: Boolean,
        val stdout: String,
        val stderr: String = "",
        val exitCode: Int
    ) {
        // 兼容旧代码的 output 属性
        val output: String
            get() = if (stderr.isNotEmpty()) "$stdout\nError: $stderr" else stdout
    }

    /**
     * 执行 Shell 命令
     * @param command 要执行的命令
     * @param useRoot 是否使用 Root 权限（默认 false）
     * @return 命令执行结果
     */
    suspend fun execute(command: String, useRoot: Boolean = false): Result = withContext(Dispatchers.IO) {
        // 实际是否使用 root：全局开关 AND 参数
        val effectiveUseRoot = globalUseRoot && useRoot
        val cmdPreview = if (command.length > 80) command.take(80) + "..." else command
        Logger.d(TAG, "[CMD] root=$effectiveUseRoot (global=$globalUseRoot, param=$useRoot), cmd=$cmdPreview")
        val startTime = System.currentTimeMillis()

        try {
            val result = if (effectiveUseRoot) {
                executeWithLibsu(command)
            } else {
                executeWithRuntime(command)
            }

            val elapsed = System.currentTimeMillis() - startTime
            Logger.d(TAG, "[DONE] exit=${result.exitCode}, elapsed=${elapsed}ms, out=${result.stdout.take(100)}")
            result
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Logger.e(TAG, "[ERROR] cmd=$cmdPreview, elapsed=${elapsed}ms", e)
            Result(
                success = false,
                stdout = "",
                stderr = "Exception: ${e.message}",
                exitCode = -1
            )
        }
    }

    /**
     * 使用 libsu 执行 Root 命令
     */
    private fun executeWithLibsu(command: String): Result {
        // 确保 SELinux 已关闭（首次执行时）
        ensureSELinuxDisabled()

        val shellResult = Shell.cmd(command).exec()
        return Result(
            success = shellResult.code == 0,
            stdout = shellResult.out.joinToString("\n"),
            stderr = shellResult.err.joinToString("\n"),
            exitCode = shellResult.code
        )
    }

    /**
     * 使用 Runtime.exec 执行普通 Shell 命令
     */
    private fun executeWithRuntime(command: String): Result {
        val process = Runtime.getRuntime().exec("sh")

        DataOutputStream(process.outputStream).use { os ->
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
        }

        val stdout = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                stdout.append(line).append("\n")
            }
        }

        val stderr = StringBuilder()
        BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                stderr.append(line).append("\n")
            }
        }

        val exitCode = process.waitFor()
        return Result(
            success = exitCode == 0,
            stdout = stdout.toString().trimEnd(),
            stderr = stderr.toString().trimEnd(),
            exitCode = exitCode
        )
    }

    // ==================== 便捷方法 ====================

    suspend fun tap(x: Int, y: Int): Boolean {
        val result = execute("input tap $x $y", useRoot = true)
        return result.success
    }

    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int = 300): Boolean {
        val result = execute("input swipe $startX $startY $endX $endY $durationMs", useRoot = true)
        return result.success
    }

    suspend fun longPress(x: Int, y: Int, durationMs: Int = 1000): Boolean {
        val result = execute("input swipe $x $y $x $y $durationMs", useRoot = true)
        return result.success
    }

    suspend fun back(): Boolean {
        val result = execute("input keyevent 4", useRoot = true)
        return result.success
    }

    suspend fun home(): Boolean {
        val result = execute("input keyevent 3", useRoot = true)
        return result.success
    }

    suspend fun screenshot(outputPath: String, useRoot: Boolean = true): Boolean {
        val result = execute("screencap -p $outputPath", useRoot)
        return result.success
    }

    suspend fun getCurrentPackage(): String? {
        val result = execute("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'", useRoot = true)
        if (result.success) {
            val regex = Regex("([a-zA-Z][a-zA-Z0-9_.]*)/")
            val match = regex.find(result.stdout)
            return match?.groupValues?.get(1)
        }
        return null
    }

    suspend fun launchApp(packageName: String): Boolean {
        // 优先使用 am start（更可靠）
        var result = execute("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName", useRoot = true)
        if (result.success) return true

        // 回退到 monkey 命令
        result = execute("monkey -p $packageName -c android.intent.category.LAUNCHER 1", useRoot = true)
        return result.success
    }

    /**
     * 检查 App 是否已持有 WRITE_SECURE_SETTINGS 权限
     * 业务目的：判断能否通过 Settings API 切换输入法（非root设备方案）
     * 操作实现：使用 checkSelfPermission 对 WRITE_SECURE_SETTINGS 进行权限查询
     */
    private fun hasWriteSecureSettingsPermission(context: android.content.Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * 切换输入法（自动选择最优方式）
     * 业务目的：在 root/非root 设备上均能可靠切换到指定输入法
     * 操作实现：
     *   步骤1 - 优先用 Settings.Secure API（非root设备）
     *           前提：App 通过 `adb shell pm grant <包名> android.permission.WRITE_SECURE_SETTINGS` 预授权
     *   步骤2 - 回退到 shell 命令（root 设备走 libsu；如无 root 且无 Settings 权限则失败）
     * @param imeId 目标输入法 ID，如 "com.android.adbkeyboard/.AdbIME"
     * @param enableFirst 是否先将 imeId 加入 enabled_input_methods（未启用的新 IME 需要）
     * @param context 用于权限检查，null 时取 App.instance
     */
    suspend fun switchIme(
        imeId: String,
        enableFirst: Boolean = false,
        context: android.content.Context? = null
    ): Boolean {
        val appContext = context ?: com.autoglm.assistant.App.instance

        // 步骤1: 尝试 Settings.Secure API（非root友好，需 WRITE_SECURE_SETTINGS 权限）
        if (hasWriteSecureSettingsPermission(appContext)) {
            try {
                // 步骤1a: 若需先启用，确保 imeId 已加入 enabled_input_methods
                // 注意: Android 14+ 禁止 targetSdk>=34 的 App 通过 Settings.Secure.getString
                // 读取 enabled_input_methods，改用 InputMethodManager.getEnabledInputMethodList()
                if (enableFirst) {
                    val imm = appContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                            as android.view.inputmethod.InputMethodManager
                    val enabledIds = imm.enabledInputMethodList.map { it.id }
                    if (!enabledIds.contains(imeId)) {
                        // 在现有列表末尾追加新 IME ID，用冒号分隔（Settings 格式）
                        val newEnabled = (enabledIds + imeId).joinToString(":")
                        android.provider.Settings.Secure.putString(
                            appContext.contentResolver,
                            android.provider.Settings.Secure.ENABLED_INPUT_METHODS,
                            newEnabled
                        )
                        Logger.d(TAG, "[SWITCH_IME] 已将 $imeId 加入 enabled_input_methods（原有 ${enabledIds.size} 个）")
                    }
                }
                // 步骤1b: 设置默认输入法
                android.provider.Settings.Secure.putString(
                    appContext.contentResolver,
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
                    imeId
                )
                Logger.i(TAG, "[SWITCH_IME] Settings API 切换成功: $imeId")
                return true
            } catch (e: SecurityException) {
                Logger.w(TAG, "[SWITCH_IME] Settings API 无权限，回退到 shell: ${e.message}")
            } catch (e: Exception) {
                Logger.e(TAG, "[SWITCH_IME] Settings API 异常，回退到 shell", e)
            }
        } else {
            Logger.d(TAG, "[SWITCH_IME] 无 WRITE_SECURE_SETTINGS 权限，走 shell（需 root）")
        }

        // 步骤2: shell 命令回退（root 设备走 libsu，非root设备缺权限会失败）
        val cmd = if (enableFirst) "ime enable $imeId && ime set $imeId"
                  else "ime set $imeId"
        val result = execute(cmd, useRoot = true)
        if (result.success) {
            Logger.i(TAG, "[SWITCH_IME] shell 命令切换成功: $imeId")
        } else {
            Logger.w(TAG, "[SWITCH_IME] shell 命令切换失败（非root设备请执行: " +
                "adb shell pm grant com.autoglm.assistant android.permission.WRITE_SECURE_SETTINGS）: ${result.stderr}")
        }
        return result.success
    }

    suspend fun setIme(imeId: String): Boolean {
        return switchIme(imeId)
    }

    suspend fun getCurrentIme(): String? {
        val result = execute("settings get secure default_input_method")
        return if (result.success) result.stdout.trim() else null
    }

    /**
     * 输入文本（使用剪贴板方式，更可靠）
     * 流程：清除输入框 -> 设置剪贴板 -> 粘贴
     * @param text 要输入的文本
     * @param context Android Context（用于设置剪贴板）
     */
    suspend fun typeText(text: String, context: android.content.Context): Result {
        Logger.d(TAG, "[TYPE] text=${text.take(30)}...")

        try {
            // 1. 清除输入框
            execute("input keyevent KEYCODE_CLEAR", useRoot = true)
            kotlinx.coroutines.delay(200)

            if (text.isEmpty()) {
                Logger.d(TAG, "[TYPE_DONE] 已清空输入框")
                return Result(true, "已清空输入框", "", 0)
            }

            // 2. 设置剪贴板内容
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("autoglm_input", text))
            }
            kotlinx.coroutines.delay(100)

            // 3. 粘贴
            val pasteResult = execute("input keyevent KEYCODE_PASTE", useRoot = true)
            kotlinx.coroutines.delay(300)

            Logger.d(TAG, "[TYPE_DONE] success=${pasteResult.success}")
            return pasteResult
        } catch (e: Exception) {
            Logger.e(TAG, "[TYPE] 异常: ${e.message}", e)
            return Result(false, "", "异常: ${e.message}", -1)
        }
    }

    /**
     * 通过剪贴板粘贴输入文本（最可靠的方式）
     * 流程：通过 am 命令设置剪贴板 -> 粘贴
     * @param text 要输入的文本
     * @param context Android Context（备用）
     */
    suspend fun typeTextViaClipboard(text: String, context: android.content.Context): Result {
        Logger.d(TAG, "[TYPE_CLIP] text=${text.take(30)}...")

        try {
            if (text.isEmpty()) {
                // 全选并删除
                execute("input keyevent KEYCODE_CTRL_LEFT KEYCODE_A", useRoot = true)
                kotlinx.coroutines.delay(100)
                execute("input keyevent KEYCODE_DEL", useRoot = true)
                kotlinx.coroutines.delay(100)
                Logger.d(TAG, "[TYPE_CLIP_DONE] Cleared input field")
                return Result(true, "Cleared input field", "", 0)
            }

            // 1. 通过 am 命令设置剪贴板（绕过 Android 10+ 限制）
            Logger.d(TAG, "[TYPE_CLIP] Setting clipboard via am command")
            val escapedText = text.replace("'", "'\\''")
            val setClipResult = execute("am broadcast -a clipper.set -e text '$escapedText'", useRoot = true)

            // 如果 clipper 不可用，尝试 service call 方式
            if (!setClipResult.success || !setClipResult.stdout.contains("result=0")) {
                Logger.d(TAG, "[TYPE_CLIP] clipper 不可用，尝试 service call 方式")
                // 使用 service call 设置剪贴板（am broadcast 不需要 root，useRoot=false）
                val base64Text = android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                val serviceResult = execute("echo '$text' | am broadcast -a ADB_INPUT_TEXT --es msg '$text'", useRoot = false)
                if (!serviceResult.success) {
                    // 最后尝试：直接通过应用 Context 设置（可能不可靠）
                    Logger.d(TAG, "[TYPE_CLIP] 尝试使用 Context 剪贴板")
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("input", text))
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "[TYPE_CLIP] Context 剪贴板设置失败: ${e.message}")
                        return Result(false, "", "设置剪贴板失败", -1)
                    }
                }
            }
            kotlinx.coroutines.delay(150)

            // 2. 全选当前内容
            Logger.d(TAG, "[TYPE_CLIP] 全选")
            execute("input keyevent KEYCODE_CTRL_LEFT KEYCODE_A", useRoot = true)
            kotlinx.coroutines.delay(100)

            // 3. 粘贴
            Logger.d(TAG, "[TYPE_CLIP] 粘贴")
            val pasteResult = execute("input keyevent KEYCODE_PASTE", useRoot = true)
            kotlinx.coroutines.delay(200)

            Logger.d(TAG, "[TYPE_CLIP_DONE] success=${pasteResult.success}")
            return pasteResult
        } catch (e: Exception) {
            Logger.e(TAG, "[TYPE_CLIP] 异常: ${e.message}", e)
            return Result(false, "", "异常: ${e.message}", -1)
        }
    }

    /**
     * 检查ADB Keyboard是否已安装
     */
    suspend fun isAdbKeyboardInstalled(): Boolean {
        val result = execute("pm list packages | grep adbkeyboard", useRoot = false)
        val installed = result.success && result.stdout.contains("adbkeyboard")
        Logger.d(TAG, "[ADB_KEYBOARD] 安装状态: $installed")
        return installed
    }
    
    /**
     * 自动安装ADB Keyboard
     * 
     * 业务目的：确保ADB Keyboard已安装，用于文本输入
     * 操作实现：从GitHub下载并通过pm install命令安装
     */
    suspend fun ensureAdbKeyboardInstalled(context: android.content.Context): Result {
        // 步骤1: 检查是否已安装
        if (isAdbKeyboardInstalled()) {
            Logger.d(TAG, "[ADB_KEYBOARD] ✅ 已安装，无需重复安装")
            return Result(true, "ADB Keyboard already installed", "", 0)
        }
        
        Logger.d(TAG, "[ADB_KEYBOARD] ⚠️ 未安装，开始自动安装...")
        
        try {
            // 步骤2: 下载APK到临时目录
            val cacheDir = context.cacheDir
            val apkFile = java.io.File(cacheDir, "adbkeyboard.apk")
            
            Logger.d(TAG, "[ADB_KEYBOARD] 📥 正在下载 ADB Keyboard APK...")
            
            // GitHub releases URL
            val downloadUrl = "https://github.com/senzhk/ADBKeyBoard/releases/download/v2.0/ADBKeyboard.apk"
            
            // 使用wget或curl下载（需要root）
            val downloadResult = execute(
                "wget -O ${apkFile.absolutePath} $downloadUrl || curl -L -o ${apkFile.absolutePath} $downloadUrl",
                useRoot = true
            )
            
            if (!downloadResult.success || !apkFile.exists()) {
                Logger.e(TAG, "[ADB_KEYBOARD] ❌ 下载失败")
                return Result(false, "", "下载 ADB Keyboard 失败", -1)
            }
            
            Logger.d(TAG, "[ADB_KEYBOARD] ✅ 下载成功: ${apkFile.length()} bytes")
            
            // 步骤3: 安装APK（需要root）
            Logger.d(TAG, "[ADB_KEYBOARD] 📦 正在安装...")
            val installResult = execute("pm install -r ${apkFile.absolutePath}", useRoot = true)
            
            // 步骤4: 清理临时文件
            apkFile.delete()
            
            if (installResult.success) {
                Logger.d(TAG, "[ADB_KEYBOARD] ✅ 安装成功")
                
                // 步骤5: 启用输入法
                val enableResult = execute("ime enable com.android.adbkeyboard/.AdbIME", useRoot = true)
                if (enableResult.success) {
                    Logger.d(TAG, "[ADB_KEYBOARD] ✅ 已启用输入法")
                }
                
                return Result(true, "ADB Keyboard installed successfully", "", 0)
            } else {
                Logger.e(TAG, "[ADB_KEYBOARD] ❌ 安装失败: ${installResult.stderr}")
                return installResult
            }
            
        } catch (e: Exception) {
            Logger.e(TAG, "[ADB_KEYBOARD] ❌ 安装异常", e)
            return Result(false, "", "安装异常: ${e.message}", -1)
        }
    }

    /**
     * 通过 ADB Keyboard 输入文本（备用方式）
     * 注意：切换输入法会导致输入框失去焦点
     */
    suspend fun typeTextViaAdbKeyboard(text: String, delayMs: Int = 300, context: android.content.Context? = null): Result {
        Logger.d(TAG, "[TYPE_ADB] text=${text.take(30)}..., delayMs=$delayMs")

        try {
            // 步骤0: 确保ADB Keyboard已安装
            if (context != null && !isAdbKeyboardInstalled()) {
                Logger.w(TAG, "[TYPE_ADB] ADB Keyboard 未安装，尝试自动安装...")
                val installResult = ensureAdbKeyboardInstalled(context)
                if (!installResult.success) {
                    return Result(
                        false, 
                        "", 
                        "ADB Keyboard 未安装且自动安装失败。请手动安装：https://github.com/senzhk/ADBKeyBoard/releases", 
                        -1
                    )
                }
            }
            
            // 任务级输入法会话开启时，不再每次输入都切换/恢复输入法
            if (imeSessionActive) {
                val currentImeResult = execute("settings get secure default_input_method", useRoot = true)
                val currentIme = if (currentImeResult.success) currentImeResult.stdout.trim() else ""
                if (!currentIme.contains("adbkeyboard", ignoreCase = true)) {
                    val switched = switchIme(ADB_KEYBOARD_IME_ID, enableFirst = true, context = context)
                    if (!switched) {
                        return Result(false, "", "输入法会话中切换ADB Keyboard失败", -1)
                    }
                }
                // 步骤1a: 通过 Java API 直接发送广播（不走 shell，不需要 INTERACT_ACROSS_USERS 权限）
                return sendAdbInputBroadcast(text, context)
            }

            // 1. 获取当前输入法
            val currentImeResult = execute("settings get secure default_input_method", useRoot = true)
            val currentIme = if (currentImeResult.success) currentImeResult.stdout.trim() else ""
            Logger.d(TAG, "[TYPE_ADB] 当前输入法: $currentIme")

            // 如果已经是 ADB Keyboard，直接发送
            if (currentIme.contains("adbkeyboard")) {
                Logger.d(TAG, "[TYPE_ADB] 已在使用 ADB Keyboard，直接发送")
                // 通过 Java API 直接发送广播（不走 shell，不需要 INTERACT_ACROSS_USERS 权限）
                return sendAdbInputBroadcast(text, context)
            }

            // 2. 切换到 ADB Keyboard
            Logger.d(TAG, "[TYPE_ADB] 正在切换到 ADB Keyboard")
            val switched = switchIme(ADB_KEYBOARD_IME_ID, enableFirst = true, context = context)
            if (!switched) {
                Logger.e(TAG, "[TYPE_ADB] 切换失败")
                return Result(false, "", "切换到 ADB Keyboard 失败", -1)
            }

            kotlinx.coroutines.delay(delayMs.toLong())

            // 3. 发送文本：通过 Java API 直接发送广播（不走 shell，不需要 INTERACT_ACROSS_USERS 权限）
            Logger.d(TAG, "[TYPE_ADB] 正在发送文本")
            val inputResult = sendAdbInputBroadcast(text, context)

            kotlinx.coroutines.delay(200)

            // 4. 恢复原输入法
            if (currentIme.isNotEmpty()) {
                Logger.d(TAG, "[TYPE_ADB] 正在恢复输入法: $currentIme")
                switchIme(currentIme)
            }

            kotlinx.coroutines.delay(300)
            return inputResult
        } catch (e: Exception) {
            Logger.e(TAG, "[TYPE_ADB] 异常: ${e.message}", e)
            return Result(false, "", "异常: ${e.message}", -1)
        }
    }

    /**
     * 通过 Java API 向 ADB Keyboard 发送输入广播
     * 目的: 替代 `am broadcast` shell 命令——App 进程执行 am broadcast 需要 INTERACT_ACROSS_USERS
     *       权限，但直接调用 Context.sendBroadcast() 不需要该权限
     * 操作: 1) 优先使用 context.sendBroadcast()；2) context 为 null 时降级到 shell（加 --user 0）
     */
    private suspend fun sendAdbInputBroadcast(text: String, context: android.content.Context?): Result {
        if (context != null) {
            // 步骤1: 直接通过 Java API 发广播，无需 shell，无需 root 或跨用户权限
            val intent = android.content.Intent("ADB_INPUT_TEXT").apply {
                putExtra("msg", text)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                context.sendBroadcast(intent)
            }
            Logger.d(TAG, "[TYPE_ADB] Java API 广播已发送，text=${text.take(30)}")
            return Result(true, "已发送 ADB_INPUT_TEXT 广播", "", 0)
        } else {
            // 步骤2: 无 context 时降级到 shell，指定 --user 0 避免 -2 (ALL_USERS) 触发权限检查
            val escapedText = text.replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`")
            Logger.w(TAG, "[TYPE_ADB] 无 context，降级到 shell am broadcast --user 0")
            return execute("am broadcast --user 0 -a ADB_INPUT_TEXT --es msg \"$escapedText\"", useRoot = false)
        }
    }

    /**
     * 开启任务级 ADB Keyboard 输入法会话。
     * 行为：保存当前输入法 -> 切换到 ADB Keyboard；后续输入不再重复切换。
     */
    suspend fun beginAdbKeyboardSession(context: android.content.Context? = null): Boolean {
        if (imeSessionActive) {
            Logger.d(TAG, "[IME_SESSION] 已处于会话中，跳过重复开启")
            return true
        }

        if (context != null && !isAdbKeyboardInstalled()) {
            val installResult = ensureAdbKeyboardInstalled(context)
            if (!installResult.success) {
                Logger.w(TAG, "[IME_SESSION] ADB Keyboard 未安装且自动安装失败")
                return false
            }
        }

        // 步骤1: 获取当前输入法 — 优先 Java API（不依赖 root），降级 shell 命令
        val currentIme = try {
            android.provider.Settings.Secure.getString(
                com.autoglm.assistant.App.instance.contentResolver,
                "default_input_method"
            ) ?: ""
        } catch (e: Exception) {
            val shellResult = execute("settings get secure default_input_method", useRoot = true)
            if (shellResult.success) shellResult.stdout.trim() else ""
        }
        imeSessionOriginalIme = currentIme

        if (currentIme.contains("adbkeyboard", ignoreCase = true)) {
            imeSessionActive = true
            Logger.i(TAG, "[IME_SESSION] 当前已是 ADB Keyboard，直接复用")
            return true
        }

        val switched = switchIme(ADB_KEYBOARD_IME_ID, enableFirst = true, context = context)
        if (!switched) {
            Logger.w(TAG, "[IME_SESSION] 切换到 ADB Keyboard 失败")
            imeSessionOriginalIme = null
            imeSessionActive = false
            return false
        }

        imeSessionActive = true
        Logger.i(TAG, "[IME_SESSION] 已开启，originalIme=$currentIme")
        return true
    }

    /**
     * 结束任务级输入法会话并恢复原输入法。
     */
    suspend fun endAdbKeyboardSession(): Boolean {
        if (!imeSessionActive) {
            return true
        }

        val originalIme = imeSessionOriginalIme.orEmpty()
        imeSessionActive = false
        imeSessionOriginalIme = null

        if (originalIme.isBlank() || originalIme.contains("adbkeyboard", ignoreCase = true)) {
            Logger.i(TAG, "[IME_SESSION] 结束会话，无需恢复输入法")
            return true
        }

        val switched = switchIme(originalIme)
        if (!switched) {
            Logger.w(TAG, "[IME_SESSION] 恢复原输入法失败: $originalIme")
            return false
        }

        Logger.i(TAG, "[IME_SESSION] 已恢复原输入法: $originalIme")
        return true
    }

    suspend fun broadcastText(text: String): Boolean {
        val base64Text = android.util.Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val result = execute("am broadcast -a ADB_INPUT_B64 --es msg '$base64Text'")
        return result.success
    }

    suspend fun clearText(): Boolean {
        val imeResult = execute("settings get secure default_input_method", useRoot = true)
        val currentIme = if (imeResult.success) imeResult.stdout.trim() else ""
        if (!currentIme.contains("adbkeyboard", ignoreCase = true)) {
            Logger.w(TAG, "[CLEAR_TEXT] 跳过 ADB_CLEAR_TEXT：当前输入法不是 ADB Keyboard (currentIme=$currentIme)")
            return false
        }

        val result = execute("am broadcast -a ADB_CLEAR_TEXT", useRoot = true)
        if (!result.success) {
            Logger.w(TAG, "[CLEAR_TEXT] ADB_CLEAR_TEXT 广播失败: ${result.stderr}")
            return false
        }
        return true
    }

    /**
     * 检查 Root 权限是否可用
     * 使用缓存避免重复检查
     */
    fun isRootAvailable(): Boolean {
        // 使用缓存结果
        rootAvailable?.let { return it }

        return try {
            val hasRoot = Shell.getShell().isRoot
            rootAvailable = hasRoot
            Logger.d(TAG, "Root available: $hasRoot")
            hasRoot
        } catch (e: Exception) {
            Logger.e(TAG, "Error checking root", e)
            rootAvailable = false
            false
        }
    }

    /**
     * 获取 UI 层级信息（通过 uiautomator dump）
     * @return UI XML 字符串，失败返回 null
     */
    suspend fun dumpUIHierarchy(): String? {
        val dumpResult = execute("uiautomator dump /sdcard/window_dump.xml", useRoot = true)
        if (!dumpResult.success) {
            Logger.e(TAG, "uiautomator dump failed: ${dumpResult.stderr}")
            return null
        }

        val readResult = execute("cat /sdcard/window_dump.xml && rm /sdcard/window_dump.xml", useRoot = true)
        return if (readResult.success) readResult.stdout else null
    }

    /**
     * 获取当前焦点窗口信息
     */
    suspend fun getWindowInfo(): String? {
        val commands = listOf(
            "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'",
            "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'",
            "dumpsys activity activities | grep -E 'topResumedActivity|topActivity'"
        )

        for (command in commands) {
            val result = execute(command, useRoot = true)
            if (result.success && result.stdout.isNotBlank()) {
                return result.stdout
            }
        }
        return null
    }
}
