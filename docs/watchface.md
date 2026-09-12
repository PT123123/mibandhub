# 表盘（Watch Face）

> **状态：✅ 已打通（2026-09-12 真机确认）。** 修正协议差异后，内置表盘下发到手环，
> 手环回 `10 04 01`，表盘成功换上。协议细节与两处修复的来龙去脉见 §2 / §2.1。
> 库管理（P1-B）与 App 内生成器（P2）还没做，页面能力仍属「最小可用」。
>
> **内置表盘**：App 里打包了三张真正的 `.bin` 表盘（`assets/watchfaces/`，
> 来源与授权见 §5.1）。
>
> 相关代码：`app/src/main/java/com/ted/shouhuan/proto/WatchFace.kt`、
> `app/src/main/java/com/ted/shouhuan/ui/watchface/`、
> `app/src/main/java/com/ted/shouhuan/data/BuiltInWatchFaces.kt`、
> `BandSession.installWatchFace()`。

## 1. 背景：上游实现的状态变化

这条流程最初是照 Gadgetbridge 的固件更新实现 `UpdateFirmwareOperationNew`
推出来的（当时 `MiBand5Coordinator.supportsAppsManagement` 继承默认 `false`，
**上游没有可抄的表盘实现**）。

**2026-09-12 更新：这个前提过时了。** Gadgetbridge 现已支持 Mi Band 5 表盘安装
（CHANGELOG："Mi/Amazfit Band 5: Support watchface installation"；官方文档说明
表盘与固件走同一条上传通道，Mi Band 5 有 3 个自定义表盘槽）。本次的两处字节差异
就是把我们的流程和 GB master 的 `UpdateFirmwareOperation(New).java` +
`devices/huami/HuamiService.java` 逐字节比对找出来的。

纪律不变：**凡是落到字节的结论，一律用真机实验台实测，不能只看源码推。**

## 2. 通道与实测结果

走华米固件的私有资源传输通道：

| 特征 | UUID | 用途 |
|---|---|---|
| 配置 | `00000003-0000-3512-2118-0009af100700` | 表盘上传前写一条「选表盘槽」命令 |
| 控制 | `00001531-0000-3512-2118-0009af100700` | 命令与回复都走这条 |
| 数据 | `00001532-0000-3512-2118-0009af100700` | 只用来灌文件内容 |

修正后的字节序列：

```
手机 → 3号配置: 39 00 00 ff ff ff <文件[18..21]>   ⓪ 选表盘槽（仅 UIHH 容器）
手机 → 1531: 01 08 <size u32le> <crc32 u32le>      ① 报元数据（0x08 = WATCHFACE）
手环 →      : 10 01 01                              ② 收下了
手机 → 1531: 03 01                                  ③ 开始推
手机 → 1532: <数据包 × N>（每 100 包插一条 00）      ④ 推数据
手机 → 1531: 00                                     ⑤ 推完了
手环 →      : 10 03 01                              ⑥ 数据齐了
手机 → 1531: 04 <crc16 u16le>                       ⑦ 请校验（CRC16 = CCITT，init 0xFFFF）
手环 →      : 10 04 01                              ⑧ 成功 ← 待真机确认
```

早期真机实测（Mi Band 5，固件 V1.0.2.76，242371 字节的包，**修正前**的流程）：

| 步骤 | 结果 |
|---|---|
| 固件通道是否存在（`1531` / `1532`） | ✅ 两个特征都在 |
| 表盘元数据 `01 08 <size> <crc32>` | ✅ 手环回 `10 01 01 …`（11 字节） |
| 数据流（每 100 包插一条 `00`） | ✅ 12119 包 / 242371 字节推完 |
| 收尾 `00` | ✅ 手环回 `10 03 01`「数据齐了」 |
| 校验命令 `04`（当时是裸的） | ❌ 收到的是 `10 20 08` / `10 20 00`，表盘不换 |

**结论**：通道完全打通、包体也被完整接收 —— 这部分是确认的；
生效此前一直未确认。界面因此标「实验性」，状态机里专门留了
`Unconfirmed` 一档，不硬拗成成功或失败。

**限速是必须的**：`BandSession.PACKET_INTERVAL_NANOS`（6 ms/包 ≈ 166 包/秒）。
手环的接收缓冲有限，全速写（实测约 300 包/秒）会在 4000 包上下被整包打回。

### 2.1 2026-09-12：找到两处协议差异（已修，真机复测通过）

