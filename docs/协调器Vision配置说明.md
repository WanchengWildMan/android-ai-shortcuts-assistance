# 协调器 Vision 支持配置说明

## 问题背景

协调器在决策时可以接收当前截图来更准确地判断任务状态，但并非所有模型都支持图片输入（Vision 功能）。

如果向不支持 Vision 的模型发送图片，会导致 **API Error: 400 - Bad Request** 错误。

## 解决方案

新增配置项 `enableVision`，控制协调器是否接收截图。

### 配置位置

1. **代码配置**：`TaskPlannerConfig.enableVision`
2. **存储配置**：`PreferenceManager.coordinatorEnableVision`
3. **默认值**：`false`（兼容大多数模型）

### 支持 Vision 的模型

以下模型支持图片输入，可以启用 Vision：

| 提供商 | 模型名称 | 说明 |
|--------|---------|------|
| 智谱 GLM | `glm-4v`, `glm-4v-plus` | 专门的视觉模型 |
| OpenAI | `gpt-4-vision-preview`, `gpt-4-turbo` | GPT-4 视觉版本 |
| Anthropic | `claude-3-opus`, `claude-3-sonnet`, `claude-3-haiku` | Claude 3 系列 |
| Google | `gemini-pro-vision` | Gemini 视觉版本 |

### 不支持 Vision 的模型

以下模型**不支持**图片输入，必须禁用 Vision：

| 提供商 | 模型名称 | 说明 |
|--------|---------|------|
| DeepSeek | `deepseek-chat`, `deepseek-reasoner` | 纯文本模型 |
| 智谱 GLM | `glm-4`, `glm-4-plus`, `glm-4-flash` | 非 V 版本 |
| 阿里通义 | `qwen-turbo`, `qwen-plus`, `qwen-max` | 纯文本模型 |
| 字节豆包 | `doubao-pro-32k` | 纯文本模型 |

## 使用方法

### 方法 1：通过设置界面（推荐）

1. 打开 App 设置
2. 找到"智能协调器"区域
3. 找到"启用 Vision（图片输入）"开关
4. 根据你使用的模型选择：
   - 使用 DeepSeek、GLM-4 等纯文本模型 → **关闭**
   - 使用 GLM-4V、GPT-4V 等视觉模型 → **打开**
5. 点击保存

### 方法 2：通过代码配置

在 `WakeWordService.kt` 中：

```kotlin
TaskPlannerConfig(
    // ... 其他配置
    enableVision = false  // 根据模型设置
)
```

### 方法 3：通过 SharedPreferences

```bash
adb shell run-as com.autoglm.assistant \\
  "echo 'coordinator_enable_vision=true' >> /data/data/com.autoglm.assistant/shared_prefs/autoglm_prefs.xml"
```

## 配置建议

### 推荐配置

| 使用场景 | enableVision | 原因 |
|---------|--------------|------|
| 使用 DeepSeek | `false` | DeepSeek 不支持 vision |
| 使用 GLM-4 | `false` | 非 V 版本不支持 vision |
| 使用 GLM-4V | `true` | 专门的视觉模型，发送截图可提高决策质量 |
| 使用 GPT-4V | `true` | 支持 vision，可以更准确判断任务状态 |
| 不确定 | `false` | 默认禁用，避免 API 错误 |

### 性能影响

| 配置 | 优点 | 缺点 |
|-----|------|------|
| `enableVision = true` | 决策更准确，可以看到界面细节 | 需要支持 vision 的模型，API 调用成本更高 |
| `enableVision = false` | 兼容所有模型，API 调用成本低 | 只能根据文本历史决策，可能不够准确 |

## 工作原理

### 启用 Vision 时

```
协调器决策流程：
1. 获取当前截图
2. 构建执行历史文本
3. 发送截图 + 文本给模型
4. 模型分析截图和历史，做出决策
```

### 禁用 Vision 时

```
协调器决策流程：
1. 构建执行历史文本
2. 只发送文本给模型（不发送截图）
3. 模型根据文本历史做出决策
```

## 代码实现

### PhoneAgent.kt

```kotlin
// 根据配置决定是否发送截图
val screenshotForCoordinator = if (agentConfig.plannerConfig?.enableVision == true) {
    screenshot?.base64Data  // 发送截图
} else {
    null  // 不发送截图
}

val decision = smartCoordinator?.decideNextStep(
    originalTask = task,
    executionHistory = executionHistory,
    screenshotBase64 = screenshotForCoordinator,
    language = agentConfig.language
)
```

### SmartCoordinator.kt

```kotlin
suspend fun decideNextStep(
    originalTask: String,
    executionHistory: String,
    screenshotBase64: String?,  // 可能为 null
    language: String = "cn"
): CoordinatorDecision? {
    // ...
    val messages = listOf(
        Message.System(systemPrompt),
        Message.User(userPrompt, screenshotBase64)  // 如果为 null，不发送图片
    )
    // ...
}
```

## 故障排查

### 问题：API Error: 400 - Bad Request

**原因**：向不支持 vision 的模型发送了图片

**解决方案**：
1. 检查使用的模型是否支持 vision
2. 如果不支持，将 `enableVision` 设置为 `false`
3. 重启 App 或服务

### 问题：决策不够准确

**原因**：禁用了 vision，协调器看不到截图

**解决方案**：
1. 如果使用支持 vision 的模型，将 `enableVision` 设置为 `true`
2. 或者优化 System Prompt，让协调器更好地理解文本历史

### 问题：不确定模型是否支持 vision

**解决方案**：
1. 查看模型提供商的文档
2. 或者先设置为 `false`，如果决策质量不满意再尝试 `true`
3. 查看日志中是否有 400 错误

## 日志示例

### 启用 Vision

```
I/AutoGLM: SmartCoordinator available: model=glm-4v-plus, defaultEnabled=true, vision=true
I/AutoGLM: [DEBUG] Screenshot attached: true
```

### 禁用 Vision

```
I/AutoGLM: SmartCoordinator available: model=deepseek-chat, defaultEnabled=true, vision=false
I/AutoGLM: [DEBUG] Screenshot attached: false
```

## 总结

- **默认禁用 Vision**，兼容大多数模型
- **使用 DeepSeek 等纯文本模型时，必须禁用 Vision**
- **使用 GLM-4V、GPT-4V 等视觉模型时，可以启用 Vision 提高决策质量**
- **通过设置界面或代码配置 `enableVision` 参数**
