# AutoGLM 快捷助手 - 系统架构与运作流程

## 1. 系统架构图

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Android 系统层                                │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────┐  ┌────────────┐ │
│  │MediaProjection│  │AccessibilitySvc│  │ AudioRecord │  │ PowerMgr  │ │
│  └──────┬───────┘  └──────┬───────┘  └──────┬─────┘  └─────┬──────┘ │
└─────────┼────────────────┼───────────────┼────────────────┼─────────┘
          │                │               │                │
┌─────────┼────────────────┼───────────────┼────────────────┼─────────┐
│  Provider APK            │               │                │         │
│  (独立进程)              │               │                │         │
│  ┌───────────────────────┴─────────┐     │                │         │
│  │ AccessibilityProviderService    │     │                │         │
│  │  · getUiHierarchy()             │     │                │         │
│  │  · performClick/Swipe/LongPress │     │                │         │
│  │  · takeScreenshot()             │     │                │         │
│  │  · performGlobalAction()        │     │                │         │
│  └───────────────┬─────────────────┘     │                │         │
└──────────────────┼───────────────────────┼────────────────┼─────────┘
                   │ AIDL IPC              │                │
┌──────────────────┼───────────────────────┼────────────────┼─────────┐
│  主应用 (app)    │                       │                │         │
│                  │                       │                │         │
│  ┌───────────────┴─────────────────┐     │                │         │
│  │      UIHierarchyManager        │     │                │         │
│  │  (AIDL客户端, 跨进程操作代理)    │     │                │         │
│  └───────────────┬─────────────────┘     │                │         │
│                  │                       │                │         │
│  ┌───────────────┴─────────────────────────────────────┐  │         │
│  │              WakeWordService (前台服务)               │  │         │
│  │  ┌─────────────────┐  ┌──────────────────────────┐  │  │         │
│  │  │ WakeEngineManager│  │      PhoneAgent          │  │  │         │
│  │  │  ├─ Porcupine   │  │  ├─ ModelClient (LLM)    │  │  │         │
│  │  │  ├─ SttWake     │  │  ├─ ActionParser         │  │  │         │
│  │  │  ├─ SystemStt   │  │  ├─ ActionExecutor       │  │  │         │
│  │  │  └─ Personal    │  │  └─ ScreenCapture        │  │  │         │
│  │  └────────┬────────┘  └───────────┬──────────────┘  │  │         │
│  │           │唤醒                    │任务执行          │  │         │
│  │  ┌────────┴────────┐  ┌───────────┴──────────────┐  │  │         │
│  │  │ SpeechRecognizer│  │   SmartCoordinator       │  │  │         │
│  │  │ (语音→文本)     │  │   (决策/监督/纠错)        │  │  │         │
│  │  └─────────────────┘  └──────────────────────────┘  │  │         │
│  └─────────────────────────────────────────────────────┘  │         │
│                                                           │         │
│  ┌─────────────────────────────────────────────────────┐  │         │
│  │              MainActivity (Compose UI)               │  │         │
│  │  ┌──────────┐ ┌───────────┐ ┌─────────────────────┐ │  │         │
│  │  │HomeScreen│ │ChatScreen │ │ConversationListScreen│ │  │         │
│  │  └──────────┘ └───────────┘ └─────────────────────┘ │  │         │
│  │  ┌──────────────────┐  ┌────────────────────────┐   │  │         │
│  │  │ MessageManager   │  │ FloatingWindowService  │   │  │         │
│  │  │ (对话持久化/管理) │  │ (悬浮窗状态显示)       │   │  │         │
│  │  └──────────────────┘  └────────────────────────┘   │  │         │
│  └─────────────────────────────────────────────────────┘  │         │
└───────────────────────────────────────────────────────────┘         │
```

## 2. 模块职责

| 模块 | 进程 | 职责 |
|------|------|------|
| **Provider APK** | provider进程 | 提供无障碍操作能力，通过 AIDL 暴露 IAccessibilityProvider 接口 |
| **UIHierarchyManager** | app进程 | AIDL 客户端，代理所有跨进程无障碍操作调用 |
| **WakeWordService** | app进程 | 前台服务，管理唤醒引擎生命周期、Agent生命周期、消息协调 |
| **WakeEngineManager** | app进程 | 管理4种唤醒引擎的创建/切换/释放，同一时刻仅一个引擎活跃 |
| **PhoneAgent** | app进程 | AI 任务执行引擎，多轮对话、LLM推理、操作解析与执行 |
| **SmartCoordinator** | app进程 | 任务监督器，基于屏幕状态决策下一步，输出目标型指令 |
| **ScreenCapture** | app进程 | MediaProjection 截图，供 Agent 和 Coordinator 感知屏幕 |
| **ActionExecutor** | app进程 | 将解析后的操作（点击/滑动/输入）通过 UIHierarchyManager 执行 |
| **MessageManager** | app进程 | 对话消息持久化（JSON文件），多对话管理 |
| **MainActivity** | app进程 | Compose UI 界面，权限管理，导航路由 |

## 3. 运作流程

### 3.1 语音唤醒流程

1. 用户在 MainActivity 点击"启动监听"
2. WakeWordService 以前台服务启动，创建通知保持存活
3. WakeEngineManager 根据配置切换到目标引擎（默认 Porcupine）
4. Porcupine 引擎在后台线程持续采集麦克风音频（16kHz / mono / PCM16）
5. 音频帧送入 Porcupine 模型进行关键词检测
6. 检测到唤醒词 → 回调 `onWakeDetected`
7. WakeWordService 暂停唤醒监听，启动 SpeechRecognizer 采集用户指令
8. 用户说完指令 → STT 返回文本 → 调用 `executeTask(text)`

### 3.2 任务执行流程（无协调器模式）

1. WakeWordService 收到指令文本，创建 PhoneAgent
2. PhoneAgent.run() 初始化 ModelClient、ScreenCapture、ActionExecutor
3. 循环执行（最大步数受 AgentConfig 限制）：
   - 截取当前屏幕（隐藏悬浮窗 → 截图 → 恢复悬浮窗）
   - 构建消息（系统提示词 + 屏幕截图base64 + 对话历史）
   - 调用 LLM 推理，获取思考过程和操作指令
   - ActionParser 解析操作（如 `do(action="Tap", element=[500,600])`）
   - ActionExecutor 通过 UIHierarchyManager → AIDL → Provider 执行操作
   - 等待操作生效（配置的延迟时间）
4. LLM 输出 `finish(message="xxx")` 时结束循环
5. WakeWordService 恢复唤醒监听

### 3.3 任务执行流程（协调器模式）

协调器模式是一个**两层循环**结构：外层为协调器决策循环，内层为 Agent 步骤执行循环。
协调器不会在每一步都介入，而是在**每个子任务完成后**才被调用。

#### 整体流程

```
用户指令 → PromptOptimizer优化 → executeWithCoordinator()
                                        │
    ┌───────────────────────────────────┘
    ▼
  协调器决策循环 (外层, 最大 maxCoordinatorSteps 轮)
    │
    ├─ 1. 截取当前屏幕
    ├─ 2. 构建执行历史摘要 (coordinatorInstructions 列表)
    ├─ 3. 调用 SmartCoordinator.decideNextStep()
    │      输入：originalTask + executionHistory + screenshot(可选)
    │      输出：CONTINUE/COMPLETE/FAILED + nextInstruction + assessment
    │
    ├─ COMPLETE → 任务结束，返回结果
    ├─ FAILED   → 任务失败，返回错误
    └─ CONTINUE → 进入子任务执行
           │
           ├─ 4. compressConversationForNewSubTask() 压缩上下文
           │      清空Agent对话历史，仅保留：
           │      · 系统prompt
           │      · User消息: 【总体任务目标】+【已完成子任务】+【当前子任务】
           │      · Assistant确认消息
           │
           ├─ 5. executeUntilFinish(instruction) ← Agent步骤循环 (内层)
           │      │
           │      ├─ 截图 → 构建消息(指令+屏幕信息) → LLM推理 → 操作解析 → 执行
           │      ├─ 检查：用户干预? → 是 → 注入对话历史, 返回 finished=true
           │      ├─ 检查：Agent调用finish? → 是 → 返回 finished=true
           │      └─ 循环直到 finish 或达到 maxAgentStepsPerCoordinatorStep
           │
           ├─ 6. 记录子任务结果到 coordinatorInstructions
           │      · 正常完成：(指令, true)
           │      · 用户干预：(原指令, false) + (干预内容, true)
           │
           └─ 7. 回到步骤1，协调器决定下一步
