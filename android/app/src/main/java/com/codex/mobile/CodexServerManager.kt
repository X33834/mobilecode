package com.codex.mobile

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 管理手机端运行环境与工作台生命周期。
 *
 * v0.3.0 起运行环境是构建机预组装的**成品镜像**：
 * Termux bootstrap + Node.js 24 + npm + Codex CLI + linux-arm64 原生引擎
 * + codex-web-local 工作台，路径/权限/符号链接全部在镜像里固化，
 * 设备端一次解压即用，无任何在线安装/补丁/包装步骤。
 * 唯一需要网络的环节：用户调用模型 API。
 *
 * v0.4.0 起镜像拆成 4 个独立分片（assets/images0..3.bin），
 * 设备端 4 线程并行解压，多核 IO 并行让首次启动提速 2-3 倍。
 */
class CodexServerManager(private val context: Context) {

    companion object {
        private const val TAG = "CodexServerManager"
        const val SERVER_PORT = 18923
        private const val PROXY_PORT = 18924
        // chat-bridge：Responses ⇄ Chat 本地协议桥（0.104.0 引擎只讲 Responses，
        // DeepSeek/Qwen/GLM 只讲 Chat，靠它在本地翻译）
        private const val BRIDGE_PORT = 18925
        // 与 app-server 前端 UI/protocol 匹配的锁版本（勿随意升级）
        private const val CODEX_VERSION = "0.104.0"
        // 内置镜像版本号，与镜像内 .runtime-version 一致（v0.4 起为分片镜像）
        private const val RUNTIME_IMAGE_VERSION = "0.6.0"
        // 构建期分出的独立 gzip+tar 分片；并行解压，线程数 = 分片数
        const val IMAGE_SHARD_COUNT = 4
        private val IMAGE_SHARDS = listOf(
            "images0.bin",
            "images1.bin",
            "images2.bin",
            "images3.bin",
        )
    }

    private var serverProcess: Process? = null
    private var proxyProcess: Process? = null
    private var bridgeProcess: Process? = null

    val isRunning: Boolean
        get() {
            val proc = serverProcess ?: return false
            return try {
                proc.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
        }

    // ── Shell helpers ──────────────────────────────────────────────────────

    /**
     * 在镜像内执行 shell 命令，返回退出码。
     */
    fun runInPrefix(
        command: String,
        onOutput: ((String) -> Unit)? = null,
    ): Int {
        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths)

        val shell = "${paths.prefixDir}/bin/sh"
        val pb = ProcessBuilder(shell, "-c", command)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line = reader.readLine()
        while (line != null) {
            Log.d(TAG, line)
            onOutput?.invoke(line)
            line = reader.readLine()
        }
        return proc.waitFor()
    }

    // ── v0.3.0：成品镜像（零网络、一次解压即用）──────────────────

    /** 镜像关键文件是否齐备（镜像是完整整体，检查门面粉碎点）。 */
    fun isRuntimeReady(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val usrDir = File(paths.prefixDir)
        if (!File(usrDir, "bin/sh").exists()) return false
        if (!File(usrDir, "bin/node").exists()) return false
        if (!File(usrDir, "lib/node_modules/@openai/codex/bin/codex.js").exists()) return false
        if (!File(
                usrDir,
                "lib/node_modules/@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/codex/codex",
            ).exists()
        ) return false
        return File(usrDir, "lib/node_modules/codex-web-local/dist-cli/index.js").exists()
    }

    /** 是否需要（重新）安装环境：文件缺失，或版本与当前 APK 不匹配。 */
    fun needsInstall(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val usrDir = File(paths.prefixDir)
        return !isRuntimeReady() || !imageVersionOk(usrDir)
    }

