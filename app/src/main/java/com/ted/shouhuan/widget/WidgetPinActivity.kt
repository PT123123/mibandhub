package com.ted.shouhuan.widget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.ted.shouhuan.MainActivity

/**
 * 「长按应用图标 → 添加桌面控件」的落点（res/xml/shortcuts.xml 里的静态快捷方式指向这里）。
 *
 * 为什么需要它：桌面长按菜单只认静态快捷方式，而把控件钉到桌面必须由前台 Activity 发起
 * `AppWidgetManager.requestPinAppWidget`（AppWidgetProvider 只是广播接收者，发不了这个请求）。
 * 所以用这个全透明、无布局的中转页把两头接起来：进来就固定主控件，随即结束，用户看到的是
 * 桌面弹的位置选择界面。
 *
 * 澎湃OS/MIUI 上 requestPinAppWidget 还受「桌面快捷方式」权限（应用信息 → 权限管理 →
 * 其他权限）管辖：没开时不弹位置选择、控件也落不到桌面。所以进来先请求这个权限
 * （[hasShortcutPermission]），授权后再钉；拿不到固定窗口时（桌面不支持，或权限仍没开），
 * [pinWidgetToHome] 会弹出正确的手动路径，我们再顺手把应用打开，别让用户对着没反应的桌面猜。
 */
class WidgetPinActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isMiui() && !hasShortcutPermission(this)) {
            requestPermissions(
                arrayOf(MIUI_HOME_SHORTCUT_PERMISSION, LAUNCHER_SHORTCUT_PERMISSION),
                REQ_SHORTCUT_PERMISSION,
            )
        } else {
            attemptPin()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SHORTCUT_PERMISSION) {
            // 无论授权与否都尝试固定；没开的话 pinWidgetToHome 会提示手动路径。
            attemptPin()
        }
    }

    private fun attemptPin() {
        val pinned = pinWidgetToHome(
            context = this,
            provider = SleepDetailWidgetProvider::class.java,
            hintManualPathOnMiui = true,
        )
        if (!pinned) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    private companion object {
        const val REQ_SHORTCUT_PERMISSION = 1001
    }
}
