# 睡眠数据同步排查记录（睡眠页一直为 0）

> **状态：✅ 根因已定位（数据被小米健康抢占），抓取逻辑已修复，方案待定。**
> 排查日期：2026-09-15。
> 相关代码：`proto/ActivitySync.kt`（字节层解析）、`proto/BandSession.kt`（`syncActivity` / `fetchActivityBlocks` / 诊断日志）、`service/ActivityDataImport.kt`（落库）。

## 1. 现象

点击同步手环数据后，睡眠页解析出来的睡眠一直为 **0**（夜间睡眠夜数/分钟数都没有），
但手环本身通过官方 Mi Fit 能正常显示睡眠。

## 2. 抓取逻辑修复（已完成）

**问题**：`syncActivity` 原来一轮只 `fetchActivityRound` 一块就返回，只能拿到最老的
一块数据（约 3.2 天前），近期睡眠根本没进库。

**修复**：参照 Gadgetbridge 的 `AbstractRepeatingFetchOperation`，新增
`fetchActivityBlocks`：从起点顺着块尾一块块 `startRequest` 循环要下去，直到手环回
"没有更多数据"（`expectedBytes == 0`）或追上当前时刻，最后**一次性**把累计全量样本
归并成夜（避免跨块边界的一觉被切碎）。实测单次可拉满 **10075 分钟（09-08 → 09-15）**。

## 3. 协议核对（结论：App 侧协议是对的）

把 App 的取数实现和 Gadgetbridge 源码逐字节比对，**完全一致**：

| 项 | App | Gadgetbridge |
| --- | --- | --- |
| 取数请求 | `00000004` 写 `01 01 + 时间8字节` | `UUID_UNKNOWN_CHARACTERISTIC4`，`COMMAND_ACTIVITY_DATA_START_DATE + fetchType + timeBytes` |
| 取数类型 | `ACTIVITY = 0x01` | `HuamiFetchDataType.ACTIVITY = 0x01`（无单独睡眠类型，睡眠含在 ACTIVITY 里） |
| 样本通道 | `00000005`，每包首字节序号 + 8 字节/分钟 | `UUID_CHARACTERISTIC_5_ACTIVITY_DATA` |
| 扩展样本布局 | `[kind, 强度, 步数, 心率, 未知, 浅睡, 深睡, REM]` | `createExtendedSample` 同布局 |
| 睡眠类型号 | `kind == 0x78` | `TYPE_SLEEP = 120 = 0x78` |
| 分期阈值 | `rem>55 → REM；deep>42 → DEEP` | `HuamiExtendedSampleProvider.postProcess` 同阈值 |

参考源码（Gadgetbridge `master`，仓库 2026-02 已归档）：
`devices/huami/HuamiExtendedSampleProvider.java`、
`service/devices/huami/operations/fetch/{FetchActivityOperation,AbstractFetchOperation,AbstractRepeatingFetchOperation,HuamiFetchDataType}.java`。

## 4. 根因（数据被官方 App 抢占并删除）

拉回**全量、含每一晚**的数据后，逐晚诊断（`processSamples` 打印 `每晚情况`）显示：

- kind 分布里**完全没有 `0x78` 睡眠类型**；
- 夜间 22:00–06:00 的浅睡/深睡/REM 字节**几乎全为 0**（每晚非零分期分钟只剩凌晨 6-7 点的起床活动）；
- 非零"分期"字节全落在白天 9-12 点，且是 `d=0x52` 这类高值——那是**运动强度，不是睡眠分期**。

结论：这条通道给出的全是裸运动数据，**手环端没有睡眠分期可给**。协议没错、数据也抓全了，
问题出在数据本身已经被取走。

原因：手机上装有官方 **Mi Fit / Mi Health（`com.mi.health`）**。Huami 手环的数据是
**"只向第一个来取的人交付"**：官方 App 同步后会给手环发 `ack (0x03)`，手环收到就把
这段数据删掉，不再向任何第三方 App 重发。所以：
- App 的同步协议即使完全正确、也不 ack、重复抓取也见不到睡眠——因为**睡眠分期已被 Mi Fit 先取走并让手环删除**；
- 已经过去的那几晚（09-11 → 09-15）若都被 Mi Fit 同步过，**无论怎么改代码都找不回了**。

## 5. 关键机制（理解后能设计共存方案）

> **我们的 App 从不发 `ack`**（`ActivitySync` 注释里明确写了，见 [ActivitySync.kt](file:///c:/Users/ted/Desktop/shouhuan/app/src/main/java/com/ted/shouhuan/proto/ActivitySync.kt#L49-L54)）。
> 因此：只要我们**先于** Mi Fit 抓到一晚的睡眠，本地就有了永久副本，**且手环数据不删，Mi Fit 之后照样能读**。
> 风险只在 Mi Fit 是"后台常驻自动同步"、会抢先。

## 6. 可选方案（按推荐度排序）

| 方案 | 做法 | 优点 | 缺点 / 前提 |
| --- | --- | --- | --- |
| **（推荐）单一 App 接管** | 卸载或停用 `com.mi.health`，让本 App 唯一连着过一晚 | 最稳，无竞争，睡眠页必有数据 | 放弃用官方 App 看睡眠/更高级功能 |
| **睡醒"首发"同步，Mi Fit 只手动开** | 关掉 Mi Fit 的后台/自启动自动同步，让它只在手动打开时才连；睡醒先用本 App 同步，再随意开 Mi Fit | 两边共存；本 App 不 ack 所以 Mi Fit 不丢数据 | 依赖"先开本 App"的动作，个别早上可能忘 |
| **禁用 Mi Fit 蓝牙权限**（不卸载） | 系统设置里关掉 Mi Fit 的"附近设备/蓝牙"权限，阻止它连手环 | 保留官方 App 做别的 | 有的系统/升级后会重新授权；路由不稳 |
| **从 Mi Fit → Health Connect 导入** | 让 Mi Fit 把睡眠写入 Health Connect，App 走已实现的 `HealthConnectSleepSource` 导入 | 完全不碰蓝牙竞争，可拉历史 | 取决于 Mi Fit 版本是否支持写入 Health Connect；需用户授权健康数据 |
| **接受现状** | 改文档/提示：睡眠只靠单一 App 同步 | 零改动 | 睡眠仍拿不到，不符合目标 |

**推荐的落地测试**：先把 Mi Fit 的后台自动同步关掉（或直接停用），让**本 App 连着一晚**，
次日本 App 自动拉取后确认睡眠页出现数据；同时观察 09-11 之前的睡眠无法找回属预期。

## 7. 遗留待办

- [ ] 确定并执行上面任一方案（优先"单一 App 接管"或"睡醒首发"）。
- [ ] 方案选定后用一整晚干净数据验证睡眠页/分期/评分。
- [ ] （可选）把逐晚诊断日志从 `processSamples` 永久保留为低开销的调试开关（默认关闭）。