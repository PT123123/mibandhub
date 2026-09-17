# 手环管家（shouhuan）

自己写的小米手环管理客户端。界面自己设计，核心是**睡眠、通知、心率**三件事。

技术栈 **Kotlin + Jetpack Compose**，首版目标是打通最小闭环：
**配对连接 → 实时心率 → 睡眠数据 → 手机通知转发到手环**。

## 仓库构成

| 路径 | 内容 |
|---|---|
| `app/` | **Android App 主体**（Kotlin + Compose） |
| `app/src/main/java/com/ted/shouhuan/ui/` | 界面：首页 / 心率 / 睡眠 / 通知 / 表盘 / 设备 |
| `app/src/main/java/com/ted/shouhuan/ble/` | BLE 连接层：连接、服务发现、串行写队列 |
| `app/src/main/java/com/ted/shouhuan/proto/` | 协议层：认证握手、心率指令、电量、表盘下发 |
| `app/src/main/java/com/ted/shouhuan/data/` | 本地存储（密钥/MAC）与数据模型 |
| `app/src/main/java/com/ted/shouhuan/service/` | 前台服务 + 通知监听 |
| `xiaomi_authkey.py` / `parse_log.py` | 取 AuthKey 的工具（配对时要用，见文末附录） |
| `tools/` | adb 直连取密钥、装机、打包等脚本 |

## 快速上手：把密钥填进去就能用

App 连手环只需要两样东西：**手环的 MAC** 和 **AuthKey**（32 位十六进制，等同于手环的控制权）。
两个都不用抄 —— 官方 App 的日志里就是明文，工具直接读出来。

### 第 1 步：取 AuthKey

三条路，从省事到麻烦：

| 方式 | 前提 | 命令 | 说明 |
|---|---|---|---|
| **adb 直读**（推荐） | 手机能连电脑的 adb | `just fetch` | **手机端零操作**。官方 App 一启动就把密钥写进日志，从 adb 拉下来解析即可 |
| 手动解析日志 | 手上有日志文件 | `python parse_log.py XiaomiFit.device.log` | 没有电脑时用：MT 管理器把日志导出到 Download 再跑 |
| 登录小米接口 | 只有账号密码 | `python xiaomi_authkey.py -e 你的账号` | 完全不碰日志；会触发风控，别连续重试 |

`just fetch` 的输出长这样，`0x` 开头那串就是 AuthKey：

```
命中 0：huamiAuthKey
  设备   ：小米手环5  (hmpace.bracelet.v5)
  AuthKey：0x0123456789abcdef0123456789abcdef
  MAC    ：AA:BB:CC:DD:EE:FF
```

### 第 2 步：在 App 里配对

1. 底部 **「设备」页** → 点「填 MAC 与 AuthKey」（已配对过则是「重新配对」）；
2. 名称随便起，MAC 与 AuthKey 直接粘 —— `0x` 前缀、冒号、横杠、大小写都不挑，
   位数对上就行（界面上会实时告诉你哪里不对）；
3. 保存后回到设备页点「连接手环」，出现「已连接」和电量就通了。

> **换手机不用重新取密钥。** AuthKey 绑的是「小米账号 + 手环」，不绑手机 ——
> 同一个小米账号下换机，把原来的 MAC 和 AuthKey 原样填回来就能连。
> 只有换了账号、或此前解绑过，才需要重新取一次。

> 手环可能同时被多个 App 连着，但指令谁先到谁说了算 —— 认证老是超时的话，
> 先在「小米运动健康」里断开它再试。设备页的连接只为「连一次看电量」，离开该页会自动断开。

密钥怎么存的：只写进应用私有目录（和 Gadgetbridge 一样），不上云、不导出，卸载即清除。
「忘记此设备」会删掉 MAC / AuthKey，但**保留心率测量记录**。

更细的路线说明、排障与实测结论见文末 **附录：AuthKey 获取工具**。

## 当前进度

- [x] 工程骨架 + Compose 界面（6 个页面，深色优先）
- [x] 协议调研：对着 Gadgetbridge 源码核对 UUID / 认证握手 / 心率指令 / 电量格式
- [x] BLE 连接层 + **两步**认证状态机（authFlags 用真机实测定成 `0x00`，见下表）
- [x] `assembleDebug` 出包，真机安装并冷启动
- [x] 心率页接上真实会话：连接 → 认证 → 单次测量，全过程有状态提示与失败原因
- [x] 真机连上手环、读到实时心率（76 bpm）
- [x] 心率页测量明细：读数 + 测量时刻（精确到秒）+ 耗时，并保留最近 20 次测量记录
- [x] 配对界面：手工填 MAC / AuthKey（输入容错 + 逐字段校验），存本地、可忘记；
      设备页读的是真实配对信息与真实连接状态，不再用演示数据
