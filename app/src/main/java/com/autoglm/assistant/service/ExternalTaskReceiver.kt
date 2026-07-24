package com.autoglm.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autoglm.assistant.util.Logger
import com.autoglm.assistant.util.ServiceHelper

class ExternalTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXECUTE_TASK) return
        val command = ExternalTaskCommand.parse(
            intent.getStringExtra(EXTRA_TASK),
            intent.getStringExtra(EXTRA_REQUEST_ID)
        ) ?: run {
            Logger.w(Logger.SERVICE, "外部任务参数无效")
            return
        }

        val service = WakeWordService.instance
        if (service == null) {
            Logger.i(Logger.SERVICE, "外部任务启动服务 requestId=${command.requestId}")
            ServiceHelper.startWakeWordService(context, startWakeWord = false)
            Logger.w(Logger.SERVICE, "服务正在初始化，请重试外部任务 requestId=${command.requestId}")
            return
        }

        Logger.i(Logger.SERVICE, "收到外部任务 requestId=${command.requestId} task=${command.task.take(40)}")
        service.executeTask(command.task)
    }

    companion object {
        const val ACTION_EXECUTE_TASK = "com.autoglm.assistant.action.EXECUTE_EXTERNAL_TASK"
        const val EXTRA_TASK = "task"
        const val EXTRA_REQUEST_ID = "requestId"
    }
}