    /**
     * 把 APK 内置的成品镜像分片 images0..3.bin（gzip 压缩的 tar，构建期生成）
     * **并行**解压为完整 Linux 用户态。全程离线；版本不匹配或损坏时重建 usr/
     * （home/ 下的用户数据独立，予以保留）。
     *
     * @throws RuntimeException 资源缺失/解压失败（APK 损坏时应提示重装）
     */
    fun installRuntimeFromAssets(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val usrDir = File(paths.prefixDir)

        if (!needsInstall()) {
            Log.i(TAG, "Runtime already installed at ${paths.prefixDir}")
            return true
        }

        onProgress("并行解压内置运行环境（一次成型，无需联网）…")

        if (usrDir.exists()) {
            Log.w(TAG, "Recreating usr (old version or corrupted)")
            deleteRecursive(usrDir)
        }
        usrDir.mkdirs()

        try {
            // 每个分片一个线程，多核 IO 并行；任一分片失败即整体失败
            val errors = java.util.concurrent.ConcurrentHashMap<String, Throwable>()
            val threads = IMAGE_SHARDS.map { name ->
                Thread {
                    try {
                        context.assets.open(name).use { input ->
                            TarExtractor.extractStream(input, File(paths.filesDir), onProgress)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "分片 $name 解压失败: ${e.message}")
                        errors[name] = e
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            if (errors.isNotEmpty()) {
                throw errors.values.first()
            }
        } catch (e: Exception) {
            Log.e(TAG, "镜像解压失败: ${e.message}")
            deleteRecursive(usrDir)
            throw RuntimeException("内置运行环境解压失败（APK 数据可能损坏）", e)
        }

        // 工作台/Codex 会把 TMPDIR 指向 usr/tmp，解压完必须确保该目录存在
        File(paths.tmpDir).mkdirs()

        val ok = isRuntimeReady()
        Log.i(TAG, "installRuntimeFromAssets -> ready=$ok")
        return ok
    }

    /** 镜像内 .runtime-version 是否与当前 APK 期望版本一致。 */
    private fun imageVersionOk(usrDir: File): Boolean {
        val verFile = File(usrDir, ".runtime-version")
        if (!verFile.exists()) return false
        return verFile.readText().trim() == RUNTIME_IMAGE_VERSION
    }

    // ── 认证：provider API Key（不依赖 OAuth/登录） ────────────────

    private fun codexBinPath(): String {
        val paths = BootstrapInstaller.getPaths(context)
        return "${paths.prefixDir}/lib/node_modules/@openai/codex-linux-arm64" +
            "/vendor/aarch64-unknown-linux-musl/codex/codex"
    }

    private data class ProviderSpec(
        val id: String,
        val model: String,
        /** 引擎直连的 base_url（写入 config.toml）。chat 型 provider 指向本地 bridge。 */
        val baseUrl: String,
        val envKey: String,
        /** 非 null 表示纯 Chat Completions 上游，须经 chat-bridge 翻译。 */
        val upstreamChatUrl: String? = null,
    )

    private val providers = listOf(
        // OpenAI 原生支持 Responses API：引擎直连
        ProviderSpec("openai", "gpt-4.1-mini", "https://api.openai.com/v1", "OPENAI_API_KEY"),
        // DeepSeek/Qwen/GLM 只提供 Chat Completions：
        // 0.104.0 引擎已删除 wire_api="chat"，必须经本地 chat-bridge 翻译
        ProviderSpec(
            "deepseek", "deepseek-chat",
            "http://127.0.0.1:$BRIDGE_PORT/deepseek/v1", "DEEPSEEK_API_KEY",
            "https://api.deepseek.com/v1",
        ),
        ProviderSpec(
            "qwen", "qwen-plus",
            "http://127.0.0.1:$BRIDGE_PORT/qwen/v1", "DASHSCOPE_API_KEY",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
        ),
        ProviderSpec(
            "glm", "glm-4.5",
            "http://127.0.0.1:$BRIDGE_PORT/glm/v1", "ZHIPU_API_KEY",
            "https://open.bigmodel.cn/api/paas/v4",
        ),
    )

    private val prefs by lazy {
        context.getSharedPreferences("mobilecode", Context.MODE_PRIVATE)
    }

    fun getConfiguredProvider(): String? = prefs.getString("provider", null)

    /** 读取 API Key（Keystore 加密存储，兼容旧明文）。 */
    fun getConfiguredApiKey(): String? =
        SecureKeyStore.decrypt(prefs.getString("api_key", null))

    /**
     * 保存用户选择的 provider + API Key，并写入 Codex 配置
     * （config.toml 注册全部 provider，auth.json 写入当前 Key）。
     *
     * v0.6.0 修复：Codex 0.104.0 彻底移除了 wire_api="chat"，引擎只会讲
     * Responses API。所有 provider 统一写 wire_api="responses"；纯 Chat 上游
     * （DeepSeek/Qwen/GLM）的 base_url 指向本地 chat-bridge（:18925），由桥把
     * Responses 翻译成 Chat Completions 转发上游。
     * 同时修复 model 写法："provider/model" 复合串 0.104.0 无法绑定自定义
     * provider（实测回落默认 openai），必须 model 与 model_provider 分离。
     */
    fun configureProvider(providerId: String, apiKey: String): Boolean {
        val spec = providers.firstOrNull { it.id == providerId }
            ?: return false
        if (apiKey.isBlank()) return false

        prefs.edit()
            .putString("provider", spec.id)
            .putString("api_key", SecureKeyStore.encrypt(apiKey))
            .apply()

        val paths = BootstrapInstaller.getPaths(context)
        val configDir = File(paths.homeDir, ".codex")
        configDir.mkdirs()

        val toml = buildString {
            appendLine("approval_policy = \"never\"")
            appendLine("sandbox_mode = \"danger-full-access\"")
            appendLine("model = \"${spec.model}\"")
            appendLine("model_provider = \"${spec.id}\"")
            appendLine()
            for (p in providers) {
                appendLine("[model_providers.${p.id}]")
                appendLine("name = \"${p.id}\"")
                appendLine("base_url = \"${p.baseUrl}\"")
                appendLine("env_key = \"${p.envKey}\"")
                appendLine("wire_api = \"responses\"")
                appendLine()
            }
        }
        File(configDir, "config.toml").writeText(toml)

        val authJson = """{"${spec.envKey}": "$apiKey", "OPENAI_API_KEY": "$apiKey"}"""
        File(configDir, "auth.json").writeText(authJson)
        Log.i(TAG, "Provider configured: ${spec.id} (${spec.model})")

        // 选中的是纯 Chat 上游 → 确保本地协议桥已启动
        if (spec.upstreamChatUrl != null && !startChatBridge()) {
            Log.e(TAG, "chat-bridge failed to start; chat-only provider ${spec.id} will not work")
        }
        return true
    }

    fun isLoggedIn(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val authFile = File(paths.homeDir, ".codex/auth.json")
        return getConfiguredApiKey() != null ||
            (authFile.exists() && authFile.readText().contains("API_KEY"))
    }

    // ── Health check（带超时，不再无限等待） ─────────────────────

    /**
     * 用最小 prompt 调 Codex exec 验证 API Key 可用性。
     * 不超过 [timeoutMs]（默认 45 秒），超时即失败返回，不阻塞进入界面。
     */
    fun healthCheck(
        onProgress: (String) -> Unit,
        timeoutMs: Long = 45_000,
    ): Boolean {
        onProgress("正在验证 API Key…")

        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths).toMutableMap()
        env["HTTPS_PROXY"] = "http://127.0.0.1:$PROXY_PORT"
        env["HTTP_PROXY"] = "http://127.0.0.1:$PROXY_PORT"
        env["NO_PROXY"] = "127.0.0.1,localhost"

        getConfiguredProvider()?.let { providerId ->
            providers.firstOrNull { it.id == providerId }?.let { spec ->
                getConfiguredApiKey()?.let { key -> env[spec.envKey] = key }
                if (spec.upstreamChatUrl != null && !startChatBridge()) {
                    Log.e(TAG, "chat-bridge unavailable for provider ${spec.id}")
                }
            }
        }

        val shell = "${paths.prefixDir}/bin/sh"
        val cmd = "${codexBinPath()} exec --skip-git-repo-check \"say hi\" 2>&1"

        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        val sb = StringBuilder()

        val readerThread = Thread {
            try {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line = reader.readLine()
                while (line != null) {
                    val clean = line.replace(Regex("\\x1b\\[[0-9;]*m"), "").trim()
                    Log.d(TAG, "[health] $clean")
                    sb.appendLine(clean)
                    onProgress(clean)
                    line = reader.readLine()
                }
            } catch (_: Exception) {}
        }
        readerThread.start()

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                if (proc.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    readerThread.join(2000)
                    val output = sb.toString().trim()
                    Log.i(TAG, "Health check exit=${proc.exitValue()} output=$output")
                    return proc.exitValue() == 0 && output.isNotEmpty()
                }
            } catch (_: Exception) {}
        }

        Log.e(TAG, "Health check timed out after ${timeoutMs}ms — killing")
        destroyProcess(proc)
        return false
    }

