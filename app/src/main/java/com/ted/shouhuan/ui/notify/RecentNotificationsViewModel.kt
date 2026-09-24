package com.ted.shouhuan.ui.notify

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ted.shouhuan.data.AppRule
import com.ted.shouhuan.data.BandNotification
import com.ted.shouhuan.data.BandPrefs
import com.ted.shouhuan.data.InstalledApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 「最近推送」浏览页的 ViewModel。
 *
 * 数据来源是 [BandPrefs.recentNotifications]（存了最多 2000 条），
 * 全部一次加载进内存后做本地筛选/统计，不撑 DataStore。
 *
 * @param parentVm 通知页的父 ViewModel，用于查询已加载的应用列表来做快捷操作。
 */
class RecentNotificationsViewModel(
    app: Application,
    private val parentVm: NotifyViewModel,
) : AndroidViewModel(app) {

    private val prefs = BandPrefs(app)

    /**
     * 全部通知，新的在前。
     * UI 用这个做原始列表展示。
     */
    val allNotifications: StateFlow<List<BandNotification>> =
        prefs.recentNotifications.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 按应用统计：应用名 → 该应用的推送条数。
     * 用于浏览页顶部的「哪些应用推送了多少条」。
     */
    val appStats: StateFlow<List<AppStat>> = allNotifications.map { list ->
        list.groupBy { it.appName }
            .map { (appName, notifications) ->
                AppStat(
                    appName = appName,
                    count = notifications.size,
                    packageName = notifications.firstOrNull()?.packageName ?: "",
                )
            }
            .sortedByDescending { it.count }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 当前选中的应用过滤器。null = 全部显示。
     */
    private val _selectedApp = MutableStateFlow<String?>(null)
    val selectedApp: StateFlow<String?> = _selectedApp.asStateFlow()

    /**
     * 过滤后的通知列表（选中的应用或全部）。
     */
    val filteredNotifications: StateFlow<List<BandNotification>> = combine(
        allNotifications,
        _selectedApp,
    ) { all, selected ->
        if (selected == null) all else all.filter { it.appName == selected }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun selectApp(appName: String?) {
        _selectedApp.value = appName
    }

    /** 来自父级 ViewModel 的已安装应用列表（用于快捷加名单反查）。 */
    val installedApps: StateFlow<List<InstalledApp>> = parentVm.installedApps

    /** 来自父级 ViewModel 的应用规则（当前白/黑名单内容）。 */
    val appRules: StateFlow<List<AppRule>> = parentVm.appRules

    /** 父级 ViewModel 的名单模式。 */
    val blacklistMode: StateFlow<Boolean> = parentVm.appFilterBlacklist

    /** 快捷加白/黑名单（委托给父级 VM）。 */
    fun quickAddToList(packageName: String, appName: String) {
        if (blacklistMode.value) {
            parentVm.addToBlacklist(packageName, appName)
        } else {
            parentVm.addAppRule(packageName, appName)
        }
    }

    /** 解析通知条目的包名：优先用自带字段，找不到再按应用名反查。 */
    fun resolvePackageName(item: BandNotification): String? = parentVm.resolvePackageName(item)
}

/** 按应用统计的一条记录。 */
data class AppStat(
    val appName: String,
    val count: Int,
    val packageName: String,
)