对照 GB master 逐字节比对，我们的流程少了/错了两处，都能解释「数据全收下了、
收尾却拒绝」：

1. **缺 ⓪ 表盘槽选择命令**。GB 对 UIHH 表盘会先往配置特征（`00000003-…`）
   写 `39 00 00 ff ff ff <表盘文件第 18..21 字节>`（那四个字节是 UIHH 头里的
   表盘 ID），再发 init。手环要靠它知道收到的包归哪个表盘槽（Mi Band 5 有
   3 个自定义槽）。
2. **⑦ 校验命令没带 CRC16**。GB 发的是 `04 <crc16 u16le>`（CCITT，init 0xFFFF，
   对照向量 "123456789" → 0x29B1 已验证），我们发的是裸 `04`。

旁证：zip 预览数据（`10 20 08`）和真 `.bin`（`10 20 00`）的收尾码不同 ——
校验失败原因不同，符合「拒绝」而非「无响应」的语义。

**复测结果：两处都修完后，内置表盘（Digital Codex）下发，手环回 `10 04 01`，
屏幕成功换上表盘。** 此前的 `10 20 08` / `10 20 00` 语义就此收案：
没有选表盘槽 / 校验不过时的拒绝应答。

## 3. 历史疑点与它的结局：当时推的确实不是表盘包

早期实测用的那份 242371 字节的包，来源记的是官方 App 缓存
`…/com.xiaomi.hm.health/files/WatchFace/data.zip`，据记里面是 160 张
`face_data_<风格>_<布局>_<序号>.png`。

**但合法表盘不长这样。** Mi Band 5 的表盘是华米私有的 `.bin` 容器 ——
文件头 + 资源索引表（资源 ID / 类型 / 偏移 / 长度）+ 资源数据块（低色深位图，
RAW / RLE / 调色板）+ 末尾校验，外加一份描述每个 UI 元素坐标与资源引用的 JSON。
官方 App 的表盘目录也是每个表盘一份 `.bin` + `.png`（预览）+ `.xml`。

**不是「几张 PNG 改名成 .bin」。** 所以当时怀疑 `data.zip` 是表盘商城的
预览图数据 —— 这个怀疑后来坐实了，App 现在会拦下 zip 并说明原因。

**但故事没到这就完了**：换成真正的 `.bin`（内置表盘，§5.1）真机重测，
数据同样完整送达，收尾还是 `10 20 00`、表盘不换 —— 说明包体之外还有问题。
这就是 §2.1 那两处协议差异的由来：**缺 ⓪ 表盘槽选择、⑦ 校验没带 CRC16**。
判别手段存档如下：

```bash
# 1) 看头 4 字节：50 4B 03 04（"PK.."）→ 是 zip，不是合法的 .bin 容器
xxd -l 16 <包文件>

# 2) 看清单：是不是一堆 face_data_*.png；有没有 .bin / json
unzip -l <包文件>
```

## 4. 包体上限

Mi Band 5 按 **615 KB** 卡。社区工具打包时会直接报：

```
[ERROR] Watchface is greater than 615kb, it will not be accepted by Mi Band 5,
please reduce size! Current size:1848kb.
```

代码里 `WatchFace.MAX_PAYLOAD_BYTES` 已按 615 KB 对齐（2026-09-12）。
仍待确认：615 KB 是手环的硬限制，还是工具侧的保守保护值。
内置三张表盘实测打包结果：459 KB / 322 KB / 304 KB，都在限内。

## 5. 表盘从哪来 / 怎么做

我们 App 的定位是**下发通道 + 库管理**，不做表盘创作（创作归 P2，见 §6）。

### 5.1 内置表盘（已落地）

随 APK 打包在 `app/src/main/assets/watchfaces/`，清单 `manifest.json`
记录每张的作者/来源/授权，界面原样展示：

| id | 名字 | 作者 | 授权 | 来源 |
|---|---|---|---|---|
| `digital-codex` | Digital Codex | gryffyn | CC BY-NC-SA 4.0 | amazfitwatchfaces #5183 |
| `mint-minimal` | 薄荷极简 | 本项目自制 | CC0-1.0 | watchface-js 生成 |
| `amber-minimal` | 琥珀极简 | 本项目自制 | CC0-1.0 | watchface-js 生成 |

