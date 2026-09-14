# Mobilecode

> **Your phone is a development machine.** Install one APK, add your own API key, and let AI write code, run commands, and manage projects right on your Android device — no PC, no server, no cloud setup.

<p align="center">
  <img src="art/hero.jpg" alt="Mobilecode hero" width="640">
</p>

<p align="center">
  <a href="#"><img src="https://img.shields.io/badge/version-0.6.0-blue" alt="Version 0.5.0"></a>
  <a href="#"><img src="https://img.shields.io/badge/platform-Android%207.0%2B%20(ARM64)-green" alt="Platform"></a>
  <a href="#"><img src="https://img.shields.io/badge/language-EN%20%7C%20%E4%B8%AD%E6%96%87-orange" alt="Language"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-lightgrey" alt="License"></a>
</p>

**English** | [中文文档](README.zh-CN.md)

---

## Table of Contents

- [What is Mobilecode?](#what-is-mobilecode)
- [Features](#features)
- [Quick Start](#quick-start)
- [Supported Model Providers](#supported-model-providers)
- [Build from Source](#build-from-source)
- [Architecture](#architecture)
- [How It Works](#how-it-works)
- [Project Structure](#project-structure)
- [Requirements](#requirements)
- [Troubleshooting](#troubleshooting)
- [Roadmap & Docs](#roadmap--docs)
- [Security & License](#security--license)

---

## What is Mobilecode?

Mobilecode is an **AI coding workspace for Android**. It embeds a complete Linux userland
(Termux-style bootstrap) — Node.js 24, npm, the Codex CLI, and its native ARM64 engine,
plus a self-hosted web workspace — directly inside the APK.

On first launch, the environment is extracted **fully offline** (4-thread parallel
extraction, ~1 minute). No `apt install`, no `npm install`, no root, no server. The only
network your device needs is the one API call to your chosen model provider.

```
┌─────────────┐   install APK   ┌──────────────────────────────────────────┐
│   APK       │ ──────────────► │  Terminal / Web workspace (localhost)    │
│  images0-3  │                 │  Codex CLI + native engine               │
│  (built-in) │   extract once  │  Node.js 24 + npm                        │
│             │ ──────────────► │  Termux Linux userland                   │
└─────────────┘                 └──────────────────────────────────────────┘
                                     │  HTTPS (API calls only)
                                     ▼
                              OpenAI · DeepSeek · Qwen · Zhipu GLM
```

## Features

- **Zero-setup AI coding on mobile** — prebuilt, versioned runtime image inside the APK
- **Fully offline first-run install** — the environment is extracted, never downloaded
- **4-thread parallel extraction** — first launch in about a minute
- **Multiple model providers** — OpenAI (default), DeepSeek, Qwen, Zhipu GLM; switch in one tap
- **Secure API key storage** — Android Keystore-backed encryption (`SecureKeyStore`)
- **Self-hosted web workspace** — served locally on `127.0.0.1:18923`, loaded in WebView
- **Built-in CONNECT proxy** — lets the native binary reach HTTPS endpoints on-device
- **Diagnostics & one-tap environment reset** — recover without reinstalling
- **Battery-optimization aware** — runs as a foreground service; survives in background
- **English & Chinese UI/error messages** — clear, actionable errors with copy-to-clipboard diagnostics

## Quick Start

1. **Download the APK**

   Latest release: [`release/Mobilecode-v0.6.0-release.apk`](release/Mobilecode-v0.6.0-release.apk) (~90 MB)

2. **Install**

   Transfer the APK to your phone and open it. Enable **“Install from unknown sources”**
   when prompted.

3. **First launch**

   The app extracts the built-in runtime environment (progress bar, ~1 minute),
   then starts the local workspace automatically.

4. **Add your API key**

   Tap the **gear icon (⚙)** in the top-right corner, pick a provider, paste your
   API key, and tap OK. The workspace restarts and you are ready to chat and code.

## Supported Model Providers

| Provider | Default model | Base URL | Env key |
| --- | --- | --- | --- |
| OpenAI | `gpt-4.1-mini` | `api.openai.com` | `OPENAI_API_KEY` |
| DeepSeek | `deepseek-chat` | `api.deepseek.com` | `DEEPSEEK_API_KEY` |
| Qwen (Alibaba) | `qwen-plus` | `dashscope.aliyuncs.com` | `DASHSCOPE_API_KEY` |
| Zhipu GLM | `glm-4.5` | `open.bigmodel.cn` | `ZHIPU_API_KEY` |

> Bring your own API key — the key never leaves your device and is stored encrypted.

## Build from Source

### Prerequisites

- **JDK 17** (AGP 8.7 / Kotlin 2.1 do **not** support JDK 21+)
- **Android SDK** — write `sdk.dir=...` into `android/local.properties`
  (first time: `sdkmanager --licenses`)
- **Python 3.10+**
- **One-time internet access** to build the image: Termux bootstrap / debs / Codex
  (~150 MB downloads, cached in `scripts/.cache`; ~80 MB image output)

### Steps

```bash
cd android

# 1. Assemble the runtime image → app/src/main/assets/images0..3.bin
python3 scripts/build-image.py

# 2. Build the APK
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk (~90 MB)
```

> Step 1 is **required**: the image shards are the app’s runtime. Shipping an APK
> without them (or with a mismatched `.runtime-version`) breaks startup.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android APK (v0.6.0)                      │
│                                                                 │
│  ┌──────────────────────┐        ┌───────────────────────────┐  │
│  │      WebView UI      │        │  MainActivity             │  │
│  │  http://127.0.0.1:   │        │  · setup steps            │  │
│  │       18923          │        │  · diagnostics & reset    │  │
│  └──────────┬───────────┘        └────────────┬──────────────┘  │
│             │                                │                  │
│             ▼                                ▼                  │
│  ┌──────────────────────┐        ┌───────────────────────────┐  │
│  │ codex-web-local      │        │ CodexServerManager        │  │
│  │ workspace server     │◄──────►│ · install/extract (4 thd) │  │
│  │ (Node.js, on-device) │        │ · proxy on 127.0.0.1:18924│  │
│  └──────────┬───────────┘        │ · config/auth (Keystore)  │  │
│             │                    └────────────┬──────────────┘  │
│             ▼                                ▼                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  files/usr  (Termux-style Linux userland, extracted)     │   │
│  │  ├── bin/sh · node · npm · codex                         │   │
│  │  └── lib/node_modules/@openai/codex (+linux-arm64)       │   │
│  │      lib/node_modules/codex-web-local                    │   │
│  └──────────────────────────────────────────────────────────┘   │
│                       │ HTTPS (API calls only)                  │
└───────────────────────┼─────────────────────────────────────────┘
                        ▼
              OpenAI · DeepSeek · Qwen · GLM
```

Key components:

- **`build-image.py`** — assembles a *ready-to-run* image on the build machine
  (Termux bootstrap + Node + Codex + workspace), rewrites absolute paths, slims
  the tree, validates dynamic dependencies, and splits the result into **4 gzip+tar
  shards** for parallel extraction.
- **`TarExtractor.kt`** — streams the shards on-device with full GNU/PAX tar support
  (long names, symlinks, permissions); each shard is extracted by its own thread.
- **`CodexServerManager.kt`** — install lifecycle, version checks (`needsInstall`),
  CONNECT proxy, workspace server, provider config & health check.
- **`SecureKeyStore.kt`** — encrypts your API key with an Android Keystore-backed key.
- **`MainActivity.kt`** — step-driven startup flow with progress, error recovery
  (retry / diagnostics / reset), and the embedded WebView workspace.

## How It Works

1. **Versioned image** — the image carries `.runtime-version` (currently `0.4.1`).
   If it ever mismatches the app’s expected version, the app re-extracts exactly once,
   so stale environments can never wedge startup.
2. **One-time offline install** — first run extracts the shards to
   `files/usr/`, writes a full-access Codex config, and initializes a git workspace.
3. **Local networking** — a Node CONNECT proxy on `127.0.0.1:18924` bridges the
   native binary to HTTPS; the workspace server listens on `127.0.0.1:18923`.
4. **Your key, your model** — provider + key are written to `~/.codex/config.toml`
   and `auth.json` inside the app sandbox; only that provider is contacted.

## Project Structure

```
mobilecode-repo/
├── README.md               ← this file
├── README.zh-CN.md         ← 中文版
├── CHANGELOG.md            ← release notes
├── DESIGN.md               ← design notes & decisions
├── SECURITY.md             ← security policy
├── CONTRIBUTING.md         ← how to contribute
├── THIRD_PARTY_NOTICES.md  ← bundled open-source components
├── art/                    ← marketing/hero assets
├── docs/
│   ├── ROADMAP.md          ← roadmap & milestones
│   └── domestic-models.md  ← domestic model provider notes
├── release/                ← prebuilt APKs
└── android/                ← curated source mirror (full source: openclaw-android)
```

## Requirements

- Android 7.0 (API 24) or newer, **ARM64** device
- ~500 MB free storage
- Internet only for model API calls (and, if building, for image downloads)

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| **"App not installed" / install fails** | ① Had a v0.4.x build installed? **Uninstall it first** — older releases were signed with a different debug certificate, so the system refuses a signature-mismatch upgrade. ② Make sure the device is Android 7.0+ on ARM64. ③ APKs transferred via WeChat/QQ may be renamed or truncated — use USB/cloud storage instead. ④ On MIUI enable Developer options → "Install via USB". Since v0.5.0 the APK is signed with a dedicated release certificate (v2+v3 schemes, same cert for debug and release builds) for maximum installer compatibility. |
| “Environment extraction failed” on launch | Tap **Retry**; if it repeats, open **⚙ → Diagnostics & Environment** to check free storage (>500 MB) and tap **Reset environment**. |
| App re-extracts the environment on every launch | Version mismatch: the built-in image `.runtime-version` must equal `RUNTIME_IMAGE_VERSION` in `CodexServerManager.kt`. Rebuild the image with `build-image.py` (v0.4.1+ aligns both). |
| Gradle build fails on another machine | Since v0.5.0 the repo no longer carries machine-specific config: you only need JDK 17 + Android SDK (`sdk.dir` in `android/local.properties`). Aliyun Maven mirrors and the Tencent Gradle mirror are built in — no proxy needed in mainland China. |
| Workspace starts but API calls fail | Check the key in ⚙ settings and that the device can reach the provider endpoint. |

## Roadmap & Docs

- [ROADMAP.md](docs/ROADMAP.md) — what is coming next
- [DESIGN.md](DESIGN.md) — architecture rationale and trade-offs
- [CHANGELOG.md](CHANGELOG.md) — version history

## Security & License

- **Security**: see [SECURITY.md](SECURITY.md) for the policy and how to report issues.
- **License**: [MIT](LICENSE) · bundled components listed in
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **Disclaimer**: this is an early-stage project. Verify generated code before running
  it, and keep your API keys private.
