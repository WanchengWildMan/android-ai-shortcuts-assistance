# Prompt 配置诊断指南

## 配置是否生效的验证方法

### 方法1：查看日志输出

在 Android Studio 的 Logcat 中搜索以下关键词：

#### Agent System Prompt
```
标签: AutoGLM
关键词: "getEffectiveSystemPrompt"
```

#### Coordinator System Prompt
```
标签: AutoGLM
关键词: "SmartCoordinator available" 或 "Calling coordinator model"
```

#### Optimizer System Prompt
```
标签: AutoGLM
关键词: "PromptOptimizer enabled" 或 "Calling optimizer model"
```

### 方法2：使用 ADB 查看 SharedPreferences

```bash
# 查看所有配置
adb shell run-as com.autoglm.assistant cat /data/data/com.autoglm.assistant/shared_prefs/autoglm_prefs.xml

# 或者导出到本地查看
adb shell run-as com.autoglm.assistant cat /data/data/com.autoglm.assistant/shared_prefs/autoglm_prefs.xml > prefs.xml
```

查找以下键值：
- `agent_system_prompt`
- `coordinator_system_prompt`
- `optimizer_system_prompt`

### 方法3：在设置界面检查

1. 打开 App 设置
2. 找到对应的提示词配置区域：
   - **Agent 提示词**: 在"基础设置"区域
   - **协调器提示词**: 在"智能协调器"区域
   - **优化器提示词**: 在"提示词优化器"区域
3. 查看显示的字符数是否正确

## 常见问题排查

### 问题1：修改后没有生效

**原因**: 修改配置后需要重启服务才能生效

**解决方案**:
1. 在设置界面修改后，点击"保存"按钮
2. 系统会自动检测关键配置变化并重启服务
3. 查看日志确认 `reinitializePhoneAgent` 被调用

**验证代码位置**: `WakeWordService.kt:629-636`

### 问题2：Coordinator Prompt 没有生效

**可能原因**:
1. 协调器未启用 - 检查 `smartCoordinatorEnabled` 是否为 true
2. 协调器 API 配置不完整 - 需要配置 URL、API Key 和 Model Name
3. 任务执行时未启用规划 - 检查 `enablePlanning` 参数

**验证方法**:
```kotlin
// WakeWordService.kt:192-220
// 只有当以下条件都满足时，才会创建 TaskPlannerConfig：
// 1. coordinatorApiUrl 不为空
// 2. coordinatorApiKey 不为空
// 3. coordinatorModelName 不为空
```

### 问题3：Optimizer Prompt 没有生效

**可能原因**:
1. 优化器未启用 - 检查 `promptOptimizerEnabled` 是否为 true
2. 优化器 API 配置不完整
3. 任务执行时未启用优化器 - 检查 `enableOptimizer` 参数

**验证方法**:
```kotlin
// WakeWordService.kt:223-246
// 只有当 promptOptimizerEnabled = true 时，才会创建 PromptOptimizerConfig
```

### 问题4：空字符串 vs null 的处理

**重要**: 所有三个 prompt 配置都使用 `isNotBlank()` 判断：
- 空字符串 `""` → 使用内置默认提示词
- 有内容的字符串 → 使用自定义提示词

**代码位置**:
- SmartCoordinator.kt:69
- PromptOptimizer.kt:44
- AgentConfig.kt:32

## 调试建议

### 1. 添加临时日志

在 `SmartCoordinator.kt:69` 添加：
```kotlin
val systemPrompt = if (config.customSystemPrompt.isNotBlank()) {
    android.util.Log.i("AutoGLM", "Using custom coordinator prompt: ${config.customSystemPrompt.take(100)}...")
    config.customSystemPrompt
} else {
    android.util.Log.i("AutoGLM", "Using default coordinator prompt")
    if (language == "en") DECISION_SYSTEM_PROMPT_EN else DECISION_SYSTEM_PROMPT_CN
}
```

### 2. 检查配置传递

在 `WakeWordService.kt:213` 添加：
```kotlin
customSystemPrompt = prefs.coordinatorSystemPrompt.also {
    android.util.Log.i("AutoGLM", "Coordinator system prompt from prefs: ${it.take(100)}...")
}
```

### 3. 验证 SharedPreferences 写入

在 `PreferenceManager.kt:143` 的 setter 中添加：
```kotlin
set(value) = prefs.edit {
    putString(KEY_COORDINATOR_SYSTEM_PROMPT, value)
    android.util.Log.i("AutoGLM", "Saved coordinator prompt: ${value.take(100)}...")
}
```

## 总结

根据代码分析，prompt 配置的实现是**完整且正确的**。如果配置没有生效，通常是以下原因之一：

1. ✅ 配置后未重启服务
2. ✅ 相关功能未启用（协调器/优化器开关）
3. ✅ API 配置不完整
4. ✅ 任务执行时的参数设置不正确

按照本文档的方法逐步排查，应该能够定位问题。
