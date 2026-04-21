# SmartCoordinator 使用指南

## 概述

SmartCoordinator（智能协调器）是一个增强功能，使用更强大的模型来：

1. **解释和分解用户指令**：将用户的简单指令转化为清晰、明确的子任务
2. **监督执行质量**：检查UI Agent的每步执行，确保正确达成目标
3. **提供纠正指导**：发现问题时给出具体的纠正指令
4. **收集信息**：总结任务执行过程中获取的有用信息

## 核心价值

### 1. 指令解释
用户说："帮我点个咖啡"
- **普通模式**：UI Agent直接理解可能不准确
- **SmartCoordinator**：解释为"在美团外卖上搜索附近咖啡店 → 选择星巴克等品牌 → 选择拿铁等常见咖啡 → 下单"

### 2. 质量把关
UI Agent每执行完一个子任务，SmartCoordinator会：
- 查看截图和操作
- 判断是否达成目标
- 如果有偏差，提供纠正指令

### 3. 信息收集
自动提取和总结：
- 商品价格、店铺评分
- 配送信息、优惠活动
- 其他有价值的数据

## 快速开始

### 1. 配置好模型

```kotlin
// 在 MainActivity 或 App 初始化时配置

// UI Agent模型配置（执行操作的模型）
val uiAgentModelConfig = ModelConfig(
    baseUrl = "https://open.bigmodel.cn/api/paas/v4",
    apiKey = "your-api-key",
    modelName = "autoglm-phone",  // 或其他UI操作模型
    maxTokens = 3000,
    temperature = 0.0f
)

// SmartCoordinator模型配置（更强大的规划和监督模型）
val smartCoordinatorModelConfig = ModelConfig(
    baseUrl = "https://open.bigmodel.cn/api/paas/v4",
    apiKey = "your-api-key",
    modelName = "glm-4-plus",  // 使用更强的模型
    maxTokens = 8000,
    temperature = 0.3f
)

// 任务规划器配置
val plannerConfig = TaskPlannerConfig(
    enabled = true,  // 启用SmartCoordinator
    plannerModelConfig = smartCoordinatorModelConfig,
    enableSupervision = true,  // 启用执行监督
    supervisorModelConfig = null,  // null表示使用plannerModelConfig
    maxCorrections = 2,  // 每个子任务最多纠正2次
    maxSubTasks = 10,  // 最多拆分10个子任务
    planningTimeout = 30000L  // 规划超时30秒
)

// Agent配置
val agentConfig = AgentConfig(
    maxSteps = 100,
    language = "cn",
    plannerConfig = plannerConfig  // 关键：传入规划器配置
)

// 创建PhoneAgent
val phoneAgent = PhoneAgent(
    context = applicationContext,
    modelConfig = uiAgentModelConfig,
    agentConfig = agentConfig
)

phoneAgent.initialize()
```

### 2. 执行任务

```kotlin
// 启动任务
lifecycleScope.launch {
    val result = phoneAgent.run("帮我在美团上点一杯星巴克拿铁")

    // 获取收集到的信息
    val gatheredInfo = phoneAgent.getGatheredInfo()
    Log.i("Task", "收集到的信息: $gatheredInfo")
}
```

## 推荐的模型配置

### UI Agent 模型
- **autoglm-phone**（智谱）
- **claude-3-5-sonnet**（Anthropic，如果支持）
- **gpt-4o-mini**（OpenAI，如果支持）

这些模型专注于**理解屏幕和执行操作**。

### SmartCoordinator 模型（更强）
- **glm-4-plus**（智谱，推荐）
- **claude-3-5-sonnet**（Anthropic，理解能力强）
- **gpt-4o / gpt-4-turbo**（OpenAI，规划能力强）
- **deepseek-chat**（DeepSeek，性价比高）

这些模型专注于**理解用户意图、规划任务、监督质量**。

## 完整示例

### 示例1：在MainActivity中集成

```kotlin
class MainActivity : ComponentActivity() {
    private lateinit var phoneAgent: PhoneAgent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化PhoneAgent with SmartCoordinator
        initializePhoneAgent()

        setContent {
            MyApp()
        }
    }

    private fun initializePhoneAgent() {
        // UI Agent模型（执行操作）
        val uiModelConfig = ModelConfig(
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            apiKey = PreferenceManager.getApiKey(this),
            modelName = "autoglm-phone",
            maxTokens = 3000,
            temperature = 0.0f
        )

        // SmartCoordinator模型（规划和监督）
        val coordinatorModelConfig = ModelConfig(
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            apiKey = PreferenceManager.getApiKey(this),
            modelName = "glm-4-plus",
            maxTokens = 8000,
            temperature = 0.3f
        )

        // 配置
        val agentConfig = AgentConfig(
            maxSteps = 100,
            language = "cn",
            plannerConfig = TaskPlannerConfig(
                enabled = true,
                plannerModelConfig = coordinatorModelConfig,
                enableSupervision = true,
                maxCorrections = 2
            )
        )

        phoneAgent = PhoneAgent(this, uiModelConfig, agentConfig)
        phoneAgent.initialize()

        // 设置回调
        phoneAgent.onStepComplete = { result ->
            Log.i("PhoneAgent", "Step complete: ${result.message}")
        }

        phoneAgent.onTaskComplete = { message ->
            Log.i("PhoneAgent", "Task complete: $message")

            // 获取收集到的信息
            val info = phoneAgent.getGatheredInfo()
            Log.i("PhoneAgent", "Gathered info: $info")
        }
    }
}
```

### 示例2：在设置界面添加配置选项

