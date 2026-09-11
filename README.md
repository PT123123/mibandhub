# 手环管家（shouhuan）

自己写的小米手环管理客户端。界面自己设计，核心是**睡眠、通知、心率**三件事。

技术栈 **Kotlin + Jetpack Compose**，首版目标是打通最小闭环：
**配对连接 → 实时心率 → 睡眠数据 → 手机通知转发到手环**。

## 仓库构成

| 路径 | 内容 |
|---|---|
| `app/` | **Android App 主体**（Kotlin + Compose） |
| `app/src/main/java/com/ted/shouhuan/ui/` | 界面：首页 / 心率 / 睡眠 / 通知 / 设备 |
| `app/src/main/java/com/ted/shouhuan/ble/` | BLE 连接层：连接、服务发现、串行写队列 |
| `app/src/main/java/com/ted/shouhuan/proto/` | 协议层：认证握手、心率指令、电量 |
| `app/src/main/java/com/ted/shouhuan/data/` | 本地存储（密钥/MAC）与数据模型 |
| `app/src/main/java/com/ted/shouhuan/service/` | 前台服务 + 通知监听 |
| `xiaomi_authkey.py` / `parse_log.py` | 取 AuthKey 的工具（配对时要用，见文末附录） |
| `tools/` | adb 直连取密钥、装机、打包等脚本 |

## 当前进度

- [x] 工程骨架 + Compose 界面（5 个页面，深色优先）
- [x] 协议调研：对着 Gadgetbridge 源码核对 UUID / 认证握手 / 心率指令 / 电量格式
- [x] BLE 连接层 + **两步**认证状态机（authFlags 用真机实测定成 `0x00`，见下表）
- [x] `assembleDebug` 出包，真机安装并冷启动
- [x] 心率页接上真实会话：连接 → 认证 → 单次测量，全过程有状态提示与失败原因
- [x] 真机连上手环、读到实时心率（76 bpm）
- [x] 心率页测量明细：读数 + 测量时刻（精确到秒）+ 耗时，并保留最近 20 次测量记录
- [ ] 睡眠数据拉取（走 chunked transfer 解析活动数据）
- [ ] 通知转发到手表
- [ ] 表盘管理 —— 正式功能还没做，但**协议可行性已经真机实测过**。
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
      表盘是否真的生效还没确认。探针在 debug 源集里：
      `app/src/debug/java/com/ted/shouhuan/debug/WatchFaceLab.kt`（release 包中不存在）。
      包体格式来自官方 App 的缓存：`files/WatchFace/data.zip`，
      里面是 160 张 `face_data_<风格>_<布局>_<序号>.png`。

## 构建与装机

```bash
just build            # 全项目：Python 校验/自检/测试/打包 + 编译 APK
just install          # 自动判断装什么（adb 设备 → APK；Termux → 小部件；否则 → 命令行入口）
```

只想跑一半、或者要单独装某一样时：

```bash
just build apk        # 只编译 APK
just build tools      # 只跑 Python 那套
just install apk      # 编译并装到 adb 连接的手机（自动处理国产 ROM 拦截）
just install cli      # 只装 xiaomi-authkey / parse-log 命令行入口
just apk release      # 编译 release
just android-clean    # 清理 Android 构建产物
```

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

## 协议要点（都从 Gadgetbridge 源码核对过，不是凭记忆写的）

| 项目 | 值 |
|---|---|
| MiBand 服务 | `0000fee0-…` / `0000fee1-…` |
| 认证特征 | `00000009-0000-3512-2118-0009af100700` |
| 心率服务 | 标准 GATT `0x180D`，测量 `0x2A37`(notify)、控制点 `0x2A39`(write) |
| 心率指令 | 连续 `15 01 01` / 手动 `15 02 01`（首字节 `0x15` 固定） |
| 认证 | **两步** AES-128-ECB：`82 00 02 01 00` → `10 82 01 <挑战值>` → `83 00 <AES(挑战值)>` → `10 83 01`。`cryptFlags=0x80`，**`authFlags` 必须是 `0x00`**（Gadgetbridge 用的 `0x08` 在本机固件上会被回 `10 83 07` 拒掉） |
| 电量 | 华米特征 `00000006-…`，`byte[1]` 是百分比 |
| 睡眠 | 活动数据里的 `rawKind`（浅睡 9 / 深睡 11），走 `00000020-…` chunked 拉取 |

---

# 附录：AuthKey 获取工具

手环的 AuthKey 是连接的前提（没有它认证过不去，手环会直接断开）。
以下是配对时用得上的工具，入口都在仓库根目录的 Python 脚本里。

> **2026-09-11 真机实测结论（重要）**
>
> 在 Redmi Note 12T Pro / HyperOS / Android 15 上，用 **adb 直读日志**把三台设备的
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
| **路线 A′ adb 直读（端到端）** | ✅ **2026-09-11 实机跑通**：Redmi Note 12T Pro / HyperOS / Android 15，一次取出 3 台设备的 MAC + AuthKey + 型号，手机端零操作 |
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
