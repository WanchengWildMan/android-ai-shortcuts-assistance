package com.autoglm.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AutomationService : AccessibilityService() {

    companion object {
        var instance: AutomationService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to handle accessibility events for automation
    }

    override fun onInterrupt() {
        // Handle interruption
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // Gesture-based actions

    fun performTap(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    suspend fun performTapAsync(x: Int, y: Int): Boolean = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }

        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                cont.resume(false)
            }
        }

        dispatchGesture(gesture, callback, null)
    }

    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    fun performLongPress(x: Int, y: Int, duration: Long = 1000): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    fun performDoubleTap(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        // First tap
        val gesture1 = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        // Second tap
        val gesture2 = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 100, 50))
            .build()

        return dispatchGesture(gesture1, null, null) && dispatchGesture(gesture2, null, null)
    }

    // Navigation actions

    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun performRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun performNotifications(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    // Text input via accessibility

    fun performTextInput(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val focusedNode = findFocusedInputNode(rootNode)

        if (focusedNode != null) {
            val arguments = android.os.Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            android.util.Log.d("AutomationService", "[TEXT_INPUT] text=${text.take(30)}..., success=$result")
            return result
        }

        android.util.Log.w("AutomationService", "[TEXT_INPUT] No focused input node found")
        return false
    }

    private fun findFocusedInputNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 方法1: 使用 findFocus(FOCUS_INPUT) 查找输入焦点（operit 推荐方式）
        val inputFocus = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputFocus != null && inputFocus.isEditable) {
            android.util.Log.d("AutomationService", "[FIND_INPUT] Found via FOCUS_INPUT: ${inputFocus.className}")
            return inputFocus
        }

        // 方法2: 使用 findFocus(FOCUS_ACCESSIBILITY) 查找无障碍焦点
        val accessibilityFocus = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (accessibilityFocus != null && accessibilityFocus.isEditable) {
            android.util.Log.d("AutomationService", "[FIND_INPUT] Found via FOCUS_ACCESSIBILITY: ${accessibilityFocus.className}")
            return accessibilityFocus
        }

        // 方法3: 递归搜索 isFocused && isEditable 的节点
        val recursiveResult = findFocusedInputNodeRecursive(rootNode)
        if (recursiveResult != null) {
            android.util.Log.d("AutomationService", "[FIND_INPUT] Found via recursive search: ${recursiveResult.className}")
            return recursiveResult
        }

        // 方法4: 递归搜索所有 isEditable 的节点（最后手段）
        val editableNode = findFirstEditableNode(rootNode)
        if (editableNode != null) {
            android.util.Log.d("AutomationService", "[FIND_INPUT] Found first editable node: ${editableNode.className}")
            return editableNode
        }

        return null
    }

    private fun findFocusedInputNodeRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedInputNodeRecursive(child)
            if (result != null) {
                return result
            }
        }

        return null
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstEditableNode(child)
            if (result != null) {
                return result
            }
        }

        return null
    }

    // Find and click by text

    fun findAndClickByText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)

        for (node in nodes) {
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            // Try parent if node is not clickable
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                parent = parent.parent
            }
        }

        return false
    }

    // Find and click by content description

    fun findAndClickByDescription(description: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return findAndClickByDescriptionRecursive(rootNode, description)
    }

    private fun findAndClickByDescriptionRecursive(
        node: AccessibilityNodeInfo,
        description: String
    ): Boolean {
        if (node.contentDescription?.toString()?.contains(description, ignoreCase = true) == true) {
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickByDescriptionRecursive(child, description)) {
                return true
            }
        }

        return false
    }

    // Get screen content as text (for debugging)

    fun getScreenText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val builder = StringBuilder()
        extractText(rootNode, builder)
        return builder.toString()
    }

    private fun extractText(node: AccessibilityNodeInfo, builder: StringBuilder) {
        node.text?.let {
            if (it.isNotBlank()) {
                builder.append(it).append("\n")
            }
        }
        node.contentDescription?.let {
            if (it.isNotBlank()) {
                builder.append("[").append(it).append("]\n")
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                extractText(child, builder)
            }
        }
    }
}
