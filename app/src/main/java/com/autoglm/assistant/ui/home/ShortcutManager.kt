package com.autoglm.assistant.ui.home

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

data class ShortcutData(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val prompt: String,  // Use {paramName} for parameters, e.g. "帮我点一份{食物}"
    val iconName: String = "Star",
    val colorHex: Long = 0xFF64B5F6,
    val enablePlanning: Boolean = true,  // Whether to use planner for this shortcut
    val description: String = ""  // 可选备注，用于描述指令用途，供意图识别参考
) {
    // Check if this shortcut has parameters
    fun hasParameters(): Boolean = PARAM_PATTERN.containsMatchIn(prompt)

    // Extract parameter names from prompt
    fun getParameterNames(): List<String> = PARAM_PATTERN.findAll(prompt).map { it.groupValues[1] }.toList()

    // Fill parameters into prompt
    fun fillParameters(values: Map<String, String>): String {
        var result = prompt
        for ((name, value) in values) {
            result = result.replace("{$name}", value)
        }
        return result
    }

    companion object {
        private val PARAM_PATTERN = Regex("\\{([^}]+)\\}")
    }
}

class ShortcutManager(private val context: Context) {
    private val gson = Gson()
    private val file = File(context.filesDir, "shortcuts.json")

    companion object {
        val DEFAULT_SHORTCUTS = listOf(
            ShortcutData("1", "查看天气", "查看{城市}今天的天气", "WbSunny", 0xFFFFB74D),
            ShortcutData("2", "点外卖", "帮我在美团点一份{食物}", "ShoppingCart", 0xFFE57373),
            ShortcutData("3", "发微信", "给{联系人}发微信说{内容}", "Message", 0xFF81C784),
            ShortcutData("4", "设置闹钟", "设置{时间}的闹钟", "Alarm", 0xFF64B5F6),
            ShortcutData("5", "播放音乐", "播放{歌曲名}", "MusicNote", 0xFFBA68C8),
            ShortcutData("6", "导航去", "导航去{目的地}", "Map", 0xFF4DB6AC),
            ShortcutData("7", "搜索", "帮我搜索{关键词}", "Search", 0xFF9575CD),
            ShortcutData("8", "记笔记", "记一条笔记: {内容}", "Edit", 0xFFFFD54F)
        )

        val AVAILABLE_ICONS = listOf(
            "Star" to Icons.Default.Star,
            "WbSunny" to Icons.Default.WbSunny,
            "Alarm" to Icons.Default.Alarm,
            "Message" to Icons.Default.Message,
            "MusicNote" to Icons.Default.MusicNote,
            "Map" to Icons.Default.Map,
            "Edit" to Icons.Default.Edit,
            "LocalTaxi" to Icons.Default.LocalTaxi,
            "Article" to Icons.Default.Article,
            "Phone" to Icons.Default.Phone,
            "Email" to Icons.Default.Email,
            "Camera" to Icons.Default.Camera,
            "Search" to Icons.Default.Search,
            "Home" to Icons.Default.Home,
            "Settings" to Icons.Default.Settings,
            "Favorite" to Icons.Default.Favorite,
            "ShoppingCart" to Icons.Default.ShoppingCart,
            "Person" to Icons.Default.Person,
            "CalendarToday" to Icons.Default.CalendarToday,
            "Schedule" to Icons.Default.Schedule
        )

        val AVAILABLE_COLORS = listOf(
            0xFFE57373, // Red
            0xFFFFB74D, // Orange
            0xFFFFD54F, // Yellow
            0xFF81C784, // Green
            0xFF4DB6AC, // Teal
            0xFF64B5F6, // Blue
            0xFF9575CD, // Purple
            0xFFBA68C8, // Pink
            0xFF90A4AE, // Grey
            0xFFA1887F  // Brown
        )

        fun getIcon(name: String): ImageVector {
            return AVAILABLE_ICONS.find { it.first == name }?.second ?: Icons.Default.Star
        }
    }

    fun loadShortcuts(): List<ShortcutData> {
        return try {
            if (file.exists()) {
                val type = object : TypeToken<List<ShortcutData>>() {}.type
                gson.fromJson(file.readText(), type) ?: DEFAULT_SHORTCUTS
            } else {
                saveShortcuts(DEFAULT_SHORTCUTS)
                DEFAULT_SHORTCUTS
            }
        } catch (e: Exception) {
            e.printStackTrace()
            DEFAULT_SHORTCUTS
        }
    }

    fun saveShortcuts(shortcuts: List<ShortcutData>) {
        try {
            file.writeText(gson.toJson(shortcuts))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addShortcut(shortcut: ShortcutData): List<ShortcutData> {
        val shortcuts = loadShortcuts().toMutableList()
        shortcuts.add(shortcut)
        saveShortcuts(shortcuts)
        return shortcuts
    }

    fun updateShortcut(shortcut: ShortcutData): List<ShortcutData> {
        val shortcuts = loadShortcuts().toMutableList()
        val index = shortcuts.indexOfFirst { it.id == shortcut.id }
        if (index >= 0) {
            shortcuts[index] = shortcut
            saveShortcuts(shortcuts)
        }
        return shortcuts
    }

    fun deleteShortcut(id: String): List<ShortcutData> {
        val shortcuts = loadShortcuts().toMutableList()
        shortcuts.removeAll { it.id == id }
        saveShortcuts(shortcuts)
        return shortcuts
    }

    fun resetToDefaults(): List<ShortcutData> {
        saveShortcuts(DEFAULT_SHORTCUTS)
        return DEFAULT_SHORTCUTS
    }
}
