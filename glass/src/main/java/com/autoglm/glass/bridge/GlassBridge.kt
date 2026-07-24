package com.autoglm.glass.bridge

import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps

/**
 * CXRServiceBridge 封装。
 * 业务目的：眼镜端通过它与手机端通信——subscribe 收手机下发的状态，sendMessage 回传意图/按键。
 * 眼镜端无需 connect，被手机 appStart 拉起后由系统桥接建立通道。
 *
 * 返回值约定：subscribe/sendMessage 返回 0 成功，负值为错误码（EINVAL/EDUP/EFAULT/EBUSY）。
 */
class GlassBridge {
    private val bridge = CXRServiceBridge()

    fun setStatusListener(listener: CXRServiceBridge.StatusListener) =
        bridge.setStatusListener(listener)

    fun subscribe(key: String, callback: CXRServiceBridge.MsgCallback): Int =
        bridge.subscribe(key, callback)

    /** 向手机回传指令，返回 0 成功 */
    fun sendMessage(key: String, caps: Caps): Int = bridge.sendMessage(key, caps)
}
