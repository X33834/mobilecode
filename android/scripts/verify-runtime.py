#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Mobilecode 运行时验证器
====================

对构建产物（usr/ 目录或解包的镜像分片）做两级验证，全部源于 v0.6.0
真机前问题排查中踩过的坑，改引擎/镜像/依赖后请重跑：

  scan  <usr-dir>    L2 静态体检（无需 qemu）
                     - 关键文件存在与可执行位
                     - ELF interpreter 必须是 bionic（/system/bin/linker64）或 musl 静态
                       （glibc 动态链接的二进制在 Android 上无法运行）
                     - ELF 的 NEEDED 在 usr/lib 闭环（libc 等系统库除外）
                     - 悬空符号链接
                     - dotslash 引导文件（JSON 格式的 rg 分发格式，Android 不可执行）

  e2e    <usr-dir>   端到端（需 qemu-aarch64-static + 宿主 node；缺失时自动跳过）
                     - 起 mock-openai.py（双协议 + 工具调用往返桩）
                     - 链路 A responses 直连：qemu 跑引擎 exec → mock → 工具循环 → 收敛
                     - 链路 B chat-bridge：qemu 跑引擎 → 桥(宿主 node, Responses⇄Chat)
                       → mock → 工具循环
                     - 链路 C 工作台：宿主 node 跑 dist-cli（express/commander 自带），
                       PATH 前置 fakebin/codex shim（exec qemu 引擎），/ 200 +
                       /codex-api/meta/methods 方法目录

注意：bridge 与工作台是纯 JS，无平台依赖 —— 必须用宿主原生 node；
     传 arm64 node 会 Exec format error（v0.6.1 踩坑）。引擎（musl 静态
     aarch64）由 qemu 执行；链路 C 的 spawn("codex") 通过 fakebin shim
     代理到 qemu，镜像内的设备 wrapper 不可在宿主使用。

用法：
  python3 scripts/verify-runtime.py scan  path/to/usr
  python3 scripts/verify-runtime.py e2e   path/to/usr
