# 无障碍服务提供者应用实现指南

## 概述

本文档说明如何创建独立的无障碍服务提供者应用（AccessibilityProvider），与主应用配合实现无 root 的屏幕操作。

## 项目配置

### 1. 创建新应用

创建一个新的 Android 应用项目，包名必须为：
```
com.autoglm.assistant.provider
```

### 2. build.gradle.kts 配置

```kotlin
android {
    namespace = "com.autoglm.assistant.provider"
    compileSdk = 34
    
    defaultConfig {
        applicationId = "com.autoglm.assistant.provider"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"  // 必须与主应用的 accessibility_version.txt 一致
    }
    
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
```

## 实现步骤

### 步骤1：复制 AIDL 文件

将主应用的 AIDL 文件复制到提供者应用：
```
app/src/main/aidl/com/autoglm/assistant/provider/IAccessibilityProvider.aidl
```

**注意**：包名路径必须完全一致！

### 步骤2：实现无障碍服务

创建 `AccessibilityProviderService.kt`：

```kotlin
package com.autoglm.assistant.provider

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoglm.assistant.provider.IAccessibilityProvider
import java.io.ByteArrayOutputStream

class AccessibilityProviderService : AccessibilityService() {
    
    companion object {
        private const val TAG = "AccessibilityProvider"
        
        @Volatile
        private var instance: AccessibilityProviderService? = null
        
        fun getInstance(): AccessibilityProviderService? = instance
    }
    
    private val binder = object : IAccessibilityProvider.Stub() {
        
        override fun getUiHierarchy(): String {
            return getUIHierarchyXml()
        }
        
        override fun performClick(x: Int, y: Int): Boolean {
            return doPerformClick(x, y)
        }
        
        override fun performLongPress(x: Int, y: Int): Boolean {
            return doPerformLongPress(x, y)
        }
        
        override fun performGlobalAction(actionId: Int): Boolean {
            return this@AccessibilityProviderService.performGlobalAction(actionId)
        }
        
        override fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean {
            return doPerformSwipe(startX, startY, endX, endY, duration)
        }
        
        override fun findFocusedNodeId(): String? {
            val rootNode = rootInActiveWindow ?: return null
            val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            return focusedNode?.let { generateNodeId(it) }
        }
        
        override fun setTextOnNode(nodeId: String, text: String): Boolean {
            // 实现文本设置逻辑
            return false
        }
        
        override fun takeScreenshot(path: String, format: String): Boolean {
            // 需要 Android 9+ 的截图 API
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return false
            }
            // 实现截图逻辑
            return false
        }
        
        override fun isAccessibilityServiceEnabled(): Boolean {
            return instance != null
        }
        
        override fun getCurrentActivityName(): String? {
            val rootNode = rootInActiveWindow
            return rootNode?.packageName?.toString()
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "无障碍服务已连接")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 处理无障碍事件（如果需要）
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "服务被中断")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "服务已销毁")
    }
    
    override fun onBind(intent: android.content.Intent?): android.os.IBinder? {
        Log.d(TAG, "服务绑定: ${intent?.action}")
        return if (intent?.action == "com.autoglm.assistant.provider.IAccessibilityProvider") {
            binder
        } else {
            super.onBind(intent)
        }
    }
    
    // 实现点击
    private fun doPerformClick(x: Int, y: Int): Boolean {
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
    
    // 实现长按
    private fun doPerformLongPress(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }
        
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1000)) // 长按1秒
            .build()
        
        return dispatchGesture(gesture, null, null)
    }
    
    // 实现滑动
    private fun doPerformSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean {
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
    
    // 获取 UI 层次结构
    private fun getUIHierarchyXml(): String {
        val rootNode = rootInActiveWindow ?: return ""
        
        return try {
            val output = ByteArrayOutputStream()
            output.use { out ->
                out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\\n".toByteArray())
                serializeNode(rootNode, out, 0)
            }
            output.toString()
        } catch (e: Exception) {
            Log.e(TAG, "获取 UI 层次结构失败", e)
            ""
        }
    }
    
    // 序列化节点为 XML
    private fun serializeNode(node: AccessibilityNodeInfo, output: ByteArrayOutputStream, depth: Int) {
        val indent = "  ".repeat(depth)
        
        output.write("$indent<node ".toByteArray())
        output.write("index=\\"${node.hashCode()}\\" ".toByteArray())
        output.write("text=\\"${node.text ?: ""}\\" ".toByteArray())
        output.write("class=\\"${node.className ?: ""}\\" ".toByteArray())
        output.write("package=\\"${node.packageName ?: ""}\\" ".toByteArray())
        output.write("content-desc=\\"${node.contentDescription ?: ""}\\" ".toByteArray())
        output.write("checkable=\\"${node.isCheckable}\\" ".toByteArray())
        output.write("checked=\\"${node.isChecked}\\" ".toByteArray())
        output.write("clickable=\\"${node.isClickable}\\" ".toByteArray())
        output.write("enabled=\\"${node.isEnabled}\\" ".toByteArray())
        output.write("focusable=\\"${node.isFocusable}\\" ".toByteArray())
        output.write("focused=\\"${node.isFocused}\\" ".toByteArray())
        output.write("scrollable=\\"${node.isScrollable}\\" ".toByteArray())
        output.write("long-clickable=\\"${node.isLongClickable}\\" ".toByteArray())
        output.write("password=\\"${node.isPassword}\\" ".toByteArray())
        output.write("selected=\\"${node.isSelected}\\" ".toByteArray())
        
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        output.write("bounds=\\"[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]\\"".toByteArray())
        
        if (node.childCount > 0) {
            output.write(">\\n".toByteArray())
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    serializeNode(child, output, depth + 1)
                    child.recycle()
                }
            }
            output.write("$indent</node>\\n".toByteArray())
        } else {
            output.write(" />\\n".toByteArray())
        }
    }
    
    // 生成节点 ID
    private fun generateNodeId(node: AccessibilityNodeInfo): String {
        return node.hashCode().toString()
    }
}
```

