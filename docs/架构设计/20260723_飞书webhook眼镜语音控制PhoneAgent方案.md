# 飞书 Webhook 眼镜语音控制 PhoneAgent 方案

## 1. 目标

用户触摸 Rokid 眼镜镜腿并说出任务后，眼镜音频经 CXR-L 传到 vivo（乐奇 App 所在手机），vivo 本地 STT 得到文本，通过飞书自定义机器人 webhook 发到指定群；电脑端长连接订阅同一群，收到消息事件后通过 USB ADB 转发给 Xiaomi（PhoneAgent 执行手机）触发任务执行。

验收任务：用户说"给文件传输助手发消息测试"，PhoneAgent 操作微信并向文件传输助手发送"测试"。

## 2. 与旧方案的区别

`docs/架构设计/20260722_乐奇云端眼镜语音控制PhoneAgent方案.md` 是基于 Rokid 旧"本地私有技能"（LocalSkillSdk / `cmd=send_nlp`）的方案，**已过时**。Rokid 当前已用 CXR-L SDK 取代本地私有技能，本方案改走 CXR-L + 飞书 webhook 中转，不再依赖本地私有技能 Intent。

## 3. 唯一正式主链路

```text
Rokid 眼镜
  │ 触摸镜腿、采集语音（CXR-L CustomApp 会话）
  ▼
vivo 手机（com.autoglm.assistant，承担眼镜桥接角色）
  │ CXR-L onGlassAiInterrupt → startAudioStream(1) → onAudioReceived(PCM)
  │ GlassPcmSession VAD 判断说完
  │ ApiSpeechRecognizer.recognizePcm() → 阿里 NLS（用 vivo 已配置的 AK）
  ▼
飞书自定义机器人 webhook（机器人 A）
  │ POST https://open.feishu.cn/open-apis/bot/v2/hook/xxx
  ▼
飞书群
  │ im.message.receive_v1 事件
  ▼
电脑端长连接（机器人 B，tools/relay/feishu_relay_listener.py）
  │ 文字匹配"新建会话"等指令：重启 AutoGLM 清空上下文
  │ 其他文本：USB ADB 广播 ExternalTaskReceiver
  ▼
Xiaomi 手机（com.autoglm.assistant，承担 PhoneAgent 执行角色）
  │ WakeWordService.executeTask(task)
  ▼
PhoneAgent 无障碍操作目标 App
```

两端都只对飞书云发起出站连接，**不需要公网 IP、不需要开放端口、不经过局域网直连、不搭建隧道**。这是满足"不要隧道"约束的最小可控方案。

## 4. 各端职责

| 端 | 职责 | 不承担的职责 |
|---|---|---|
| Rokid 眼镜 | 镜腿交互、采音、显示对话历史 | 不运行 PhoneAgent |
| vivo 手机（眼镜桥接端） | CXR-L 连接眼镜、PCM 采集 VAD、阿里 NLS STT、飞书 webhook 发送 | 不执行 PhoneAgent 任务 |
| 飞书云 | webhook 接收、群消息事件推送 | 不解密任务明文，不保存 Rokid 凭据 |
| 电脑（中转端） | 飞书长连接订阅群消息、文字匹配"新建会话"等指令、ADB 转发到 Xiaomi | 不做 STT，不开放公网端口 |
| Xiaomi 手机（PhoneAgent 执行端） | `WakeWordService.executeTask()`、无障碍操作目标 App | 不连接眼镜，不做 STT |

## 5. 关键代码位置

### 5.1 vivo 侧（眼镜桥接端，app 模块）

