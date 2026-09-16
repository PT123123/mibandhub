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
 * 拿不到固定窗口时（桌面不支持，或小米的「桌面快捷方式」权限没开），[pinWidgetToHome] 会
 * 弹出正确的手动路径，我们再顺手把应用打开，别让用户对着没反应的桌面猜。
 */
class WidgetPinActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}