### 步骤3：配置 AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.autoglm.assistant.provider">

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.AppCompat">
        
        <!-- 无障碍服务 -->
        <service
            android:name=".AccessibilityProviderService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            
            <!-- HARD: 这个 Action 必须与主应用中的 PROVIDER_ACTION 一致 -->
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
                <action android:name="com.autoglm.assistant.provider.IAccessibilityProvider" />
            </intent-filter>
            
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>
        
    </application>

</manifest>
```

### 步骤4：创建无障碍服务配置

创建 `app/src/main/res/xml/accessibility_service_config.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="100"
    android:packageNames="@null" />
```

### 步骤5：添加字符串资源

创建 `app/src/main/res/values/strings.xml`：

```xml
<resources>
    <string name="app_name">Accessibility Provider</string>
    <string name="accessibility_service_description">
        为主应用提供无障碍服务功能，用于实现无 root 的屏幕操作。
        请仅在信任主应用的情况下启用此服务。
    </string>
</resources>
```

## 编译与集成

### 1. 编译 APK

```bash
./gradlew assembleRelease
```

编译后的 APK 位于：
```
app/build/outputs/apk/release/app-release.apk
```

### 2. 集成到主应用

将编译好的 APK 复制到主应用：
```bash
cp app-release.apk [主应用路径]/app/src/main/assets/accessibility_provider.apk
```

### 3. 更新版本号

在主应用中更新 `app/src/main/assets/accessibility_version.txt`，填入与提供者应用 `versionName` 相同的版本号。

## 注意事项

### 包名 (HARD)
- 提供者应用的包名必须：`com.autoglm.assistant.provider`
- 与主应用代码中的配置保持一致

### Action (HARD)
- Service 的 intent-filter 必须包含：`com.autoglm.assistant.provider.IAccessibilityProvider`
- 与主应用的 `PROVIDER_ACTION` 保持一致

### 权限
- 用户需要在系统设置中手动启用无障碍服务
- 提供者应用需要在设置中被信任

### 测试
1. 安装提供者应用
2. 在设置 > 无障碍 中启用服务
3. 运行主应用测试绑定和操作

## 安全建议

1. **签名验证**：在提供者应用中验证主应用的签名，防止被恶意应用调用
2. **权限控制**：只响应来自信任包名的请求
3. **日志脱敏**：避免在日志中输出敏感信息
4. **混淆配置**：正确配置 ProGuard 规则，保护 AIDL 接口

## ProGuard 规则

```proguard
# 保留 AIDL 接口
-keep class com.autoglm.assistant.provider.IAccessibilityProvider { *; }
-keep class com.autoglm.assistant.provider.IAccessibilityProvider$Stub { *; }

# 保留无障碍服务
-keep class com.autoglm.assistant.provider.AccessibilityProviderService { *; }
```

## 故障排查

### 服务无法绑定
- 检查包名和 Action 是否正确
- 确认 `android:exported="true"`
- 查看 Logcat 中的错误日志

### 操作无响应
- 确认无障碍服务已在系统设置中启用
- 检查 Android 版本是否支持（最低 API 24）
- 确认手势权限 `canPerformGestures="true"`

## 参考资料

- [Android 无障碍服务官方文档](https://developer.android.com/guide/topics/ui/accessibility/service)
- [AIDL 官方文档](https://developer.android.com/guide/components/aidl)
