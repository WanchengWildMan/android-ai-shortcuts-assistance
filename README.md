# AutoGLM Android Assistant

基于 [Open-AutoGLM](https://github.com/THUDM/AutoGLM) 框架构建的安卓 AI 助手应用。说出指令，让 AI 替你操作手机——发消息、比价格、填表单、查信息，无需手动点点点。

---

## 这是什么

AutoGLM Android Assistant 是一款**语音驱动的 AI 手机自动化工具**。

核心体验：

1. 说出唤醒词（如"小爱同学"）
2. 说出你的任务（如"帮我查一下京东 iPhone 16 最低价"）
3. 等待 AI 自动完成操作，结果直接反馈给你

适用场景：

- 跨应用查询（比价、搜索）
- 批量操作（批量回复消息、整理联系人）
- 表单填写和流程操作
- 任何需要反复点击才能完成的繁琐任务

---

## 架构设计

本项目采用**双应用架构**，无障碍服务由独立的 Provider 应用承载：

```
┌───────────────────────┐          AIDL IPC          ┌──────────────────────┐
│  主应用 (AutoGLM)     │ ◄────────────────────────► │  Provider 应用        │
│  - UI / 语音          │                            │  - 无障碍服务         │
│  - AI 决策            │  请求点击 / 获取层级        │  - 点击滑动操作       │
│  - 任务调度           │ ◄────────────────────────► │  - UI 结构获取        │
└───────────────────────┘                            └──────────────────────┘
```

将无障碍服务独立部署，可降低主应用被目标应用检测的风险，同时支持独立升级。

---

## 系统要求

| 项目 | 要求 |
|------|------|
| Android 版本 | 7.0（API 24）及以上 |
| 构建工具 | Android Studio + JDK 17 |
| 编译 SDK | Android 34 |
| AI 模型服务 | 需要部署 AutoGLM-Phone API |

---

## 安装步骤

### 1. 编译安装

需要同时安装**主应用**和 **Provider 应用**两个 APK：

```bash
# 一键安装两个应用（推荐）
./install_both.sh

# 或分别安装
./gradlew :app:installDebug
./gradlew :provider:installDebug
```

### 2. 启用无障碍服务

```
手机设置 → 无障碍 → 已安装的服务 → Accessibility Provider → 开启
```

Provider 应用只需授权一次无障碍服务，此后无需打开该应用。

### 3. 授权悬浮窗权限

应用首次启动时会引导开启悬浮窗权限，授权后 AI 执行任务时可以在屏幕上显示状态提示。

---

## 配置

打开主应用 → 右上角设置图标，依次配置以下项目。

### 必填：AI 模型 API

| 字段 | 说明 | 示例 |
|------|------|------|
| API URL | AutoGLM-Phone 服务地址 | `http://192.168.1.100:8000/v1` |
| API Key | 访问密钥 | `sk-xxxx` |
| 模型名称 | 模型标识符 | `autoglm-phone-9b` |

> 需要自行搭建或申请 AutoGLM-Phone 模型服务，参考 [Open-AutoGLM 项目](https://github.com/THUDM/AutoGLM)。

---

### 必填（语音唤醒）：Porcupine Access Key

语音唤醒功能基于 [Picovoice Porcupine](https://picovoice.ai/platform/porcupine/)，需要 Access Key 才能运行。**免费版支持无限次数调用，仅需注册。**

#### 申请步骤

**第一步：注册 Picovoice 账户**

前往 [https://console.picovoice.ai/signup](https://console.picovoice.ai/signup) 注册免费账户（支持 GitHub / Google 快速登录）。

**第二步：获取 Access Key**

登录后进入 [Picovoice Console](https://console.picovoice.ai/)，首页即可看到你的 Access Key（一段较长的字符串），复制它。

**第三步：填入应用**

在应用设置页面找到 **Porcupine Access Key** 字段，粘贴刚才复制的 Key，保存。

#### 选择唤醒词

应用内置了预训练的中文唤醒词模型（`小爱_zh_android_v4_0_0.ppn`），唤醒词为**"小爱同学"**。

如果想使用自定义唤醒词：

1. 登录 [Picovoice Console](https://console.picovoice.ai/)
2. 进入 **Porcupine** → **Train** 页面
3. 输入你想要的唤醒词短语（支持中文），点击训练
4. 下载生成的 `.ppn` 文件（平台选择 **Android**）
5. 在应用设置中导入该 `.ppn` 文件

> 注意：每个 `.ppn` 文件绑定特定平台（Android/iOS/…）且绑定 Access Key，不可混用。

---

### 可选：语音识别（STT）

应用支持两种语音识别方式：

| 方式 | 说明 | 配置 |
|------|------|------|
| 豆包键盘（推荐） | 通过字节跳动输入法语音识别，免费 | 安装豆包输入法，设置中选择 IME_VOICE |
| 讯飞 REST API | 通过科大讯飞语音识别接口 | 需要填入讯飞 AppID / API Key / Secret |

---

### 可选：智能协调器

开启后，复杂任务会先进行任务规划和拆解，再逐步执行。

| 参数 | 说明 |
|------|------|
| 协调器 API URL | 协调模型服务地址（可与执行模型相同） |
| 协调器模型名称 | 协调模型标识符 |

---

## 使用方式

### 语音唤醒模式

1. 确保语音唤醒服务已启动（首页显示"语音唤醒已就绪"）
2. 说出唤醒词（默认：**"小爱同学"**）
3. 听到提示音后说出你的任务
4. AI 开始执行，屏幕上显示执行进度
5. 执行完成后，结果出现在对话历史中

### 快捷指令模式

首页可以配置常用快捷指令，点击直接触发任务，无需语音。

### 手动输入模式

在对话页面直接输入任务文字，点击发送执行。

---

## 执行过程说明

每步执行过程：

1. **截取屏幕** — AI 理解当前界面状态
2. **决策操作** — 输出思考过程和指令
3. **执行操作** — 通过无障碍服务完成点击/输入/滑动
4. **循环直到完成** — 每步截图结果反馈给 AI

执行过程可在对话气泡中实时查看（设置中可开关"显示执行过程"）。

---

## 故障排除

### 语音唤醒不工作

- 确认应用有麦克风权限
- 确认 Porcupine Access Key 已填入且保存设置
- 确认语音唤醒服务已启动（首页状态区域显示"已就绪"）
- 在安静环境中清晰说出唤醒词

### 操作执行失败 / 无反应

- 确认 Accessibility Provider 应用已安装且无障碍服务已开启
- 检查 AI 模型 API 地址是否可访问
- 查看日志：`adb logcat | grep AutoGLM`

### 无法连接到 Provider 服务

```bash
adb logcat | grep -E "ProviderBindService|UIHierarchyManager"
```

确认 Provider 应用：已安装 → 无障碍服务已开启 → 未被系统后台限制杀掉

### AI 任务执行卡死

- 检查手机是否开启了"后台无限制"（允许应用在后台长时间运行）
- 应用首页若显示橙色电量优化警告，点击按指引关闭

---

## 开发与构建

```bash
# 构建调试版
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug

# 构建并安装到已连接手机
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:installDebug

# 查看实时日志
adb logcat | grep -E "AutoGLM|PhoneAgent|WakeWordService"
```

详细文档见 [docs/](docs/) 目录：

- [无障碍服务部署指南](docs/无障碍服务部署指南.md)
- [协调器运作逻辑说明](docs/协调器运作逻辑说明.md)
- [执行步数配置说明](docs/执行步数配置说明.md)

---

## 注意事项

- **资费**：Porcupine 免费版已够个人使用；AI 模型 API 按部署方式不同，费用自行承担
- **隐私**：截图和语音数据发送到你配置的 AI 服务，请确认服务的可信度
- **敏感操作**：支付、密码等敏感界面执行前会请求人工确认
- **耗电**：语音唤醒服务持续监听麦克风，不使用时建议在应用内关闭

---

## License

本项目基于 Open-AutoGLM 框架开发。
