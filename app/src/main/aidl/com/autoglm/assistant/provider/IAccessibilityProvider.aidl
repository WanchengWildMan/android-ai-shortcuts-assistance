package com.autoglm.assistant.provider;

import android.os.ParcelFileDescriptor;

/**
 * AIDL接口定义：无障碍服务提供者
 * 
 * 该接口用于主应用与独立的无障碍服务提供者应用之间的跨进程通信。
 * 通过这种方式，无障碍服务在独立应用中运行，降低被目标应用检测的风险。
 */
interface IAccessibilityProvider {
    /**
     * 获取当前屏幕的 UI 层次结构 XML
     * @return UI 层次结构的 XML 字符串
     */
    String getUiHierarchy();
    
    /**
     * 在指定坐标执行点击操作
     * @param x X 坐标
     * @param y Y 坐标
     * @return 操作是否成功
     */
    boolean performClick(int x, int y);
    
    /**
     * 在指定坐标执行长按操作
     * @param x X 坐标
     * @param y Y 坐标
     * @return 操作是否成功
     */
    boolean performLongPress(int x, int y);
    
    /**
     * 执行全局操作（如返回、主页、最近任务等）
     * @param actionId 全局操作ID（参见 AccessibilityService.GLOBAL_ACTION_*）
     * @return 操作是否成功
     */
    boolean performGlobalAction(int actionId);
    
    /**
     * 执行滑动手势
     * @param startX 起始 X 坐标
     * @param startY 起始 Y 坐标
     * @param endX 结束 X 坐标
     * @param endY 结束 Y 坐标
     * @param duration 滑动持续时间（毫秒）
     * @return 操作是否成功
     */
    boolean performSwipe(int startX, int startY, int endX, int endY, long duration);
    
    /**
     * 查找当前具有焦点的节点 ID
     * @return 焦点节点的 ID，如果没有则返回 null
     */
    String findFocusedNodeId();
    
    /**
     * 在指定节点上设置文本
     * @param nodeId 节点 ID
     * @param text 要设置的文本
     * @return 操作是否成功
     */
    boolean setTextOnNode(String nodeId, String text);
    
    /**
     * 截取屏幕截图
     * @param fd 截图保存的文件描述符
     * @param format 图片格式（如 "PNG", "JPEG"）
     * @return 操作是否成功
     */
    boolean takeScreenshot(in ParcelFileDescriptor fd, String format);
    
    /**
     * 检查无障碍服务是否已启用
     * @return 无障碍服务是否已在系统设置中启用
     */
    boolean isAccessibilityServiceEnabled();
    
    /**
     * 获取当前 Activity 名称
     * @return 当前 Activity 的完整类名
     */
    String getCurrentActivityName();
}
