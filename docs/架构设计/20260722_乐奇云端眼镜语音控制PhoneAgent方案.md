# 乐奇本地私有技能控制 PhoneAgent 方案

> ⚠️ **本方案已过时**：基于 Rokid 旧"本地私有技能"（LocalSkillSdk / `cmd=send_nlp`）的方案，Rokid 当前已用 CXR-L SDK 取代本地私有技能。
>
> 当前正式方案见 [`20260723_飞书webhook眼镜语音控制PhoneAgent方案.md`](./20260723_飞书webhook眼镜语音控制PhoneAgent方案.md) —— 走 CXR-L + 飞书 webhook 中转，不依赖本地私有技能 Intent。
>
> 本文档保留仅作历史参考，不要再按此方案实施。

## 1. 目标

用户触摸 Rokid 眼镜镜腿并说出任务后，由乐奇 App 所在手机承接眼镜语音会话，Rokid 云端完成 ASR/NLP，再由乐奇手机上的 AutoGLM 本地私有技能 APK 接收识别结果。APK 将任务发送到受控云端消息中转，电脑主动订阅并接收任务，再通过 USB ADB 将任务交给 root PhoneAgent 手机的 `WakeWordService.executeTask()`。

首个验收任务：用户说“给文件传输助手发消息测试”，PhoneAgent 操作微信并向文件传输助手发送“测试”。

## 2. 唯一正式主链路

```text
Rokid 眼镜
  │ 触摸镜腿、采集语音
  ▼
乐奇 App 手机
  │ Rokid 语音 SDK 上传语音
  ▼
Rokid 语音云端
  │ ASR、NLP、本地私有技能路由
  ▼
乐奇手机上的 AutoGLM 本地私有技能 APK
  │ Android Intent extras: cmd/id/asr/nlp/action
  │ 主动建立 WSS 连接并发布加密任务
  ▼
受控云端消息中转
  │ 鉴权、短期存储、幂等投递、ACK
  ▼
电脑任务接收器
  │ 主动建立 WSS 连接并订阅当前设备通道
  │ USB ADB 受控转发
  ▼
root PhoneAgent 手机
  │ ExternalTaskReceiver
  ▼
WakeWordService.executeTask(task)
  ▼
PhoneAgent 无障碍操作微信
```

正式链路不要求乐奇手机连接电脑，也不要求电脑具有公网 IP 或开放入站端口：

- 乐奇手机只向消息中转发起出站 WSS 连接。
- 电脑只向消息中转发起出站 WSS 连接。
- 消息中转负责关联发布端和订阅端。
- PhoneAgent 手机继续通过 USB 连接电脑，不直接暴露网络服务。

## 3. 各端职责

| 端 | 职责 | 不承担的职责 |
|---|---|---|
| Rokid 眼镜 | 镜腿交互、采音 | 不运行 PhoneAgent |
| 乐奇 App 手机 | 连接眼镜、运行 Rokid 语音 SDK、接收云端结果 | 不操作微信 |
| Rokid 云端 | ASR、NLP、匹配 AutoGLM 本地私有技能 | 不连接电脑 |
| AutoGLM 本地技能 APK | 接收 Intent 中的 `asr/nlp/action`，加密并发布任务 | 不重复 STT，不执行 PhoneAgent |
| 受控云端消息中转 | 验证发布者/订阅者、短期保存任务、投递和确认 | 不读取任务明文，不保存 Rokid 凭据，不执行 PhoneAgent |
| 电脑任务接收器 | 订阅任务、解密校验，用 USB ADB 转给 PhoneAgent 手机 | 不做 STT，不开放公网端口 |
| root PhoneAgent 手机 | 调用 `WakeWordService.executeTask()` 并操作目标 App | 不连接眼镜，不运行乐奇 STT |

## 4. Rokid 官方能力依据

官方文档本地副本：`/Users/admin/Documents/rokid-projects/rokid-platform-docs`。

### 4.1 本地私有技能即 App 模式

`2-RokidDocument/1-SkillsKit/getting-started/creat.md:50-58`：

- 私有技能只对授权设备开放。
- 本地私有技能需要编写 APK 推送到设备。
- 云端私有技能才需要另建后端服务。

`creat.md:193-203`：本地技能的后端服务由开发者上传 APK，不是 HTTPS CloudApp 服务。

### 4.2 Rokid 语音 SDK 在本机交付 ASR/NLP

`5-enableVoice/rokid-vsvy-sdk-docs/LocalSkillSdk/LocalSkillSdk.md:70-86` 定义本地技能 Intent 参数：

| 参数 | 含义 |
|---|---|
| `cmd=send_nlp` | 本地技能 NLP 命中 |
| `id` | 技能 ID |
| `type` | `activity` 或 `service` |
| `form` | `cut`、`scene` 或 `service` |
| `extra` | 附加配置 |
| `nlp` | NLP JSON |
| `action` | Action JSON |
| `asr` | 用户语音识别文本 |

