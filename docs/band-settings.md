# 手环本机设置（Band Settings）

> **状态：⚠️ 协议照 Gadgetbridge 逐字抄定，还没在真机上全流程验证过。**
> 每一步下发都会把原始字节打进 `BandSession` 的日志（`设置 xx xx -> 已下发/失败`），
> 跑挂了靠日志定位。
>
> 相关代码：`app/src/main/java/com/ted/shouhuan/proto/BandSettings.kt`（字节构造）、
> `BandSession.applyXxx()`（下发）、`service/BandService.kt`（连接后整套推送 +
> 手机电量提醒）、`ui/device/DeviceScreen.kt`（设备页 UI）、
> `data/BandPrefs.kt`（偏好存储）。

## 1. 功能清单（对齐 Notify for Mi Band 的能力面）

| 功能 | 手环侧/手机侧 | 协议载体 |
| --- | --- | --- |
| 菜单顺序（长按拖拽） | 手环 | chunked 通道（类型号 2） |
| 快捷方式（左右滑卡片，长按拖拽 + 增删） | 手环 | chunked 通道（类型号 2） |
| 佩戴手（左/右） | 手环 | 用户设置特征 00000008 |
| 抬腕亮屏开关 | 手环 | 配置特征 00000003 |
| 滑动解锁（锁屏后上滑解锁） | 手环 | 配置特征 00000003 |
| 断开提醒（手环与手机断连时手环自己振动） | 手环 | 配置特征 00000003 |
| 勿扰模式（关闭 / 定时 / 自动） | 手环 | 配置特征 00000003 |
| 夜间模式（关闭 / 定时 / 日落自动） | 手环 | 配置特征 00000003 |
| 连接提醒（App 连上手环时手环提示） | 手机→手环 | 文字通知（chunked 类型 0） |
| 低电量提醒（手机电量 ≤ 阈值） | 手机→手环 | 文字通知 |
| 充满提醒（手机充满电） | 手机→手环 | 文字通知 |
| 自动心率检测（手环全天定时探测心率） | 手环 | 心率控制点 00002a39（`0x14` + 间隔分钟，0 = 关） |

这些手环本机设置**写完即生效、手环自己持久化，但没有读取接口** ——
应用侧存一份偏好，连接成功后整套下发（`BandService.pushBandSettings()`），
用户当场改的当场也推一条（`DeviceViewModel.applySetting()`）。默认值全部
对齐 MB5 出厂状态，所以首次连接就整套下发，也只是把手环写回它本来就在的状态。

## 2. 字节协议（来源：Gadgetbridge 0.8.x，MiBand5Support 分支）

### 2.1 菜单顺序 / 快捷方式 —— `HuamiSupport.setDisplayItemsNew`

MB5 的 `setDisplayItems` / `setShortcuts` 都走 `setDisplayItemsNew(builder, isShortcuts, forceWatchface=true, …)`：

```
0x1e [00 00 <menuType> 0x12] [序号 00 <menuType> <id>] × N
```

- `menuType`：`0xff` = 主菜单，`0xfd` = 快捷方式；
- `0x12`（watchface）固定占第 0 位 —— 上游 forceWatchface=true；
- 每项 4 字节：序号从 1 起递增；
- payload 超过单包 17 字节（MTU 23 − 6），按 `writeToChunkedOld` 分块写到
  chunked 通道（00000020），**类型号是 2**（不是通知的 0）——
  见 `Notify.chunk(data, type = BandSettings.CHUNKED_TYPE_DISPLAY_ITEMS)`；
- 项数上限 16：上游规则是超限时把「更多」钉在第 16 位防手环截断；
  我们在 UI 直接限了 16 项（`DeviceViewModel.MAX_ITEMS`），到不了那条规则；
- 菜单项 id 表 = GB `HuamiMenuType.idLookup`（逐字抄进 `BandSettings.Item`），
  MB5 默认菜单 = `pref_miband5_display_items_default`（status, pai, hr, notifications,
  breathing, eventreminder, weather, workout, more, stress, period, nfc），
  默认快捷方式 = `pref_miband5_shortcuts_default`（notifications, weather, music）。

### 2.2 开关类 —— 直写配置特征 00000003（GB `writeToConfiguration`，无 chunking）

