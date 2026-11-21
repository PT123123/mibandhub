package com.mibandhub.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * QQ / TIM 全屏来电的无障碍桥。
 *
 * QQ 来电不会发通知，而是直接拉起 [com.tencent.av.ui.VideoInviteActivity]，
 * 所以只能靠无障碍读界面：
 *   - 识别来电 Activity 进来 → 读来电人 → 让 CallBridge 推手环来电页；
 *   - 手环按「拒接」→ CallBridge 回调 [clickReject] → 点击 QQ 界面上的「拒绝」；
 *   - Activity 关闭（接起/拒绝/对方取消）→ 撤掉手环来电页并恢复音量。
 *
 * 只在 QQ/TIM 的窗口事件上工作（见 accessibility_service_config.xml 的 packageNames），
 * 不读其他应用的内容。
 */
class QqCallAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "QqCallA11y"

        /** QQ 来电页（语音/视频都用这个 Activity，日志已确认）。 */
        private const val QQ_INVITE_ACTIVITY = "com.tencent.av.ui.VideoInviteActivity"

        private val REJECT_TEXTS = listOf("拒绝", "拒接", "挂断")
        private val IGNORED_TEXTS = setOf(
            "拒绝", "拒接", "挂断", "接听", "视频通话", "语音通话", "邀请你视频通话",
            "邀请你语音通话", "邀请你", "QQ", "TIM", "通话", "视频", "语音",
        )

        @Volatile
        var instance: QqCallAccessibilityService? = null
            private set
    }

    private var inviteVisible = false
    private var rejected = false
    private var lastLabel = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // XML 已经配好，这里再兜底一次，防止某些 ROM 丢掉 flags。
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        Log.d(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString().orEmpty()
        if (pkg != "com.tencent.mobileqq" && pkg != "com.tencent.tim") return
        val cls = e.className?.toString().orEmpty()

        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (isInviteActivity(cls)) {
                    if (!inviteVisible) {
                        inviteVisible = true
                        rejected = false
                        lastLabel = ""
                        val label = readCaller().ifBlank { "QQ 通话" }
                        lastLabel = label
                        CallBridge.get(applicationContext)
                            .notifyAccessibilityCallStarted(label) { clickReject() }
                        Log.d(TAG, "识别到 QQ 来电页 -> $label")
                    } else {
                        updateLabel()
                    }
                } else if (inviteVisible) {
                    // 来电页被接起、拒绝或关闭
                    inviteVisible = false
                    rejected = false
                    CallBridge.get(applicationContext).notifyAccessibilityCallEnded()
                    Log.d(TAG, "QQ 来电页结束：$cls")
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 内容变化事件的 className 往往是子 View，不能拿它判断 Activity；
                // 只要来电页还在，就尝试更新联系人名字。
                if (inviteVisible && !rejected) updateLabel()
            }
        }
    }

    private fun isInviteActivity(cls: String): Boolean =
        cls == QQ_INVITE_ACTIVITY || cls.contains("InviteActivity")

    private fun updateLabel() {
        val label = readCaller()
        if (label.isNotBlank() && label != lastLabel) {
            lastLabel = label
            CallBridge.get(applicationContext).notifyAccessibilityCallUpdated(label)
            Log.d(TAG, "来电人更新 -> $label")
        }
    }

    /** 点击 QQ 来电页上的「拒绝」。由手环按键回调触发。 */
    fun clickReject(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findRejectNode(root)
        if (node == null) {
            Log.d(TAG, "QQ 来电页没找到「拒绝」按钮")
            return false
        }
        val clicked = clickNodeOrParent(node)
        if (clicked) rejected = true
        Log.d(TAG, "点击 QQ 拒绝 -> $clicked")
        return clicked
    }

    private fun findRejectNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isVisibleToUser) {
            val label = buildString {
                node.text?.toString()?.let { append(it) }
                append(' ')
                node.contentDescription?.toString()?.let { append(it) }
            }.trim()
            if (label.isNotBlank() && REJECT_TEXTS.any { label.contains(it) }) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            findRejectNode(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            current = current.parent
            depth++
        }
        // 有些 QQ 版本把点击事件挂在自绘 View 上，isClickable 为 false 但 ACTION_CLICK 仍然有效
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** 从 QQ 来电页读来电人。 */
    private fun readCaller(): String {
        val root = rootInActiveWindow ?: return ""
        val texts = ArrayList<String>()
        collectTexts(root, texts)
        for (raw in texts) {
            extractName(raw)?.let { return it }
        }
        return ""
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return
        if (node.isVisibleToUser) {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), out)
        }
    }

    private fun extractName(raw: String): String? {
        var s = raw.trim()
        if (s.isBlank()) return null
        // 「张三邀请你视频通话」→ 张三
        val inviteIndex = s.indexOf("邀请你")
        if (inviteIndex > 0) s = s.substring(0, inviteIndex).trim()
        s = s.removeSuffix("邀请").trim()
        if (s.isBlank() || s.length > 24) return null
        if (IGNORED_TEXTS.any { s.equals(it, ignoreCase = true) }) return null
        if (s.contains("通话") || s.contains("视频") || s.contains("语音") ||
            s.contains("接听") || s.contains("拒绝") || s.contains("挂断")
        ) {
            return null
        }
        return s
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