    /**
     * 销毁子进程：先 SIGTERM 优雅退出，最多等 [timeoutMs]；
     * 超时则 SIGKILL 强杀。node 收到 SIGTERM 默认直接退出，
     * 但卡在不可中断 IO 时只能强杀，否则进程残留占着 18923/18924 端口，
     * 下次启动 EADDRINUSE 反复失败（重启手机才能恢复）。
     */
    private fun destroyProcess(proc: Process, timeoutMs: Long = 3_000) {
        try {
            proc.destroy()
            if (!proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "destroyProcess: ${e.message}")
        }
    }

    // ── Proxy（原生二进制的 DNS/TLS 桥） ─────────────────────────

    fun startProxy(): Boolean {
        // 进程已退出但对象未清空时视为未运行，重新拉起
        if (proxyProcess != null) {
            try {
                proxyProcess?.exitValue()
                proxyProcess = null // 已退出
            } catch (_: IllegalThreadStateException) {
                return true // 仍在运行
            }
        }

        val paths = BootstrapInstaller.getPaths(context)
        val proxyScript = File(paths.homeDir, "proxy.js")

        try {
            context.assets.open("proxy.js").use { input ->
                proxyScript.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract proxy.js asset: ${e.message}")
            return false
        }

        val pidFile = File(paths.homeDir, ".proxy.pid")
        if (pidFile.exists()) {
            try {
                val oldPid = pidFile.readText().trim()
                ProcessBuilder("kill", oldPid).start().waitFor()
                Thread.sleep(500)
            } catch (_: Exception) {}
            pidFile.delete()
        }

        val env = buildEnvironment(paths)
        val shell = "${paths.prefixDir}/bin/sh"
        val cmd = "exec node ${proxyScript.absolutePath}"

        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        proxyProcess = proc

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[proxy] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "Proxy exited with code: ${proc.waitFor()}")
            // 进程退出后清空引用，下次 startProxy 能重新拉起
            if (proxyProcess === proc) proxyProcess = null
        }.start()

