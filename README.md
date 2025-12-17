# AutoGLM Android Assistant

基于 Open-AutoGLM 框架的安卓原生语音助手应用，支持离线语音唤醒和自动化手机操作。

## 功能特性

- **离线语音唤醒**: 使用 Picovoice Porcupine 实现低功耗离线唤醒词检测
- **语音对话**: 支持语音识别和语音合成
- **AI 手机操作**: 基于 AutoGLM-Phone 模型的智能手机自动化操作
- **双模式执行**: 支持无障碍服务和 Shell 命令两种操作模式

## 项目结构

```
app/src/main/java/com/autoglm/assistant/
├── App.kt                          # Application 类
├── MainActivity.kt                 # 主界面
├── service/
│   ├── WakeWordService.kt          # 语音唤醒后台服务
│   ├── AutomationService.kt        # 无障碍服务
│   └── FloatingWindowService.kt    # 悬浮窗服务
├── core/
│   ├── agent/
│   │   ├── PhoneAgent.kt           # 主 Agent 逻辑
│   │   └── AgentConfig.kt          # 配置类
│   ├── action/
│   │   ├── ActionExecutor.kt       # 动作执行器
│   │   └── ActionParser.kt         # 动作解析器
│   └── screen/
│       ├── ScreenCapture.kt        # 屏幕截取
│       └── AppDetector.kt          # 应用检测
├── ai/
│   ├── ModelClient.kt              # AI 模型客户端
│   ├── ModelConfig.kt              # 模型配置
│   └── MessageBuilder.kt           # 消息构建
├── voice/
│   ├── WakeWordEngine.kt           # 唤醒词引擎
│   ├── SpeechRecognizer.kt         # 语音识别
│   └── TextToSpeech.kt             # 语音合成
├── ui/
│   └── theme/
│       └── Theme.kt                # Compose 主题
└── util/
    ├── ShellExecutor.kt            # Shell 命令执行
    ├── ImageUtils.kt               # 图片处理工具
    ├── PreferenceManager.kt        # 配置存储
    └── PermissionHelper.kt         # 权限管理
```

## 构建要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Gradle 8.2
- Android SDK 34 (targetSdk)
- minSdk 24 (Android 7.0)

## 配置步骤

### 1. 获取 Porcupine 访问密钥

1. 访问 [Picovoice Console](https://console.picovoice.ai/)
2. 注册账户并创建项目
3. 获取 Access Key
4. 在应用设置中填入 Access Key

### 2. 配置 AI 模型 API

在应用设置中配置以下参数：

- **API URL**: AutoGLM-Phone 模型服务地址（如 `http://your-server:8000/v1`）
- **API Key**: 模型 API 密钥
- **Model Name**: 模型名称（默认 `autoglm-phone-9b`）

### 3. 授权权限

应用需要以下权限：

- **麦克风权限**: 用于语音唤醒和语音识别
- **通知权限**: 用于显示后台服务通知
- **悬浮窗权限**: 用于显示悬浮控制球
- **无障碍服务**: 用于执行自动化操作

## 使用方式

### 语音唤醒

1. 启动应用并授予所有权限
2. 点击 "Start" 按钮开始语音唤醒服务
3. 说出唤醒词（默认为 "Porcupine"）
4. 听到提示音后说出您的指令
5. 等待 AI 执行任务

### 手动输入

1. 在主界面输入框中输入任务描述
2. 点击发送按钮执行任务

## 自定义唤醒词

要使用自定义唤醒词：

1. 访问 [Picovoice Console](https://console.picovoice.ai/)
2. 创建自定义唤醒词模型（.ppn 文件）
3. 将模型文件放入应用资源目录
4. 修改 WakeWordEngine 配置

## 开发说明

### 添加新的应用支持

在 `AppDetector.kt` 中添加应用包名映射：

```kotlin
private val APP_PACKAGES = mapOf(
    "应用名" to "com.example.app",
    // ...
)
```

### 添加新的动作类型

1. 在 `ActionType` 枚举中添加新类型
2. 在 `ActionParser` 中添加解析逻辑
3. 在 `ActionExecutor` 中实现执行逻辑

### 修改系统提示词

在 `MessageBuilder.kt` 中修改 `DEFAULT_SYSTEM_PROMPT_CN` 或 `DEFAULT_SYSTEM_PROMPT_EN`。

## 注意事项

1. **电量消耗**: 语音唤醒服务会持续监听麦克风，建议在不使用时关闭
2. **网络连接**: 执行任务需要连接到 AI 模型服务器
3. **敏感操作**: 涉及支付、登录等敏感操作时，系统会请求人工确认
4. **Root 权限**: Shell 命令模式需要 Root 权限或 ADB 授权

## 故障排除

### 语音唤醒不工作

- 检查麦克风权限是否已授予
- 确认 Porcupine Access Key 已正确配置
- 尝试在安静环境中使用

### 无法执行操作

- 检查无障碍服务是否已启用
- 确认 AI 模型服务器连接正常
- 查看日志输出了解具体错误

### 截屏失败

- 首次使用需要授权屏幕截取权限
- 某些安全页面（如支付页面）无法截取

## 许可证

本项目基于 Open-AutoGLM 框架开发，遵循相应的开源许可证。
