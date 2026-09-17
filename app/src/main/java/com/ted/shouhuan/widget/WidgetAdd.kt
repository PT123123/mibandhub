package com.ted.shouhuan.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * 主动把控件固定到桌面（API 26+ 的 requestPinAppWidget）。
 *
 * 为什么需要它：桌面「长按应用图标」菜单里的控件区只给接了小米小部件体系的 App 留位，
 * 第三方 App 的长按菜单里只有快捷方式；桌面自己那份小部件列表又常按包名缓存，
 * 新装/更新的控件刷不出来。requestPinAppWidget 走系统 AppWidgetService 的固定流程，
 * 从应用里直接把控件钉到桌面，绕开这两个坑。
 *
 * 小米/红米/澎湃OS 上另有两处必须注意：
 *  - 它受「应用信息 → 权限管理 → 其他权限 → 桌面快捷方式」这个特殊权限管辖。权限没开
 *    时调用不弹固定界面、控件也落不到桌面 —— 光在 manifest 里声明权限不够，必须在
 *    运行时请求（见 [hasShortcutPermission] / DeviceScreen、WidgetPinActivity 里的
 *    请求逻辑），请求被拒后引导手动打开。
 *  - 小米小部件技术规范 §四.1 说带 `addType=appWidgetDetail` 的 extras 会去调「小米Widget
 *    商店详情页」，而详情页只列**通过小米审核上架**的组件；侧载 App 传了这套 extras 只会
 *    把用户丢进一个空页面。所以这里一律按原生方式调用，不传那套 extras。
 *
 * @param hintManualPathOnMiui 小米上先把手动路径提示一遍。小米桌面有时候既不弹界面也不落
 *   控件（权限没开），提前把路径给用户，好过让他对着没反应的桌面猜。
 * @return 系统接受了这次固定请求。true 不等于控件一定已经在桌面上 —— 桌面还可能弹二次确认。
 */
fun pinWidgetToHome(
    context: Context,
    provider: Class<out AppWidgetProvider>,
    hintManualPathOnMiui: Boolean = false,
): Boolean {
    if (hintManualPathOnMiui && isMiui()) {
        Toast.makeText(context, MIUI_MANUAL_HINT, Toast.LENGTH_LONG).show()
    }
    val manager = AppWidgetManager.getInstance(context)
    if (!manager.isRequestPinAppWidgetSupported()) {
        Toast.makeText(context, manualHint(), Toast.LENGTH_LONG).show()
        return false
    }
    val ok = manager.requestPinAppWidget(ComponentName(context, provider), null, null)
    if (!ok) {
        Toast.makeText(context, manualHint(), Toast.LENGTH_LONG).show()
    }
    return ok
}

/** 澎湃OS 桌面包名（com.miui.home）的「桌面快捷方式」权限。老 MIUI/第三方桌面认下面那个。 */
const val MIUI_HOME_SHORTCUT_PERMISSION = "com.miui.home.permission.INSTALL_SHORTCUT"

/** 原生 launcher 的「桌面快捷方式」权限（MIUI 上显示成「其他权限」里的同一个开关）。 */
const val LAUNCHER_SHORTCUT_PERMISSION = "com.android.launcher.permission.INSTALL_SHORTCUT"

/**
 * 「桌面快捷方式」权限是否已授予。
 *
 * 澎湃OS/MIUI 上它是特殊运行时权限，manifest 声明只是让开关出现在
 * 应用信息 → 权限管理 → 其他权限，必须再运行时请求（或在设置里手动开）才有值。
 * 两个权限字符串对应同一个开关，任一授予就算开。非小米桌面不需要它，直接放行。
 */
fun hasShortcutPermission(context: Context): Boolean {
    if (!isMiui()) return true
    return ContextCompat.checkSelfPermission(context, MIUI_HOME_SHORTCUT_PERMISSION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, LAUNCHER_SHORTCUT_PERMISSION) ==
        PackageManager.PERMISSION_GRANTED
}

/** 打开本应用的系统设置页 —— 小米的「桌面快捷方式」权限就藏在这里面。 */
fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

/** 当前桌面是不是小米那套（MIUI / 澎湃OS：小米、红米、POCO 三家共用一套桌面行为）。 */
fun isMiui(): Boolean {
    val brand = Build.BRAND.lowercase()
    val manufacturer = Build.MANUFACTURER.lowercase()
    return brand in MIUI_BRANDS || manufacturer in MIUI_BRANDS
}

private val MIUI_BRANDS = setOf("xiaomi", "redmi", "poco")

/** 桌面上手动加控件的那条路：小米走双指捏合，别家走长按空白处。 */
private fun manualHint(): String = if (isMiui()) MIUI_MANUAL_HINT else OTHER_MANUAL_HINT

private const val MIUI_MANUAL_HINT =
    "没能自动添加：请在桌面双指捏合（或长按空白处）→ 添加小部件 → 找「手环管家」，" +
        "没有就滑到最底部的「安卓小组件」。红米还要在 应用信息 → 权限管理 → 其他权限 " +
        "里打开「桌面快捷方式」。"

private const val OTHER_MANUAL_HINT =
    "当前桌面不支持在应用内固定控件：请长按桌面空白处 → 小部件 → 找到「手环管家」。"
