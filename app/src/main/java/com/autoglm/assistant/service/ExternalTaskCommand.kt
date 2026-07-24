package com.autoglm.assistant.service

data class ExternalTaskCommand(
    val task: String,
    val requestId: String
) {
    companion object {
        fun parse(task: String?, requestId: String?): ExternalTaskCommand? {
            val normalizedTask = task?.trim().orEmpty()
            val normalizedRequestId = requestId?.trim().orEmpty()
            if (normalizedTask.isEmpty() || normalizedRequestId.isEmpty()) return null
            return ExternalTaskCommand(normalizedTask, normalizedRequestId)
        }
    }
}