`LocalSkillSdk.md:88-129`：本地技能由 Activity 或 Service 承接；Manifest 配置技能 ID、FORM、TYPE、COMPONENT 后，Rokid 语音 SDK 可通过 Android 启动机制访问该组件。

`LocalSkillSdk.md:191-218`：官方示例在 `onCreate/onNewIntent` 中读取 Intent 并解析 `nlp`。

`LocalSkillSdk.md:383-415`：官方 `HandyManager` 示例可直接获得 `localBean.asr`。

### 4.3 私有技能无需审核

`getting-started/creat.md:263-267`：可添加账号绑定设备或自定义 type id/SN 作为测试设备。

`getting-started/creat.md:287-297`：公有技能需要审核；私有技能无需审核，会自动发布，约十分钟后进入“开发中”并生效。

## 5. 本地技能 APK 设计

新增独立 Android Application 模块 `:rokid_bridge`，安装在运行乐奇 App 的手机，不安装在 PhoneAgent 手机。

### 5.1 Manifest

模块通过构建配置注入 Rokid 技能 ID，禁止写死真实技能 ID：

```xml
<activity
    android:name=".RokidSkillActivity"
    android:enabled="true"
    android:exported="true"
    android:launchMode="singleTask">
    <meta-data
        android:name="com.rokid.ai.skill.local.ID"
        android:value="${rokidLocalSkillId}" />
    <meta-data
        android:name="com.rokid.ai.skill.local.FORM"
        android:value="cut" />
</activity>
```

`RokidSkillActivity` 只接受 Rokid SDK 投递的本地技能 Intent，不提供 Launcher 入口。

### 5.2 Intent 处理

1. `onCreate()` 与 `onNewIntent()` 均进入同一处理函数。
2. 验证 `cmd == "send_nlp"`。
3. 优先读取 `asr`；若 `asr` 缺失，再从 `nlp` JSON 提取 ASR 字段。
4. 拒绝空文本与超过配置上限的文本。
5. 生成全局唯一 `requestId`，将任务写入本地待发送队列。
6. 由后台传输组件加密并发送任务，收到云端 ACK 后删除本地副本。
7. Activity 立即结束，不长时间占据乐奇 App 前台。

### 5.3 移动端可靠发送

手机可能切换蜂窝网络、Wi-Fi 或短时离线，因此不能只依赖一次 WebSocket `send`：

- 有网络且 WSS 已连接：立即发布。
- 网络断开：任务保存在应用私有存储中。
- 重连后：按创建顺序重发未确认任务。
- 收到同一 `requestId` 的 ACK 后：标记完成并清除。
- 重试采用有限指数退避；服务端依靠 `requestId` 去重。

## 6. 受控云端消息中转设计

具体平台、账号、区域和接收方尚未确定，本方案不配置未知外部服务。选型必须满足以下接口和安全条件。

### 6.1 必需能力

1. 支持 Android APK 与电脑客户端主动建立 TLS 保护的长连接，或支持 HTTPS 发布加电脑端长轮询。
2. 支持发布、订阅和 ACK；手机无需知道电脑公网地址。
3. 支持每个设备独立通道，订阅者不能读取其他设备消息。
4. 支持短期离线存储；消息确认或超过 TTL 后自动删除。
5. 支持设备凭证撤销、速率限制、连接日志和投递审计。

### 6.2 最小化消息信封

中转服务只能看到路由所需元数据和密文：

```json
{
  "version": 1,
  "type": "task",
  "deviceId": "随机设备标识",
  "requestId": "uuid",
  "createdAt": 1784697600000,
  "expiresAt": 1784697900000,
  "nonce": "base64",
  "ciphertext": "base64"
}
```

密文解开后：

```json
{
  "asr": "给文件传输助手发消息测试"
}
```

不得上传：

- Rokid key、secret、token、账号信息；
- 手机通讯录、微信账号、截图或界面树；
- `nlp`、`action` 全量内容，除非后续有明确业务需求；
- PhoneAgent 模型密钥及其他配置。

### 6.3 鉴权与加密

- 手机发布凭证和电脑订阅凭证必须分离，可单独撤销。
- WSS/HTTPS 负责传输加密。
- `asr` 在手机端进行端到端加密，只有电脑可解密；中转服务不持有解密密钥。
- 密钥通过用户受控的离线配对流程配置，不写入 Git 或日志。
- 每条任务校验版本、时间窗口、`requestId`、设备标识和认证标签，防止伪造与重放。

### 6.4 生命周期