- [x] 睡眠数据拉取 —— **真机全流程打通（2026-09-12/13）**：设备页「同步手环数据」
      拉手环保留的全部明细，解析成睡眠夜入库，睡眠页/首页展示正常。
      走华米两条私有特征（`00000004` 控制 / `00000005` 数据），流程：

      ```
      手机 → 04: 01 01 <年 u16le> <月> <日> <时> <分> 00 <时区>   从某时刻起要数据
      手环 → 04: 10 01 01 <采样数 u32le> <起始时间 8B>
      手机 → 04: 02（并订阅 05）                                  开始传
      手环 → 05: <计数器 1B> <采样数据 …>                          一批批推
      手环 → 04: 10 02 01                                         传完了
      （04: 03 ACK —— GB 发它让手环删掉已传数据；本项目**从不发**，见下）
      ```

      **2026-09-13 起同步改为「只读」**：不再发 04: 03 ACK，数据同步后**留在手环上**
      （此前 ack 即删，拉完就没了；小米运动健康的后台同步也会消费掉数据，我们这边
      再抢就只剩零头 —— 睡眠总拉不到主要就是这个时序问题）。不 ack 的代价是同一
      窗口每次都会原样重推，入库端幂等去重：睡眠夜按醒来日取分钟数多的一条
      （窗口边界可能把一晚切成半截，半截的不能覆盖完整的），心率分钟样本按时刻去重。
      配套调整：**打开 app 自动拉近 7 天**（BandService 在 onStartCommand/认证通过后
      触发，10 分钟去抖，静默执行、心率测量时让路），设备页「同步手环数据」仍拉
      十年全量（约 15~30 天留存），两者共用 BandSession 的取数互斥锁不会叠加。

      真机实测：手环声明 1293 个采样 → 收到 10344 字节，`10344 ÷ 8 = 1293`
      **一个不差**。两个踩坑（都是打印原始字节才看出来的）：

      - **采样是 8 字节，不是 4**。Gadgetbridge 对 Mi Band 5 用 4
        （`mActivitySampleSize` 默认值，只有 Amazfit GTR/GTS 设成 8），照它抄会错位。
        布局对齐 `HuamiExtendedActivitySample`：
        `kind, intensity, steps, heartRate, unknown1, sleep, deepSleep, remSleep`。
      - **`10 02 04` 不是「传完了」**。第一版把它当结束信号，只拿到 240 字节就收工；
        实际数据是它**之后**才开始推的。完成条件是「收够声明的采样数」或推流安静下来。

      kind → 分期（来自 `HuamiExtendedSampleProvider`）：`120` = 睡眠，
      再用 `deep&127 > 42` / `rem&127 > 55` 细分成深睡 / REM，否则浅睡；
      `115`/`118` = 未佩戴，`64` = 跑步。数据里的 `0x80` 是「无数据」，GB 会 `&127` 抹成 0。

      实验台：`app/src/debug/java/com/ted/shouhuan/debug/ActivityLab.kt`。

      两个后来踩掉的坑（详见提交 1c6010e）：**「样本包序号跳变」的根因不在 BLE
      层，在应用自己的接收管道** —— SharedFlow 缓冲满静默丢包 + 每包重新订阅
      `first{}` 的间隙漏包；改成专用无丢失 Channel + 轮询取包后，同一条链路
      286 包零跳变。**起始应答里的数是采样条数不是字节数**（真机两次对上：
      2738×8 = 21904、8568×8 = 68544），照字节用会让进度条提前「收满」。
- [ ] 通知转发到手表
- [x] 表盘下发 —— **已真机验证（2026-09-12）：内置表盘下发后手环成功换上。**
      另有**在线表盘市场**：表盘页「进入市场」→ 浏览带预览的表盘（本仓库
      `market/` 目录，GitHub raw 分发）→ 下载到本机 → 进「我的表盘」一键下发。
      只收自制 CC0 内容；生成器在 `tools/watchfaces/make_market.py`。
      上游没有可抄的实现：Gadgetbridge 对 Mi Band 5 不支持表盘安装/切换
      （`MiBand5Coordinator` 继承的 `supportsAppsManagement` 默认 false，
      整个 huami 目录下只有 Zepp OS 设备才有 `PREF_WATCHFACE`）。
      于是照 `UpdateFirmwareOperationNew`（`MiBand5Support extends MiBand4Support`，
      Mi Band 5 用的就是它）的字节自己试。实测结果：

      | 步骤 | 结果 |
      |---|---|
      | 固件通道是否存在（`1531` 控制 / `1532` 数据） | ✅ 两个特征都在 |
      | 表盘元数据 `01 08 <size u32le> <crc32 u32le>` | ✅ 手环回 `10 01 01 …`（11 字节） |
      | 数据流（每 100 包插一条 `00` 到 `1531`） | ✅ 12119 包 / 242371 字节推完 |
      | 收尾 `00` | ✅ 手环回 `10 03 01`「数据齐了」 |
      | 校验命令 `04` | ❓ 收到的是没记录过的 `10 20 08` / `10 20 00`，**不是** `10 04 01` |

      也就是说**通道完全打通、包体也被完整接收**，卡在最后一步的校验语义上，
      表盘是否真的生效还没确认 —— 这正是页面上标「实验性」的原因。
      包体格式来自官方 App 的缓存：`files/WatchFace/data.zip`，
      里面是 160 张 `face_data_<风格>_<布局>_<序号>.png`。

      页面现在带**三张内置表盘**（`app/src/main/assets/watchfaces/`）：
      社区表盘 Digital Codex（gryffyn 作，CC BY-NC-SA 4.0，来自
      amazfitwatchfaces #5183），加两张用 watchface-js 自制的极简表盘（CC0）。
      它们是真正的华米 `.bin` 容器（UIHH 文件头）。用真 `.bin` 复测后收尾
      仍是 `10 20 00` —— 2026-09-12 对照 Gadgetbridge master（现已支持
      Mi Band 5 表盘安装）逐字节比对，找到两处协议差异并修复：
      ① 表盘上传前缺一条「选表盘槽」命令（`39 00 00 ff ff ff <表盘ID>`，
      写到 `00000003-…` 配置特征）；② 收尾校验应是 `04 <crc16 u16le>` 而
      不是裸 `04`。修正后真机下发内置表盘：手环回 `10 04 01`，表盘成功换上。
      细节见 `docs/watchface.md` §2.1。

      **下发必须限速**（`BandSession.PACKET_INTERVAL_NANOS`，6 ms/包 ≈ 166 包/秒）：
      手环的接收缓冲有限，而每秒能灌多少取决于当时协商的 BLE 连接间隔。
      全速写（实测约 300 包/秒）会在 4000 包上下被整包打回；匀速推就能跑完。
      这里是**真机跑生产代码发现的** —— 实验台那次刚好跑在较慢的连接参数上，
      所以看起来正常，换成应用里这条路立刻就暴露了。

### 外部数据源：健康连接（Health Connect / 小米运动健康）

