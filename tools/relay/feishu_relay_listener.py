#!/usr/bin/env python3
"""飞书长连接接收端 + adb 转发到 Xiaomi PhoneAgent
收到机器人 B 长连接事件，解析出文本：
- 文字匹配"新建会话"等指令：重启 Xiaomi 上的 AutoGLM，清空当前任务/上下文
- 其他文本：广播给 ExternalTaskReceiver 触发 PhoneAgent 执行任务

凭据从环境变量读取，避免硬编码进仓库：
- FEISHU_APP_ID / FEISHU_APP_SECRET：机器人 B（Mac 端长连接订阅方）的凭据
- XIAOMI_SERIAL：PhoneAgent 手机的 adb serial
- ADB_PATH：adb 可执行文件路径（默认按 homebrew 路径找）
"""
import json
import os
import re
import subprocess
import time
import uuid

import lark_oapi as lark
from lark_oapi.api.im.v1 import (
    P2ImChatAccessEventBotP2pChatEnteredV1,
    P2ImMessageMessageReadV1,
    P2ImMessageReceiveV1,
)

APP_ID = os.environ.get("FEISHU_APP_ID", "")
APP_SECRET = os.environ.get("FEISHU_APP_SECRET", "")
XIAOMI_SERIAL = os.environ.get("XIAOMI_SERIAL", "")
PHONEAGENT_PKG = "com.autoglm.assistant"
ACTION = "com.autoglm.assistant.action.EXECUTE_EXTERNAL_TASK"

ADB = os.environ.get("ADB_PATH", "/opt/homebrew/share/android-commandlinetools/platform-tools/adb")

# 文字匹配指令集：识别到这些就不下发任务给 PhoneAgent，而是触发特殊动作
# 新建会话：清空当前任务和对话历史（重启 AutoGLM 进程实现，因为 PhoneAgent.run 默认 resetHistory=true）
NEW_SESSION_PATTERNS = [
    r"^\s*新\s*建\s*会\s*话\s*$",
    r"^\s*新\s*对\s*话\s*$",
    r"^\s*重\s*置\s*会\s*话\s*$",
    r"^\s*清\s*空\s*对\s*话\s*$",
    r"^\s*new\s*session\s*$",
    r"^\s*clear\s*$",
]


def is_new_session(text: str) -> bool:
    return any(re.search(p, text, re.IGNORECASE) for p in NEW_SESSION_PATTERNS)


def restart_autoglm() -> None:
    """重启 Xiaomi 上 AutoGLM：force-stop 清空当前任务/上下文，再拉起 MainActivity 等待新指令"""
    print("NEW_SESSION 重启 AutoGLM 清空上下文", flush=True)
    try:
        subprocess.run([ADB, "-s", XIAOMI_SERIAL, "shell", "am", "force-stop", PHONEAGENT_PKG],
                       capture_output=True, text=True, timeout=10)
        time.sleep(1)
        subprocess.run([ADB, "-s", XIAOMI_SERIAL, "shell", "am", "start",
                        "-n", f"{PHONEAGENT_PKG}/.MainActivity"],
                       capture_output=True, text=True, timeout=10)
        print("NEW_SESSION done", flush=True)
    except Exception as e:
        print(f"NEW_SESSION_ERROR {e}", flush=True)


def forward_to_xiaomi(text: str) -> None:
    request_id = f"feishu-{int(time.time())}-{uuid.uuid4().hex[:6]}"
    cmd = [
        ADB, "-s", XIAOMI_SERIAL, "shell", "am", "broadcast",
        "-a", ACTION,
        "-n", f"{PHONEAGENT_PKG}/.service.ExternalTaskReceiver",
        "--es", "task", text,
        "--es", "requestId", request_id,
    ]
    print(f"FWD_XIAOMI requestId={request_id} text={text}", flush=True)
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        print(f"ADB_RESULT rc={out.returncode} {out.stdout.strip()} {out.stderr.strip()}", flush=True)
    except Exception as e:
        print(f"ADB_ERROR {e}", flush=True)


def do_p2_im_message_receive_v1(data: P2ImMessageReceiveV1) -> None:
    event = data.event
    message = event.message
    try:
        content_obj = json.loads(message.content) if message.content else {}
        text = content_obj.get("text", "")
    except Exception:
        text = ""
    print(f"RECV_MSG chat_id={message.chat_id} text={text}", flush=True)
    if not text:
        return
    # 文字匹配指令：新建会话等不下发任务，触发特殊动作
    if is_new_session(text):
        restart_autoglm()
        return
    forward_to_xiaomi(text)


def do_p2_im_message_message_read_v1(_data: P2ImMessageMessageReadV1) -> None:
    pass


def do_p2_im_chat_access_event_bot_p2p_chat_entered_v1(
    _data: P2ImChatAccessEventBotP2pChatEnteredV1,
) -> None:
    pass


def main():
    if not APP_ID or not APP_SECRET:
        print("ERROR: 请设置环境变量 FEISHU_APP_ID 和 FEISHU_APP_SECRET", flush=True)
        raise SystemExit(1)
    if not XIAOMI_SERIAL:
        print("ERROR: 请设置环境变量 XIAOMI_SERIAL", flush=True)
        raise SystemExit(1)

    event_handler = (
        lark.EventDispatcherHandler.builder("", "", lark.LogLevel.WARNING)
        .register_p2_im_chat_access_event_bot_p2p_chat_entered_v1(
            do_p2_im_chat_access_event_bot_p2p_chat_entered_v1
        )
        .register_p2_im_message_message_read_v1(do_p2_im_message_message_read_v1)
        .register_p2_im_message_receive_v1(do_p2_im_message_receive_v1)
        .build()
    )
    print("正在启动飞书长连接 + adb 转发...", flush=True)
    ws_client = lark.ws.Client(
        APP_ID, APP_SECRET, event_handler=event_handler, log_level=lark.LogLevel.WARNING
    )
    print("飞书长连接已启动；收到消息会打印 RECV_MSG；识别到'新建会话'等指令会重启 AutoGLM 清空上下文", flush=True)
    ws_client.start()


if __name__ == "__main__":
    main()
