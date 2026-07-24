package com.autoglm.glass.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 眼镜镜腿键/触控板系统广播。
 * 业务目的：监听眼镜系统下发的按键 action，转为 KeyType 事件回传手机。
 * 文档依据《眼镜端按键与系统广播》：运行时 registerReceiver，onReceive 匹配后 abortBroadcast 避免重复处理。
 */
enum class KeyType(val action: String) {
    CLICK("com.android.action.ACTION_SPRITE_BUTTON_CLICK"),
    BUTTON_DOWN("com.android.action.ACTION_SPRITE_BUTTON_DOWN"),
    BUTTON_UP("com.android.action.ACTION_SPRITE_BUTTON_UP"),
    DOUBLE_CLICK("com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK"),
    LONG_PRESS("com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS"),
    AI_START("com.android.action.ACTION_AI_START"),
    TWO_FINGER_TAP("com.android.action.ACTION_TWO_FINGER_SINGLE_TAP"),
    TWO_FINGER_DOUBLE_TAP("com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP"),
    SWIPE_FWD("com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"),
    SWIPE_BACK("com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"),
    SETTINGS("com.android.action.ACTION_SETTINGS_KEY");

    companion object {
        /** 全部 action，供 IntentFilter 注册 */
        val allActions: List<String> = entries.map { it.action }

        fun fromAction(action: String?): KeyType? = entries.firstOrNull { it.action == action }
    }
}

/**
 * 按键广播接收器：匹配 action 后回调 onKey，并 abortBroadcast 避免其他接收器重复处理。
 */
class KeyReceiver(private val onKey: (KeyType) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val keyType = KeyType.fromAction(intent?.action) ?: return
        onKey(keyType)
        runCatching { abortBroadcast() }
    }
}