| 设置 | 开 | 关 |
| --- | --- | --- |
| 抬腕亮屏（`06 05`） | `06 05 00 01 00 00 00 00` | `06 05 00 00` |
| 滑动解锁（`06 16`） | `06 16 00 01` | `06 16 00 00` |
| 断开提醒（`06 0c`） | `06 0c 00 01 00 00 00 00` | `06 0c 00 00 00 00 00 00` |
| 勿扰（`09`） | 定时 `09 81 <时> <分> <时> <分>`；自动 `09 83` | `09 82` |
| 夜间模式（`1a`） | 定时 `1a 01 <时> <分> <时> <分>`；日落 `1a 02` | `1a 00` |

- 断开提醒是**手环侧**功能：手环自己检测蓝牙断连并振动，App 不参与事后通知
  （断连瞬间链路已经没了，App 发什么手环都收不到）；「定时」形态往
  cmd[4..7] 填起止时刻，常开全填零；
- 勿扰/夜间的时间字节 = 当天的小时/分钟（GB 往 cmd 填 `Calendar.HOUR_OF_DAY/MINUTE`）；
- 上游勿扰还有「定时期间抬腕仍可唤醒」的变体（cmd[1] 清掉 0x80 位），没做。

### 2.3 佩戴手 —— 用户设置特征 00000008（GB `setWearLocation`）

```
左手 20 00 00 02
右手 20 00 00 82
```

GB 写之前先在这条特征上开 notify（收手环回执），写完关掉 ——
`BandSession.applyWearLocation()` 照做。

### 2.4 自动心率检测 —— 心率控制点 00002a39（GB `HuamiSupport.setHeartrateMeasurementInterval`）

这是**唯一一条不走配置特征**的手环本机设置：和单次/连续测量共用标准 GATT
心率控制点（00002a39），所以下发仪式照 GB 抄 —— 写之前在控制点上开 notify
（收手环回执），写完关掉。

```
{0x14, <间隔分钟>}     0 分钟 = 关闭
```

- `0x14` = GB `HuamiService.COMMAND_SET_PERIODIC_HR_MEASUREMENT_INTERVAL`，
  分钟数由偏好里的秒值 / 60 得来，GB 夹在 0..120；
- 对应官方 App「心率检测 → 检测模式」里的**自动心率检测**档（GB 中文界面上
  这个间隔项叫「全天心率检测」，旁边的档位列表就是官方的「检测频率」：1/5/10/30 分钟），
  0 就是官方那个「关闭」；
- 我们默认关（应用侧 `SET_AUTO_HR` 缺省 false，连接后下发 `14 00`），打开时按
  **30 分钟**一档下发 —— 官方标称 15 天续航的测试条件正是「以 30 分钟为频率的
  自动心率检测」，官方 FAQ 给省电建议时也推荐这一档；
- 代价：关掉后手环不再产生连续心率分钟样本，桌面控件的心率曲线没有数据源
  （手动测量、实时心率不受影响，它们走的是同一条特征上的 `0x15` 指令）；
- 官方那一页还有**辅助睡眠检测**（"使用心率检测以提高睡眠检测精度"，
  GB 写 `{0x15, 0x00, 0x01}` / 关 `{0x15, 0x00, 0x00}`，同样是心率控制点）
  和运动时的活动监测，都没做 —— 前者会拖睡眠判定精度，需要时再加。

### 2.5 手机提醒（`BandService`）

- **连接提醒**：`session.authenticated` 出现「假 → 真」跳变（含断线重连）后，
  缓 1.5 秒等手环收尾，先整套下发设置，再按偏好发一条文字通知；
- **低电量提醒**：注册 `ACTION_BATTERY_CHANGED` 广播；未充电且电量 ≤ 阈值时
  发一条；发过一次就锁住，回充或回升超过「阈值 + 5」才复位，避免贴着阈值反复响；
- **充满提醒**：`EXTRA_STATUS == BATTERY_STATUS_FULL` 且在充电时发一条，
  拔掉充电器复位；
- 三个提醒都只在手环处于认证连接状态时发送（`sendBandReminder()` 里再查一次），
  手环不在时静默放弃 —— 不做补发，事件过了补发反而误导。

## 3. 已知边界

- **不反向同步**：手环上（或官方 App 里）改的设置不会读回来，App 里显示的
  永远是自己存的偏好；下次连接会把 App 的偏好整套压过去。
- **MB5 没有数字密码**：GB 的 `setPassword`（`06 21 00 <enable> <pwd> 00`）是
  PIN 类设备的；MB5 的屏幕锁就是「滑动解锁」，走 `06 16`。
- **通知内容的样式**与通知页转发的是同一套（`proto/Notify.kt`），提醒的
  应用名固定显示「手环管家」。