手环只留最近 15~30 天的分钟明细（本 app 同步只读不删，但手环自己的留存期一到
就滚掉），更早的历史只能靠外部来源补。App 的外部数据源就是系统健康连接：
设备页 → 数据同步 → **从健康连接导入**，读 `SleepSessionRecord`（会话 + 分期），
口径与手环同步一致（总时长只含深/浅/REM，不足 30 分钟的碎片丢弃）；手环没有的
评分用同一个 `ActivitySync.sleepScore` 计算，两个来源的分数可比。

**2026-09-13 真机实测结论（小米运动健康国内版 / HyperOS）**：

| 检查点 | 结果 |
|---|---|
| 小米运动健康里的「同步到健康连接」开关 | ❌ **不存在**。App 设置（地区/教练/云服务/双接授权/通知/缓存）和「三方数据管理」（Zepp Life/微信/支付宝/小米电视/北大国际）里都没有 |
| 小米运动健康声明的健康连接写入权限 | ✅ 有：`WRITE_SLEEP` / `WRITE_HEART_RATE` / `WRITE_STEPS` 等 11 项（`dumpsys package com.mi.health` 可见），初始全部 `granted=false` |
| 健康连接 App 本体 | ⚠️ 一直停在「开始使用」引导页 —— 从未初始化。这是「导入为 0」的前提缺口，得先走完引导 |
| 从健康连接侧反向授权 | ✅ 可行：健康连接 → 应用权限 → 小米运动健康 → 全部允许，全部写入开关（含睡眠）都能打开 |
| 授权后小米运动健康是否真的写入 | ❌ 实测触发刷新后健康连接里仍无睡眠数据 —— 写入时机锁在小米自己的版本开关里，第三方 App 无从强制 |

**结论**：权限侧我们能做的都做完了，写不写等小米版本放开（国际版 Mi Fitness
有该功能）。健康连接里一旦出现睡眠会话，「从健康连接导入」当场就能补回历史。
设备页的两个跳转按钮（**打开健康连接** / **打开小米运动健康**）就是替你省掉
上面这些排查的；表盘市场同理补了「下载完卡片上直接安装」。

另外一个接健康连接的硬性要求，踩过一次：接入方 manifest 必须声明「数据用途
说明」入口（Android 13- 的 `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`
/ 14+ 的 `VIEW_PERMISSION_USAGE` + `HEALTH_PERMISSIONS` 别名），缺了建连直接
`IllegalStateException: incorrect health permission states` —— 授权对话框能弹、
权限能授，但一读取就挂。本项目用 `HealthRationaleActivity` 满足。

### 桌面控件：昨晚睡眠（主，2×2）/ 1×2 睡眠+心率 / 1×1 睡眠时长

三个桌面小部件（`com.ted.shouhuan.widget`），**走标准 RemoteViews AppWidget**
—— 只用 LinearLayout/TextView/ImageView/ProgressBar 这类远程视图白名单控件，
不依赖任何厂商私有接口：

| 控件 | 内容 | 尺寸声明 | 添加入口 |
|---|---|---|---|
| 昨晚睡眠 | 时长 + 入睡→醒来；拉大显示得分、分期占比条、夜间清醒 | 2×2 起步（`minWidth/Height=110dp`，小米小部件规范的认可尺寸），可拉到 1×2 / 4×4 | **长按应用图标菜单**（唯一配了 `miuiWidget` 标识的控件）、应用内「添加到桌面」 |
| 睡眠与心率 | 上半是睡眠块，下半是近 24 小时心率曲线（当前 bpm + 曲线里的低/高角标） | 1×2 格（1 列宽 × 2 行高） | 桌面「安卓小组件」、应用内「添加到桌面」 |
| 睡眠时长 | 最近一晚睡眠时长（醒来那天是今天标「昨晚睡眠」，否则直接标日期），点了开应用 | 1×1 格（`targetCellWidth/Height=1/1`，12- 的桌面按 `minWidth/Height=40dp` 落格） | 同上 |

#### 「长按应用图标 → 添加控件」为什么不生效（2026-09-17 查清）

澎湃OS/MIUI 长按图标菜单里那块**控件预览只收录「小米小部件」**——判定依据是 manifest 里
有没有小米小部件标识（`<meta-data android:name="miuiWidget" android:value="true"/>`，
小米小部件技术规范 §4），侧载 App 不写这个标识就永远进不了那一格。之前试过的
「补 `com.android.launcher.permission.INSTALL_SHORTCUT` 系列权限」是无效尝试：那批权限
管的是控件/快捷方式**钉到桌面**这一步，跟能否被列出来无关（列不列由 AppWidgetService
里的 provider 注册状态决定）。现在做了三件事：

1. **主控件按小米小部件规范配齐**：`miuiWidget` 标识 + 小米认可的尺寸（2×2 / 4×2 / 4×4，
   1×1 这种非标尺寸进不了体系，§3）+ 根布局 `@android:id/background`（§7，系统靠这个固定
   id 统一加圆角，并要求根布局有非全透明背景）+ label 2~8 汉字且不等于应用名（§12.5）。
2. **加静态快捷方式**（`res/xml/shortcuts.xml`）：长按应用图标 → 「添加桌面控件」，点一下由
   无界面中转页 `WidgetPinActivity` 走 `requestPinAppWidget` 把主控件钉到桌面。长按菜单只认
   静态快捷方式，这是第三方 App 自己能给的那条入口（Widgets 是广播接收者，发不了固定请求，
   必须由前台 Activity 发起，所以中间需要这个中转页）。
3. **应用内卡片保留三个「添加到桌面」**，小米/红米上额外提示真实路径（桌面双指捏合 →
   添加小部件 → 手环管家 / 底部「安卓小组件」）以及**应用信息 → 权限管理 → 其他权限 →
   桌面快捷方式**这个特殊权限：红米上它关着时 `requestPinAppWidget` 不会落控件。
   故意**不**传小米规范的 `addType=appWidgetDetail` extras —— 那会去调「小米Widget 商店
   详情页」，而详情页只列通过了小米审核上架的组件，侧载 App 传了只会把用户丢进空页面。

