package com.mibandhub.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.mibandhub.app.R
import com.mibandhub.app.ShouhuanApp

/**
 * 「通知使用权」缺失的提醒。
 *
 * 没有这项权限，本 App 收不到任何手机通知，转发链路整条是哑的 —— 而症状
 * 只表现为「手环没动静」，用户很难自己想到是权限问题。所以缺权限时在通知栏
 * 挂一条常驻提醒，点一下直接落到系统的「通知使用权」设置页。
 *
 * [sync] 幂等：有权就撤掉通知，没权就补上（通知是 ongoing + onlyAlertOnce，
 * 反复调用不会反复响）。调用点是 Application 启动和 Activity 每次 onResume，
 * 所以从系统设置授权回来后提醒会自己消失。
 */
object NotifyPermissionReminder {

    /** 本 App 是否拿到了「通知使用权」。 */
    fun hasPermission(context: Context): Boolean {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        }.getOrNull() ?: return false
        if (raw.isBlank()) return false
        val our = ComponentName(context, BandNotificationListener::class.java).flattenToString()
        return raw.split(':').any { it.trim() == our }
    }

    /**
     * 跳到系统「通知使用权」页。不同 ROM（尤其 OPPO / ColorOS）入口不一样，
     * 这里用通用 ACTION，不存在时兜底到应用详情页而不是让点击无反应。
     */
    fun settingsIntent(context: Context): Intent {
        val listener = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        val appDetail = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null),
        )
        val target = when {
            listener.resolveActivity(context.packageManager) != null -> listener
            appDetail.resolveActivity(context.packageManager) != null -> appDetail
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        return target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 按当前权限状态补上 / 撤掉那条提醒通知。 */
    fun sync(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val granted = hasPermission(context)
        // 进程刚起来时系统还在绑定监听器，此刻读到「未绑定」是正常的，
        // 直接报警会变成误报 —— 冷启动宽限期内只处理「压根没授权」这一种。
        val justStarted = System.currentTimeMillis() - processStartAt < PROCESS_GRACE_MS
        val bound = isListenerBound()

        when {
            !granted -> post(manager, context, NOTIFICATION_ID)
            bound -> manager.cancel(NOTIFICATION_ID)
            justStarted -> Unit
            else -> post(manager, context, NOTIFICATION_ID_BOUND_MISSING)
        }
    }

    /** 冷启动时由 [com.mibandhub.app.ShouhuanApp] 记下进程起点，给上面的宽限期用。 */
    fun markProcessStart() {
        processStartAt = System.currentTimeMillis()
    }

    private fun post(manager: NotificationManager, context: Context, id: Int) {
        val missingGrant = id == NOTIFICATION_ID
        val pending = PendingIntent.getActivity(
            context,
            REQUEST_SETTINGS + id,
            settingsIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (missingGrant) {
            "⚠ 手环管家还没拿到通知使用权"
        } else {
            "⚠ 通知转发没生效：系统没有启动监听服务"
        }
        val body = if (missingGrant) {
            "点此打开系统设置授权，否则微信、短信等通知不会转发到手环。"
        } else {
            "权限显示已授权，但系统没有真正启动通知监听（ColorOS 等会在后台管理上拦截）。" +
                "请允许本 App 自启动/后台运行，然后重新打开一次 App。"
        }
        val notification = NotificationCompat.Builder(context, ShouhuanApp.CHANNEL_PERMISSION)
            .setSmallIcon(R.drawable.ic_stat_band)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { manager.notify(id, notification) }
    }

    /** 通知栏提醒的固定 ID：同一条常驻通知，反复 sync 只是覆盖它。 */
    private const val NOTIFICATION_ID = 4201
    private const val REQUEST_SETTINGS = 4202

    /** 「已授权但监听服务没被绑定」用的另一条通知，和「没授权」分开，避免互相覆盖。 */
    private const val NOTIFICATION_ID_BOUND_MISSING = 4203

    /** 冷启动宽限：进程起来这么多时间内，「未绑定」不算异常（系统还在绑定中）。 */
    private const val PROCESS_GRACE_MS = 8_000L

    /** 本进程起点，由 [markProcessStart] 写入。 */
    private var processStartAt = System.currentTimeMillis()

    /**
     * 监听服务有没有真的被系统创建过。
     *
     * 「设置里已授权」和「系统真的把监听服务跑起来了」是两件事：ColorOS 等 ROM
     * 会在后台管理上拦截，导致授权记录为真、监听服务却永远不被绑定，
     * 结果就是一条通知都收不到。所以判断转发能不能用，必须两个都看。
     */
    fun isListenerBound(): Boolean = BandNotificationListener.instantiatedAt > 0L

    /**
     * 打开「自启动 / 后台运行」相关的设置页，返回是否成功打开。
     *
     * 为什么是逐个试而不是返回一个 Intent：ColorOS 的自启动页只允许系统设置
     * 自己调起，第三方 App 拿 Intent 去 startActivity 会被 SecurityException 拒掉
     * —— 而这个异常是在**我们进程里**抛的，用户看到的就是「点一下直接退回桌面」。
     * 所以每个候选都用 runCatching 包住，打不开就换下一个，一个都打不开
     * 才退回应用详情页（至少能进到本 App 的权限/电池页）。
     *
     * 候选顺序：厂商专页 → 系统电池用量 → 本 App 通知设置 → 应用详情。
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val vendor = listOf(
            // ColorOS 自启动管理（部分版本才有）
            "com.oplus.securitypermission/com.oplus.securitypermission.startup.StartupAppListActivity",
            // ColorOS 电池 / 后台耗电管理
            "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
            "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerConsumptionActivity",
        )
        for (name in vendor) {
            val intent = Intent()
            intent.component = ComponentName(name.substringBefore('/'), name.substringAfter('/'))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch(context, intent)) return true
        }

        // 系统「电池用量」页：各 ROM 都有，第三方 App 也能正常拉起。
        if (launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return true

        // 本 App 的通知设置页：先解决「通知使用权」被系统关掉的场景。
        if (launch(
                context,
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        ) {
            return true
        }

        // 最后兜底：应用详情页。
        return launch(
            context,
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }

    /** 试一次跳转：任何异常（含厂商页拒绝被第三方拉起）都不许冒到界面上。 */
    private fun launch(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