- `app/src/main/java/com/autoglm/assistant/glass/GlassRelayClient.kt` — 飞书 webhook 发送客户端
- `app/src/main/java/com/autoglm/assistant/glass/GlassAudioController.kt` — CXR-L 音频流控制器（onAiInterrupt → startAudioStream → PCM → VAD）
- `app/src/main/java/com/autoglm/assistant/glass/GlassPcmSession.kt` — PCM 缓冲 + VAD 静音检测
- `app/src/main/java/com/autoglm/assistant/glass/GlassSessionManager.kt` — CXRLink 会话管理
- `app/src/main/java/com/autoglm/assistant/glass/GlassCommandGateway.kt` — 眼镜→手机指令路由
- `app/src/main/java/com/autoglm/assistant/glass/GlassProtocol.kt` — 双向指令协议（含 CMD_CHAT 对话消息推送）
- `app/src/main/java/com/autoglm/assistant/ui/settings/GlassChannelSection.kt` — 眼镜通道设置 UI（webhook URL 配置入口）
- `app/src/main/java/com/autoglm/assistant/service/WakeWordService.kt` — `recognizeGlassPcm()` + `sendGlassResultToRelay()`

### 5.2 眼镜端（:glass 模块）

- `glass/src/main/java/com/autoglm/glass/activities/MainActivity.kt` — CustomApp 会话入口
- `glass/src/main/java/com/autoglm/glass/ui/GlassStatusScreen.kt` — 单绿色 UI：顶部状态行 + 可滑动对话历史列表
- `glass/src/main/java/com/autoglm/glass/ui/StatusViewModel.kt` — 消息历史 StateFlow + 滚动控制
- `glass/src/main/java/com/autoglm/glass/ui/ChatMessage.kt` — 对话消息数据类
- `glass/src/main/java/com/autoglm/glass/bridge/GlassBridge.kt` — CXRServiceBridge 封装
- `glass/src/main/java/com/autoglm/glass/bridge/GlassProtocol.kt` — 眼镜端协议镜像（含 CMD_CHAT 解码）
- `glass/src/main/java/com/autoglm/glass/receiver/KeyReceiver.kt` — 镜腿键/触控板系统广播

### 5.3 Xiaomi 侧（PhoneAgent 执行端，app 模块）

- `app/src/main/java/com/autoglm/assistant/service/ExternalTaskReceiver.kt` — 接收 ADB 广播触发 executeTask
- `app/src/main/java/com/autoglm/assistant/service/ExternalTaskCommand.kt` — 任务参数解析
- `app/src/main/java/com/autoglm/assistant/core/agent/PhoneAgent.kt` — PhoneAgent 主循环（含单步超时检测）
- `app/src/main/java/com/autoglm/assistant/core/agent/AgentConfig.kt` — `stepTimeoutMs` 默认 20s

### 5.4 电脑端（中转监听）

- `tools/relay/feishu_relay_listener.py` — 飞书长连接 + ADB 转发 + "新建会话"文字匹配

## 6. PhoneAgent 单步超时死循环检测

为防止模型流式响应死循环或 action 执行卡死拖垮整条任务，PhoneAgent 单步执行超时强制中止：

- `AgentConfig.stepTimeoutMs` 默认 20000ms，可配置，不写死在业务逻辑
- `StepResult.timeout` 标记单步是否超时
- `executeStep` 整体包到 `withTimeoutOrNull(stepTimeoutMs)`，超时返回 `timeout=true` 的 StepResult
- 外层循环（`executeUntilFinish`、`executeDirectly`、`executeWithCoordinator`）检测到 `timeout=true` 立即中止当前步/协调器轮
- 协调器收到"上一指令超时被强制中止"的标记后自行决策换路或停止

## 7. 眼镜端对话历史 UI

眼镜端不再只是被动状态显示屏，支持对话历史浏览：

- `GlassProtocol.CMD_CHAT` 手机→眼镜推送对话消息（role + text）
- `StatusViewModel.messages` 维护消息列表 StateFlow（最多 100 条，超出丢弃最早）
- `GlassStatusScreen` 顶部状态行 + LazyColumn 消息历史，新消息自动滚到最新
- 触控板上下滑手势翻历史（Y 位移 >20px 翻一条）

消息历史的数据源（vivo 侧回推，闭合"说完话眼镜要看到识别内容"的反馈）：

