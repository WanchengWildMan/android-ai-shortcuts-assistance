package com.autoglm.rokidbridge

object RokidSkillCommand {
    fun parse(command: String?, asr: String?): String? {
        if (command != "send_nlp") return null
        return asr?.trim()?.takeIf { it.isNotEmpty() }
    }
}
