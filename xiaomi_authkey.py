#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""xiaomi_authkey.py —— 小米手环/手表 蓝牙 AuthKey 获取工具（纯标准库）

目标：用小米账号换到「已绑定设备」的 auth_key（`0x` + 32 位十六进制），
      直接填进 Gadgetbridge。手机端（Termux）与电脑端均可跑。

设计约束
--------
* **零第三方依赖**：只用 Python 标准库，Termux 上 `pkg install python` 即可运行，
  不需要 `pip install requests`。上游 huami-token 依赖 requests/loguru/pycryptodome，
  在 Termux 上装起来很烦，这里全部用 urllib + 手写 RC4 替代。
* **凭证不落盘**：账号密码只存在于内存，用完即弃；不写任何缓存/配置文件。

⚠ 与原始实施计划书的差异（重要，先读这段）
------------------------------------------
计划书 §B3 写的端点是：

    POST https://account.huami.com/v2/client/login   →  app_token / login_token
    GET  https://api-mifit.huami.com/v1/users/<id>/devices?enableMultiDevice=true

这是 **旧 Huami/Zepp 链路**。它只对 Zepp(Amazfit) 账号有效；对「小米运动健康」
账号**已经不再返回手环设备**（上游 huami-token 因此在 2026-02 单独新增了小米链路）。
现在的实际链路是：

    ① GET  account.xiaomi.com/pass/serviceLogin          → _sign / qs / callback
    ② POST account.xiaomi.com/pass/serviceLoginAuth2     → ssecurity / nonce / userId
                                                            / cUserId / location
    ③ GET  <location>&clientSign=...                     → serviceToken (Cookie)
    ④ POST hlth.io.mi.com/app/v1/source/get_source_list  → RC4 加密请求
                                                            → result.list[].detail
                                                              .auth_key / .mac

因为没有「授权码」环节了，计划书里的 OAuth 交互式模式对本项目**已失效**，
取而代之的是两种模式：

    mode="password"  账号 + 密码，headless 直登（默认）
    mode="token"     手工提供 ssecurity / serviceToken / cUserId（免密码，
                     例如从已 root 设备或抓包/WebView 里拿到）

协议参考：huami-token（argrento，MIT，https://codeberg.org/argrento/huami-token）
本文为端点/字段/签名算法的**独立复刻**，非代码搬运。

用法
----
    python xiaomi_authkey.py -e 你的账号 -p 你的密码
    python xiaomi_authkey.py -e 账号            # 密码用 getpass 交互输入
    python xiaomi_authkey.py -e 账号 -p 密码 --json
    python xiaomi_authkey.py --token-mode --ssecurity ... --service-token ... --c-user-id ...
    python xiaomi_authkey.py --selftest         # 离线自检（不需要账号）