收录纪律：**只收条款明确允许再分发的**。实测 amazfitwatchfaces 的 Mi Band 5
新上传区几乎全是「Paid content」面，明确开放授权的很少 —— 宁缺毋滥，
缺的额度用自制补。自制两张走的是 `watchface-js`（npm，`wfjs writeBin
-i <目录> -m miband5`）：Pillow 画素材（数字条 + 背景 + 内嵌预览图）→
按解包 Digital Codex 得到的 `watchface.json` 模板填布局 → 打包 →
`readBin` 往返校验。生成脚本收在 `tools/watchfaces/`（含复现步骤）。
格式要点：表盘 = UIHH 头的 .bin 容器；
布局 JSON 只用了 Background / Time / Activity(Steps) / Date / Battery
这几个已验证的块；格式里 alpha 反相存储（文件里 0x00 = 不透明）。

### 5.2 社区工具链（都不需要自己逆向格式）

| 途径 | 形态 | 备注 |
|---|---|---|
| `MiBandWFTool`（lvpokka，基于 AmazfitBipTools） | Windows exe | v2.1.4 起支持 Mi Band 5；拖 `.bin` 解包 → 改图 / 改 JSON → 拖回重打包 |
| `Johnson070/MiBand-5-watchface-editor` | 开源 GUI | 解包 / 编辑 / 存 `.bin`；有杀软误报史（引用 wininet.dll、混淆壳） |
| `watchface-js`（npm） | JS 库 + CLI | `wfjs readBin -i x.bin -m miband5` / `writeBin`。**已实测可用**：内置自制表盘就是它打包的；将来若要在 App 内生成表盘，这是最值得参考的实现 |
| 米坛「资源包工具坊」 | 网页 | 解包 / 打包，不用装任何东西 |
| 米坛社区 / 各社区表盘站 | 资源 | 现成的 Mi Band 5 表盘包（注意授权，见 §5.1） |

> **为什么可以「替换大法」**：官方 App 靠 **`.bin` 文件名**识别表盘，不看内容
> （所以社区才有这套 —— 换掉文件、沿用原名，再同步一次）。

### 5.3 在线市场（已落地）

表盘页有「在线市场」入口：App 从本仓库 `market/` 目录拉目录清单
（主源 jsDelivr CDN，备源 raw.githubusercontent.com，谁成谁算；
`faces/`、`previews/` 跟着目录成功的那个源走），浏览带预览图的表盘、
按需下载到 `filesDir/market/<id>/`，下载完自动进入表盘页「我的表盘」库，
点选即走同一条下发链路。注意 jsDelivr 有 CDN 缓存：推完新目录要主动刷一次
`https://purge.jsdelivr.net/gh/PT123123/mibandhub@main/market/index.json`。

- **目录格式**：`{version, updated, faces:[{id,name,author,license,file,
  preview,sizeBytes,crc32,note}]}`；`crc32` 用于下载后校验（对不上即弃）。
- **内容纪律**：只收本仓库自制（CC0）—— 与 §5.1 同一条授权底线，不抓第三方站。
- **生成**：`tools/watchfaces/make_market.py` 批量产出（4 布局 × 8 配色 = 32 张：
  数码 / 终端 / 极简 / 大字 × 冰蓝 玫红 橙阳 森绿 青瓷 石墨 樱粉 葡紫），
  打包后把 UIHH 头第 18..21 字节补丁成唯一表盘 ID（`0x4D4B00xx`），避免市场
  表盘在手环侧互相覆盖。ID 补丁后的包要先真机验证一张再批量信任。
- **实现**：`data/MarketRepository.kt`（HttpURLConnection + org.json，零新依赖）、
  `data/WatchfaceLibrary.kt`（内置 + 已下载合并视图）、`ui/market/`（市场页）。

## 6. 后续计划

1. ~~真机复测修正后的流程~~ ✅ 已完成（2026-09-12，见 §2.1）
2. **市场真机验证**：拉目录 → 下载 → 安装一张 patched-ID 表盘（确认 ID 补丁被手环接受）
3. 再做 P1-B「表盘库管理」：导入 / 命名 / 分组 / 预览 / 一键下发
4. 「App 内建表盘生成器 / 编辑器」归 P2（`tools/watchfaces/` 的生成脚本已是雏形）
5. 待确认的小事：615 KB 是否手环硬限（§4）；两张内置自制表盘共用默认表盘 ID
   （watchface-js 默认值），都装会互相覆盖 —— 市场侧已用唯一 ID 解决，内置的
   要不要也补丁，做库管理时一并考虑

---

_最后更新：2026-09-12_