- 默认任务 TTL：5 分钟，通过配置管理，不写死在业务逻辑。
- 电脑 ACK 后立即删除云端消息。
- 过期任务不得下发 PhoneAgent。
- 同一 `requestId` 在手机、云端、电脑和 PhoneAgent 入口均只执行一次。

## 7. 电脑任务接收器设计

在 `tools/rokid_bridge/` 提供常驻客户端：

1. 从本机安全配置读取中转地址、订阅凭证、设备标识和解密密钥。
2. 主动连接受控消息中转，不监听公网端口。
3. 收到消息后验证信封、时效、认证标签和 `requestId`。
4. 解密得到 `asr`，进行长度和空白校验。
5. 使用参数数组调用 ADB，不拼接 shell 字符串。
6. 指定 PhoneAgent 手机 serial，向 `ExternalTaskReceiver` 发送任务。
7. 仅在 PhoneAgent 入口接受任务后向中转服务发送 ACK。
8. 本机保存短期去重记录，避免重连重复执行。

## 8. PhoneAgent 手机任务入口

新增 `ExternalTaskReceiver`：

```text
action: com.autoglm.assistant.action.EXECUTE_EXTERNAL_TASK
extra: task
extra: requestId
```

Receiver：

1. 校验 action、任务文本和 `requestId`。
2. 确保 `WakeWordService` 以前台服务启动，`startWakeWord=false`。
3. 调用现有 `WakeWordService.executeTask(task)`。
4. 已有任务执行时不抢占、不覆盖；向电脑返回拒绝状态，不发送成功 ACK。
5. 已接受的 `requestId` 不重复执行。

## 9. 开发联调链路

`adb reverse` 只用于开发阶段验证本地技能能否正确收到 ASR 并生成消息，不作为正式移动链路：

```text
乐奇手机 APK ws://127.0.0.1:8765
  → adb reverse
  → 电脑本地测试接收器
```

该测试通过后再接入经用户确认的受控云端消息中转。

## 10. 分阶段执行方案

### 阶段 A：电脑 → PhoneAgent 手机

1. 实现 `ExternalTaskReceiver` 和 Manifest 声明。
2. 编写输入校验、去重和服务状态测试。
3. 通过电脑 ADB 注入“给文件传输助手发消息测试”。
4. 验证 `WakeWordService.executeTask()` 被调用并完成微信操作。

### 阶段 B：乐奇本地私有技能

1. 新建 `:rokid_bridge` 模块。
2. 实现本地技能 Manifest 元数据与 Intent 解析。
3. 实现本地待发送队列和传输接口。
4. 使用 `adb reverse` 和本地测试接收器验证 `cmd=send_nlp/asr=...`。
5. 确认 APK 能从 Rokid 语音 SDK 收到真实 `asr`。

### 阶段 C：受控云端消息中转

1. 用户明确选择公司内部或个人受控的消息中转平台、账号、区域和数据边界。
2. 按最小权限创建发布端与订阅端独立凭证。
3. 实现手机发布端的端到端加密、离线队列与 ACK。
4. 实现电脑订阅端的解密、校验、去重和 ADB 转发。
5. 验证手机使用蜂窝网络、电脑使用固定网络时仍能可靠投递。

### 阶段 D：Rokid 私有技能真机闭环

1. 在 Rokid 开发者平台创建“本地私有技能”。
2. 配置入口词、通用任务意图和用户语句。
3. 将平台生成的技能 ID 写入本机构建配置。
4. 将 APK 安装到乐奇 App 手机。
5. 添加眼镜对应账号绑定设备或 type id/SN 为测试设备。
6. 等待私有技能进入“开发中”。
7. 触摸眼镜镜腿并说验收任务。
8. 依次验证本地技能、中转投递、电脑订阅、PhoneAgent 和微信结果。

## 11. 验收标准

1. 乐奇手机不连接电脑、使用蜂窝网络时仍可发布任务。
2. 电脑无公网 IP、不开入站端口时仍可主动订阅任务。
3. 中转服务无法读取 `asr` 明文，且不接收其他敏感数据。
4. 乐奇手机日志能确认收到 `cmd=send_nlp` 和非空 `asr`。
5. 电脑确认消息鉴权、解密、时效和去重均通过后才转发。
6. root PhoneAgent 手机日志出现 `WakeWordService.executeTask called`。
7. 微信文件传输助手收到“测试”。
8. 重复、过期、伪造或未认证消息均不执行。
9. PhoneAgent 正在执行任务时，新任务不会覆盖当前任务。

## 12. 不在本轮范围内

1. 公有技能发布和审核。
2. CloudApp HTTPS 回调或电脑公网入站服务。
3. 电脑重新上传 PCM 到 Rokid WebSocket ASR。
4. PhoneAgent 手机承担眼镜连接或 STT。
5. 在云端保存截图、账号数据或 PhoneAgent 配置。
