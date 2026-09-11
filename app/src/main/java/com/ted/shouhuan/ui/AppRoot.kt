package com.ted.shouhuan.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ted.shouhuan.ui.device.DeviceScreen
import com.ted.shouhuan.ui.device.DeviceViewModel
import com.ted.shouhuan.ui.device.PairingScreen
import com.ted.shouhuan.ui.heart.HeartRateScreen
import com.ted.shouhuan.ui.heart.HeartRateViewModel
import com.ted.shouhuan.ui.home.HomeScreen
import com.ted.shouhuan.ui.notify.NotifyScreen
import com.ted.shouhuan.ui.sleep.SleepScreen
import com.ted.shouhuan.ui.watchface.WatchFaceScreen
import com.ted.shouhuan.ui.watchface.WatchFaceViewModel

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val Tabs = listOf(
    Tab("home", "首页", Icons.Rounded.Home),
    Tab("heart", "心率", Icons.Rounded.MonitorHeart),
    Tab("sleep", "睡眠", Icons.Rounded.Bedtime),
    Tab("notify", "通知", Icons.Rounded.Notifications),
    Tab("watchface", "表盘", Icons.Rounded.Wallpaper),
    Tab("device", "设备", Icons.Rounded.Watch),
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    // 提到这里创建：一是切 tab 不丢测量状态，二是连接本身是「一条」长连接，
    // 让心率页和以后的设备页共用同一个会话，别各连各的。
    val heartVm: HeartRateViewModel = viewModel()

    // 表盘页单独一条会话：它是一次性的「连上 → 传完 → 断开」，
    // 跟心率那条长连接混在一起只会互相干扰。
    val watchFaceVm: WatchFaceViewModel = viewModel()

    // 设备页的状态提到这里：配对页和设备页要共用同一份配对信息，
    // 在配对页存完回到设备页，那页已经是新数据了，不需要手动刷新。
    val deviceVm: DeviceViewModel = viewModel()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                Tabs.forEach { tab ->
                    val selected = current?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(tab.route) {
                                // 底部导航的标准做法：回到起始页且不堆栈，切换 tab 不叠历史
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(inner),
        ) {
            composable("home") { HomeScreen() }
            composable("heart") { HeartRateScreen(heartVm) }
            composable("sleep") { SleepScreen() }
            composable("notify") { NotifyScreen() }
            composable("watchface") { WatchFaceScreen(watchFaceVm) }
            composable("device") {
                DeviceScreen(deviceVm, onPair = { nav.navigate("pairing") { launchSingleTop = true } })
            }
            // 配对页不是 tab，从设备页推上来；存完就 pop 回去。
            composable("pairing") { PairingScreen(deviceVm, onDone = { nav.popBackStack() }) }
        }
    }
}
