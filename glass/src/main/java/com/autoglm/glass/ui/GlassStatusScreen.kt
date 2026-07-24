package com.autoglm.glass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * 眼镜端状态渲染界面。
 * 业务目的：单绿色（眼镜屏为单绿色通道）显示 Agent 执行状态 + 可滑动对话历史。
 * 约束：信息精简，只显示关键状态行；消息历史最多 100 条，超出自动丢弃最早。
 */
@Composable
fun GlassStatusScreen(viewModel: StatusViewModel) {
    val state by viewModel.state.collectAsState()
    val connected by viewModel.bridgeConnected.collectAsState()
    val messages by viewModel.messages.collectAsState()

    // HARD: 眼镜屏单绿色通道，统一用 #00FF66
    val green = Color(0xFF00FF66)
    val greenDim = Color(0xFF66BB99)
    val greenUser = Color(0xFF99FFCC)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 镜腿触控板上下滑手势：垂直位移>阈值即翻一条消息
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { /* 不做事，位移在 change 时累积 */ },
                    onDragCancel = { },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // 眼镜触控板 Y 向下为正（手指向下滑=往历史方向）
                        if (abs(dragAmount.y) > 20f) {
                            viewModel.scroll(if (dragAmount.y > 0) 1 else -1)
                        }
                    }
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        // 未连接且无消息：显示等待提示
        if (!connected && !state.isRunning && messages.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("AutoGLM", color = green, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                Text("等待连接", color = greenDim, fontSize = 13.sp)
            }
            return@Box
        }

        // 顶部：当前任务状态行（始终显示，便于用户一眼看到当前进度）
        Column(modifier = Modifier.padding(12.dp)) {
            if (state.isRunning) {
                val stepText = if (state.currentStep > 0) "步骤 ${state.currentStep}" else "执行中"
                Text(stepText, color = green, fontSize = 14.sp)
                if (state.thinkingLine.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        state.thinkingLine,
                        color = greenDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (state.taskSummary.isNotEmpty()) {
                Text("完成", color = green, fontSize = 14.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    state.taskSummary,
                    color = greenDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (messages.isEmpty()) {
                Text("就绪", color = green, fontSize = 14.sp)
            } else {
                Text("会话", color = green, fontSize = 14.sp)
            }
        }

        // 主体：对话历史列表，自动滚动到最新
        if (messages.isNotEmpty()) {
            val listState = rememberLazyListState()
            // 新消息到达时自动滚到末尾（除非用户主动往上翻历史）
            LaunchedEffect(messages.size) {
                if (state.scrollIndex == 0) {
                    listState.animateScrollToItem(messages.lastIndex)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 40.dp, start = 12.dp, end = 12.dp, bottom = 12.dp)
            ) {
                items(messages) { msg ->
                    val color = when (msg.role) {
                        "user" -> greenUser
                        "agent" -> green
                        else -> greenDim
                    }
                    val prefix = when (msg.role) {
                        "user" -> "你: "
                        "agent" -> "Agent: "
                        else -> ""
                    }
                    Text(
                        "$prefix${msg.content}",
                        color = color,
                        fontSize = 12.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
