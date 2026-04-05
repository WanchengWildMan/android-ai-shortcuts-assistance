# AutoGLM Android App - 项目上下文总结

## 📁 代码目录结构

```
android-app/app/src/main/java/com/autoglm/assistant/
├── ai/                          # AI模型交互
│   ├── MessageBuilder.kt        # 🔥 Agent系统prompt构建
│   ├── ModelClient.kt           # 模型API调用
│   └── ModelResponse.kt         # 模型响应解析
│
├── core/                        # 核心功能
│   ├── agent/
│   │   └── PhoneAgent.kt        # 🔥 主Agent逻辑
│   ├── action/
│   │   ├── ActionExecutor.kt    # 🔥 动作执行器（Launch/Tap/Swipe/FINISH等）
│   │   ├── ActionParser.kt      # 动作解析
│   │   └── ActionType.kt        # 动作类型定义
│   ├── screen/
│   │   ├── AppDetector.kt       # 🔥 应用检测（动态搜索+包名映射）
│   │   └── ScreenAnalyzer.kt    # 屏幕分析
│   └── planner/
│       └── PromptOptimizer.kt   # 🔥 Prompt优化器（用户指令扩展）
│
├── service/
│   └── WakeWordService.kt       # 🔥 后台服务（唤醒词+任务执行）
│
├── util/
│   ├── ShellExecutor.kt         # 🔥 Shell命令执行（Root模式控制）
│   ├── PreferenceManager.kt     # 🔥 设置管理
│   ├── Logger.kt                # 日志工具
│   └── PermissionHelper.kt      # 权限检查
│
├── MainActivity.kt              # 🔥 主界面+设置界面
└── App.kt                       # 🔥 应用初始化
```

## 🎯 核心功能模块

### 1. **Agent系统** (PhoneAgent + MessageBuilder)
- **[PhoneAgent.kt](app/src/main/java/com/autoglm/assistant/core/agent/PhoneAgent.kt)**
  - 主Agent逻辑，循环执行：截图 → LLM分析 → 动作执行
  - 调用 PromptOptimizer 优化用户指令
  - 管理对话上下文

- **[MessageBuilder.kt](app/src/main/java/com/autoglm/assistant/ai/MessageBuilder.kt) (第110-129行)**
  - 构建Agent系统prompt
  - **规则第1条（重要）**：强调不要误认AutoGLM界面为目标应用
  - 定义所有可用动作（Launch/Tap/Swipe/Type/Back/Home/Wait/FINISH等）
  - 18条必须遵循的规则

### 2. **动作执行系统** (ActionExecutor + ActionParser)
- **[ActionExecutor.kt](app/src/main/java/com/autoglm/assistant/core/action/ActionExecutor.kt)**
  - 执行所有Agent动作
  - **Launch**: 非Root用Intent，Root用shell（自动回退）
  - **FINISH**: 任务完成后自动返回AutoGLM app（309-326行）
  - 支持两种模式：`SHELL_INPUT`（需要Root）和 `ACCESSIBILITY`（需要辅助功能）

### 3. **应用查找系统** (AppDetector)
- **[AppDetector.kt](app/src/main/java/com/autoglm/assistant/core/screen/AppDetector.kt)**
  - 预定义应用映射（中文名→包名）
  - **动态搜索**：从设备PackageManager查找（183-294行）
  - 评分机制：精确匹配100分，包含匹配80分，英文关键词映射60分
  - 支持天气、相册、笔记等通用应用的英文关键词匹配

### 4. **Prompt优化器** (PromptOptimizer)
- **[PromptOptimizer.kt](app/src/main/java/com/autoglm/assistant/core/planner/PromptOptimizer.kt) (第156-202行)**
  - 将简短指令扩展为详细任务描述
  - **新增指导**（174-181行）：
    - 界面确认：确保在正确界面
    - 广告处理：关闭启动广告
    - 导航路径：明确如何到达目标
    - 异常返回：错误界面使用返回键
    - 弹窗处理：权限请求等
  - 支持对话上下文（理解"继续"等指代）

### 5. **Root模式控制** (ShellExecutor + PreferenceManager)
- **[ShellExecutor.kt](app/src/main/java/com/autoglm/assistant/util/ShellExecutor.kt) (第25-27行)**
  - `globalUseRoot`: 全局Root模式开关
  - 在execute()中检查：`effectiveUseRoot = globalUseRoot && useRoot`
  - 自动关闭SELinux（input命令需要）

- **[PreferenceManager.kt](app/src/main/java/com/autoglm/assistant/util/PreferenceManager.kt) (第132-134行)**
  - `useRootMode`: 保存Root模式设置
  - 默认值：true（开启）

