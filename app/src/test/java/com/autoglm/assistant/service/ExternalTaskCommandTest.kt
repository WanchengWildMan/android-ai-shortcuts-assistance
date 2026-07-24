package com.autoglm.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalTaskCommandTest {
    @Test
    fun `合法任务返回规范化命令`() {
        val command = ExternalTaskCommand.parse("  给文件传输助手发消息测试  ", "request-1")

        assertEquals("给文件传输助手发消息测试", command?.task)
        assertEquals("request-1", command?.requestId)
    }

    @Test
    fun `空任务被拒绝`() {
        assertNull(ExternalTaskCommand.parse("   ", "request-1"))
    }

    @Test
    fun `空请求标识被拒绝`() {
        assertNull(ExternalTaskCommand.parse("打开微信", "   "))
    }
}
