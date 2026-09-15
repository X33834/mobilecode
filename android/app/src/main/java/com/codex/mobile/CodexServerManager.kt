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
/**
 * 设置页对外暴露的供应商摘要（不泄漏内部 envKey / 上游等实现细节）。
 */
data class ProviderSummary(
    val id: String,
    val label: String,
    val models: List<String>,
    val allowCustomBaseUrl: Boolean,
    val customOnly: Boolean,
)

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
        private const val RUNTIME_IMAGE_VERSION = "0.7.2"
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

    /**
     * 模型服务商规格。
     * - [models] 第一个元素即默认模型；[directBaseUrl] 非 null 表示引擎可**直连**
     *   Responses API（OpenAI 风格），写入 config.toml 的 base_url。
     * - [chatUpstream] 非 null 表示纯 Chat Completions 上游，引擎无法直连，
     *   必须经本地 chat-bridge（:18925）翻译；此时 config.toml 的 base_url 指向桥。
     * - [allowCustomBaseUrl] 是否允许用户覆盖 base_url（OpenAI 兼容代理/自建/本地）。
     * - [customOnly] 仅「自定义」供应商为 true：base_url 与上游完全由用户决定。
     */
    private data class ProviderSpec(
        val id: String,
        val label: String,
        val models: List<String>,
        val envKey: String,
        val directBaseUrl: String? = null,
        val chatUpstream: String? = null,
        val allowCustomBaseUrl: Boolean = true,
        val customOnly: Boolean = false,
    ) {
        /** 是否纯 Chat 上游（须经桥翻译）。 */
        val isChatOnly: Boolean get() = chatUpstream != null
        val defaultModel: String get() = models.firstOrNull() ?: ""
    }

    /** 内置供应商目录（顺序即设置页下拉顺序）。custom 为「自建/兼容端点」入口。 */
    private val providers = listOf(
        // OpenAI：原生 Responses API，引擎直连
        ProviderSpec(
            "openai", "OpenAI",
            listOf("gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano", "gpt-4o", "gpt-4o-mini", "o4-mini", "o3-mini"),
            "OPENAI_API_KEY",
            directBaseUrl = "https://api.openai.com/v1",
        ),
        // OpenRouter：转发到各家，且原生支持 OpenAI Responses API → 直连
        ProviderSpec(
            "openrouter", "OpenRouter",
            listOf("openai/gpt-4.1", "anthropic/claude-3.5-sonnet", "google/gemini-2.0-pro-exp-02-05", "deepseek/deepseek-chat"),
            "OPENROUTER_API_KEY",
            directBaseUrl = "https://openrouter.ai/api/v1",
        ),
        // DeepSeek：仅 Chat Completions，须经本地桥
        ProviderSpec(
            "deepseek", "DeepSeek",
            listOf("deepseek-chat", "deepseek-reasoner"),
            "DEEPSEEK_API_KEY",
            chatUpstream = "https://api.deepseek.com/v1",
        ),
        // 通义千问：DashScope 兼容模式，仅 Chat，须经桥
        ProviderSpec(
            "qwen", "通义千问 Qwen",
            listOf("qwen-plus", "qwen-max", "qwen-turbo", "qwen2.5-coder-32b-instruct"),
            "DASHSCOPE_API_KEY",
            chatUpstream = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        ),
        // 智谱 GLM：仅 Chat，须经桥
        ProviderSpec(
            "glm", "智谱 GLM",
            listOf("glm-4.5", "glm-4-plus", "glm-4-air", "glm-4-flash"),
            "ZHIPU_API_KEY",
            chatUpstream = "https://open.bigmodel.cn/api/paas/v4",
        ),
        // Moonshot（Kimi）：OpenAI 兼容，仅 Chat，须经桥
        ProviderSpec(
            "moonshot", "Moonshot Kimi",
            listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
            "MOONSHOT_API_KEY",
            chatUpstream = "https://api.moonshot.cn/v1",
        ),
        // Ollama：本地 OpenAI 兼容，仅 Chat，须经桥（默认本地 11434）
        ProviderSpec(
            "ollama", "Ollama（本地）",
            listOf("llama3.1", "qwen2.5", "deepseek-r1", "phi3"),
            "OLLAMA_API_KEY",
            chatUpstream = "http://127.0.0.1:11434/v1",
        ),
        // 自定义：base_url 与上游完全由用户填写（兼容各类 OpenAI 代理 / 自建 / vLLM）
        ProviderSpec(
            "custom", "自定义（OpenAI 兼容）",
            listOf("custom-model"),
            "CUSTOM_API_KEY",
            customOnly = true,
        ),
    )

    private val prefs by lazy {
        context.getSharedPreferences("mobilecode", Context.MODE_PRIVATE)
    }

    fun getConfiguredProvider(): String? = prefs.getString("provider", null)

    /** 用户选定的模型（可能为自定义文本）。 */
    fun getConfiguredModel(): String? {
        val m = prefs.getString("model", null)
        return if (m.isNullOrBlank()) providers.firstOrNull { it.id == getConfiguredProvider() }?.defaultModel else m
    }

    /** 用户选定的自定义 base_url（仅允许覆盖的供应商）。 */
    fun getConfiguredBaseUrl(): String? {
        val raw = prefs.getString("base_url", null)
        return if (raw.isNullOrBlank()) null else raw
    }

    /** 用户在「自定义」供应商下选择的端点类型：responses（直连）或 chat（经桥）。 */
    fun getCustomEndpointType(): String = prefs.getString("custom_endpoint", "chat") ?: "chat"

    /** 全部内置供应商摘要（设置页用）。 */
    fun getAllProviders(): List<ProviderSummary> = providers.map { it.toSummary() }

    /** 按 id 取供应商摘要。 */
    fun getProviderSummary(id: String?): ProviderSummary? = providers.firstOrNull { it.id == id }?.toSummary()

    private fun ProviderSpec.toSummary(): ProviderSummary =
        ProviderSummary(id, label, models, allowCustomBaseUrl, customOnly)

    /** 读取 API Key（Keystore 加密存储，兼容旧明文）。 */
    fun getConfiguredApiKey(): String? =
        SecureKeyStore.decrypt(prefs.getString("api_key", null))

    /**
     * 保存用户选择的 provider + 模型 + API Key（+ 可选自定义 base_url / 端点类型），
     * 并写入 Codex 配置（config.toml 注册全部 provider，auth.json 写入当前 Key）。
     *
     * v0.6.0 修复：Codex 0.104.0 彻底移除了 wire_api="chat"，引擎只会讲
     * Responses API。纯 Chat 上游（DeepSeek/Qwen/GLM/Ollama/自定义-Chat）的
     * base_url 指向本地 chat-bridge（:18925），由桥把 Responses 翻译成 Chat
     * Completions 转发上游；直连型（OpenAI/OpenRouter/自定义-Responses）的
     * base_url 直接写用户端点。
     * model 与 model_provider 必须分离（复合串 0.104.0 无法绑定自定义 provider）。
     *
     * @param baseUrl 用户自定义 base_url（OpenAI 兼容代理/自建/本地），为空则用默认。
     * @param endpointType 仅「自定义」供应商使用："responses"（直连）或 "chat"（经桥）。
     */
    fun configureProvider(
        providerId: String,
        model: String,
        apiKey: String,
        baseUrl: String? = null,
        endpointType: String = "chat",
        startBridge: Boolean = true,
    ): Boolean {
        val spec = providers.firstOrNull { it.id == providerId }
            ?: return false
        if (apiKey.isBlank()) return false
        val effectiveModel = if (model.isBlank()) spec.defaultModel else model
        val customBase = if (baseUrl.isNullOrBlank()) null else baseUrl.trim().removeSuffix("/")

        // 计算所选 provider 的引擎 base_url 与是否经桥
        val (engineBaseUrl: String, bridged: Boolean, bridgeRoutes: String) = when {
            spec.customOnly -> {
                if (endpointType == "responses") {
                    // 直连 Responses：base_url 即用户端点；无需桥
                    val u = customBase ?: "https://api.openai.com/v1"
                    Triple(u, false, "")
                } else {
                    // 经桥（默认）：base_url 指向本地桥 /custom，上游=用户端点
                    val u = customBase ?: "https://api.openai.com/v1"
                    Triple("http://127.0.0.1:$BRIDGE_PORT/custom/v1", true, "/custom=$u")
                }
            }
            spec.isChatOnly -> {
                val u = customBase ?: spec.chatUpstream!!
                Triple("http://127.0.0.1:$BRIDGE_PORT/${spec.id}/v1", true, "/${spec.id}=$u")
            }
            else -> {
                // 直连 Responses
                val u = customBase ?: spec.directBaseUrl!!
                Triple(u, false, "")
            }
        }

        prefs.edit().apply {
            putString("provider", spec.id)
            putString("model", effectiveModel)
            putString("api_key", SecureKeyStore.encrypt(apiKey))
            if (spec.allowCustomBaseUrl && customBase != null) putString("base_url", customBase)
            else remove("base_url")
            if (spec.customOnly) putString("custom_endpoint", endpointType) else remove("custom_endpoint")
            // 桥路由：仅当选中 provider 经桥且上游被自定义时记录，供 startChatBridge 读取
            if (bridged && bridgeRoutes.isNotBlank()) putString("bridge_routes", bridgeRoutes)
            else remove("bridge_routes")
            apply()
        }

        val paths = BootstrapInstaller.getPaths(context)
        val configDir = File(paths.homeDir, ".codex")
        configDir.mkdirs()

        val toml = buildString {
            appendLine("approval_policy = \"never\"")
            appendLine("sandbox_mode = \"danger-full-access\"")
            appendLine("model = \"$effectiveModel\"")
            appendLine("model_provider = \"${spec.id}\"")
            appendLine()
            for (p in providers) {
                val pBase = when {
                    p.customOnly -> {
                        if (getCustomEndpointType() == "responses") (getConfiguredBaseUrl() ?: "https://api.openai.com/v1")
                        else "http://127.0.0.1:$BRIDGE_PORT/custom/v1"
                    }
                    p.isChatOnly -> "http://127.0.0.1:$BRIDGE_PORT/${p.id}/v1"
                    else -> p.directBaseUrl ?: "https://api.openai.com/v1"
                }
                appendLine("[model_providers.${p.id}]")
                appendLine("name = \"${p.id}\"")
                appendLine("base_url = \"$pBase\"")
                appendLine("env_key = \"${p.envKey}\"")
                appendLine("wire_api = \"responses\"")
                appendLine()
            }
        }
        File(configDir, "config.toml").writeText(toml)

        val safeKey = org.json.JSONObject.quote(apiKey)
        val authJson = """{"${spec.envKey}": $safeKey, "OPENAI_API_KEY": $safeKey}"""
        File(configDir, "auth.json").writeText(authJson)
        Log.i(TAG, "Provider configured: ${spec.id} model=$effectiveModel base=$engineBaseUrl bridged=$bridged")

        if (bridged && startBridge && !startChatBridge()) {
            Log.e(TAG, "chat-bridge failed to start; chat-only provider ${spec.id} will not work")
        }
        return true
    }

    /** 当前所选 provider 是否需要经本地 chat-bridge（结合端点类型与自定义端点判断）。 */
    private fun isChatBridgedProvider(providerId: String?): Boolean {
        val spec = providers.firstOrNull { it.id == providerId } ?: return false
        return if (spec.customOnly) getCustomEndpointType() == "chat" && !getConfiguredBaseUrl().isNullOrBlank()
        else spec.isChatOnly
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
                if (isChatBridgedProvider(providerId) && !startChatBridge()) {
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
        // 自定义 Chat 上游 / 覆盖内置上游：把路由表经 env 传给桥（桥会合并默认路由）
        prefs.getString("bridge_routes", null)?.takeIf { it.isNotBlank() }?.let {
            env["CHAT_BRIDGE_ROUTES"] = it
        }
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
                if (isChatBridgedProvider(providerId) && !startChatBridge()) {
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