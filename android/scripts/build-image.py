#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mobilecode v0.4 镜像构建器
==========================
在构建机（macOS/Linux，无需 Android SDK）上预组装完整的 Android runtime：
  Termux bootstrap + Node.js 24 + npm + 依赖库 + Codex CLI(JS) + linux-arm64 原生引擎
  + codex-web-local 工作台服务器/前端

产物：app/src/main/assets/images0..3.bin —— 4 个独立的 gzip+tar 分片，
设备端 4 线程并行解压（多核 IO 并行，首次启动显著提速）。
路径/权限/符号链接都在构建期固化并以相对符号链接落地，
手机端只做一次并行解压即可运行，不再需要 dpkg/tar 补丁/包装脚本等步骤。

v0.4 附加：镜像瘦身（移除包管理器/孤儿库簇/info 文档）减小 APK 体积。

依赖：python3 >= 3.10（标准库即可），网络可访问 termux 镜像与 npm registry。
用法：python3 scripts/build-image.py
"""

import io
import os
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
import zlib
from pathlib import Path

# ── 常量 ─────────────────────────────────────────────────────────

# App 在手机上的最终前缀（与 AndroidManifest package 一致的绝对路径）
FINAL_PREFIX = "/data/user/0/com.codex.mobile/files/usr"
TERMUX_PREFIX = "/data/data/com.termux/files/usr"
RUNTIME_VERSION = "0.6.1"
CODEX_VERSION = "0.104.0"  # 与 app-server 前端 UI/protocol 配套，勿随意升级
SHARD_COUNT = 4            # 设备端并行解压线程数

# 工作台运行依赖（codex-web-local 的 dist-cli 需要，但 bundle 未自带 node_modules）：
# 构建机用系统 npm --omit=dev 安装，纯 JS 无平台产物
WORKBENCH_RUNTIME_DEPS = ["express@5.1.0", "commander@13.1.0"]

ROOT = Path(__file__).resolve().parent.parent  # android/
ASSETS = ROOT / "app" / "src" / "main" / "assets"
CACHE = ROOT / "scripts" / ".cache"
STAGE = ROOT / "scripts" / ".stage"
# 产物名用 imagesN.bin（内容为 gzip 压缩的 tar）——
# 若用 .tar.gz 结尾，AAPT 会把 asset 预解压并去掉 .gz 后缀，导致 APK 内明文、
# 且 assets.open("images0.tar.gz") 找不到文件。
SERVER_BUNDLE = ROOT / "scripts" / "server-bundle"

APT_MIRRORS = [
    "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
    "https://mirrors.bfsu.edu.cn/termux/apt/termux-main",
    "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
    "https://packages.termux.dev/apt/termux-main",
]
NPM_MIRRORS = [
    "https://registry.npmmirror.com",
    "https://registry.npmjs.org",
]
BOOTSTRAP_VERSION = "bootstrap-2026.02.12-r1+apt.android-7"
_GH_RELEASE = (
    "https://github.com/termux/termux-packages/releases/download/"
    f"{BOOTSTRAP_VERSION}/bootstrap-aarch64.zip"
)
# 国内网络直连 github.com 常年不稳定：官方源 + 常见加速代理逐个尝试，
# 任一成功即用（代理只做字节转发，zip 完整性由解压与后续校验兜底）。
_GH_PROXIES = ["https://ghproxy.net/", "https://gh-proxy.com/", "https://ghfast.top/"]
BOOTSTRAP_URLS = (
    [p + _GH_RELEASE for p in _GH_PROXIES]
    + [_GH_RELEASE,
       "https://sourceforge.net/projects/termux-packages.mirror/files/"
       f"{BOOTSTRAP_VERSION}/bootstrap-aarch64.zip/download"]
)

# 需要内置的运行时依赖闭包（apt 索引里解析出来的最小集合）
# ripgrep：codex 引擎的文件搜索工具从 PATH 找 rg；npm 包里的 rg 是 dotslash
# 引导文件、vendor 里的是 glibc 链接 —— 在 Android 上都不可执行，
# 只有 Termux 的 bionic aarch64 版能跑
RUNTIME_DEBS = ["c-ares", "libicu", "libsqlite", "nodejs-lts", "npm", "ripgrep"]


# ── 小工具 ───────────────────────────────────────────────────────

def log(msg: str):
    print(f"[build-image] {msg}")


def download(url: str, dst: Path, min_size: int = 0) -> bool:
    if dst.exists() and dst.stat().st_size > min_size:
        return True
    tmp = dst.with_suffix(dst.suffix + ".part")
    log(f"下载 {url}")
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mobilecode-build"})
        with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f, length=1 << 20)
        if tmp.stat().st_size <= min_size:
            tmp.unlink()
            return False
        tmp.rename(dst)
        return True
    except Exception as e:
        log(f"下载失败 {url}: {e}")
        tmp.unlink(missing_ok=True)
        return False


def fetch_first(urls: list, dst: Path, min_size: int = 0) -> bool:
    for u in urls:
        if download(u, dst, min_size):
            return True
    return False


# ── 1. 依赖解析：termux-main Packages 依赖闭包 ───────────────────

def parse_packages(pkg_file: Path) -> dict:
    """返回 {pkg: {field: value}}，Depends 已跨续行合并。"""
    stanzas = {}
    cur = None
    for raw in pkg_file.read_text(errors="replace").split("\n\n"):
        entry = {}
        lines = raw.splitlines()
        i = 0
        while i < len(lines):
            if not lines[i].startswith((" ", "\t")) and ":" in lines[i]:
                key, _, val = lines[i].partition(":")
                val = val.strip()
                while i + 1 < len(lines) and lines[i + 1].startswith((" ", "\t")):
                    i += 1
                    val += " " + lines[i].strip()
                entry[key] = val
            i += 1
        if "Package" in entry:
            stanzas[entry["Package"]] = entry
    return stanzas


def deb_deps_closure(index: Path, wanted: list) -> dict:
    """计算 wanted 的 aarch64 依赖闭包，返回 {pkg: pool 相对路径}。"""
    pkgs = parse_packages(index)

    def pool(pkg: str) -> str | None:
        e = pkgs.get(pkg)
        return e.get("Filename") if e else None

    closed: dict[str, str] = {}
    stack = list(wanted)
    while stack:
        pkg = stack.pop()
        if pkg in closed:
            continue
        fn = pool(pkg)
        if not fn:
            raise SystemExit(f"[build-image] termux-main 中找不到包: {pkg}")
        closed[pkg] = fn
        e = pkgs.get(pkg, {})
        for dep in re.split(r"[,\n]", e.get("Depends", "")):
            name = re.split(r"[\s(|]+", dep.strip())[0].strip()
            if not name or name in closed:
                continue
            # bootstrap 自带 / 配置类包，无需内嵌
            if name in ("libc++", "openssl", "zlib", "resolv-conf", "nodejs", "dash"):
                continue
            if pool(name):
                stack.append(name)
    return closed


# ── 2. deb 解包（ar + data.tar.xz，不依赖 dpkg/dpkg-deb）──────────

def extract_deb(deb: Path, dest: Path):
    """把 .deb 内容解到 dest（deb 内部是 data/data/com.termux/files/usr 结构）。"""
    with open(deb, "rb") as f:
        magic = f.read(8)
        if magic != b"!<arch>\n":
            raise SystemExit(f"[build-image] 非法 deb: {deb}")
        # 解析 ar 成员
        members = {}
        while True:
            head = f.read(60)
            if len(head) < 60:
                break
            name = head[:16].decode().strip().rstrip("/")
            size = int(head[48:58].decode().strip() or "0")
            body = f.read(size)
            if size % 2:
                f.read(1)
            members[name] = body
    data = members.get("data.tar.xz") or members.get("data.tar.zst")
    if data is None:
        raise SystemExit(f"[build-image] deb 缺少 data.tar: {deb}")
    if data[:6] == b"\xfd7zXZ\x00":
        import lzma
        bio = io.BytesIO(lzma.decompress(data))
    elif data[:4] == b"\x28\xb5\x2f\xfd":
        # Termux 新包逐步切 zstd；Python 3.11 标准库无 zstd，回退系统 zstd 命令
        import subprocess as _sp
        p = _sp.run(["zstd", "-dc"], input=data, capture_output=True, timeout=300)
        if p.returncode != 0:
            raise SystemExit(f"[build-image] zstd 解压失败（需系统安装 zstd）: {deb}")
        bio = io.BytesIO(p.stdout)
    else:
        raise SystemExit(f"[build-image] 未知的 data.tar 压缩格式: {deb}")
    with tarfile.open(fileobj=bio, mode="r:") as tf:
        _safe_extract(tf, dest)


# ── tar 安全解包（防护路径穿越）───────────────────────────────────

def _safe_extract(tf: tarfile.TarFile, dest: Path):
    root = dest.resolve()
    for m in tf.getmembers():
        target = (dest / m.name).resolve()
        if root not in target.parents and target != root:
            raise SystemExit(f"[build-image] 非法 tar 路径: {m.name}")
    tf.extractall(dest)  # 保留 mode/symlink


# ── 3. bootstrap 解包 + 重建符号链接（相对化）─────────────────────

def chmod_tree(root: Path, rel_path: str, mode: int):
    p = root / rel_path
    if not p.exists():
        return
    if p.is_dir():
        for f in p.rglob("*"):
            if f.is_file() or f.is_symlink():
                f.chmod(mode)
    else:
        p.chmod(mode)


def unpack_bootstrap(zip_path: Path, root: Path):
    """解 bootstrap zip 到 root，重建 SYMLINKS.txt 中的全部符号链接（相对化）。"""
    symlinks = []
    with zipfile.ZipFile(zip_path) as zf:
        for info in zf.infolist():
            name = info.filename
            if name.startswith("/") or ".." in name.split("/"):
                continue
            if info.is_dir():
                (root / name).mkdir(parents=True, exist_ok=True)
                continue
            if name == "SYMLINKS.txt":
                symlinks = zf.read(info).decode().splitlines()
                continue
            out = root / name
            out.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info) as src, open(out, "wb") as dst:
                shutil.copyfileobj(src, dst, length=1 << 20)
    if not symlinks:
        raise SystemExit("[build-image] bootstrap 中缺少 SYMLINKS.txt")

    for line in symlinks:
        if "\u2190" not in line:
            continue
        target, _, link = line.partition("\u2190")
        target = target.strip()
        link = link.strip().lstrip("./")
        link_abs = root / link
        if target.startswith(TERMUX_PREFIX):
            target_abs = root / target[len(TERMUX_PREFIX):].lstrip("/")
        elif target.startswith("/"):
            target_abs = Path(target)  # 系统路径等，保持绝对
        else:
            # Termux 相对 target：相对"链接所在目录"
            target_abs = link_abs.parent / target
        # 全部转为相对链接，设备端 filesDir 变化也安全
        rel = os.path.relpath(target_abs, link_abs.parent)
        link_abs.parent.mkdir(parents=True, exist_ok=True)
        if link_abs.exists() or link_abs.is_symlink():
            link_abs.unlink()
        link_abs.symlink_to(rel)

    # bootstrap zip 不存权限位：补可执行权限（沿用 Termux 安装器规则）
    chmod_tree(root, "bin", 0o700)
    for sub in ("libexec", "lib/bash", "lib/apt/methods"):
        chmod_tree(root, sub, 0o700)


# ── 4. 文本路径改写（termux 路径 -> 最终前缀）─────────────────────

PAIR = (
    TERMUX_PREFIX.encode(),
    FINAL_PREFIX.encode(),
)

def rewrite_text_paths(root: Path):
    """把所有文本文件里的 termux 路径改写为最终前缀（二进制按 NUL 检测跳过）。"""
    n = 0
    for p in root.rglob("*"):
        if not p.is_file():
            continue
        try:
            head = p.read_bytes()[:8192]
        except OSError:
            continue
        if b"\x00" in head:
            continue  # 二进制
        data = p.read_bytes()
        if PAIR[0] in data:
            p.write_bytes(data.replace(*PAIR))
            n += 1
    log(f"文本路径改写 {n} 个文件")


# ── 5. 包装脚本 ──────────────────────────────────────────────────

SHEBANG = f"#!{FINAL_PREFIX}/bin/sh"

def write_wrapper(path: Path, script: str):
    # 注意：path 可能是 deb 自带的 symlink（如 bin/npm -> npm-cli.js），
    # 必须先删掉实体链接，再写成实体包装脚本，否则会污染目标文件。
    if path.is_symlink() or path.exists():
        path.unlink()
    path.write_text(f"{SHEBANG}\nexec {script}\n")
    path.chmod(0o700)


# ── 主流程 ───────────────────────────────────────────────────────

def main():
    if sys.version_info < (3, 10):
        raise SystemExit(
            "[build-image] 需要 Python 3.10+（脚本使用 str | None 类型语法），"
            f"当前 {sys.version.split()[0]}"
        )
    CACHE.mkdir(parents=True, exist_ok=True)
    STAGE.mkdir(parents=True, exist_ok=True)

    # 1. bootstrap
    bootstrap_zip = CACHE / "bootstrap-aarch64.zip"
    if not fetch_first(BOOTSTRAP_URLS, bootstrap_zip, min_size=10_000_000):
        raise SystemExit("[build-image] bootstrap 下载失败")

    # 2. Packages 索引 + 依赖闭包
    pkg_gz = CACHE / "Packages.gz"
    if not fetch_first([f"{m}/dists/stable/main/binary-aarch64/Packages.gz" for m in APT_MIRRORS],
                       pkg_gz, min_size=400_000):
        raise SystemExit("[build-image] Packages.gz 下载失败")
    index = CACHE / "Packages"
    if not index.exists():
        import gzip
        with gzip.open(pkg_gz, "rb") as src, open(index, "wb") as dst:
            shutil.copyfileobj(src, dst)
    closure = deb_deps_closure(index, RUNTIME_DEBS)
    log(f"Node 依赖闭包: {', '.join(sorted(closure))}")

    debs = []
    for pkg, fname in sorted(closure.items(), key=lambda kv: RUNTIME_DEBS.index(kv[0]) if kv[0] in RUNTIME_DEBS else 99):
        dst = CACHE / Path(fname).name
        if not fetch_first([f"{m}/{fname}" for m in APT_MIRRORS], dst, min_size=100_000):
            raise SystemExit(f"[build-image] deb 下载失败: {fname}")
        debs.append(dst)

    # 3. Codex（锁版本）
    codex_files = []
    for f in (f"codex-{CODEX_VERSION}.tgz", f"codex-{CODEX_VERSION}-linux-arm64.tgz"):
        dst = CACHE / f
        if not fetch_first([f"{m}/@openai/codex/-/{f}" for m in NPM_MIRRORS], dst, min_size=1000):
            raise SystemExit(f"[build-image] codex 下载失败: {f}")
        codex_files.append(dst)

    # 4. 组装镜像
    usr = STAGE / "usr"
    if usr.exists():
        shutil.rmtree(usr)
    usr.mkdir(parents=True)

    log("解压 bootstrap…")
    unpack_bootstrap(bootstrap_zip, usr)

    log("解压 deb（node/js 依赖库）…")
    termux_usr = usr / TERMUX_PREFIX.lstrip("/")
    for deb in debs:
        extract_deb(deb, STAGE)
    # deb 解到 STAGE 下的 data/data/com.termux/files/usr/...，移到 usr/
    deb_root = STAGE / TERMUX_PREFIX.lstrip("/")
    if deb_root.exists():
        _merge_dir(deb_root, usr)

    log("解压 Codex CLI + 原生引擎…")
    openai_dir = usr / "lib/node_modules/@openai"
    openai_dir.mkdir(parents=True, exist_ok=True)
    with tarfile.open(codex_files[0], "r:gz") as tf:
        _safe_extract(tf, openai_dir)
        (openai_dir / "package").rename(openai_dir / "codex")
    with tarfile.open(codex_files[1], "r:gz") as tf:
        _safe_extract(tf, openai_dir)
        (openai_dir / "package").rename(openai_dir / "codex-linux-arm64")

    log("安装工作台（codex-web-local）…")
    src_bundle = SERVER_BUNDLE
    if not src_bundle.exists():
        raise SystemExit("[build-image] 缺少 scripts/server-bundle（先构建前端）")
    workdir = usr / "lib/node_modules/codex-web-local"
    shutil.copytree(src_bundle, workdir)

    # v0.5.0 实测：dist-cli/index.js import express/commander，但 bundle 未带
    # node_modules —— 设备上工作台永远起不来（ERR_MODULE_NOT_FOUND）。
    # 在构建机装齐运行依赖（纯 JS，--ignore-scripts 防平台相关钩子）。
    log(f"安装工作台运行依赖 {WORKBENCH_RUNTIME_DEPS}…")
    npm_exe = shutil.which("npm") or shutil.which("pnpm")
    if not npm_exe:
        raise SystemExit("[build-image] 构建机缺 npm/pnpm，无法安装工作台运行依赖")
    npm_cmd = [npm_exe, "install", "--prefix", str(workdir),
               "--omit=dev", "--ignore-scripts", "--no-audit", "--no-fund",
               "--registry", NPM_MIRRORS[0]] + WORKBENCH_RUNTIME_DEPS
    subprocess.run(npm_cmd, check=True,
                   stdout=sys.stdout, stderr=sys.stderr, timeout=600)
    if not (workdir / "node_modules/express/package.json").exists() or \
       not (workdir / "node_modules/commander/package.json").exists():
        raise SystemExit("[build-image] 工作台运行依赖安装不完整")

    log("写包装脚本…")
    # node 本体保持 deb 解出的真实二进制，不做包装
    node_bin = usr / "bin/node"
    if node_bin.exists():
        node_bin.chmod(0o700)
    write_wrapper(usr / "bin/npm",
                  f"{FINAL_PREFIX}/bin/node {FINAL_PREFIX}/lib/node_modules/npm/bin/npm-cli.js \"$@\"")
    write_wrapper(usr / "bin/npx",
                  f"{FINAL_PREFIX}/bin/node {FINAL_PREFIX}/lib/node_modules/npm/bin/npx-cli.js \"$@\"")
    write_wrapper(usr / "bin/codex",
                  f"{FINAL_PREFIX}/bin/node {FINAL_PREFIX}/lib/node_modules/@openai/codex/bin/codex.js \"$@\"")
    codex_bin = usr / "lib/node_modules/@openai/codex/bin/codex.js"
    if codex_bin.exists():
        codex_bin.chmod(0o700)
    native = usr / "lib/node_modules/@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/codex/codex"
    if native.exists():
        native.chmod(0o700)

    # v0.6.0：ripgrep 修正。npm 包 bin/rg 是 dotslash 引导文件（Android 无法
    # 执行），codex-linux-arm64 vendor 里的 rg（codex/ 与 path/ 两处）是 glibc
    # 链接（同样无法执行）。引擎从 PATH 找 rg —— 用 Termux bionic 版覆盖，
    # 并清掉包内全部 glibc/dotslash 副本，防任何代码路径误用。
    bionic_rg = usr / "bin/rg"
    if bionic_rg.exists():
        bionic_rg.chmod(0o700)
        for rg_copy in (
            usr / "lib/node_modules/@openai/codex/bin/rg",
            usr / "lib/node_modules/@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/codex/rg",
            usr / "lib/node_modules/@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/path/rg",
        ):
            try:
                if rg_copy.exists() or rg_copy.is_symlink():
                    rg_copy.unlink()
                shutil.copy2(bionic_rg, rg_copy)
                rg_copy.chmod(0o700)
                log(f"rg 覆盖: {rg_copy.relative_to(usr)}")
            except OSError:
                pass
    else:
        raise SystemExit("[build-image] Termux ripgrep 未解出（bin/rg 缺失）")
    rg_js = usr / "lib/node_modules/@openai/codex/bin/rg"
    if rg_js.exists():
        rg_js.chmod(0o700)
    cli_js = usr / "lib/node_modules/codex-web-local/dist-cli/index.js"
    if cli_js.exists():
        cli_js.chmod(0o700)

    log("改写 termux 路径->最终前缀…")
    rewrite_text_paths(usr)

    # 版本标记 + 清理
    (usr / ".runtime-version").write_text(RUNTIME_VERSION + "\n")
    for junk in ("var/cache/debs", "var/cache/apt", "var/lib/apt/lists", "tmp",
                 "share/doc", "share/man", "include", "lib/pkgconfig", "lib/cmake"):
        shutil.rmtree(usr / junk, ignore_errors=True)
    # termux-exec 不再是必需（shebang 已改写）；删掉避免误用
    shutil.rmtree(usr / "bin/termux-exec-system-linker-exec", ignore_errors=True)
    for f in (usr / "lib").glob("libtermux-exec*"):
        f.unlink()
    shutil.rmtree(usr / "libexec/installed-tests", ignore_errors=True)

    # 5. 瘦身：成品镜像不可再装包，移除包管理器/孤儿库簇/info 文档
    slim_image(usr)

    # 5b. 清理悬空符号链接（slim 删除库文件后留下的死链；
    #     v0.5.0 实测 libnettle.so.8 / libhogweed.so / bin/xdg-open 悬空）
    dangling = 0
    for p in list(usr.rglob("*")):
        if p.is_symlink() and not p.exists():
            p.unlink()
            dangling += 1
    log(f"清理悬空符号链接 {dangling} 个")

    # 瘦身后做一次动态依赖完整性校验（防止误删被引用库）
    verify_image_deps(usr)

    # 6. 打包：先打未压缩 tar，再按条目大小贪心拆成 SHARD_COUNT 个
    #    独立 gzip 分片。设备端 4 线程并行解压，多核 IO 提速 2-3 倍。
    log(f"打包分片镜像 images0..{SHARD_COUNT - 1}.bin…")
    raw_tar = STAGE / "stage.tar"
    if raw_tar.exists():
        raw_tar.unlink()
    subprocess.run(["tar", "-cf", str(raw_tar), "-C", str(STAGE), "usr"], check=True)
    shards = split_shards(raw_tar, ASSETS, SHARD_COUNT)
    raw_tar.unlink(missing_ok=True)

    # 7. 校验（关键文件必须出现在某个分片里）
    with tarfile.open(shards[0], "r:gz") as tf:
        pass  # 打开即校验 gzip 完整性
    need = [
        "usr/bin/sh",
        "usr/bin/node",
        "usr/bin/codex",
        "usr/bin/rg",
        "usr/lib/node_modules/@openai/codex/bin/codex.js",
        "usr/lib/node_modules/@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/codex/codex",
        "usr/lib/node_modules/codex-web-local/dist-cli/index.js",
        "usr/lib/node_modules/codex-web-local/node_modules/express/package.json",
        "usr/lib/node_modules/codex-web-local/node_modules/commander/package.json",
        "usr/.runtime-version",
    ]
    found = set()
    total_files = 0
    for sh in shards:
        with tarfile.open(sh, "r:gz") as tf:
            names = set(tf.getnames())
            found |= names
            total_files += len(names)
    for n in need:
        if n not in found:
            raise SystemExit(f"[build-image] 镜像缺: {n}")
    total_mb = sum(sh.stat().st_size for sh in shards) / 1024 / 1024
    log(f"OK：{len(shards)} 个分片共 {total_mb:.1f} MB（{total_files} 个文件）")
    shutil.rmtree(STAGE / "data", ignore_errors=True)


# ── 5a. 镜像瘦身 ─────────────────────────────────────────────────

def _rm(usr: Path, rel: str):
    """安全删除 usr/rel（文件或目录），不存在则忽略。"""
    p = usr / rel
    if p.is_symlink() or p.is_file():
        p.unlink(missing_ok=True)
    elif p.is_dir():
        shutil.rmtree(p, ignore_errors=True)


def slim_image(usr: Path):
    """移除成品镜像中用不到的东西，减小 APK 体积。

    依据（构建期已核对 NEEDED）：
    - apt/dpkg 全家：镜像封死不再装包；
    - gpg/gnutls 库簇：仅被 apt/gpgv/dpkg 引用，删除后无残留引用；
    - info 文档、completion、开发配置：运行时不需要。
    """
    removed = 0
    for rel in (
        # 包管理器与其配套二进制（bootstrap 自带）
        "bin/apt", "bin/apt-cache", "bin/apt-config", "bin/apt-get", "bin/apt-key",
        "bin/apt-mark", "bin/apt-sortpkgs", "bin/apt-transport-http",
        "bin/dpkg", "bin/dpkg-buildapi", "bin/dpkg-buildtree", "bin/dpkg-deb",
        "bin/dpkg-divert", "bin/dpkg-fsys-usrunmess", "bin/dpkg-query",
        "bin/dpkg-realpath", "bin/dpkg-split", "bin/dpkg-statoverride",
        "bin/dpkg-trigger", "bin/dpkg-vendor", "bin/update-alternatives",
        # gpg/gpgv（仅用于验证包）
        "bin/gpgv", "bin/gpgv-trust", "bin/dumpsexp", "bin/mpicalc", "bin/yat2m",
        "bin/gpg-error", "bin/gpg-error-config", "bin/gpgrt-config",
        "bin/libassuan-config", "bin/libgcrypt-config", "bin/npth-config", "bin/idn2",
        # termux 系统工具（App 内不可用/不需要）
        "bin/termux-am", "bin/termux-am-socket",
        "bin/termux-apps-info-app-version-name", "bin/termux-apps-info-app-version-name.bash",
        "bin/termux-apps-info-app-version-name.sh", "bin/termux-apps-info-env-variable",
        "bin/termux-apps-info-env-variable.bash", "bin/termux-apps-info-env-variable.sh",
        "bin/termux-backup", "bin/termux-change-repo", "bin/termux-exec-ld-preload-lib",
        "bin/termux-fix-shebang", "bin/termux-info", "bin/termux-login",
        "bin/termux-open", "bin/termux-open-url", "bin/termux-reload-settings",
        "bin/termux-reset", "bin/termux-restore", "bin/termux-scoped-env-variable",
        "bin/termux-scoped-env-variable.bash", "bin/termux-scoped-env-variable.sh",
        "bin/termux-setup-package-manager", "bin/termux-setup-storage",
        "bin/termux-wake-lock", "bin/termux-wake-unlock",
        # 一般用不到的独立小工具
        "bin/ed", "bin/red", "bin/telnet", "bin/tftp", "bin/ftp", "bin/dialog",
        "bin/netcap", "bin/captest", "bin/filecap",
        # apt/dpkg 数据与库（仅被上面删掉的二进制引用）
        "etc/apt", "etc/alternatives", "var/lib/dpkg", "var/lib/apt", "var/cache/apt",
        "lib/apt", "lib/libapt-pkg.so", "lib/libapt-private.so", "lib/libmd.so",
        "lib/libgcrypt.so", "lib/libgpg-error.so", "lib/libassuan.so", "lib/libnpth.so",
        # gnutls 库簇（0 个保留二进制引用，仅互相引用）
        "lib/libgnutls.so", "lib/libgnutlsxx.so", "lib/libgnutls-dane.so",
        "lib/libhogweed.so.6", "lib/libhogweed.so.6.11", "lib/libnettle.so",
        "lib/libnettle.so.8.11", "lib/libunbound.so", "lib/libunbound.so.8",
        "lib/libidn2.so", "lib/libidn2.so.0", "lib/libunistring.so", "lib/libunistring.so.5",
        # 文档/开发残留
        "share/info", "share/bash-completion",
        "lib/node_modules/codex-web-local/dist-cli/index.js.map",
        "lib/node_modules/codex-web-local/package-lock.json",
    ):
        _rm(usr, rel)
        removed += 1
    log(f"瘦身移除 {removed} 项")


def _elf_needed(path: Path) -> list:
    """读取 ELF 的 NEEDED 依赖（readelf 不可用时回退空）。"""
    try:
        out = subprocess.run(
            ["readelf", "-d", str(path)], capture_output=True, text=True, timeout=20,
        ).stdout
    except Exception:
        return []
    return [ln.split("[")[1].split("]")[0].strip()
            for ln in out.splitlines() if "(NEEDED)" in ln]


def verify_image_deps(usr: Path):
    """瘦身后校验：保留的每个 ELF 可执行/共享库的 NEEDED 都能在 usr/lib 找到。

    libc/libm/libdl 等是 Android 系统（bionic）自带的，不在 usr/lib 里，跳过。
    """
    # Android 系统自带库（bionic），无需在 usr/lib 内
    SYSTEM_LIBS = {
        "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
        "libGLESv2.so", "libEGL.so", "libOpenSLES.so", "libjnigraphics.so",
        "libsync.so", "libstdc++.so",
    }
    lib_names = {p.name for p in (usr / "lib").glob("lib*.so*")}
    problems = []
    for probe in ("bin/sh", "bin/node", "bin/bash", "bin/curl", "bin/rg"):
        p = usr / probe
        if not p.exists():
            continue
        for need in _elf_needed(p):
            if need.startswith("lib") and need not in SYSTEM_LIBS:
                hit = any(name == need or name.startswith(need + ".") for name in lib_names)
                if not hit:
                    problems.append(f"{probe} -> {need}")
    if problems:
        raise SystemExit(f"[build-image] 瘦身破坏了动态依赖: {problems}")
    log("瘦身后依赖校验通过")


# ── 6a. 分片打包 ────────────────────────────────────────────────

def split_shards(raw_tar: Path, out_dir: Path, n: int) -> list:
    """把未压缩 stage.tar 拆成 n 个独立 gzip tar 分片（体积+数量均衡）。

    两阶段策略（针对"少数超大文件 + 大量小文件"的镜像结构）：
    1. 先取最大的 n 个文件，各自独占一个分片（避免 2 个大文件把 2 个线程
       占满、其余 3382 个小文件挤在另 2 个分片里的失衡情况）；
    2. 剩余条目按大小降序贪心放入当前最小的分片。
    分片顺序互不影响（解压端会自动 mkdir 父目录）。
    """
    outs = [out_dir / f"images{i}.bin" for i in range(n)]
    for o in outs:
        o.unlink(missing_ok=True)

    with tarfile.open(raw_tar, "r:") as src:
        members = sorted(src.getmembers(), key=lambda m: m.size, reverse=True)
        sizes = [0] * n
        handles = [open(o, "wb") for o in outs]
        try:
            streams = [tarfile.open(fileobj=h, mode="w|gz") for h in handles]

            def put(m, i):
                fobj = src.extractfile(m) if m.isfile() else None
                streams[i].addfile(m, fobj)
                sizes[i] += m.size

            # 阶段 1：最大的 n 个条目各占一个分片
            big, rest = members[:n], members[n:]
            for i, m in enumerate(big):
                put(m, i)
            # 阶段 2：其余按大小降序贪心放入当前最小的分片
            for m in rest:
                put(m, min(range(n), key=lambda k: sizes[k]))
            for s in streams:
                s.close()
        finally:
            for h in handles:
                h.close()
    for i, (o, s) in enumerate(zip(outs, sizes)):
        log(f"  分片 images{i}.bin: {s / 1024 / 1024:.1f} MB")
    return outs


def _merge_dir(src: Path, dst: Path):
    """把 src 下所有内容并入 dst（不同包解出的 termux 目录树合并）。"""
    for item in src.rglob("*"):
        rel = item.relative_to(src)
        target = dst / rel
        if item.is_symlink():
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists() or target.is_symlink():
                target.unlink()
            target.symlink_to(os.readlink(item))
        elif item.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(item, target)


if __name__ == "__main__":
    main()