另外，小米的刷新广播 `miui.appwidget.action.APPWIDGET_UPDATE`（展现刷新）在
`BandWidgetProvider` 里接住了：原生 `AppWidgetProvider` 只认
`android.appwidget.action.APPWIDGET_UPDATE`，不接这条，澎湃OS 桌面刷新控件时发的广播会被
丢掉，控件只能干等 30 分钟周期或下一次同步。

实现要点：

- **心率曲线是现画的位图**。分钟样本按像素列分桶取均值（1440 点压到控件宽度），
  纵轴按窗口内低/高动态收放（跨度不足 30 bpm 撑满，免得平稳的睡眠心率抖成锯齿），
  断采超过半小时直接断线（摘下手环的时段不伪造数据）；拖拽缩放触发
  `onAppWidgetOptionsChanged` 后按新宽度重画，曲线不会被拉伸。
- **心率分钟序列开始落盘**：同步样本里的 `heartRate > 0` 分钟以
  `epochMillis,bpm` 存进 DataStore（`heart_rate_series`，留 3 天，曲线只画 24 小时）。
  手环那边要把「心率监测」开着才有连续分钟值，没数据控件显示「暂无心率数据」。
- **刷新时机**：手环同步完成（设备页手动或打开 app 自动拉取）/ 健康连接导入完成 /
  开机广播，外加 `updatePeriodMillis` 30 分钟的兜底（跨零点换「昨晚」、窗口滑动都靠它）。
  控件刷新统一走 `WidgetRenderer.updateAll`，广播侧用 `goAsync` 包住，
  不在主线程阻塞读 DataStore。
- 文字用平台 autoSize（minSdk 26 原生支持，远程视图里可用），五列网格的窄格子
  里「7小时12分」会自动缩字，不会截断；卡片底是近黑半透明圆角，深浅壁纸共用。

## 构建与装机

```bash
just build            # 全项目：Python 校验/自检/测试/打包 + 编译 APK
just install          # 自动判断装什么（adb 设备 → APK；Termux → 小部件；否则 → 命令行入口）
```

只想跑一半、或者要单独装某一样时：

```bash
just build apk        # 只编译 APK
just build tools      # 只跑 Python 那套
just install apk      # 只装不编译：把现成的 debug APK 装到 adb 连接的手机
just install cli      # 只装 xiaomi-authkey / parse-log 命令行入口
just apk release      # 单独编 release（不出正式分发包；分发走 just release）
just android-clean    # 清理 Android 构建产物
```

给人装 / 要在手机上长期用、还想在线升级时，别用上面这套，走 `just release`
（正式签名 + GitHub Release + Obtainium），见下一节。

> **构建默认走离线**。本机 `dl.google.com` 不可达、`maven.google.com` 超时，让 gradle
> 在线解析依赖会长时间空转（实测一次 `assembleDebug` 卡了 26 分钟、一个文件都没产出）。
> 依赖都在本地缓存里，离线跑 1 分多钟就出包。改过依赖版本后才需要 `just deps`
> （或 `ONLINE=1 just build`）联网拉一次。
>
> 另外**别把 `just build` 接进管道**（`| tail`、`| grep`）：gradle 守护进程会攥着管道写端，
> 构建早就 `BUILD SUCCESSFUL` 了命令行还挂着不返回。要看进度就
> `tail -f app/build/gradle.log`。

> 国产 ROM 常拦 `adb install`（报 `INSTALL_FAILED_USER_RESTRICTED`）。
> `tools/android_install.sh` 做了三级降级：`adb install` → `pm install` → 调起系统安装器 UI，
> 最后一级需要你在手机上点一下「安装」。

## 正式版分发（GitHub Release + Obtainium）

