package com.autoglm.assistant.util

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.delay

/**
 * 屏幕解锁器 — 息屏唤醒时自动亮屏并解锁
 *
 * 业务目的：当唤醒词在息屏状态下检测到时，先亮屏解锁再执行后续 STT/Agent 操作。
 *
 * 流程：
 * 1. 检查屏幕是否关闭（PowerManager.isInteractive）
 * 2. 若关闭：通过 WakeLock 亮屏
 * 3. 检查是否有锁屏（KeyguardManager.isKeyguardLocked）
 * 4. 若有锁屏且配置了密码：通过 root input 命令滑动解锁 + 输入密码
 * 5. 等待解锁完成后返回
 *
 * 依赖：Root 权限（用于 input swipe/text 命令）
 */
object ScreenUnlocker {

    private const val TAG = "ScreenUnlocker"

    /** 加密存储中锁屏密码对应的 key */
    const val SECURE_KEY_LOCK_PASSWORD = "lock_screen_password"

    // 设备屏幕高度相关的默认解锁滑动参数（从屏幕底部 3/4 处向上滑到 1/4 处）
    // 这些值在 ensureScreenUnlocked 中根据实际屏幕尺寸动态计算
    private const val SWIPE_DURATION_MS = 300

    /**
     * 检查屏幕是否关闭（息屏状态）
     */
    fun isScreenOff(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isInteractive
    }

    /**
     * 检查是否处于锁屏状态
     */
    fun isKeyguardLocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked
    }

    /**
     * 确保屏幕已解锁（主入口）
     *
     * @param context 应用上下文
     * @return true=屏幕已解锁可用, false=解锁失败
     */
    suspend fun ensureScreenUnlocked(context: Context): Boolean {
        // 步骤1: 检查屏幕状态
        if (!isScreenOff(context) && !isKeyguardLocked(context)) {
            Logger.d(TAG, "屏幕已亮且未锁屏，无需解锁")
            return true
        }

        // 步骤2: 亮屏
        if (isScreenOff(context)) {
            Logger.i(TAG, "屏幕关闭，通过 KEYCODE_WAKEUP 亮屏")
            val wakeResult = ShellExecutor.execute("input keyevent KEYCODE_WAKEUP", useRoot = true)
            if (!wakeResult.success) {
                Logger.e(TAG, "亮屏失败: ${wakeResult.stderr}")
                return false
            }
            delay(500) // 等待屏幕点亮动画
        }

        // 步骤3: 检查是否有锁屏
        if (!isKeyguardLocked(context)) {
            Logger.d(TAG, "无锁屏，直接可用")
            return true
        }

        // 步骤4: 获取加密存储的锁屏密码
        val password = SecureStorage.getDecrypted(context, SECURE_KEY_LOCK_PASSWORD)
        if (password.isEmpty()) {
            Logger.w(TAG, "未配置锁屏密码，无法自动解锁")
            return false
        }

        // 步骤5: 滑动解除锁屏界面（从下向上滑）
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val swipeX = screenWidth / 2
        val swipeStartY = screenHeight * 3 / 4
        val swipeEndY = screenHeight / 4

        Logger.i(TAG, "滑动解锁: ($swipeX, $swipeStartY) → ($swipeX, $swipeEndY)")
        ShellExecutor.swipe(swipeX, swipeStartY, swipeX, swipeEndY, SWIPE_DURATION_MS)
        delay(500) // 等待密码输入界面出现

        // 步骤6: 输入密码（使用 input text 逐字符输入，避免空格等特殊字符问题）
        Logger.i(TAG, "输入锁屏密码（${password.length}位）")
        val inputResult = ShellExecutor.execute("input text '$password'", useRoot = true)
        if (!inputResult.success) {
            Logger.e(TAG, "输入密码失败: ${inputResult.stderr}")
            return false
        }
        delay(200)

        // 步骤7: 按 Enter 确认（适用于 PIN 码和密码模式）
        ShellExecutor.execute("input keyevent KEYCODE_ENTER", useRoot = true)
        delay(800) // 等待解锁动画完成

        // 步骤8: 验证是否解锁成功
        val unlocked = !isKeyguardLocked(context)
        Logger.i(TAG, "解锁${if (unlocked) "成功" else "失败"}")
        return unlocked
    }
}