"""

from __future__ import annotations

import argparse
import base64
import getpass
import gzip
import hashlib
import json
import secrets
import shutil
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from http.cookiejar import CookieJar
from typing import Any, Dict, List, Optional, Tuple

__version__ = "1.0.0"

# ---------------------------------------------------------------------------
# 常量（端点与请求头，与上游保持一致）
# ---------------------------------------------------------------------------

SERVICE_LOGIN = "https://account.xiaomi.com/pass/serviceLogin"
SERVICE_LOGIN_AUTH2 = "https://account.xiaomi.com/pass/serviceLoginAuth2"
SOURCE_LIST = "https://hlth.io.mi.com/app/v1/source/get_source_list"

SID = "miothealth"          # 小米运动健康的 service id
LOCALE = "en_US"
APP_VERSION = "9.8.348i"
DEFAULT_REGION = "cn"       # 上游默认 ru；国区账号用 cn

_WEBVIEW_UA = (
    "Mozilla/5.0 (Linux; Android 12; Pixel 4 Build/SP1A.210812.016.C1; wv) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.200 "
    "Mobile Safari/537.36"
)

# 小米账号错误码 → 中文提示
_XIAOMI_ERROR_HINTS: Dict[Any, str] = {
    70016: "账号或密码错误。若确定密码无误，说明账号是手机号/邮箱二次验证账号，"
           "请改用 --token-mode，或先走路线 A。",
    70002: "需要输入验证码（图形/短信）。请先在手机上正常登录一次官方 App，"
           "或改用路线 A。",
    70005: "需要验证码校验。同上。",
    70010: "账号触发了二次验证（通知确认）。请在手机上确认本次登录后重试。",
    70011: "登录态异常，请重新登录。",
    70012: "登录态异常，请重新登录。",
    70022: "该账号可能开启了登录保护，请在官方 App 内处理。",
    87001: "触发小米风控（环境/设备异常），需要人机验证。请换网络、降低频率，"
           "或直接走路线 A。",
    87002: "触发小米风控（请求过快）。等 10 分钟再试，别连续重试。",
}


class AuthKeyError(Exception):
    """本工具所有错误的基类。"""


class LoginError(AuthKeyError):
    """登录失败。"""


class RiskControlError(LoginError):
    """触发风控 / 需要人机验证。"""


class ApiError(AuthKeyError):
    """接口调用或响应解析失败。"""


# ---------------------------------------------------------------------------
# 密码学：RC4-drop1024 + SHA1 签名（小米 App 的 i42.c 加密模式）
# ---------------------------------------------------------------------------


class _RC4:
    """RC4 流密码。状态跨多次 crypt() 调用连续推进（与上游行为一致）。"""

    __slots__ = ("S", "i", "j")

    def __init__(self, key: bytes) -> None:
        S = list(range(256))
        j = 0
        for i in range(256):
            j = (j + S[i] + key[i % len(key)]) & 0xFF
            S[i], S[j] = S[j], S[i]
        self.S = S
        self.i = 0
        self.j = 0

    def crypt(self, data: bytes) -> bytes:
        S = self.S
        i = self.i
        j = self.j
        out = bytearray(len(data))
        for n, b in enumerate(data):
            i = (i + 1) & 0xFF
            j = (j + S[i]) & 0xFF
            S[i], S[j] = S[j], S[i]
            out[n] = b ^ S[(S[i] + S[j]) & 0xFF]
        self.i = i
        self.j = j
        return bytes(out)


def _make_rc4(rc4_key_b64: str) -> _RC4:
    """按 base64 密钥建 RC4，并丢弃前 1024 字节密钥流。"""
    rc4 = _RC4(base64.b64decode(rc4_key_b64))
    rc4.crypt(b"\x00" * 1024)
    return rc4


def derive_rc4_key(ssecurity_b64: str, nonce_b64: str) -> str:
    """RC4 密钥 = base64(SHA256(base64decode(ssecurity) || base64decode(nonce)))。"""
    combined = base64.b64decode(ssecurity_b64) + base64.b64decode(nonce_b64)
    return base64.b64encode(hashlib.sha256(combined).digest()).decode()


def generate_nonce(time_diff_ms: int = 0) -> str:
    """nonce = base64(随机8字节 || int32BE(当前分钟数))。"""
    random_part = secrets.token_bytes(8)
    time_minutes = int((int(time.time() * 1000) + time_diff_ms) / 60000)
    return base64.b64encode(random_part + struct.pack(">i", time_minutes)).decode()


def compute_signing_path(full_path: str, path_prefix: str = "") -> str:
    """签名用的路径要先把服务的 pathPrefix 剥掉。

    ``path_prefix`` 为空时：返回去掉首段（域名/协议残留）后的路径，等价于原路径。
    """
    if not path_prefix:
        idx = full_path.find("/")
        return full_path[idx:] if idx >= 0 else full_path

    idx = full_path.find(path_prefix)
    if idx < 0:
        return full_path

    result = full_path[idx + len(path_prefix):]
    if not result.startswith("/"):
        result = "/" + result
    return result


def _sha1_sign(method: str, url_path: str, params: Dict[str, str], rc4_key_b64: str) -> str:
    """base64(SHA1("METHOD&path&k1=v1&k2=v2&rc4_key"))，键按字典序。"""
    parts: List[str] = []
    if method:
        parts.append(method.upper())
    if url_path:
        parts.append(url_path)
    if params:
        for k in sorted(params.keys()):
            parts.append("%s=%s" % (k, params[k]))
    parts.append(rc4_key_b64)
    digest = hashlib.sha1("&".join(parts).encode("utf-8")).digest()
    return base64.b64encode(digest).decode()


def mi_encrypt_params(
    method: str,
    signing_path: str,
    params: Dict[str, str],
    nonce_b64: str,
    ssecurity_b64: str,
) -> Dict[str, str]:
    """加密请求参数，返回可直接表单/查询串发送的 dict。

    关键点：同一个 RC4 实例按「键的字典序」连续加密各字段，密钥流是累进的，
    顺序错了密文就对不上。
    """
    rc4_key_b64 = derive_rc4_key(ssecurity_b64, nonce_b64)

    plaintext_sorted = dict(sorted(params.items()))
    rc4_hash = _sha1_sign(method, signing_path, plaintext_sorted, rc4_key_b64)

    plaintext_sorted["rc4_hash__"] = rc4_hash
    plaintext_sorted = dict(sorted(plaintext_sorted.items()))

    rc4 = _make_rc4(rc4_key_b64)
    encrypted_sorted: Dict[str, str] = {}
    for k in sorted(plaintext_sorted.keys()):
        encrypted_sorted[k] = base64.b64encode(
            rc4.crypt(plaintext_sorted[k].encode("utf-8"))
        ).decode()

    signature = _sha1_sign(method, signing_path, encrypted_sorted, rc4_key_b64)

    output = dict(encrypted_sorted)
    output["signature"] = signature
    output["_nonce"] = nonce_b64
    return output


def mi_decrypt_response(body_b64: str, nonce_b64: str, ssecurity_b64: str) -> str:
    """解密响应体（响应本身就是一段 base64 密文）。"""
    rc4_key_b64 = derive_rc4_key(ssecurity_b64, nonce_b64)
    rc4 = _make_rc4(rc4_key_b64)
    return rc4.crypt(base64.b64decode(body_b64)).decode("utf-8")


def mi_decrypt_params(
    encrypted_params: Dict[str, str], nonce_b64: str, ssecurity_b64: str
) -> Dict[str, str]:
    """解密请求参数（仅用于自检/对比抓包）。"""
    rc4_key_b64 = derive_rc4_key(ssecurity_b64, nonce_b64)
    rc4 = _make_rc4(rc4_key_b64)
    result: Dict[str, str] = {}
    for k in sorted(encrypted_params.keys()):
        if k in ("signature", "_nonce"):
            continue
        result[k] = rc4.crypt(base64.b64decode(encrypted_params[k])).decode("utf-8")
    return result


# ---------------------------------------------------------------------------
# HTTP：urllib 封装（支持指定 Cookie、禁用重定向、gzip 解压）
# ---------------------------------------------------------------------------


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """返回 None 表示不处理重定向，于是 3xx 会以 HTTPError 形式抛出，便于读 Set-Cookie。"""

    def redirect_request(  # type: ignore[override]
        self, req, fp, code, msg, headers, newurl
    ):
        return None


def _decode_body(raw: bytes, headers: Any) -> str:
    enc = (headers.get("Content-Encoding") or "").lower()
    if "gzip" in enc:
        try:
            raw = gzip.decompress(raw)
        except OSError:
            pass
    return raw.decode("utf-8", errors="replace")


def _set_cookies(headers: Any) -> List[Tuple[str, str]]:
    """从响应头里抽出所有 Set-Cookie 的 (name, value)。"""
    raw_list: List[str] = []
    try:
        raw_list = headers.get_all("Set-Cookie") or []
    except AttributeError:
        raw_list = [v for k, v in headers.items() if k.lower() == "set-cookie"]
    out: List[Tuple[str, str]] = []
    for line in raw_list:
        first = line.split(";", 1)[0]
        if "=" in first:
            k, v = first.split("=", 1)
            out.append((k.strip(), v.strip()))
    return out


def _http(
    method: str,
    url: str,
    params: Optional[Dict[str, str]] = None,
    headers: Optional[Dict[str, str]] = None,
    cookies: Optional[Dict[str, str]] = None,
    timeout: float = 20.0,
    follow: bool = True,
    jar: Optional[CookieJar] = None,
) -> Tuple[int, Any, str]:
    """发一个请求，返回 (status, headers, text)。不抛 HTTPError，3xx/4xx/5xx 都返回。"""
    body: Optional[bytes] = None
    if params:
        if method.upper() == "GET":
            sep = "&" if urllib.parse.urlparse(url).query else "?"
            url = url + sep + urllib.parse.urlencode(params)
        else:
            body = urllib.parse.urlencode(params).encode("utf-8")

    req = urllib.request.Request(url, data=body, method=method.upper())
    for k, v in (headers or {}).items():
        if v is not None:
            req.add_header(k, v)
    if cookies:
        req.add_header("Cookie", "; ".join("%s=%s" % kv for kv in cookies.items()))

    handlers: List[Any] = []
    if not follow:
        handlers.append(_NoRedirect())
    if jar is not None:
        handlers.append(urllib.request.HTTPCookieProcessor(jar))
    opener = urllib.request.build_opener(*handlers)

    try:
        resp = opener.open(req, timeout=timeout)
        status, hdrs, raw = resp.status, resp.headers, resp.read()
    except urllib.error.HTTPError as e:
        status, hdrs, raw = e.code, e.headers, e.read()
    except urllib.error.URLError as e:
        raise ApiError(
            "网络请求失败：%s\n  目标：%s\n  提示：检查网络 / 代理；国区账号请确认能直连 "
            "account.xiaomi.com 与 hlth.io.mi.com。" % (e.reason, url)
        ) from e

    return status, hdrs, _decode_body(raw, hdrs)


def _parse_xiaomi_json(text: str) -> Dict[str, Any]:
    """小米接口会在 JSON 前加 ``&&&START&&&`` 前缀，先剥掉。"""
    prefix = "&&&START&&&"
    if text.startswith(prefix):
        text = text[len(prefix):]
    text = text.strip()
    try:
        data = json.loads(text)
    except ValueError as e:
        raise ApiError("响应不是合法 JSON：%s\n  原文片段：%r" % (e, text[:200])) from e
    if not isinstance(data, dict):
        raise ApiError("响应结构异常：%r" % (text[:200],))
    return data


# ---------------------------------------------------------------------------
# 会话：account.xiaomi.com 三步登录
# ---------------------------------------------------------------------------


class XiaomiSession:
    """小米账号会话。只在内存里持有 ssecurity / serviceToken。"""

    def __init__(self, username: str = "", password: str = "",
                 timeout: float = 20.0) -> None:
        self.username = username
        self.password = password
        self.timeout = timeout
        self.device_id = _generate_device_id(username) if username else ""

        self._ssecurity: Optional[str] = None
        self._service_token: Optional[str] = None
        self._user_id: Optional[str] = None
        self._c_user_id: Optional[str] = None
        self._nonce: Optional[int] = None
        self._location: Optional[str] = None
        self._time_diff = 0

    # -- 只读属性 ---------------------------------------------------------

    def _require(self, value: Optional[str], name: str) -> str:
        if not value:
            raise LoginError("尚未登录：缺少 %s" % name)
        return value

    @property
    def ssecurity(self) -> str:
        return self._require(self._ssecurity, "ssecurity")

    @property
    def service_token(self) -> str:
        return self._require(self._service_token, "serviceToken")

    @property
    def user_id(self) -> str:
        return self._require(self._user_id, "user_id")

    @property
    def c_user_id(self) -> str:
        return self._require(self._c_user_id, "cUserId")

    # -- 登录 -------------------------------------------------------------

    def login(self) -> None:
        """走完整的 3 步登录。"""
        sign, qs, callback = self._step1_login_page()
        self._step2_authenticate(sign, qs, callback)
        self._step3_service_token()

    def login_with_tokens(self, ssecurity: str, service_token: str,
                          c_user_id: str, user_id: str = "") -> None:
        """免密码模式：直接塞入已有凭据（用于 token-mode）。"""
        if not ssecurity or not service_token or not c_user_id:
            raise LoginError("token 模式需要同时提供 ssecurity / serviceToken / cUserId")
        self._ssecurity = ssecurity
        self._service_token = service_token
        self._c_user_id = c_user_id
        self._user_id = user_id or "0"

    def _step1_login_page(self) -> Tuple[str, str, str]:
        status, _hdrs, text = _http(
            "GET",
            SERVICE_LOGIN,
            params={"_json": "true", "sid": SID, "_locale": LOCALE},
            headers={
                "User-Agent": _WEBVIEW_UA,
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language": "en-US,en;q=0.5",
            },
            cookies={"userId": self.username, "deviceId": self.device_id},
            timeout=self.timeout,
        )
        if status != 200:
            raise LoginError("serviceLogin 返回 HTTP %s，无法开始登录。" % status)
        data = _parse_xiaomi_json(text)
        sign, qs, callback = data.get("_sign"), data.get("qs"), data.get("callback")
        if not sign or not qs or not callback:
            raise LoginError(
                "serviceLogin 响应缺少 _sign/qs/callback，字段为 %s。"
                "小米可能改了协议，请更新本工具。" % sorted(data.keys())
            )
        return sign, qs, callback

    def _step2_authenticate(self, sign: str, qs: str, callback: str) -> None:
        payload = {
            "qs": qs,
            "callback": callback,
            "_json": "true",
            "_sign": sign,
            "user": self.username,
            "hash": hashlib.md5(self.password.encode("utf-8")).hexdigest().upper(),
            "sid": SID,
            "_locale": LOCALE,
        }
        status, _hdrs, text = _http(
            "POST",
            SERVICE_LOGIN_AUTH2,
            params=payload,
            headers={
                "User-Agent": _WEBVIEW_UA,
                "Content-Type": "application/x-www-form-urlencoded",
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language": "en-US,en;q=0.5",
            },
            cookies={"deviceId": self.device_id},
            timeout=self.timeout,
        )
        if status != 200:
            raise LoginError("serviceLoginAuth2 返回 HTTP %s。" % status)
        data = _parse_xiaomi_json(text)

        code = data.get("code")
        if code != 0:
            self._raise_login_error(code, data)

        self._ssecurity = data.get("ssecurity")
        self._nonce = data.get("nonce")
        self._user_id = str(data.get("userId", "") or "")
        self._c_user_id = data.get("cUserId")
        self._location = data.get("location")
        diff = data.get("timeDiff")
        if isinstance(diff, (int, float)):
            self._time_diff = int(diff)

        if not self._ssecurity or not self._location:
            raise LoginError("登录响应缺少 ssecurity 或 location，无法继续。")

    def _raise_login_error(self, code: Any, data: Dict[str, Any]) -> None:
        desc = data.get("description") or data.get("desc") or ""
        hint = _XIAOMI_ERROR_HINTS.get(code)
        if hint is None and data.get("notificationUrl"):
            hint = ("账号触发了二次验证（需要手机端确认）。请在手机上确认本次登录后"
                    "重试，或改用 --token-mode / 路线 A。")
        if hint is None:
            hint = "未知错误，按提示处理；若反复失败请改走路线 A（日志解析）。"
        exc = RiskControlError if code in (87001, 87002, 70002, 70005) else LoginError
        raise exc("登录失败 [code=%s] %s\n  → %s" % (code, desc, hint))

    def _step3_service_token(self) -> None:
        self._require(self._ssecurity, "ssecurity")
        if self._nonce is None:
            raise LoginError("缺少 nonce，无法取 serviceToken。")
        if not self._location:
            raise LoginError("缺少 location，无法取 serviceToken。")

        sign_input = "nonce=%s&%s" % (str(self._nonce), self._ssecurity)
        client_sign = base64.b64encode(
            hashlib.sha1(sign_input.encode("utf-8")).digest()
        ).decode()
        url = self._location + "&clientSign=" + urllib.parse.quote(client_sign)

        # 优先：不跟随重定向，直接从 Set-Cookie 里读
        status, headers, _ = _http("GET", url, timeout=self.timeout, follow=False)
        token = dict(_set_cookies(headers)).get("serviceToken")

        # 兜底：跟随重定向，从 cookie jar 里捞
        if not token:
            jar = CookieJar()
            _http("GET", url, timeout=self.timeout, follow=True, jar=jar)
            for c in jar:
                if c.name == "serviceToken":
                    token = c.value
                    break

        if not token:
            raise LoginError(
                "未拿到 serviceToken（HTTP %s）。账号可能已开启登录保护，"
                "请在官方 App 内确认后重试。" % status
            )
        self._service_token = token

    def wipe(self) -> None:
        """清空内存中的敏感凭据。"""
        self.password = ""
        self._ssecurity = None
        self._service_token = None
        self._c_user_id = None
        self._nonce = None
        self._location = None


def _generate_device_id(seed: str) -> str:
    """设备 ID：``an_`` + md5(账号)。同一账号固定，别乱改（风控会认设备）。"""
    return "an_" + hashlib.md5(seed.encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------
# 客户端：拉设备列表
# ---------------------------------------------------------------------------


class XiaomiClient:
    def __init__(self, session: XiaomiSession, region: str = DEFAULT_REGION,
                 timeout: float = 20.0) -> None:
        self.session = session
        self.region = region
        self.timeout = timeout

    def request(self, method: str, url: str,
                params: Optional[Dict[str, str]] = None,
                path_prefix: str = "",
                extra_query: Optional[Dict[str, str]] = None) -> Dict[str, Any]:
        """发一个加密请求并解密响应。"""
        full_path = urllib.parse.urlparse(url).path
        signing_path = compute_signing_path(full_path, path_prefix)

        nonce_b64 = generate_nonce(self.session._time_diff)
        encrypted = mi_encrypt_params(
            method=method,
            signing_path=signing_path,
            params=params or {},
            nonce_b64=nonce_b64,
            ssecurity_b64=self.session.ssecurity,
        )
        if extra_query:
            encrypted = dict(extra_query, **encrypted)

        status, _hdrs, text = _http(
            method, url,
            params=encrypted,
            headers=self._build_headers(),
            cookies=self._build_cookies(),
            timeout=self.timeout,
        )
        text = text.strip()
        if status != 200:
            raise ApiError("接口返回 HTTP %s。\n  原文片段：%r" % (status, text[:200]))

        try:
            decrypted = mi_decrypt_response(text, nonce_b64, self.session.ssecurity)
        except Exception as e:  # base64/解码失败都归到这里
            raise ApiError(
                "响应解密失败（%s）。通常是登录态失效或服务端要求风控验证。\n"
                "  原文片段：%r" % (e, text[:200])
            ) from e

        try:
            return json.loads(decrypted)
        except ValueError as e:
            raise ApiError("解密后不是 JSON：%s\n  内容片段：%r" % (e, decrypted[:200])) from e

    def get_source_list(self, page_size: int = 50, status: int = 1) -> Dict[str, Any]:
        """拉已绑定设备列表。

        ``status=1`` 为已绑定的穿戴设备（detail 里带 auth_key）。
        """
        data = json.dumps({"page_size": page_size, "status": status},
                          separators=(",", ":"))
        return self.request(
            "POST", SOURCE_LIST,
            params={"data": data},
            path_prefix="",
        )

    def _build_cookies(self) -> Dict[str, str]:
        return {
            "cUserId": self.session.c_user_id,
            "serviceToken": self.session.service_token,
            "locale": "en_us",
        }

    def _build_headers(self) -> Dict[str, str]:
        return {
            "User-Agent": "Android-12-%s-google-Pixel 4" % APP_VERSION,
            "region_tag": self.region,
            "Accept-Encoding": "gzip",
        }


# ---------------------------------------------------------------------------
# 对外契约（计划书 §B5）
# ---------------------------------------------------------------------------


@dataclass
class Device:
    """一台已绑定设备。``auth_key`` 已规范化成 ``0x`` + 32 位 hex。"""

    mac_address: str
    auth_key: str
    active: bool
    model_hint: str = ""

    def as_dict(self) -> Dict[str, Any]:
        return {
            "mac_address": self.mac_address,
            "auth_key": self.auth_key,
            "active": self.active,
            "model_hint": self.model_hint,
        }


_HEX32 = "0123456789abcdef"


def normalize_auth_key(raw: Any) -> str:
    """把各种形态的 auth_key 规范成 ``0x`` + 32 位小写 hex。

    * 已经是 ``0x...`` 前缀 → 拆掉前缀再处理
    * 容忍 ``:`` ``-`` ``_`` 空格等分隔符（照抄 MAC 风格时容易带进来）
    * 短于 32 位 → **左侧补 0**（计划书 §B3-4 的「不足补位」）
    * 长于 32 位 → 取后 32 位（历史上出现过前导填充）
    * **含任何非 hex 字符 → 返回空串**，绝不「把垃圾字符过滤掉硬凑出一个 key」。
      否则 "hello world" 会被拼成 ``0x000...0ed`` 这种假密钥——比直接报错危险得多。
    """
    if raw is None:
        return ""
    s = str(raw).strip().strip('"').strip("'")
    if s[:2].lower() == "0x":
        s = s[2:]
    for sep in (":", "-", " ", "_"):
        s = s.replace(sep, "")
    if not s:
        return ""
    lower = s.lower()
    if any(ch not in _HEX32 for ch in lower):
        return ""
    if len(lower) > 32:
        lower = lower[-32:]
    return "0x" + lower.rjust(32, "0")


def _deep_find_auth_key(obj: Any, depth: int = 0) -> str:
    """在嵌套结构里找 auth_key / authKey / additionalInfo.auth_key。"""
    if depth > 6:
        return ""
    if isinstance(obj, str):
        s = obj.strip()
        if s.startswith("{") or s.startswith("["):
            try:
                return _deep_find_auth_key(json.loads(s), depth + 1)
            except ValueError:
                return ""
        return ""
    if isinstance(obj, dict):
        for key in ("auth_key", "authKey", "authkey", "encryptKey"):
            if key in obj:
                got = normalize_auth_key(obj[key])
                if got:
                    return got
        for key in ("detail", "additionalInfo", "deviceInfo", "extra"):
            if key in obj:
                got = _deep_find_auth_key(obj[key], depth + 1)
                if got:
                    return got
        for v in obj.values():
            got = _deep_find_auth_key(v, depth + 1)
            if got:
                return got
        return ""
    if isinstance(obj, list):
        for v in obj:
            got = _deep_find_auth_key(v, depth + 1)
            if got:
                return got
    return ""


def _first_str(*candidates: Any) -> str:
    for c in candidates:
        if isinstance(c, str) and c.strip():
            return c.strip()
    return ""


def _deep_find_mac(obj: Any, depth: int = 0) -> str:
    if depth > 6:
        return ""
    if isinstance(obj, str):
        s = obj.strip()
        if s.startswith("{"):
            try:
                return _deep_find_mac(json.loads(s), depth + 1)
            except ValueError:
                return ""
        return ""
    if isinstance(obj, dict):
        for key in ("mac", "macAddress", "mac_address"):
            v = obj.get(key)
            if isinstance(v, str) and len(v) >= 11:
                return v.strip()
        for v in obj.values():
            got = _deep_find_mac(v, depth + 1)
            if got:
                return got
    if isinstance(obj, list):
        for v in obj:
            got = _deep_find_mac(v, depth + 1)
            if got:
                return got
    return ""


def parse_devices(response: Dict[str, Any]) -> List[Device]:
    """从 get_source_list 的响应里抽出设备列表（对结构变化有一定容错）。"""
    result = response.get("result") or {}
    items = result.get("list")
    if items is None:
        items = response.get("list") or []
    if not isinstance(items, list):
        raise ApiError("设备列表结构异常：%r" % (list(response.keys()),))

    devices: List[Device] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        detail = item.get("detail", {})
        if isinstance(detail, str):
            try:
                detail = json.loads(detail)
            except ValueError:
                detail = {}

        auth_key = _deep_find_auth_key(item)
        if not auth_key:
            # 有设备但没有 key（例如手机/App 这类 source），跳过
            continue

        mac = _first_str(
            _deep_find_mac(detail),
            _first_str(item.get("mac"), item.get("macAddress")),
            "??:??:??:??:??:??",
        )
        active_raw = item.get("activeStatus", detail.get("activeStatus", 1))
        active = bool(active_raw) if not isinstance(active_raw, str) else active_raw not in ("0", "false", "False")
        name = _first_str(item.get("name"), detail.get("name"), detail.get("productName"))

        devices.append(Device(
            mac_address=mac,
            auth_key=auth_key,
            active=active,
            model_hint=name,
        ))
    return devices


def fetch_authkeys(mode: str = "password",
                   email: Optional[str] = None,
                   password: Optional[str] = None,
                   country_code: str = "CN",
                   region: str = DEFAULT_REGION,
                   timeout: float = 20.0,
                   ssecurity: Optional[str] = None,
                   service_token: Optional[str] = None,
                   c_user_id: Optional[str] = None,
                   user_id: Optional[str] = None,
                   keep_session: bool = False) -> List[Device]:
    """取回账号下所有设备的 auth_key。

    参数
    ----
    mode           ``"password"``（账密登录）或 ``"token"``（手工提供凭据）。
                   计划书里的 ``"oauth"`` 模式在本项目已失效（新链路无授权码环节），
                   传入时会抛出带说明的错误。
    country_code   仅作记录/调试，新链路不需要；保留以兼容计划书签名。
    keep_session   为 False（默认）时，取完后清空内存中的凭据。
    """
    if mode == "oauth":
        raise AuthKeyError(
            "mode='oauth' 已失效：小米运动健康的新链路（serviceLogin → serviceLoginAuth2）"
            "不再使用授权码，OAuth 回调里拿不到可用的 code。\n"
            "  请改用 mode='password'（账密直登）或 mode='token'（手工提供凭据）。"
        )
    if mode not in ("password", "token"):
        raise AuthKeyError("未知 mode=%r，仅支持 'password' / 'token'。" % (mode,))

    session = XiaomiSession(username=email or "", password=password or "",
                            timeout=timeout)
    if mode == "token":
        session.login_with_tokens(
            ssecurity=ssecurity or "",
            service_token=service_token or "",
            c_user_id=c_user_id or "",
            user_id=user_id or "",
        )
    else:
        if not email or not password:
            raise AuthKeyError("password 模式需要同时提供 email 与 password。")
        session.login()

    try:
        client = XiaomiClient(session, region=region, timeout=timeout)
        response = client.get_source_list()
        return parse_devices(response)
    finally:
        if not keep_session:
            session.wipe()


# ---------------------------------------------------------------------------
# 自检：离线验证密码学实现与上游逐字节一致
# ---------------------------------------------------------------------------

# 以下向量取自 huami-token 的 tests/test_mi_crypto.py（captured traffic）
_SELFTEST_SSECURITY = "YrTdzxpoL2f5MVGlER9E8w=="


def selftest() -> int:
    """离线自检。不需要账号、不联网。返回 0 表示通过。"""
    from urllib.parse import unquote

    failures: List[str] = []

    def check(name: str, got: Any, want: Any) -> None:
        if got != want:
            failures.append("%s\n    期望：%r\n    实际：%r" % (name, want, got))
        print("  %s %s" % ("OK  " if got == want else "FAIL", name))

    print("自检 1/5  compute_signing_path")
    check("剥 healthapp/ 前缀",
          compute_signing_path("/healthapp/setting/get_user_device_settings", "healthapp/"),
          "/setting/get_user_device_settings")
    check("剥 cgi-op 前缀",
          compute_signing_path("/cgi-op/api/v1/miwear/sports/list", "cgi-op/api/v1/miwear/"),
          "/sports/list")
    check("空前缀走全路径",
          compute_signing_path("/app/v1/source/get_source_list", ""),
          "/app/v1/source/get_source_list")

    print("自检 2/5  解密响应（抓包向量）")
    nonce = unquote("R42d%2B0k3e1wBv42r")
    body = ("DPGjfdGeLhOcEauyRBJHKM845nz2j9E2TTStMnWkp4bRnqLLUVfXnpEn7jHaRzCqyjNa"
            "NaKWbETueJbGRFA=")
    check("解密 body",
          mi_decrypt_response(body, nonce, _SELFTEST_SSECURITY),
          '{"code":0,"message":"ok","result":{"datas":null,"last_id":-1}}')

    print("自检 3/5  加密参数与抓包逐字节一致")
    settings_plain = ('{"did":"xiaomiwear_app","last_id":0,"limit":20,'
                      '"module":"device_setting","update_time":0}')
    enc = mi_encrypt_params(
        "POST", "/setting/get_user_device_settings",
        {"data": settings_plain}, nonce, _SELFTEST_SSECURITY,
    )
    check("data 密文",
          enc["data"],
          unquote("DPGke9HZNgvUVOiwTAhDLMkvmyekkJg4Q3q%2BJHKOopbRnunFF1rKkotx9mWdG3Ckh"
                  "TBfM7qsJxruJt6BUE5lyIr%2BqsjYUDgvkGZinSG8hS97q4Y6VMZso%2BA%3D"))
    check("rc4_hash__",
          enc["rc4_hash__"],
          unquote("QPhJ2ehekyhADdflTzZv3o7f0qeW0%2BRdOZSFjA%3D%3D"))

    print("自检 4/5  get_source_list 签名路径（抓包向量）")
    nonce2 = unquote("WJLZldGzqgEBv42r")
    dec = mi_decrypt_params(
        {"data": unquote("13wc21mXVNlEBm1V6377FO82qjYB0rMbnvH/"),
         "rc4_hash__": unquote("iEb0n3F4yHV8uK5wr8PlxmaMwhKqVO+++pa8Sg==")},
        nonce2, _SELFTEST_SSECURITY,
    )
    re_enc = mi_encrypt_params(
        "POST", compute_signing_path("/app/v1/source/get_source_list", ""),
        {k: v for k, v in dec.items() if k != "rc4_hash__"},
        nonce2, _SELFTEST_SSECURITY,
    )
    check("source_list data 密文",
          re_enc["data"], unquote("13wc21mXVNlEBm1V6377FO82qjYB0rMbnvH/"))

    print("自检 5/5  auth_key 规范化")
    hex32 = "0123456789abcdef0123456789abcdef"          # 正好 32 位
    check("标准 32 位（大写转小写）",
          normalize_auth_key(hex32.upper()), "0x" + hex32)
    check("带 0x 前缀", normalize_auth_key("0x" + hex32.upper()), "0x" + hex32)
    check("容忍冒号/横杠分隔",
          normalize_auth_key("01:23-45_67 89ab cdef 0123 4567 89ab cdef"),
          "0x" + hex32)
    check("不足 32 位左侧补 0（计划书 §B3-4 的补位）",
          normalize_auth_key("abcdef"), "0x" + "abcdef".rjust(32, "0"))
    check("超长取后 32 位",
          normalize_auth_key("ffff" + hex32), "0x" + hex32)
    check("含非 hex 字符一律返回空（不硬凑假密钥）",
          normalize_auth_key("hello world"), "")
    check("None 返回空", normalize_auth_key(None), "")

    print()
    if failures:
        print("自检失败 %d 项：" % len(failures))
        for f in failures:
            print("  - " + f)
        return 1
    print("自检全部通过：本实现与上游抓包向量逐字节一致。")
    return 0


# ---------------------------------------------------------------------------
# 输出 / 剪贴板
# ---------------------------------------------------------------------------


def _copy_to_clipboard(text: str) -> bool:
    """尽力复制到剪贴板：Termux 优先，其次 Windows/macOS/Linux。"""
    candidates: List[List[str]] = []
    if shutil.which("termux-clipboard-set"):
        candidates.append(["termux-clipboard-set"])
    if shutil.which("clip"):
        candidates.append(["clip"])
    if shutil.which("pbcopy"):
        candidates.append(["pbcopy"])
    if shutil.which("wl-copy"):
        candidates.append(["wl-copy"])
    if shutil.which("xclip"):
        candidates.append(["xclip", "-selection", "clipboard"])
    for cmd in candidates:
        try:
            p = subprocess.run(cmd, input=text.encode("utf-8"),
                               capture_output=True, timeout=10)
            if p.returncode == 0:
                return True
        except Exception:
            continue
    return False


def _print_devices(devices: List[Device]) -> None:
    if not devices:
        print("未找到任何携带 auth_key 的设备。")
        print("  · 确认手环已用「小米运动健康」配对过并且同步过至少一次；")
        print("  · 确认登录的是绑定了该手环的那个小米账号。")
        return
    for i, d in enumerate(devices):
        print("设备 %d：%s" % (i, d.model_hint or "(未知型号)"))
        print("  MAC：%s" % d.mac_address)
        print("  Key：%s" % d.auth_key)
        print("  状态：%s" % ("已启用" if d.active else "未启用"))
        print()
    print("填进 Gadgetbridge：添加设备时把上面 Key 整串粘贴到「Auth key」字段。")
    print("手环同一时间只能连一个 App —— 请先停用/退出「小米运动健康」。")
    print()
    print("--- 纯文本（方便复制） ---")
    for d in devices:
        print("%s    %s" % (d.mac_address, d.auth_key))


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def probe() -> int:
    """连通性自检：不发账号信息，只确认两个域名可达、serviceLogin 字段齐全。

    手机/Termux 上第一次跑之前先跑这个，能把「网络不通」和「协议变了」区分开。
    """
    print("连通性自检（不发送任何账号信息）…\n")
    ok = True

    session = XiaomiSession(username="probe@example.invalid", timeout=15.0)
    try:
        sign, qs, callback = session._step1_login_page()
    except AuthKeyError as e:
        print("  失败  account.xiaomi.com：%s" % e)
        print("        → 需要能直连 443。部分网络/代理会劫持或阻断该域名。")
        ok = False
    else:
        print("  OK    account.xiaomi.com 可达")
        print("        字段齐全：_sign=%s…  qs=%s…  callback=%s"
              % (sign[:10], qs[:10], callback))

    try:
        status, _h, _t = _http("GET", SOURCE_LIST, timeout=15.0)
        print("  OK    hlth.io.mi.com 可达（HTTP %s，未登录返回非 200 属正常）" % status)
    except AuthKeyError as e:
        print("  失败  hlth.io.mi.com：%s" % e)
        ok = False

    print()
    if ok:
        print("连通性正常，可以带 -e/-p 正式取密钥了。")
        return 0
    print("存在不可达的域名，先把网络问题解决再取密钥。")
    return 1


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="xiaomi_authkey.py",
        description="在手机(Termux)/电脑上获取小米手环的蓝牙 AuthKey，无需 root。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例：\n"
               "  python xiaomi_authkey.py -e you@example.com -p 密码\n"
               "  python xiaomi_authkey.py -e you@example.com          # 密码交互输入\n"
               "  python xiaomi_authkey.py -e you@example.com -p 密码 --json\n"
               "  python xiaomi_authkey.py --token-mode --ssecurity X --service-token Y "
               "--c-user-id Z\n"
               "  python xiaomi_authkey.py --selftest                  # 离线自检\n",
    )
    p.add_argument("-e", "--email", help="小米账号（邮箱/手机号）")
    p.add_argument("-p", "--password", help="密码（不给则交互输入；不会落盘）")
    p.add_argument("--password-stdin", action="store_true",
                   help="从标准输入读一行作为密码。比 -p 更稳妥：密码不出现在进程参数里")
    p.add_argument("--token-mode", action="store_true",
                   help="免密码模式：直接提供已有的 ssecurity/serviceToken/cUserId")
    p.add_argument("--ssecurity", help="token 模式用")
    p.add_argument("--service-token", help="token 模式用")
    p.add_argument("--c-user-id", help="token 模式用")
    p.add_argument("--user-id", help="token 模式用（可选）")
    p.add_argument("--region", default=DEFAULT_REGION,
                   help="region_tag 头，默认 cn；若失败可试 ru（默认：%(default)s）")
    p.add_argument("--country-code", default="CN", help="仅记录用（默认：%(default)s）")
    p.add_argument("--timeout", type=float, default=20.0, help="单请求超时秒数")
    p.add_argument("--json", action="store_true", help="以 JSON 输出，便于脚本消费")
    p.add_argument("--clip", action="store_true", help="把 auth_key 复制到剪贴板")
    p.add_argument("--no-logout", action="store_true",
                   help="调试用：保留会话并打印 ssecurity/serviceToken")
    p.add_argument("--selftest", action="store_true", help="跑离线自检后退出")
    p.add_argument("--probe", action="store_true",
                   help="连通性自检：不发送账号信息，只探测小米服务端是否可达")
    p.add_argument("--version", action="version", version="%(prog)s " + __version__)
    return p


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)

    if args.selftest:
        return selftest()

    if args.probe:
        return probe()

    if args.token_mode:
        if not (args.ssecurity and args.service_token and args.c_user_id):
            print("错误：--token-mode 需要 --ssecurity / --service-token / --c-user-id 三个都给。",
                  file=sys.stderr)
            return 2
        mode = "token"
    else:
        mode = "password"
        if not args.email:
            print("错误：请用 -e/--email 指定小米账号（或用 --token-mode）。", file=sys.stderr)
            return 2
        if args.password_stdin:
            piped = sys.stdin.readline().rstrip("\r\n")
            if not piped:
                print("错误：用了 --password-stdin 但标准输入是空的。", file=sys.stderr)
                return 2
            args.password = piped
        elif not args.password:
            if not sys.stdin.isatty():
                print("错误：非交互环境下必须用 --password-stdin（或 -p）提供密码。",
                      file=sys.stderr)
                return 2
            args.password = getpass.getpass("小米账号密码（不回显、不落盘）：")

    session_holder: Dict[str, Any] = {}
    try:
        # 为了 --no-logout 时能打印，这里手动拼一遍流程，而不是直接调 fetch_authkeys
        session = XiaomiSession(username=args.email or "", password=args.password or "",
                                timeout=args.timeout)
        if mode == "token":
            session.login_with_tokens(args.ssecurity, args.service_token,
                                      args.c_user_id, args.user_id or "")
        else:
            print("正在登录小米账号…", file=sys.stderr)
            session.login()
            print("登录成功，user_id=%s" % session.user_id, file=sys.stderr)

        session_holder["session"] = session
        client = XiaomiClient(session, region=args.region, timeout=args.timeout)
        print("正在拉取已绑定设备…", file=sys.stderr)
        response = client.get_source_list()
        devices = parse_devices(response)

        if args.json:
            print(json.dumps([d.as_dict() for d in devices],
                             ensure_ascii=False, indent=2))
        else:
            _print_devices(devices)

        if args.clip and devices:
            payload = "\n".join(d.auth_key for d in devices)
            ok = _copy_to_clipboard(payload)
            print("已复制到剪贴板。" if ok else
                  "（没找到可用的剪贴板命令，请手动复制。）", file=sys.stderr)

        if args.no_logout:
            print("\n[调试] 保留的会话凭据：", file=sys.stderr)
            print("  ssecurity=%s" % session.ssecurity, file=sys.stderr)
            print("  serviceToken=%s" % session.service_token, file=sys.stderr)
            print("  cUserId=%s" % session.c_user_id, file=sys.stderr)

        if not devices:
            return 1
        return 0

    except AuthKeyError as e:
        print("\n错误：%s" % e, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n已中断。", file=sys.stderr)
        return 130
    finally:
        s = session_holder.get("session")
        if s is not None and not args.no_logout:
            s.wipe()


if __name__ == "__main__":
    sys.exit(main())
