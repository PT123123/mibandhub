#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""离线测试套件：py -m unittest discover -s tests -v

不联网、不需要账号。覆盖：
  * 密码学实现与上游抓包向量的一致性（复用 --selftest）
  * AuthKey 规范化（补位 / 截尾 / 拒绝非 hex）
  * get_source_list 响应解析（新结构 + 旧结构 + 缺 key 的设备）
  * 日志解析（路线 A）：配对 MAC、去重、过滤占位符、转义 JSON
  * CLI 端到端（subprocess 跑真实进程）
"""

from __future__ import annotations

import contextlib
import io
import json
import os
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
FIXTURE = os.path.join(HERE, "fixture_sample.log")

# fixture 里的真实密钥条数。往夹具里加密钥时**只改这一处**，
# 别在多处重复硬编码数字（同类手算错误已经栽过一次了）。
FIXTURE_KEY_COUNT = 5

sys.path.insert(0, ROOT)

import parse_log                      # noqa: E402
import xiaomi_authkey as xa           # noqa: E402

PY = sys.executable


class TestCryptoVectors(unittest.TestCase):
    """密码学部分靠上游抓包向量把关，一个断言覆盖全部。"""

    def test_selftest_all_pass(self) -> None:
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = xa.selftest()
        self.assertEqual(rc, 0, "自检输出：\n" + buf.getvalue())

    def test_encrypt_is_deterministic_for_same_nonce(self) -> None:
        nonce = "R42d+0k3e1wBv42r"
        ssec = "YrTdzxpoL2f5MVGlER9E8w=="
        a = xa.mi_encrypt_params("POST", "/x/y", {"data": "{}"}, nonce, ssec)
        b = xa.mi_encrypt_params("POST", "/x/y", {"data": "{}"}, nonce, ssec)
        self.assertEqual(a, b)

    def test_roundtrip_with_generated_nonce(self) -> None:
        nonce = xa.generate_nonce()
        self.assertEqual(len(nonce), 16)          # 12 字节 → base64 16 字符
        ssec = "YrTdzxpoL2f5MVGlER9E8w=="
        enc = xa.mi_encrypt_params("POST", "/p", {"data": '{"k":"v"}'}, nonce, ssec)
        dec = xa.mi_decrypt_params(
            {k: v for k, v in enc.items() if k not in ("signature", "_nonce")}, nonce, ssec
        )
        self.assertEqual(dec["data"], '{"k":"v"}')


class TestNormalizeAuthKey(unittest.TestCase):
    def test_pads_left_to_32(self) -> None:
        self.assertEqual(xa.normalize_auth_key("abcdef"),
                         "0x" + "abcdef".rjust(32, "0"))

    def test_strips_0x_and_lowercases(self) -> None:
        h32 = "0123456789abcdef0123456789abcdef"
        self.assertEqual(xa.normalize_auth_key("0x" + h32.upper()), "0x" + h32)

    def test_tolerates_separators(self) -> None:
        self.assertEqual(
            xa.normalize_auth_key("01:23-45_67 89ab cdef 0123 4567 89ab cdef"),
            "0x0123456789abcdef0123456789abcdef",
        )

    def test_truncates_to_last_32(self) -> None:
        h32 = "0123456789abcdef0123456789abcdef"
        self.assertEqual(xa.normalize_auth_key("ffff" + h32), "0x" + h32)

    def test_rejects_non_hex_instead_of_silently_scrubbing(self) -> None:
        """回归测试：早期实现会把垃圾字符过滤掉硬凑出假密钥。"""
        self.assertEqual(xa.normalize_auth_key("hello world"), "")
        self.assertEqual(xa.normalize_auth_key("not-a-key"), "")
        self.assertEqual(xa.normalize_auth_key(None), "")
        self.assertEqual(xa.normalize_auth_key(""), "")


class TestParseDevices(unittest.TestCase):
    def test_modern_shape(self) -> None:
        raw_key = "0xA3C10E34E5C14637EEA6B9EFC06106"   # 故意只有 30 位，考补位
        response = {"code": 0, "result": {"list": [
            {"name": "Mi Band 9",
             "detail": json.dumps({
                 "mac": "AA:BB:CC:DD:EE:FF",
                 "auth_key": raw_key})},
            {"name": "Redmi Band 3",
             "detail": json.dumps({
                 "mac": "11:22:33:44:55:66",
                 "auth_key": "a1b2c3d4e5f60718293a4b5c6d7e8f90",
                 "activeStatus": 0})},
        ]}}
        devices = xa.parse_devices(response)
        self.assertEqual(len(devices), 2)
        # 期望值由输入推导，不手算，避免把补位位数写错
        self.assertEqual(devices[0].auth_key,
                         "0x" + raw_key[2:].lower().rjust(32, "0"))
        self.assertEqual(devices[0].mac_address, "AA:BB:CC:DD:EE:FF")
        self.assertEqual(devices[0].model_hint, "Mi Band 9")
        self.assertTrue(devices[0].active)
        self.assertFalse(devices[1].active)
        for d in devices:
            self.assertRegex(d.auth_key, r"^0x[0-9a-f]{32}$")

    def test_legacy_shape_with_additional_info(self) -> None:
        """旧 Huami 结构（macAddress + additionalInfo）也要能解析，便于容错。"""
        response = {"result": {"list": [
            {"macAddress": "AA:BB:CC:DD:EE:FF", "activeStatus": 1,
             "additionalInfo": json.dumps({"auth_key": "deadbeefcafebabe0123456789abcdef"})},
        ]}}
        devices = xa.parse_devices(response)
        self.assertEqual(len(devices), 1)
        self.assertEqual(devices[0].auth_key, "0xdeadbeefcafebabe0123456789abcdef")

    def test_device_without_key_is_skipped(self) -> None:
        response = {"result": {"list": [
            {"name": "手机", "detail": json.dumps({"mac": "11:22:33:44:55:66"})},
        ]}}
        self.assertEqual(xa.parse_devices(response), [])

    def test_empty_list(self) -> None:
        self.assertEqual(xa.parse_devices({"result": {"list": []}}), [])
        self.assertEqual(xa.parse_devices({}), [])

    def test_bad_structure_raises(self) -> None:
        with self.assertRaises(xa.ApiError):
            xa.parse_devices({"result": {"list": "not-a-list"}})


class TestFetchAuthkeysContract(unittest.TestCase):
    def test_oauth_mode_is_rejected_with_explanation(self) -> None:
        """计划书的 oauth 模式在新链路下已失效，必须给出可操作的说明。"""
        with self.assertRaises(xa.AuthKeyError) as ctx:
            xa.fetch_authkeys(mode="oauth", email="a@b.c", password="x")
        msg = str(ctx.exception)
        self.assertIn("password", msg)
        self.assertIn("token", msg)

    def test_unknown_mode(self) -> None:
        with self.assertRaises(xa.AuthKeyError):
            xa.fetch_authkeys(mode="whatever")

    def test_password_mode_requires_credentials(self) -> None:
        with self.assertRaises(xa.AuthKeyError):
            xa.fetch_authkeys(mode="password", email="a@b.c")

    def test_token_mode_requires_all_three(self) -> None:
        with self.assertRaises(xa.LoginError):
            xa.fetch_authkeys(mode="token", ssecurity="s", service_token="t")


class TestParseLog(unittest.TestCase):
    def setUp(self) -> None:
        with open(FIXTURE, "r", encoding="utf-8") as fh:
            self.hits = parse_log.scan_lines(fh)

    def test_finds_all_real_keys(self) -> None:
        keys = sorted(h.key for h in self.hits)
        self.assertEqual(keys, sorted([
            "0x4f3a9c1e7b2d8056ae31c9f04d6b7e28",
            "0xa1b2c3d4e5f60718293a4b5c6d7e8f90",
            "0xdeadbeefcafebabe0123456789abcdef",
            # 以下两条为实测格式（huamiAuthKey），见 fixture 末尾注释
            "0xc0ffee1234567890abcdef0123456789",
            "0x1234567890abcdef1234567890abcdef",
        ]))

    def test_filters_all_zero_placeholder(self) -> None:
        self.assertNotIn("0x" + "0" * 32, [h.key for h in self.hits])

    def test_deduplicates_same_key_same_mac(self) -> None:
        dupes = [h for h in self.hits
                 if h.key == "0xdeadbeefcafebabe0123456789abcdef"]
        self.assertEqual(len(dupes), 1)

    def test_pairs_nearby_mac(self) -> None:
        by_key = {h.key: h for h in self.hits}
        self.assertEqual(by_key["0x4f3a9c1e7b2d8056ae31c9f04d6b7e28"].mac,
                         "AA:BB:CC:DD:EE:FF")
        self.assertEqual(by_key["0xa1b2c3d4e5f60718293a4b5c6d7e8f90"].mac,
                         "11:22:33:44:55:66")

    def test_handles_escaped_json_quotes(self) -> None:
        """日志里嵌 JSON 时引号是转义的（``\\"auth_key\\":\\"...\\"``）。

        转义先还原成普通引号，再由通用 ``authKey`` 线索统一命中 ——
        source 不再单列一个 ``(json)`` 变体（那个变体正是当初漏掉
        huamiAuthKey 的原因）。
        """
        hit = next(h for h in self.hits
                   if h.key == "0xa1b2c3d4e5f60718293a4b5c6d7e8f90")
        self.assertEqual(hit.source, "authKey")

    def test_ignores_non_key_garbage_and_short_values(self) -> None:
        keys = [h.key for h in self.hits]
        self.assertNotIn("0x" + "0" * 25 + "abcdef", keys)
        # 条数集中定义在 FIXTURE_KEY_COUNT，改夹具时只改那一处
        self.assertEqual(len(self.hits), FIXTURE_KEY_COUNT)

    def test_huami_authkey_pairs_mac_on_same_line(self) -> None:
        """实测格式（小米运动健康 3.48.3）。

        最强线索：huamiAuthKey 与 mac 在**同一行**，配对零歧义，且不需要
        靠 mac_lookback 去猜；顺带还能带出设备名与型号。
        """
        hit = next(h for h in self.hits
                   if h.key == "0xc0ffee1234567890abcdef0123456789")
        self.assertEqual(hit.source, "huamiAuthKey")
        self.assertEqual(hit.mac, "A1:B2:C3:D4:E5:F6")
        self.assertEqual(hit.name, "小米手环5")
        self.assertEqual(hit.model, "hmpace.bracelet.v5")

    def test_field_name_matching_is_case_insensitive(self) -> None:
        """历史 bug 回归：正则在开头 ``auth`` 上大小写敏感。

        以前 ``huamiAuthKey`` 匹配不到，因为 ``[Kk]`` 只补了 K 没补 A。
        全小写的 ``huamiauthkey`` 现在也应被同一条线索兜住。
        """
        hit = next(h for h in self.hits
                   if h.key == "0x1234567890abcdef1234567890abcdef")
        self.assertEqual(hit.source, "huamiAuthKey")

    def test_hit_as_dict_carries_device_metadata(self) -> None:
        hit = next(h for h in self.hits
                   if h.key == "0xc0ffee1234567890abcdef0123456789")
        d = hit.as_dict()
        self.assertEqual(d["device_name"], "小米手环5")
        self.assertEqual(d["model"], "hmpace.bracelet.v5")

    def test_looks_like_real_key_helper(self) -> None:
        self.assertFalse(parse_log._looks_like_real_key("0x" + "0" * 32))
        self.assertFalse(parse_log._looks_like_real_key("0x" + "f" * 32))
        self.assertFalse(parse_log._looks_like_real_key("0xdead"))
        self.assertTrue(parse_log._looks_like_real_key(
            "0x4f3a9c1e7b2d8056ae31c9f04d6b7e28"))


class TestCliEndToEnd(unittest.TestCase):
    """真的开子进程跑，验证从命令行到输出的整条链路。"""

    # 子进程的中文输出钉死走 UTF-8：Windows 上管道默认用 ANSI 代码页（GBK）
    # 编码，而下面所有断言都按 utf-8 解码 —— 不钉死的话，在 GBK 控制台环境
    # 下子进程吐 GBK 字节，父进程 UnicodeDecodeError（stdout 直接变 None）
    ENV = {**os.environ, "PYTHONIOENCODING": "utf-8"}

    def _run(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [PY, os.path.join(ROOT, "parse_log.py"), *args],
            capture_output=True, text=True, encoding="utf-8", cwd=ROOT, env=self.ENV,
        )

    def test_json_output(self) -> None:
        p = self._run(FIXTURE, "--json")
        self.assertEqual(p.returncode, 0, p.stderr)
        data = json.loads(p.stdout)
        self.assertEqual(len(data), FIXTURE_KEY_COUNT)
        for row in data:
            self.assertRegex(row["auth_key"], r"^0x[0-9a-f]{32}$")

    def test_keys_only_output(self) -> None:
        p = self._run(FIXTURE, "--keys-only")
        self.assertEqual(p.returncode, 0, p.stderr)
        lines = [ln for ln in p.stdout.strip().splitlines() if ln.strip()]
        self.assertEqual(len(lines), FIXTURE_KEY_COUNT)
        for ln in lines:
            self.assertRegex(ln, r"^0x[0-9a-f]{32}$")

    def test_deduplicates_across_multiple_files(self) -> None:
        """同一个日志传两次，条数不应翻倍。

        真实场景：一台设备会在 device.log / main.log / hmble_log.log 里
        都留下记录，一次传多个日志时必须跨文件去重。
        """
        p = self._run(FIXTURE, FIXTURE, "--keys-only")
        self.assertEqual(p.returncode, 0, p.stderr)
        lines = [ln for ln in p.stdout.strip().splitlines() if ln.strip()]
        self.assertEqual(len(lines), FIXTURE_KEY_COUNT)

    def test_text_output_mentions_gadgetbridge(self) -> None:
        p = self._run(FIXTURE)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("AuthKey", p.stdout)
        self.assertIn("Gadgetbridge", p.stdout)

    def test_stdin_mode(self) -> None:
        with open(FIXTURE, "r", encoding="utf-8") as fh:
            payload = fh.read()
        p = subprocess.run(
            [PY, os.path.join(ROOT, "parse_log.py"), "-", "--keys-only"],
            input=payload, capture_output=True, text=True, encoding="utf-8", cwd=ROOT,
            env=self.ENV,
        )
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertEqual(len(p.stdout.strip().splitlines()), FIXTURE_KEY_COUNT)

    def test_missing_file_exits_nonzero_with_hint(self) -> None:
        p = self._run("/definitely/not/here.log")
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("找不到文件", p.stderr)

    def test_main_tool_selftest_cli(self) -> None:
        p = subprocess.run(
            [PY, os.path.join(ROOT, "xiaomi_authkey.py"), "--selftest"],
            capture_output=True, text=True, encoding="utf-8", cwd=ROOT, env=self.ENV,
        )
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("自检全部通过", p.stdout)

    def test_main_tool_refuses_oauth_mode_gracefully(self) -> None:
        """--token-mode 缺参数时必须友好报错，不能抛栈。"""
        p = subprocess.run(
            [PY, os.path.join(ROOT, "xiaomi_authkey.py"), "--token-mode", "--ssecurity", "x"],
            capture_output=True, text=True, encoding="utf-8", cwd=ROOT, env=self.ENV,
        )
        self.assertEqual(p.returncode, 2)
        self.assertNotIn("Traceback", p.stderr)

    def test_password_stdin_empty_exits_cleanly(self) -> None:
        p = subprocess.run(
            [PY, os.path.join(ROOT, "xiaomi_authkey.py"),
             "-e", "a@b.c", "--password-stdin"],
            input="", capture_output=True, text=True, encoding="utf-8", cwd=ROOT,
            env=self.ENV,
        )
        self.assertEqual(p.returncode, 2)
        self.assertNotIn("Traceback", p.stderr)
        self.assertIn("password-stdin", p.stderr)

    def test_help_documents_password_stdin(self) -> None:
        p = subprocess.run(
            [PY, os.path.join(ROOT, "xiaomi_authkey.py"), "-h"],
            capture_output=True, text=True, encoding="utf-8", cwd=ROOT, env=self.ENV,
        )
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("--password-stdin", p.stdout)
        self.assertIn("--probe", p.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