给手机/平板长期用的那个包走 GitHub Release，配 [Obtainium](https://github.com/ImranR98/Obtainium)
在线升级 —— 不用每次改版本都重新 adb 装机。

**永久直链**（每发一版自动指向最新，Obtainium 少配置一项就靠它）：

    https://github.com/PT123123/mibandhub/releases/latest/download/shouhuan.apk

### 为什么必须用正式 key

`debug` 包签的是 `~/.android/debug.keystore`，**每个 JDK 安装/每台机器都会重新生成**。
拿 debug 包分发，换个环境发下一版就是「同包名、不同签名」——用户点安装直接
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`，只能卸载重装（配对信息全丢）。

所以正式包用仓库外备份的那把 key：

| 东西 | 位置 |
|---|---|
| 密钥 + 口令（工作副本） | 仓库外的密钥库文件（alias `androiddebugkey`）+ 仓库根 `keystore.properties`，口令等参数都写在这个文件里，二者均被 `.gitignore` 覆盖 |
| 备份（唯一无法补救的资产） | 仓库外的密钥库目录（keystore 本体，另在密码管理器存一份） |
| 证书 SHA256 | `E0:BB:84:3A:91:92:A9:57:97:27:BE:09:08:35:40:A2:77:B1:5B:AC:96:7D:49:CF:26:A8:F3:0D:75:0A:64:00` |

**这把 key 丢了 = 所有已安装的设备以后都装不上更新**，只能卸载重装。请另外往密码管理器里存一份。
`keystore.properties` 不存在时 gradle 依然能编 debug / 跑测试，只是出不了可分发的 release 包
（刻意**不**回退 debug key —— 那样出的包能直装、看着一切正常，换台机器才暴雷）。

### 发一版

```bash
just release bump     # versionCode+1、versionName 末段+1（发新版必做，否则用户装不上）
just release          # assembleRelease → dist/shouhuan.apk(+留档副本) → 自检签名/包名/版本
git push              # 代码先上去（publish 会拦下没 push 的情况）
just release publish  # 打 tag v<版本> + gh release create + 真下载一次比 sha256
```

`just release` 的自检是硬门槛：**只认证书 SHA256 白名单里的正式 key**（仓库外备份的那把固定 keystore）、校验包名与
`versionCode/versionName` 和 `build.gradle.kts` 一致、包不能比最后一次提交旧。

`just release publish` 的四道闸：① 工作区必须干净 ② HEAD 必须已 push
③ 同名 tag 不能指向别的 commit ④ 发布后真去拉一次永久直链比对 sha256。
它每条 `gh` 命令都显式带 `-R PT123123/mibandhub` —— 仓库里一旦出现 `upstream` remote，
`gh` 会优先往 upstream 发（报 `422 target_commitish is invalid`），不能让它自己猜。

### 资产命名（别改）

Release 上挂两份同名内容：`shouhuan.apk`（固定名，直链和 Obtainium 认它）+
`shouhuan-<版本>.apk`（留档）。**固定名是刻意的** —— `releases/latest/download/<name>`
按资产名精确匹配，名字里带版本号的话每发一版都得改配置。

### Obtainium 配置

1. 添加应用 → 选 **GitHub**，仓库地址填 `https://github.com/PT123123/mibandhub`
2. 版本号正则、APK 筛选正则都填 `^shouhuan\.apk$`，压实只认固定名那一份
3. 「包含预发布」不用开

> **首次从 debug 包切过来**：两者包名都是 `com.ted.shouhuan`，签名不同 → 装不上，
> 需要先卸载旧版再装（配对信息要重填一次，用 `just fetch` 拿 AuthKey 粘进配对页即可）。
> 之后 Obtainium 就能一直覆盖升级。装了正式包以后，`just app-install`（debug）会因签名冲突失败，
> 要本地调试就先卸正式包。

## 协议要点（都从 Gadgetbridge 源码核对过，不是凭记忆写的）

| 项目 | 值 |
|---|---|
| MiBand 服务 | `0000fee0-…` / `0000fee1-…` |
| 认证特征 | `00000009-0000-3512-2118-0009af100700` |
| 心率服务 | 标准 GATT `0x180D`，测量 `0x2A37`(notify)、控制点 `0x2A39`(write) |
| 心率指令 | 连续 `15 01 01` / 手动 `15 02 01`（首字节 `0x15` 固定） |
| 认证 | **两步** AES-128-ECB：`82 00 02 01 00` → `10 82 01 <挑战值>` → `83 00 <AES(挑战值)>` → `10 83 01`。`cryptFlags=0x80`，**`authFlags` 必须是 `0x00`**（Gadgetbridge 用的 `0x08` 在本机固件上会被回 `10 83 07` 拒掉） |
| 电量 | 华米特征 `00000006-…`，`byte[1]` 是百分比 |
| 睡眠 | 活动明细 fetch（`00000004` 控制 / `00000005` 数据）里的 8 字节样本：`kind==120` 为睡眠分钟，深/REM 字节 `&127` 后按阈值分级（>42 深睡 / >55 REM）；**不走** chunked 通道 |

---

# 附录：AuthKey 获取工具

手环的 AuthKey 是连接的前提（没有它认证过不去，手环会直接断开）。
以下是配对时用得上的工具，入口都在仓库根目录的 Python 脚本里。

> **2026-09-11 真机实测结论（重要）**
>
> 在某红米机型 / HyperOS / Android 15 上，用 **adb 直读日志**把三台设备的
> AuthKey 全部取到了，**全程手机端零操作**。这比计划书的路线 A（MT 管理器 + SAF 选文件）
> 简单得多，而且**不需要**手动触发「导出日志」—— 官方 App 一启动就把密钥明文写进
> `XiaomiFit.device.log`。见《路线 A′：adb 直读》。
>
> 实测拿到的字段名是 **`huamiAuthKey`**（不是计划书说的 `encryptKey`）。
> `encryptKey` 确实存在，但要手动触发「导出日志」才出现；`huamiAuthKey` 则一直有。

---

## ⚠️ 先说最重要的一件事：计划书 §B3 的端点已经失效

计划书 §B3 写的是：

```
POST https://account.huami.com/v2/client/login          → app_token / login_token
GET  https://api-mifit.huami.com/v1/users/<id>/devices  → additionalInfo.auth_key
```

**这是旧 Huami/Zepp 链路**，只对 Zepp(Amazfit) 账号有效。对「**小米运动健康**」账号，
它已经不再返回手环设备了（`huami-token` 上游因此在 2026-02 单独重写了一条小米链路）。

现在实际要走的是：

| 步骤 | 请求 | 拿到什么 |
|---|---|---|
| ① | `GET  account.xiaomi.com/pass/serviceLogin` | `_sign` / `qs` / `callback` |
| ② | `POST account.xiaomi.com/pass/serviceLoginAuth2` | `ssecurity` / `nonce` / `userId` / `cUserId` / `location` |
| ③ | `GET  <location>&clientSign=...` | `serviceToken`（Cookie） |
| ④ | `POST hlth.io.mi.com/app/v1/source/get_source_list` | `result.list[].detail.auth_key` |

第 ④ 步的请求体是 **RC4-drop1024 加密 + SHA1 签名**的，不是明文表单。

**由此带来的两个改动：**

1. 计划书里的 **OAuth 授权码模式（§B2 交互式 / §B4 WebView 拦截 code）已经失效**——
   新链路根本没有授权码环节，登录从 `serviceLogin` 直接走账密校验。所以「WebView 拦截
   `hm.xiaomi.com/watch.do?code=`」这条路现在是走不通的。本工具因此提供两种模式：
   - `mode="password"`：账号 + 密码 headless 直登（默认，真·一键）
   - `mode="token"`：手工提供 `ssecurity` / `serviceToken` / `cUserId`（免密码，
     例如从别的渠道拿到凭据时用）
2. `fetch_authkeys(mode="oauth")` 会**明确报错并说明原因**，而不是静默失败或给你一个坏结果。

> 协议来源：[huami-token](https://codeberg.org/argrento/huami-token)（argrento，MIT）。
> 本项目是对其端点 / 字段 / 签名算法的**独立复刻**，未搬运代码。

---

## 交付物

| 文件 | 作用 |
|---|---|
| `xiaomi_authkey.py` | 路线 B 主程序。**零第三方依赖**，只用 Python 标准库（上游要 requests/loguru/pycryptodome，Termux 上装起来很烦，这里全部用 urllib + 手写 RC4 替代） |
| `parse_log.py` | 路线 A：从官方 App 调试日志里解析 AuthKey。支持多文件、跨文件去重、带出设备名/型号 |
| `tools/adb_fetch.sh` | **最省事的一条路**：手机连着 adb 时，直接把日志拉到本地解析，手机端零操作（`just fetch`） |
| `tests/` | 37 个离线测试 + 模拟日志夹具，含上游抓包向量与真实日志格式 |
| `termux/` | Termux 一键部署：桌面小部件脚本 + 安装器 |
| `justfile` | 任务编排：`just build` / `install` / `clean` / `apk` / `fetch` / `setup-phone` / `deps` |
| `tools/gradle.sh` | gradle 统一入口：离线优先、输出进 `app/build/gradle.log`、失败时指出是缺依赖还是代码问题 |
| `tools/install_cli.sh` | 生成 `xiaomi-authkey` / `parse-log` 命令行入口（`just install cli`） |

---

## 快速开始

### 电脑 / 任意有 Python 的机器

```bash
python xiaomi_authkey.py --selftest   # 离线自检，不联网、不需要账号
python xiaomi_authkey.py --probe      # 连通性自检，不发送账号信息
python xiaomi_authkey.py -e 你的账号            # 密码交互输入
python xiaomi_authkey.py -e 你的账号 --json     # JSON 输出
```

### 手机（Termux）

```bash
pkg install python
cd <本仓库目录>
bash termux/install.sh          # 装成桌面小部件
```

再去桌面长按 → 小部件 → **Termux:Widget**，就有两个入口：

- `authkey.sh` → 路线 B，输账号密码，一键出密钥并自动复制到剪贴板
- `parsekey.sh` → 路线 A，弹系统文件选择器让你挑日志，解析并复制

想要 `parsekey` 的自动复制功能，额外装 `pkg install termux-api` **以及**从 F-Droid 装
「Termux:API」这个独立 App（它不是 pip/apt 包）。不装也能用，只是不能自动复制。

---

## 路线 A′：adb 直读（最省事，2026-09-11 实测推荐）

**只要手机能连 adb，就不需要在手机上做任何事。**

```bash
just fetch              # 只连了一台设备
just fetch <serial>     # 多台设备时指定
# 等价于：bash tools/adb_fetch.sh
```

原理有三条反直觉但实测成立的点：

1. **adb shell 能读 `Android/data/`**。计划书 §A3 说的分区存储限制对普通 App 和 Termux
   成立，但 `adb shell` 用户带 `ext_data_rw` 组，可以直接 `pull` 那些文件 ——
   不需要 root，也不需要 `MANAGE_EXTERNAL_STORAGE`。
2. **不需要触发「导出日志」**。官方 App 一启动就把 `huamiAuthKey` 明文写进
   `XiaomiFit.device.log`（手环没连着也照写）。计划书 §A2 那套「猛点关于页图标」的流程，
   只有走 `encryptKey` 线索时才用得上。
3. **密钥与 MAC 在同一行**，配对零歧义，还能顺带解析出型号：

   ```
   DeviceInfo(device=Device(did='huami.xxxx', model='hmpace.bracelet.v5',
     name='小米手环5', detail=Detail(mac=AA:BB:CC:DD:EE:FF, sn=...)),
     ..., huamiAuthKey=0123456789abcdef0123456789abcdef, ...)
   ```

实测输出示例：

```
命中 0：huamiAuthKey
  设备   ：小米手环52  (hmpace.bracelet.v5)
  AuthKey：0x0123456789abcdef0123456789abcdef
  MAC    ：AA:BB:CC:DD:EE:FF
```

> 型号对应：`hmpace.bracelet.v5` → Gadgetbridge 里选 **Mi Band 5**；
> `hmpace.motion.v6nfc` → **Mi Band 6**。拿不准就以日志里的 `name=` 为准。

---

## 装 Gadgetbridge 并配好权限（`just setup-phone`）

密钥拿到之后手机端还需要一个客户端。一条命令装好：

```bash
just setup-phone                   # 自动选唯一在线设备
just setup-phone <serial>          # 多台设备时
just setup-phone -- --check-only   # 只体检当前配置，不改动任何东西
```

它从清华 F-Droid 镜像下载 Gadgetbridge 稳定版（`f-droid.org` 国内直连会 15s 超时，
镜像实测 10s 下完），校验 sha256 后安装，然后把这些一次配好：

| 项目 | 说明 |
|---|---|
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` / `ACCESS_FINE_LOCATION` | 连手环必需。**只给这几个** —— 短信/通讯录/相机/传感器属于可选功能，留给用户在 App 里按需开 |
| `POST_NOTIFICATIONS` | 前台服务常驻通知 |
| 电池优化白名单 + `standby-bucket=active` | 防后台被杀。HyperOS 上这是手环频繁掉线的头号原因 |
| 通知使用权 | 用 `cmd notification allow_listener` 直接授权，不用去设置里手点 |

装完仍有两件事**必须手动做**（adb 改不了）：

1. HyperOS 里把 Gadgetbridge 的「自启动」打开、省电策略设为「无限制」
2. 在 App 里添加设备，填 `authkeys.txt` 里的 MAC 与 Auth key

> 手环同一时间只能连一个 App —— 配对前先在「小米运动健康」里断开或停用。

APK 缓存在 `.cache/apk/`（已 gitignore），重跑不会重新下载。F-Droid 发新版后覆盖常量即可：

```bash
GB_VERSION_CODE=253 GB_SHA256=<新哈希> just setup-phone
```


## 路线 A：手动 / 手机端解析

只有手机、没有电脑时，仍是计划书那条路：

```bash
python parse_log.py                      # 读安卓默认日志路径
python parse_log.py XiaomiFit.device.log # 读指定文件
python parse_log.py *.log                # 多个日志一起（自动跨文件去重）
cat log.txt | python parse_log.py -      # 管道
python parse_log.py --find               # 扫描常见位置
python parse_log.py log.txt --keys-only  # 只输出密钥
```

**关于分区存储（计划书 §A3 提到的那个坑）**：`Android/data/com.mi.health/` 是**别的 App 的**
外部私有目录，Android 11+ 下普通 App 和 Termux 都读不了（除非 root 或授予
MANAGE_EXTERNAL_STORAGE）。本脚本检测到「存在但打不开」时会直接告诉你这一点，
并给出正规做法：用 MT 管理器把日志复制/分享到 Download，再解析——也就是计划书说的走 SAF。

`parsekey.sh` 用的 `termux-storage-get` 就是这个思路的自动化版本：系统文件选择器把文件
「给」Termux，全程不需要特殊权限。

解析器支持的线索（`-v` 可查看）：

- `huamiAuthKey=<32hex>` —— **实测最强线索**；`=`、`:`、`"key":"value"` 三种形态都能吃
- `encryptKey=<32hex>` / `"encryptKey":"<32hex>"`（要手动触发「导出日志」才出现）
- `authKey=` / `"auth_key":"<32hex>"`
- `additionalInfo={...auth_key...}`

结果可靠性做了四件事：**配对最近的 MAC**（同行的优先，否则回看 300 行）、
**跨文件去重**（同一设备在 device / main / hmble 三个日志里都会留痕）、
**过滤全 0/全 f 这类占位符**、**带出设备名与型号**。日志里嵌 JSON 时引号是转义的
（`\"auth_key\"`），解析前会先还原，不用为转义形式另写一套正则。

---

## 路线 B：登录接口

```bash
# 账密模式
python xiaomi_authkey.py -e you@example.com
python xiaomi_authkey.py -e you@example.com --password-stdin < pw.txt
python xiaomi_authkey.py -e you@example.com -p 密码 --json --clip

# token 模式（免密码）
python xiaomi_authkey.py --token-mode \
  --ssecurity <...> --service-token <...> --c-user-id <...>

# 网络排障
python xiaomi_authkey.py --probe
```

`--region` 默认 `cn`（上游默认 `ru`）。如果拉设备列表失败，可以试试 `--region ru`。

Python 里调用（计划书 §B5 的契约）：

```python
from xiaomi_authkey import fetch_authkeys, Device

devices: list[Device] = fetch_authkeys(
    mode="password", email="you@example.com", password="...", country_code="CN"
)
for d in devices:
    print(d.mac_address, d.auth_key, d.active, d.model_hint)
```

### 错误处理

小米错误码做了中文映射，`-1004`/密码错/2FA/风控都会给可操作的提示而不是抛栈：

| code | 提示 |
|---|---|
| 70016 | 账号或密码错误（并提示可能需要改用 token 模式） |
| 70002 / 70005 | 需要验证码 |
| 70010 | 需要二次验证，请在手机上确认 |
| 87001 / 87002 | 触发风控，换网络、降低频率，或改走路线 A |

---

## 验证状态（哪些是真测过的）

我尽量只把**验证过的东西**说成已验证：

| 项目 | 状态 |
|---|---|
| 密码学实现（RC4-drop1024 / SHA1 签名 / 密钥派生） | ✅ **逐字节一致**：用上游抓包的已知答案向量比对，全部通过 |
| 加密参数与抓包完全一致（含 `rc4_hash__`） | ✅ 通过 |
| 登录第 ① 步端点与字段解析 | ✅ 实测返回 `_sign` / `qs` / `callback`，且 callback 是 `https://sts-hlth.io.mi.com/healthapp/sts`，确认 `miothealth` 服务走对了 |
| `hlth.io.mi.com` 可达性 | ✅ 实测（未登录返回 401，符合预期） |
| 登录第 ② ③ 步（账密 → ssecurity/serviceToken） | ⚠️ **未实测**——需要真账号，我不会拿你的账号去试 |
| `get_source_list` 真实响应结构 / `detail.auth_key` | ⚠️ **未实测**，按上游实现对齐；解析层写了较强容错（新旧结构、嵌套、缺 key 都能处理） |
| 日志解析器 | ✅ 模拟日志 + **真实日志双重验证**（37 个测试；真机 7.8 MB 的 `XiaomiFit.device.log` 跑通） |
| **路线 A′ adb 直读（端到端）** | ✅ **2026-09-11 实机跑通**：某红米机型 / HyperOS / Android 15，一次取出 3 台设备的 MAC + AuthKey + 型号，手机端零操作 |
| `huamiAuthKey` 字段 / 同行 MAC 配对 / 型号解析 | ✅ 真机日志确认（小米运动健康 3.48.3） |

所以走**路线 A′** 时不需要操心下面这些；只有走**路线 B（登录接口）**才需要按
`--selftest` → `--probe` → 正式取密钥的顺序排障，哪一步断了一眼就能看出是网络、协议还是数据问题。

---

## 排障

**读不到日志**
先确认日志里到底有什么字段（实测 `huamiAuthKey` 最常见）：
`grep -a -i 'authkey\|encryptkey' XiaomiFit.device.log | head`。
连 `huamiAuthKey` 都没有时，才走计划书 §A2 的方式二（装「小米健康研究」，
连上手环后再触发日志）。也可能是 App 大版本改了字段名——用 `python parse_log.py -v`
看支持哪些线索。

**连着 adb 却拉不到日志**
`adb shell ls /sdcard/Android/data/com.mi.health/files/log` 看目录在不在。
若报 Permission denied，说明这台 ROM 收紧了 `Android/data` 的 shell 访问权，
退回路线 A 的手动方式。

**`--probe` 就失败**
需要能直连 `account.xiaomi.com` 和 `hlth.io.mi.com` 的 443。部分网络/代理会劫持或阻断。

**登录报 70016**
账号或密码问题。如果确定密码没错，说明账号是手机号/邮箱二次验证类账号，
改用 `--token-mode`，或者直接走路线 A。

**报风控（87001/87002）**
换网络、等十分钟再试，别连续重试。`authkey.sh` 已经做了「一次登录 + 一次取设备」，
就是避免反复请求触发风控。

**拉不到设备**
确认手环是用「小米运动健康」配对且同步过至少一次，且登录的是绑定了该手环的那个账号。

---

## 安全

- **账号密码只在内存**：不写配置、不写缓存、不落盘。`--password-stdin` 让密码
  连进程参数都不进。
- **取完即清**：默认取完设备后清空会话凭据（`--no-logout` 仅用于调试，会打印 token）。
- **不联网外传**：只有和小米官方服务器的请求，没有任何第三方上报。
- **密钥等同设备控制权**：只复制到剪贴板/打印到屏幕，不做云端同步。
- `.gitignore` 已排除日志和密钥文件，别把它们提交进版本库。

---

## 与计划书验收清单的对照

- [x] `fetch_authkeys()` 函数，返回 `[{mac_address, auth_key, active, model_hint}]`
- [x] auth_key 为 `0x` + 32 位 hex（不足自动左侧补位）
- [x] 错误码（密码错 / 2FA / 风控）有明确中文报错，不崩溃
- [x] 全程不落盘账号密码
- [x] 输出可直接粘贴进 Gadgetbridge 的「Auth key」字段
- [x] 路线 A 兜底方案（正则 + 日志路径 + SAF 选文件）
- [x] 手机端一键（Termux:Widget，两个入口）
- [ ] **真机拿到 `0x` 开头的 32 位 hex 密钥** ← 这一步得你在真机上跑，我这边没有你的账号和设备

上游 `huami-token` 是活的、在维护的。如果哪天本工具失效，先去它那儿看有没有新提交——
多半是小米又改了协议。

---

## 任务编排（just）

装了 [`just`](https://github.com/casey/just) 之后，两个命令覆盖日常操作，其余都是细分入口：

```bash
just                     # 列出所有命令
just build               # 全项目：Python 校验/自检/测试/打包 + 编译 APK
just install             # 自动判断装什么
```

```bash
just build apk           # 只编译 APK        just install apk [serial]   # 装到手机
just build tools         # 只跑 Python 那套  just install cli [目录]     # 装命令行入口
just clean               # 清 Python 侧      just install termux        # 装 Termux 小部件
just clean all           # 连 Android 一起清 just install /some/dir      # = install cli /some/dir
just apk [release]       # 编译指定 variant
just app-install         # 等价于 just install apk
just android-clean       # 只清 Android 产物
just deps                # 改过依赖版本后，联网拉一次（平时一律离线）
just fetch               # 从 adb 连接的手机直接提取 AuthKey（手机端零操作）
just setup-phone         # 给手机装 Gadgetbridge 并配好连手环所需的权限
just setup-phone -- --check-only   # 只体检当前配置，不改动任何东西
```

- **`just build`** 依次跑：全量 `py_compile` + `bash -n` 语法检查 →
  `--selftest` 密码学向量自检 → 37 个测试用例 → 打包成
  `dist/shouhuan-authkey-<版本>.zip`（附 `.sha256`）→ 编译 debug APK。
  任何一步失败立即中止，不会产出半成品。只想跑一遍 APK 就用 `just build apk`。
- **`just install`** 没给目标时自动判断：**adb 连着设备 → 装 APK**；Termux →
  委派给 `termux/install.sh` 装成桌面小部件；两者都不是 → 在 `~/.local/bin` 生成
  `xiaomi-authkey` / `parse-log` 两个命令（Windows 下请在 Git Bash 里用）。
  **仓库挪了位置要重新跑一次**，因为包装脚本里写的是绝对路径。
  加 `DRY_RUN=1` 可以先看它打算做什么、不真的装。
- **`just fetch`** 把手机上的日志拉下来直接解析，手机端不用做任何事；
  多台设备时写 `just fetch <serial>`。
- **`just setup-phone`** 给 adb 连接的手机装 Gadgetbridge（F-Droid 稳定版，走清华镜像 +
  sha256 校验），并把连手环必需的权限、电池白名单、通知使用权一次配好，手机端零操作。
  多台设备时写 `just setup-phone <serial>`。装完只剩两件事必须手动做：HyperOS 里打开
  「自启动」+ 省电策略设为「无限制」，以及在 App 里填 MAC 与 AuthKey。
  加 `-- --check-only` 可以只体检、不改动。
- 换解释器：`just PY=/usr/bin/python3 build`。

> `justfile` 里有三处坑值得记住：
> ① heredoc 内容要跟 recipe 一样缩进（just 靠缩进判定 recipe 边界）；
> ② 原生程序（`python.exe`、`adb.exe`、`curl.exe`）收到 `/c/...` 形式的 POSIX 路径会解析成
> `C:\c\...` —— 凡是要交给原生程序的路径都得先转成原生形式，而 bash 内建命令（`mv`、`[ -f ]`）
> 仍用 POSIX 形式，`tools/setup_phone.sh` 里的 `to_native()` 就是干这个的；
> ③ **带 shebang 的 recipe 拿不到 `*ARGS` 位置参数**（`$#` 恒为 0），只有 `{{...}}` 插值可靠，
> 所以 `build` / `install` / `clean` 用的是具名参数而不是 `$1`。

### 不用 just 也行

```bash
python -m unittest discover -s tests -p "test_*.py"   # 测试套件
python xiaomi_authkey.py --selftest                   # 密码学自检
bash -n termux/*.sh                                   # shell 语法检查
```