"""
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

KEY_FILES = [
    "bin/sh", "bin/node", "bin/codex", "bin/rg",
    "lib/node_modules/@openai/codex/bin/codex.js",
    "lib/node_modules/@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/codex/codex",
    "lib/node_modules/codex-web-local/dist-cli/index.js",
    "lib/node_modules/codex-web-local/node_modules/express/package.json",
    "lib/node_modules/codex-web-local/node_modules/commander/package.json",
    ".runtime-version",
]
# Android bionic 自带、无需在 usr/lib 内的依赖
SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
    "libGLESv2.so", "libEGL.so", "libOpenSLES.so", "libjnigraphics.so",
    "libsync.so", "libstdc++.so", "ld-android.so", "libbase.so",
}
TOOL_TIMEOUT = 180


def log(msg):
    print(f"[verify] {msg}", flush=True)


def fail(msg):
    print(f"[verify][FAIL] {msg}", flush=True)
    sys.exit(1)


# ── ELF 解析（纯 stdlib：e_ident / e_machine / PT_INTERP / DT_NEEDED）──

def _read_elf(path: Path):
    """返回 (is_elf, machine, interp, needed) 或 None。仅支持 64 位 LE。"""
    try:
        data = path.read_bytes()
    except OSError:
        return None
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None
    if data[4] != 2 or data[5] != 1:  # 64-bit / little-endian
        return None
    e_machine = struct.unpack_from("<H", data, 18)[0]
    e_phoff = struct.unpack_from("<Q", data, 32)[0]
    e_phentsize = struct.unpack_from("<H", data, 54)[0]
    e_phnum = struct.unpack_from("<H", data, 56)[0]

    interp = None
    dyn = None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        p_offset = struct.unpack_from("<Q", data, off + 8)[0]
        p_filesz = struct.unpack_from("<Q", data, off + 32)[0]
        if p_type == 3:  # PT_INTERP
            interp = data[p_offset:p_offset + p_filesz].split(b"\0")[0].decode(errors="replace")
        elif p_type == 2:  # PT_DYNAMIC
            dyn = data[p_offset:p_offset + p_filesz]
    needed = []
    strtab = None
    if dyn:
        tags = []
        for j in range(0, len(dyn) - 15, 16):
            tag, val = struct.unpack_from("<qQ", dyn, j)
            tags.append((tag, val))
            if tag == 0:
                break
        for tag, val in tags:
            if tag == 5:  # DT_STRTAB（vaddr，段内偏移近似：ELF 通常 vaddr==offset）
                strtab = val
        if strtab is not None:
            # 把 vaddr 转成文件偏移：用 PT_LOAD 简单映射
            file_off = strtab
            for i in range(e_phnum):
                off = e_phoff + i * e_phentsize
                p_type = struct.unpack_from("<I", data, off)[0]
                if p_type == 1:  # PT_LOAD
                    p_offset = struct.unpack_from("<Q", data, off + 8)[0]
                    p_vaddr = struct.unpack_from("<Q", data, off + 16)[0]
                    p_filesz = struct.unpack_from("<Q", data, off + 32)[0]
                    if p_vaddr <= strtab < p_vaddr + p_filesz:
                        file_off = p_offset + (strtab - p_vaddr)
                        break
            for tag, val in tags:
                if tag == 1:  # DT_NEEDED
                    end = data.index(b"\0", file_off + val)
                    needed.append(data[file_off + val:end].decode(errors="replace"))
    return e_machine, interp, needed


MACH_AARCH64 = 0xB7
BIONIC_INTERP = "/system/bin/linker64"


def is_dotslash(path: Path) -> bool:
    try:
        head = path.read_bytes()[:2048]
        return head.lstrip().startswith(b"{") and b"dotslash" in head
    except OSError:
        return False


def scan(usr: Path):
    problems = []

    for rel in KEY_FILES:
        p = usr / rel
        if not p.exists():
            problems.append(f"缺关键文件: {rel}")
        elif rel.endswith((".js", ".json", ".py")) or rel == ".runtime-version":
            continue
        elif not os.access(p, os.X_OK) and not p.is_symlink():
            problems.append(f"关键文件不可执行: {rel}")

    ver = (usr / ".runtime-version")
    if ver.exists():
        log(f"镜像版本: {ver.read_text().strip()}")

    # rg 专项：v0.5.0 教训 —— dotslash / glibc 版在 Android 不可执行
    for rel in ("bin/rg",
                "lib/node_modules/@openai/codex/bin/rg",
                "lib/node_modules/@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/codex/rg"):
        p = usr / rel
        if p.exists() and is_dotslash(p):
            problems.append(f"rg 仍是 dotslash 引导文件: {rel}")
        elif p.exists():
            info = _read_elf(p)
            if info:
                machine, interp, needed = info
                if machine != MACH_AARCH64:
                    problems.append(f"{rel}: 非 aarch64 ELF")
                elif interp and interp != BIONIC_INTERP and "musl" not in rel:
                    problems.append(f"{rel}: interpreter={interp}（glibc 动态链接，Android 不可运行）")

    # 全树 ELF 体检 + 悬空链接 + dotslash
    n_elf = n_dangling = 0
    for p in usr.rglob("*"):
        if p.is_symlink() and not p.exists():
            problems.append(f"悬空符号链接: {p.relative_to(usr)}")
            n_dangling += 1
            continue
        if not p.is_file() or p.is_symlink():
            continue
        if p.suffix in (".js", ".json", ".py", ".md", ".txt", ".pem", ".map"):
            continue
        if is_dotslash(p):
            problems.append(f"dotslash 引导文件: {p.relative_to(usr)}")
            continue
        info = _read_elf(p)
        if not info:
            continue
        n_elf += 1
        machine, interp, needed = info
        if machine != MACH_AARCH64:
            problems.append(f"非 aarch64 ELF: {p.relative_to(usr)}")
            continue
        lib_names = {q.name for q in (usr / "lib").glob("lib*.so*")}
        for need in needed:
            if need in SYSTEM_LIBS:
                continue
            if not any(n == need or n.startswith(need + ".") for n in lib_names):
                problems.append(f"{p.relative_to(usr)}: NEEDED {need} 在镜像内缺失")

    log(f"ELF 扫描 {n_elf} 个，悬空链接 {n_dangling} 个")
    if problems:
        print("\n".join("  ✗ " + s for s in problems))
        fail(f"scan 发现 {len(problems)} 个问题")
    log("scan 全部通过 ✓")


# ── e2e ─────────────────────────────────────────────────────────

def _wait_port(port, timeout=20):
    import socket
    deadline = time.time() + timeout
    while time.time() < deadline:
        with socket.socket() as s:
            s.settimeout(0.5)
            try:
                s.connect(("127.0.0.1", port))
                return True
            except OSError:
                time.sleep(0.3)
    return False


def _gen_certs(tmp: Path):
    cert, key = tmp / "mock-cert.pem", tmp / "mock-key.pem"
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                    "-keyout", str(key), "-out", str(cert), "-days", "2",
                    "-subj", "/CN=127.0.0.1",
                    "-addext", "subjectAltName=IP:127.0.0.1,DNS:localhost"],
                   check=True, capture_output=True)
    return cert, key


def _run_engine(usr: Path, qemu: str, sysroot: str, home: Path,
                env_extra: dict, config_toml: str, prompt: str, timeout_s):
    engine = (usr / "lib/node_modules/@openai/codex-linux-arm64"
                   "/vendor/aarch64-unknown-linux-musl/codex/codex")
    (home / ".codex").mkdir(parents=True, exist_ok=True)
    (home / ".codex/config.toml").write_text(config_toml)
    env = dict(os.environ)
    env.update({
        "HOME": str(home), "CODEX_HOME": str(home / ".codex"),
        "TMPDIR": str(home / "tmp"), "SHELL": "/bin/sh",
        "MOCK_API_KEY": "sk-mock", "DEEPSEEK_API_KEY": "sk-mock",
        "NO_PROXY": "127.0.0.1,localhost",
    })
    env.update(env_extra)
    (home / "tmp").mkdir(exist_ok=True)
    cmd = [engine, "exec", "--skip-git-repo-check", prompt]
    if qemu:
        cmd = [qemu] + (["-L", sysroot] if sysroot else []) + cmd
    p = subprocess.run(cmd, env=env, cwd=str(home), capture_output=True,
                       text=True, timeout=timeout_s)
    return p


def _check_loop_output(out: str, expect_echo: str, stderr: str = ""):
    # 工具真实执行的标志：引擎 echo 出 mock 下发的字符串，且回答收敛（E2E OK / CHAT LOOP OK）
    if expect_echo not in out:
        fail(f"端到端工具循环未收敛（未见 {expect_echo!r}）\n"
             f"--- stdout 尾部 ---\n{out[-600:]}\n--- stderr 尾部 ---\n{stderr[-400:]}")
    if "E2E OK" not in out and "CHAT LOOP OK" not in out:
        fail(f"最终回答缺失\n--- 引擎输出尾部 ---\n{out[-800:]}")
    log(f"  工具循环收敛 ✓（{expect_echo}）")


def e2e(usr: Path, qemu: str, sysroot: str):
    qemu = qemu or shutil.which("qemu-aarch64-static")
    if not qemu:
        log("未检测到 qemu-aarch64-static —— 跳过 e2e（scan 已通过；"
            "在 ARM64 构建机或安装 qemu 后可跑完整链路）")
        return
    qemu = str(Path(qemu).resolve())
    sysroot = str(Path(sysroot).resolve()) if sysroot else None
    # bridge 与工作台是纯 JS：宿主原生 node 直接跑（arm64 node 会 Exec format error）
    host_node = shutil.which("node")
    if not host_node:
        fail("宿主未找到 node（链路 B/C 需要：bridge 与工作台均为纯 JS）")
    log(f"宿主 node: {host_node}")
    scripts = Path(__file__).resolve().parent
    bridge = scripts.parent / "app/src/main/assets/chat-bridge.js"
    mock = scripts / "mock-openai.py"

    with tempfile.TemporaryDirectory(prefix="mc-verify-") as td:
        tmp = Path(td)
        home = tmp / "home"
        home.mkdir()
        cert, key = _gen_certs(tmp)

        # 1. mock 双协议
        mock_proc = subprocess.Popen(
            [sys.executable, str(mock), "--http", "18080", "--tls", "18443",
             "--cert", str(cert), "--key", str(key)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            if not _wait_port(18080) or not _wait_port(18443):
                fail("mock 启动失败")
            log("mock(responses+chat 双协议) 已就绪")

            # 2. responses 直连（引擎 → mock :18443 TLS）
            log("e2e 链路 A：Responses 直连 + 工具循环")
            p = _run_engine(
                usr, qemu, sysroot, home,
                {"SSL_CERT_FILE": str(cert)},
                'approval_policy = "never"\nsandbox_mode = "danger-full-access"\n'
                'model = "gpt-mock"\nmodel_provider = "mock"\n\n'
                '[model_providers.mock]\nname = "mock"\n'
                'base_url = "https://127.0.0.1:18443/v1"\n'
                'env_key = "MOCK_API_KEY"\nwire_api = "responses"\n',
                "run the verification echo", TOOL_TIMEOUT)
            _check_loop_output(p.stdout, "e2e-ok-from-mock", p.stderr)

            # 3. chat-bridge（引擎 → 桥 → mock chat :18080）
            log("e2e 链路 B：chat-bridge（DeepSeek 场景）+ 工具循环")
            bridge_proc = subprocess.Popen(
                [host_node, str(bridge)],
                env={**os.environ, "CHAT_BRIDGE_ROUTES": "/deepseek=http://127.0.0.1:18080/v1",
                     "CHAT_BRIDGE_PORT": "18925"},
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                if not _wait_port(18925):
                    fail("chat-bridge 启动失败")
                p = _run_engine(
                    usr, qemu, sysroot, home, {},
                    'approval_policy = "never"\nsandbox_mode = "danger-full-access"\n'
                    'model = "deepseek-chat"\nmodel_provider = "bridged-chat"\n\n'
                    '[model_providers.bridged-chat]\nname = "bridged-chat"\n'
                    'base_url = "http://127.0.0.1:18925/deepseek/v1"\n'
                    'env_key = "DEEPSEEK_API_KEY"\nwire_api = "responses"\n',
                    "run the verification echo", TOOL_TIMEOUT)
                _check_loop_output(p.stdout, "chat-loop-ok", p.stderr)
            finally:
                bridge_proc.terminate()

            # 4. 工作台探活（dist-cli + express/commander 自带依赖）
            # dist-cli 内部 spawn("codex", ...) 走 PATH：镜像里的 usr/bin/codex 是
            # 设备 wrapper（shebang/exec 均为设备路径），宿主不可用 —— 在 fakebin
            # 放 shim 代理到 qemu + musl 引擎。
            log("e2e 链路 C：工作台开箱启动 + app-server 方法目录")
            port = 18933
            fakebin = tmp / "fakebin"
            fakebin.mkdir()
            engine = (usr / "lib/node_modules/@openai/codex-linux-arm64"
                           "/vendor/aarch64-unknown-linux-musl/codex/codex")
            shim = fakebin / "codex"
            shim.write_text(
                "#!/bin/sh\n"
                f'exec {qemu} {"-L " + sysroot + " " if sysroot else ""}{engine} "$@"\n')
            shim.chmod(0o755)
            wb = subprocess.Popen(
                [host_node,
                 str(usr / "lib/node_modules/codex-web-local/dist-cli/index.js"),
                 "--port", str(port), "--no-password"],
                env={**os.environ, "HOME": str(home), "CODEX_HOME": str(home / ".codex"),
                     "TMPDIR": str(home / "tmp"),
                     "PATH": f"{fakebin}:{usr}/bin:/usr/bin:/bin"},
                cwd=str(home), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                if not _wait_port(port):
                    fail("工作台启动失败（检查 express/commander 是否已随镜像安装）")
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=10) as r:
                    if r.status != 200:
                        fail(f"工作台 / 返回 {r.status}")
                with urllib.request.urlopen(
                        f"http://127.0.0.1:{port}/codex-api/meta/methods", timeout=120) as r:
                    methods = json.load(r)["data"]
                for need in ("newConversation", "sendUserTurn", "addConversationListener"):
                    if need not in methods:
                        fail(f"app-server 方法目录缺 {need}")
                log(f"  工作台 HTTP 200 ✓ / app-server 方法 {len(methods)} 个 ✓")
            finally:
                wb.terminate()
        finally:
            mock_proc.terminate()
    log("e2e 全部通过 ✓")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    cmd, target = sys.argv[1], Path(sys.argv[2]).resolve()
    if not target.exists():
        fail(f"目录不存在: {target}")

    def opt(name, default=None):
        if name in sys.argv:
            return sys.argv[sys.argv.index(name) + 1]
        return default

    if cmd == "scan":
        scan(target)
    elif cmd == "e2e":
        e2e(target, opt("--qemu"), opt("--sysroot"))
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()