```

#### 协调器的输入与输出

**输入（每次 decideNextStep 调用时）：**

| 输入项 | 来源 | 说明 |
|--------|------|------|
| originalTask | 用户原始指令（经优化器处理后） | 贯穿整个任务不变 |
| executionHistory | coordinatorInstructions 列表格式化 | 所有已下发指令 + 完成状态 |
| screenshotBase64 | ScreenCapture 截图 | 仅当 enableVision=true 且非第一步时发送 |
| language | AgentConfig | cn 或 en |

executionHistory 示例：
```
协调器指令1: 打开淘宝搜索"五粮液"并查看价格 [✓ 已完成]
协调器指令2: 打开京东搜索"五粮液"并比较价格 [→ 已执行]
```

**输出（解析后的 CoordinatorDecision）：**

| 输出项 | 说明 |
|--------|------|
| status | CONTINUE / COMPLETE / FAILED |
| nextInstruction | CONTINUE时的自然语言目标指令（如"打开淘宝搜索五粮液"） |
| assessment | 当前状态评估（思考过程/决策理由） |
| gatheredInfo | 收集到的信息（如价格数据），累积保存 |

#### 关键时序：协调器监测频率

协调器**不是每一步都运行**。Agent 执行一个子任务可能需要多步（如打开淘宝→点搜索框→输入文字→点搜索），这些步骤全部在 `executeUntilFinish` 内层循环中完成，不触发协调器。只有当 Agent 的内层循环结束（finish 或达步数限制或用户干预）后，才返回外层协调器循环，由协调器决定下一步。

#### 用户干预流程

用户通过通知栏"干预"按钮或浮窗输入指令：
1. 指令加入 `pendingInterventions` 队列（支持多条排队）
2. Agent 在每一步开始前检查队列
3. 有干预时：drain 队列 → 合并为一条 → 注入对话历史 → 返回 finished=true
4. 外层协调器收到干预结果 → executionHistory 中记录"原指令被中断 + 干预内容"
5. 协调器根据干预内容重新规划下一步

### 3.4 对话与消息流程

1. 用户在 ChatScreen 输入文本或语音指令到达
2. MessageManager 将用户消息追加到当前 Conversation
3. 若为新对话（标题为"新对话"），截取用户消息前30字符作为标题
4. 消息通过 WakeWordService 传递给 PhoneAgent
5. Agent 执行过程中的思考/操作/结果通过回调流式传回
6. 回调消息写入 MessageManager → 更新 ChatScreen UI
7. 对话以 JSON 格式持久化到 `filesDir/conversations/`

### 3.5 跨进程通信流程（AIDL）

1. app 启动时 UIHierarchyManager.bindToService() 绑定 Provider
2. Provider APK 需提前安装并启用无障碍服务
3. 绑定成功后获取 IAccessibilityProvider 代理对象
4. ActionExecutor 调用代理方法（如 performClick）
5. 请求通过 Binder 传递到 Provider 进程
6. Provider 进程中的 AccessibilityProviderService 执行实际操作
7. 结果通过 Binder 返回给 app 进程

## 4. 线程与协程模型

```
┌─────────────────────────────────────────────────┐
│ Main Thread (UI)                                │
│  · Compose UI 渲染                               │
│  · Activity/Service 生命周期回调                   │
│  · StateFlow 收集 (LaunchedEffect)               │
└─────────────────────┬───────────────────────────┘
                      │ bindService / StateFlow
┌─────────────────────┴───────────────────────────┐
│ CoroutineScope(Dispatchers.Default + SupervisorJob) │
│  · WakeWordService 内部协程域                      │
│  · 唤醒引擎监听循环                                │
│  · PhoneAgent.run() 任务执行                       │
│  · SmartCoordinator 决策调用                       │
└─────────────────────┬───────────────────────────┘
                      │
┌─────────────────────┴───────────────────────────┐
│ AudioRecord 线程 (Native)                        │
│  · 由 PorcupineWakeEngine 创建的音频采集线程       │
│  · 持续读取麦克风 PCM 数据                         │
│  · 数据送入 Porcupine 检测或 SpeechRecognizer     │
└─────────────────────────────────────────────────┘
```