```kotlin
// SettingsScreen.kt
@Composable
fun SettingsScreen() {
    var enableSmartCoordinator by remember { mutableStateOf(false) }
    var coordinatorModel by remember { mutableStateOf("glm-4-plus") }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("智能协调器设置", style = MaterialTheme.typography.titleLarge)

        Spacer(modifier = Modifier.height(16.dp))

        // 启用开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("启用智能协调器")
                Text(
                    "使用更强模型解释指令和监督执行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enableSmartCoordinator,
                onCheckedChange = {
                    enableSmartCoordinator = it
                    PreferenceManager.setSmartCoordinatorEnabled(it)
                }
            )
        }

        if (enableSmartCoordinator) {
            Spacer(modifier = Modifier.height(16.dp))

            // 模型选择
            Text("协调器模型", style = MaterialTheme.typography.titleMedium)

            listOf(
                "glm-4-plus" to "智谱GLM-4 Plus（推荐）",
                "glm-4" to "智谱GLM-4",
                "deepseek-chat" to "DeepSeek Chat（性价比高）"
            ).forEach { (model, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            coordinatorModel = model
                            PreferenceManager.setCoordinatorModel(model)
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = coordinatorModel == model,
                        onClick = {
                            coordinatorModel = model
                            PreferenceManager.setCoordinatorModel(model)
                        }
                    )
                    Text(label, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
```

## 工作流程

```
用户输入："帮我点杯咖啡"
    ↓
SmartCoordinator 分析和规划
    ├─ 理解意图：用户想在外卖平台点咖啡
    ├─ 分解任务：
    │   1. 打开美团外卖
    │   2. 搜索咖啡店
    │   3. 选择商品（拿铁等）
    │   4. 下单（提醒用户支付）
    └─ 为每个子任务生成详细指令
    ↓
执行子任务1：打开美团外卖
    ├─ UI Agent执行操作
    ├─ SmartCoordinator监督
    ├─ ✓ 成功 → 继续
    └─ ✗ 失败 → 提供纠正指令
    ↓
执行子任务2：搜索咖啡店
    ├─ UI Agent执行
    ├─ SmartCoordinator监督
    ├─ ⚠ 需要纠正："搜索框位置不对，应该在顶部"
    └─ 重新执行
    ↓
... 继续其他子任务 ...
    ↓
任务完成
    └─ 返回收集到的信息（店铺名称、价格等）
```

## 成本考虑

使用SmartCoordinator会增加API调用成本：
- **规划阶段**：每个任务调用1次
- **监督阶段**：每个子任务调用1-3次（取决于是否需要纠正）

**示例**：
- 任务分解为3个子任务
- 每个子任务平均纠正0.5次
- 总调用次数：1（规划）+ 3 × 1.5（监督）= 5.5次

**建议**：
- 对于复杂任务，值得使用（提高成功率）
- 对于简单任务，可以禁用（降低成本）
- 可以只启用规划，禁用监督（`enableSupervision = false`）

## 日志查看

启用SmartCoordinator后，可以在logcat中查看详细日志：

```
I/AGENT: ========== SMART COORDINATOR: PLANNING ==========
I/AGENT: Original task: 帮我点杯咖啡
I/AGENT: Task successfully planned into 4 sub-tasks
I/AGENT: ========== EXECUTING WITH TASK PLAN ==========
I/AGENT: ---------- Executing Sub-task 1/4 ----------
I/AGENT: Goal: 打开美团外卖App
I/AGENT: ✓ Supervision: Sub-task 1 completed successfully
I/AGENT: ---------- Executing Sub-task 2/4 ----------
I/AGENT: Goal: 搜索咖啡店
I/AGENT: ⚠ Supervision: Needs correction (attempt 1/2)
I/AGENT: Correction instruction: 搜索框在顶部中央...
```

## 故障排除

### 1. SmartCoordinator未启用
**问题**：任务仍然直接执行，没有规划
**解决**：
- 确保 `plannerConfig.enabled = true`
- 确保 `plannerModelConfig` 不为null
- 检查日志：应该看到 "SmartCoordinator initialized"

### 2. API调用失败
**问题**：日志显示 "Task planning failed"
**解决**：
- 检查API Key是否正确
- 检查模型名称是否支持
- 检查网络连接
- 查看详细错误日志

### 3. 纠正次数过多
**问题**：子任务反复纠正，仍然失败
**解决**：
- 检查UI Agent模型是否理解中文指令
- 增加 `maxCorrections` 次数
- 查看纠正指令是否清晰明确

## 高级用法

### 动态切换

```kotlin
// 复杂任务使用SmartCoordinator
fun executeComplexTask(task: String) {
    val agentConfig = AgentConfig(
        plannerConfig = TaskPlannerConfig(enabled = true, ...)
    )
    phoneAgent.updateConfig(agentConfig)
    phoneAgent.run(task)
}

// 简单任务不使用
fun executeSimpleTask(task: String) {
    val agentConfig = AgentConfig(
        plannerConfig = null  // 禁用
    )
    phoneAgent.updateConfig(agentConfig)
    phoneAgent.run(task)
}
```

### 只使用规划，不监督

```kotlin
// 降低成本的配置
val plannerConfig = TaskPlannerConfig(
    enabled = true,
    plannerModelConfig = coordinatorModelConfig,
    enableSupervision = false,  // 关闭监督
    maxCorrections = 0
)
```

这样只进行任务分解和指令解释，但不监督每步执行。

## 总结

SmartCoordinator是一个强大的增强功能，可以：
- ✅ 提高任务理解准确度
- ✅ 确保执行质量
- ✅ 自动收集信息
- ⚠️ 但会增加API成本和延迟

根据实际需求选择是否启用。