### 6. **设置界面** (MainActivity - SettingsScreen)
- **[MainActivity.kt](app/src/main/java/com/autoglm/assistant/MainActivity.kt)**
  - **Root模式开关**（1199-1224行）：即时生效
  - **辅助功能提示**（1226-1276行）：非Root模式下实时检测并提示
  - **自动重启服务**（834-850行）：配置变化时重启使其生效
  - **系统工具**：重启Input服务、重启Zygote

### 7. **应用初始化** (App.kt)
- **[App.kt](app/src/main/java/com/autoglm/assistant/App.kt) (第28行)**
  - 启动时初始化Root模式设置

## 🔑 关键概念

### 运行模式
1. **Root模式**：使用shell命令执行（需要设备Root）
   - 优点：功能强大，无需辅助功能
   - 缺点：需要Root权限

2. **非Root模式**：使用Accessibility Service
   - 优点：不需要Root
   - 缺点：需要开启辅助功能权限

### 动作类型
- **LAUNCH**: 启动应用
- **TAP**: 点击坐标
- **SWIPE**: 滑动
- **TYPE**: 输入文字
- **BACK/HOME**: 导航
- **FINISH**: 任务完成（自动返回AutoGLM）

### Agent工作流程
1. 用户发起任务
2. PromptOptimizer 优化指令
3. PhoneAgent 循环：
   - 截图
   - LLM分析当前状态
   - 决定下一步动作
   - ActionExecutor 执行
   - 检查是否完成（FINISH）
4. 任务完成返回AutoGLM界面

## 📝 最近重要改动

1. **Root模式全局开关**：可在设置中切换，即时生效
2. **非Root启动应用**：使用Intent而非shell命令
3. **辅助功能权限提示**：非Root模式下实时检测并引导开启
4. **自动重启服务**：修改API/Model配置后自动重启
5. **任务完成返回**：FINISH动作自动返回AutoGLM app
6. **动态应用查找**：从设备PackageManager搜索，支持中文名
7. **Prompt增强**：添加界面确认、广告处理等指导
8. **Agent第一条规则强化**：不要误认AutoGLM界面为目标应用

## 🚀 快速定位功能

- **修改系统prompt** → `MessageBuilder.kt:110-129`
- **修改动作执行逻辑** → `ActionExecutor.kt`
- **修改应用查找** → `AppDetector.kt:183-294`
- **修改Prompt优化器** → `PromptOptimizer.kt:156-202`
- **添加设置项** → `PreferenceManager.kt` + `MainActivity.kt`
- **修改Root控制** → `ShellExecutor.kt:95-110`

## 🔧 常见开发场景

### 场景1: 添加新的动作类型
1. 在 `ActionType.kt` 添加新枚举
2. 在 `ActionParser.kt` 添加解析逻辑
3. 在 `ActionExecutor.kt` 添加执行逻辑
4. 在 `MessageBuilder.kt` 更新系统prompt描述

### 场景2: 修改Agent行为规则
直接编辑 `MessageBuilder.kt` 的"必须遵循的规则"部分

### 场景3: 添加新的应用映射
编辑 `AppDetector.kt` 的 `CN_APP_NAMES` 或 `APP_PACKAGES`

### 场景4: 优化Prompt指导
编辑 `PromptOptimizer.kt` 的 `SYSTEM_PROMPT_CN` 或 `SYSTEM_PROMPT_EN`

### 场景5: 添加新的设置项
1. 在 `PreferenceManager.kt` 添加 key 常量和 getter/setter
2. 在 `MainActivity.kt` 的 SettingsScreen 添加 UI 组件
3. 在 `App.kt` 初始化（如果需要）

## 📚 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose
- **权限**: Accessibility Service (非Root模式)
- **Shell执行**: libsu (Root模式)
- **屏幕截图**: MediaProjection API
- **LLM交互**: 自定义 ModelClient（支持流式输出）
- **状态管理**: StateFlow + ViewModel 模式

## 🐛 调试技巧

### 查看日志
```bash
# 查看 Agent 日志
adb logcat -s AutoGLM:D AGENT:D ACTION:D

# 查看应用查找日志
adb logcat -s AppDetector:D

# 查看 Shell 执行日志
adb logcat -s Shell:D
```

### 常见问题

1. **启动应用失败（非Root模式）**
   - 检查是否开启了辅助功能权限
   - 查看 logcat 中的 Intent 错误

2. **点击/滑动不生效（Root模式）**
   - 检查 SELinux 是否已关闭
   - 查看 `ShellExecutor` 日志中的命令执行结果

3. **应用找不到**
   - 检查 `AppDetector` 日志，查看搜索过程
   - 确认应用已安装且有启动器图标

4. **设置修改后不生效**
   - 检查是否自动重启了服务
   - 手动停止并重启服务

## 🎓 进一步学习

- **Jetpack Compose**: 现代 Android UI 框架
- **Accessibility Service**: Android 辅助功能服务
- **MediaProjection**: 屏幕截图和录制
- **libsu**: Root shell 执行库
- **Coroutines**: Kotlin 协程用于异步操作
