package com.autoglm.rokidbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RokidSkillCommandTest {
    @Test
    fun `send nlp 命令返回 ASR 文本`() {
        assertEquals(
            "给文件传输助手发消息测试",
            RokidSkillCommand.parse("send_nlp", "  给文件传输助手发消息测试  ")
        )
    }

    @Test
    fun `非 send nlp 命令被拒绝`() {
        assertNull(RokidSkillCommand.parse("other", "打开微信"))
    }

    @Test
    fun `空 ASR 文本被拒绝`() {
        assertNull(RokidSkillCommand.parse("send_nlp", "   "))
    }
}