- vivo STT 出文本后，`WakeWordService.sendGlassResultToRelay()` 先调用 `GlassStatusSink.pushChat("user", text)` 把识别文本回显到眼镜，用户即刻确认"你: xxx"
- webhook 中转成功/失败再回推一条 `system` 消息（"已发送"/"发送失败"/"未配置中转地址"），避免"说完话不知道有没有发出去"的中间黑箱
- `GlassStatusSink.pushChat(role, text)` 经 `encodeChatCaps` → `sendCustomCmd(KEY_PHONE_TO_GLASS)` 推送，仅在链路就绪且会话构建完成后发送

**已知未闭环项**：执行任务的是 Xiaomi，眼镜连的是 vivo，两者无连接，因此 Xiaomi 上 PhoneAgent 的执行进度/最终结果目前无法回到眼镜。眼镜端只能看到"你说了什么 + 是否已中转发出"，看不到"Xiaomi 执行到哪一步/是否完成"。要做到全闭环需增加 Xiaomi→飞书→vivo→眼镜的回程通道，暂列后续。

## 8. "新建会话"文字匹配指令

眼镜用户说"新建会话"（或其他配置的同义触发词），链路识别为指令而非任务：

- Mac 端 `feishu_relay_listener.py` 正则匹配："新建会话"/"新对话"/"重置会话"/"清空对话"/"new session"/"clear"
- 命中指令：`am force-stop com.autoglm.assistant && am start MainActivity` 重启 Xiaomi 上的 AutoGLM，清空当前任务和对话上下文
- 不命中：照常广播给 ExternalTaskReceiver 触发 PhoneAgent 执行

由于 `PhoneAgent.run(task, resetHistory=true)` 默认清空历史，重启后下一条任务自然就是全新会话。

## 9. 凭据与环境变量

### 9.1 vivo 侧（眼镜桥接端）

通过设置页 `GlassChannelSection` 配置：
- `glassFeishuWebhookUrl` — 飞书自定义机器人 webhook URL（机器人 A）

阿里 NLS STT 凭据复用 vivo 上已有的 `aliNlsAkId` / `aliNlsAkSecret` / `aliNlsAppKey`（走 vivo 自己的阿里云账号）。

### 9.2 Mac 侧（中转监听端）

环境变量（不硬编码进仓库）：
- `FEISHU_APP_ID` / `FEISHU_APP_SECRET` — 机器人 B（长连接订阅方）的飞书应用凭据
- `XIAOMI_SERIAL` — PhoneAgent 手机的 adb serial
- `ADB_PATH`（可选）— adb 可执行文件路径

运行：
```bash
export FEISHU_APP_ID=cli_xxx
export FEISHU_APP_SECRET=xxx
export XIAOMI_SERIAL=c6764233
# 用项目已有的 lark_oapi 环境（如 /Users/admin/Documents/厦大体育预约/.venv）
python3 tools/relay/feishu_relay_listener.py
```

## 10. 飞书应用配置要求

### 机器人 A（发送方，webhook 自定义机器人）

- 类型：群自定义机器人
- 拉进接收群即可，无需开放平台应用
- 拿到 webhook URL：`https://open.feishu.cn/open-apis/bot/v2/hook/xxx`

### 机器人 B（接收方，飞书开放平台应用）

- 类型：自建应用，开启机器人能力
- 权限：`im:message`（接收群聊中单聊及群聊消息）
- 事件订阅：`im.message.receive_v1`，订阅方式选"长连接"
- 拉进接收群
- 拿到 App ID 和 App Secret 供 Mac 端长连接使用

**约束**：A 和 B 必须在**同一个飞书群**里，A 发的消息才会触发 B 的 `im.message.receive_v1` 事件。飞书规则：应用自己用 API 发的消息不会回推给同一应用的长连接，所以 A 和 B 必须是两个独立发送方。

## 11. 不在本轮范围内

1. Rokid 灵珠平台 SSE 接入（需公网 HTTPS 服务，违背"不要隧道"约束，暂不采用）
2. 公有技能发布和审核
3. CloudApp HTTPS 回调或电脑公网入站服务
4. 电脑重新上传 PCM 到 Rokid WebSocket ASR
5. PhoneAgent 手机承担眼镜连接或 STT
6. 在云端保存截图、账号数据或 PhoneAgent 配置