        // 验证代理真的在监听（proxy.js 启动失败会秒退，不能静默放行）
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen("127.0.0.1", PROXY_PORT)) {
                Log.i(TAG, "CONNECT proxy started on 127.0.0.1:$PROXY_PORT")
                return true
            }
            Thread.sleep(200)
        }
        Log.e(TAG, "Proxy did not listen on 127.0.0.1:$PROXY_PORT within 5s")
        try { proc.destroy() } catch (_: Exception) {}
        proxyProcess = null
        return false
    }

    /** 探测本地端口是否已在监听（连接失败即返回 false）。 */
    private fun isPortOpen(host: String, port: Int): Boolean {
        return try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 300) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stopProxy() {
        val proc = proxyProcess ?: return
        proxyProcess = null
        destroyProcess(proc)
    }

    // ── chat-bridge（Responses ⇄ Chat 本地协议桥） ───────────────

    /**
     * 启动本地 chat-bridge（127.0.0.1:18925）。
     * Codex 0.104.0 只讲 Responses API；DeepSeek/Qwen/GLM 只讲 Chat
     * Completions。桥在设备本地把两者互译（详见 assets/chat-bridge.js）。
     * 已在运行则直接返回 true。
     */
    fun startChatBridge(): Boolean {
        if (bridgeProcess != null) {
            try {
                bridgeProcess?.exitValue()
                bridgeProcess = null
            } catch (_: IllegalThreadStateException) {
                return true
            }
        }

        val paths = BootstrapInstaller.getPaths(context)
        val bridgeScript = File(paths.homeDir, "chat-bridge.js")
        try {
            context.assets.open("chat-bridge.js").use { input ->
                bridgeScript.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract chat-bridge.js asset: ${e.message}")
            return false
        }

        val env = buildEnvironment(paths)
        val shell = "${paths.prefixDir}/bin/sh"
        val cmd = "exec node ${bridgeScript.absolutePath}"

        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = try {
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start chat-bridge: ${e.message}")
            return false
        }
        bridgeProcess = proc

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[bridge] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "chat-bridge exited with code: ${proc.waitFor()}")
            if (bridgeProcess === proc) bridgeProcess = null
        }.start()

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen("127.0.0.1", BRIDGE_PORT)) {
                Log.i(TAG, "chat-bridge started on 127.0.0.1:$BRIDGE_PORT")
                return true
            }
            Thread.sleep(200)
        }
        Log.e(TAG, "chat-bridge did not listen on 127.0.0.1:$BRIDGE_PORT within 5s")
        destroyProcess(proc)
        bridgeProcess = null
        return false
    }

    fun stopChatBridge() {
        val proc = bridgeProcess ?: return
        bridgeProcess = null
        destroyProcess(proc)
    }

    // ── Server lifecycle ────────────────────────────────────────

    /**
     * 启动 codex-web-local 工作台服务器（镜像内置）。
     * CONNECT 代理须先运行；通过 env 注入用户 API Key 让 app-server 读到凭据。
     */
    fun startServer(): Boolean {
        if (isRunning) {
            Log.i(TAG, "Server already running")
            return true
        }

        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths).toMutableMap()
        env["HTTPS_PROXY"] = "http://127.0.0.1:$PROXY_PORT"
        env["HTTP_PROXY"] = "http://127.0.0.1:$PROXY_PORT"
        // 本地服务（工作台/引擎/chat-bridge）绝不能被 CONNECT 代理劫持
        env["NO_PROXY"] = "127.0.0.1,localhost"

        getConfiguredProvider()?.let { providerId ->
            providers.firstOrNull { it.id == providerId }?.let { spec ->
                getConfiguredApiKey()?.let { key -> env[spec.envKey] = key }
                // chat 型 provider：先确保协议桥在跑（工作台会话经引擎→桥→上游）
                if (spec.upstreamChatUrl != null && !startChatBridge()) {
                    Log.e(TAG, "chat-bridge unavailable for provider ${spec.id}")
                }
            }
        }

        val serverScript = "${paths.prefixDir}/lib/node_modules/codex-web-local/dist-cli/index.js"
        if (!File(serverScript).exists()) {
            Log.e(TAG, "Server script not found: $serverScript")
            return false
        }

        val shell = "${paths.prefixDir}/bin/sh"
        val command = "exec node $serverScript --port $SERVER_PORT --no-password"

        Log.i(TAG, "Starting server: $command")

        val pb = ProcessBuilder(shell, "-c", command)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        serverProcess = proc

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[server] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "Server process exited with code: ${proc.waitFor()}")
        }.start()

        return true
    }

    fun waitForServer(timeoutMs: Long = 60_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val url = URL("http://127.0.0.1:$SERVER_PORT/")

        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..399) {
                    Log.i(TAG, "Server is ready (HTTP $code)")
                    return true
                }
            } catch (_: Exception) {
                // Not ready yet
            }
            Thread.sleep(500)
        }

        Log.e(TAG, "Server did not become ready within ${timeoutMs}ms")
        return false
    }

    fun stopServer() {
        val proc = serverProcess ?: return
        serverProcess = null

        // destroyProcess 带超时：本方法会在 Activity.onDestroy（主线程）被调，
        // 无上限 waitFor 会造成 ANR。
        destroyProcess(proc)

        stopChatBridge()
        stopProxy()
        Log.i(TAG, "Server stopped")
    }

    // ── 其它初始化 ──────────────────────────────────────────────

    fun ensureFullAccessConfig() {
        val paths = BootstrapInstaller.getPaths(context)
        // 工作台/Codex 依赖 TMPDIR（usr/tmp）；二次启动（环境已就绪）也须确保存在
        File(paths.tmpDir).mkdirs()
        val configDir = File(paths.homeDir, ".codex")
        configDir.mkdirs()
        val configFile = File(configDir, "config.toml")
        val desired = """
            |approval_policy = "never"
            |sandbox_mode = "danger-full-access"
        """.trimMargin().trim() + "\n"

        if (configFile.exists()) {
            val current = configFile.readText()
            if (current.contains("approval_policy") && current.contains("danger-full-access")) {
                return
            }
        }
        configFile.writeText(desired)
        Log.i(TAG, "Wrote full-access config to $configFile")
    }

    fun ensureDefaultWorkspace() {
        val paths = BootstrapInstaller.getPaths(context)
        val workspaceDir = File(paths.homeDir, "codex")
        if (workspaceDir.exists()) return

        workspaceDir.mkdirs()
        runInPrefix("cd ${workspaceDir.absolutePath} && git init 2>&1")
        Log.i(TAG, "Created default workspace at $workspaceDir")
    }

    private fun buildEnvironment(
        paths: BootstrapInstaller.Paths,
    ): Map<String, String> {
        val env = HashMap<String, String>()
        env["PREFIX"] = paths.prefixDir
        env["HOME"] = paths.homeDir
        env["PATH"] = "${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin"
        env["LD_LIBRARY_PATH"] = "${paths.prefixDir}/lib"
        env["TERMUX_PREFIX"] = paths.prefixDir
        env["LANG"] = "en_US.UTF-8"
        env["TMPDIR"] = paths.tmpDir
        env["TMP"] = paths.tmpDir
        env["TEMP"] = paths.tmpDir
        env["TERM"] = "xterm-256color"
        env["ANDROID_DATA"] = "/data"
        env["ANDROID_ROOT"] = "/system"
        env["APT_CONFIG"] = "${paths.prefixDir}/etc/apt/apt.conf"
        env["DPKG_ADMINDIR"] = "${paths.prefixDir}/var/lib/dpkg"
        env["SSL_CERT_FILE"] = "${paths.prefixDir}/etc/tls/cert.pem"
        env["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
        env["CURL_CA_BUNDLE"] = "${paths.prefixDir}/etc/tls/cert.pem"
        env["GIT_SSL_CAINFO"] = "${paths.prefixDir}/etc/tls/cert.pem"
        env["GIT_CONFIG_NOSYSTEM"] = "1"
        env["GIT_EXEC_PATH"] = "${paths.prefixDir}/libexec/git-core"
        env["GIT_TEMPLATE_DIR"] = "${paths.prefixDir}/share/git-core/templates"
        env["OPENSSL_CONF"] = "${paths.prefixDir}/etc/tls/openssl.cnf"
        env["NODE_OPTIONS"] = "--openssl-config=${paths.prefixDir}/etc/tls/openssl.cnf --unhandled-rejections=warn"
        // 本地回环服务（工作台 18923 / chat-bridge 18925）不走 CONNECT 代理
        env["NO_PROXY"] = "127.0.0.1,localhost"
        env["CONTAINER"] = "1"
        return env
    }

    private fun deleteRecursive(fileOrDir: File) {
        if (fileOrDir.isDirectory) {
            fileOrDir.listFiles()?.forEach { child ->
                deleteRecursive(child)
            }
        }
        fileOrDir.delete()
    }
}