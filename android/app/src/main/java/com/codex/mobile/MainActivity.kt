package com.codex.mobile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/** 启动流程中的一个阶段。estimateSec 用于给用户预估剩余耗时。 */
private data class SetupStep(val label: String, val estimateSec: Int, val weight: Int)

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CodexMainActivity"

        /**
         * 首次启动的完整步骤表。weight 只用于进度条权重；estimateSec 用于
         * 「预计还需」展示。非首次启动时大部分步骤会瞬间跳过，进度会快速推进。
         *
         * v0.2.0 起：Node/Codex/原生引擎全部内置在 APK 里，关键路径零网络。
         * v0.4.0 起：镜像 4 分片并行解压，解压耗时约为单线程的 1/3。
         * 已移除：镜像测速、apt/npm 在线安装（曾在弱网下反复失败卡死）。
         */
        private val STEPS = listOf(
            SetupStep("解压内置环境（4 线程并行）", 40, 10),
            SetupStep("启动网络代理", 5, 1),
            SetupStep("检查 API 配置", 5, 3),
            SetupStep("启动工作台", 20, 2),
        )
        private val TOTAL_WEIGHT = STEPS.sumOf { it.weight }
        private val STEP_COUNT = STEPS.size
    }

    private lateinit var webView: WebView
    private lateinit var loadingOverlay: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var metaText: TextView
    private lateinit var settingsBtn: android.widget.ImageButton
    private lateinit var serverManager: CodexServerManager

    // ── 进度跟踪状态 ───────────────────────────────────────────────
    @Volatile private var currentStep = -1
    @Volatile private var doneWeight = 0
    @Volatile private var startedAtMs = 0L
    @Volatile private var setupFinished = false
    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            refreshMeta()
            tickHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        progressBar = findViewById(R.id.progressBar)
        metaText = findViewById(R.id.metaText)
        settingsBtn = findViewById(R.id.settingsBtn)

        serverManager = CodexServerManager(this)

        settingsBtn.setOnClickListener {
            showSettingsMenu()
        }

        requestBatteryOptimizationExemption()
        startForegroundService()
        setupWebView()

        // 通知栏"重启工作台"动作：不重走完整 setup，只重启 server（环境须已就绪）
        if (intent?.action == CodexForegroundService.ACTION_RESTART) {
            handleRestartAction()
            return
        }

        startSetupFlow()
    }

    /** singleTop 下通知栏动作只走这里，必须单独处理，否则通知按钮失效。 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == CodexForegroundService.ACTION_RESTART) {
            handleRestartAction()
        }
    }

    override fun onDestroy() {
        tickHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
        serverManager.stopServer()
        stopService(Intent(this, CodexForegroundService::class.java))
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        try {
            @Suppress("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not request battery optimization exemption: ${e.message}")
        }
    }

    private fun startForegroundService() {
        val intent = Intent(this, CodexForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            setSupportZoom(false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String,
            ): Boolean {
                // 本地工作台留在内嵌 WebView；外部链接交给系统浏览器，
                // 避免用户被困在 WebView 里回不去工作台。
                val isLocal = url.startsWith("http://127.0.0.1") ||
                    url.startsWith("http://localhost")
                if (!isLocal) {
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "No browser for $url: ${e.message}")
                    }
                    return true
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d(TAG, "[WebView] ${msg.sourceId()}:${msg.lineNumber()} ${msg.message()}")
                return true
            }
        }
    }

    private fun startSetupFlow() {
        resetProgress()
        showLoading(true)
        setStatus(getString(R.string.status_initializing))

        Thread {
            try {
                runSetup()
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                runOnUiThread {
                    showError(e.message ?: getString(R.string.error_bootstrap))
                }
            }
        }.start()
    }

    // ── 进度控制 ──────────────────────────────────────────────────

    private fun resetProgress() {
        currentStep = -1
        doneWeight = 0
        startedAtMs = System.currentTimeMillis()
        setupFinished = false
        tickHandler.removeCallbacks(tickRunnable)
        tickHandler.post(tickRunnable)
    }

    /** 进入第 [index] 步（0 起）。beginStep 可能从后台线程调用，UI 更新必须回主线程。 */
    private fun beginStep(index: Int) {
        currentStep = index.coerceIn(0, STEP_COUNT - 1)
        val label = STEPS[currentStep].label
        runOnUiThread {
            setStatus(label)
            refreshMeta()
        }
    }

    /** 标记当前步骤完成，推进进度条。 */
    private fun endStep() {
        if (currentStep in 0 until STEP_COUNT) {
            doneWeight += STEPS[currentStep].weight
        }
        runOnUiThread { refreshMeta() }
    }

    private fun refreshMeta() {
        if (setupFinished) return
        var fraction = if (TOTAL_WEIGHT > 0) doneWeight * 100 / TOTAL_WEIGHT else 0
        // 解压步骤内按分片完成数细分进度（0→25→50→75→100%），避免长时间卡 0%
        if (currentStep == 0 && CodexServerManager.IMAGE_SHARD_COUNT > 0) {
            val stepShare = STEPS[0].weight * 100 / TOTAL_WEIGHT
            fraction = stepShare * extractionDoneShards.get() / CodexServerManager.IMAGE_SHARD_COUNT
        }
        progressBar.post { progressBar.progress = fraction }
        val elapsedSec = ((System.currentTimeMillis() - startedAtMs) / 1000).coerceAtLeast(0)
        val elapsed = String.format(Locale.US, "%02d:%02d", elapsedSec / 60 % 60, elapsedSec % 60)
        val stepInfo = if (currentStep >= 0) "步骤 ${currentStep + 1}/$STEP_COUNT · " else ""
        val remaining = remainingText()
        metaText.text = "${stepInfo}已用 $elapsed · $remaining"
    }

    private fun remainingText(): String {
        if (currentStep < 0) return "即将开始"
        val remainingSec = STEPS.drop(currentStep).sumOf { it.estimateSec }
        return when {
            remainingSec <= 0 -> "即将完成"
            remainingSec < 60 -> "预计还需不到 1 分钟"
            else -> "预计还需约 ${(remainingSec + 59) / 60} 分钟"
        }
    }

    // ── 流程执行 ──────────────────────────────────────────────────

    /**
     * 镜像内 shebang 写死 `/data/user/0/com.codex.mobile/files/usr/bin/sh`，
     * 但个别设备（或高版本系统）上 context.filesDir 返回 `/data/data/com.codex.mobile/files`。
     * 两者实际是同一目录的两种视图，这里若发现 /data/user/0 视图缺失则创建符号链接，
     * 保证镜像内所有 #!/data/user/0/... 脚本可执行。
     */
    private fun ensurePrefixPathAlias() {
        try {
            val expected = "/data/user/0/$packageName"
            val aliasDir = java.io.File(expected)
            if (aliasDir.exists()) return

            // 用真实 filesDir 推导 /data/data 视图
            val dataData = "/data/data/$packageName"
            val realData = java.io.File(dataData)
            if (!realData.exists()) return

            // /data/user/0 不存在时，尝试挂符号链接（需要可写；部分设备 /data 根不可写则跳过）
            java.io.File("/data/user/0").mkdirs()
            android.system.Os.symlink(dataData, expected)
            Log.i(TAG, "已创建路径别名: $expected -> $dataData")
        } catch (e: Exception) {
            Log.w(TAG, "路径别名创建失败（不影响 /data/data 视图）: ${e.message}")
        }
    }

    /**
     * 通知栏"重启工作台"动作：直接重启 server 并加载工作台，
     * 不重走首次 setup（环境已就绪）。
     */
    private fun handleRestartAction() {
        // 环境未就绪时（如首次启动点了通知），退回完整 setup
        if (serverManager.needsInstall()) {
            runOnUiThread {
                toast("运行环境尚未就绪，正在走首次初始化…")
                startSetupFlow()
            }
            return
        }
        toast("正在重启工作台…")
        Thread {
            try {
                serverManager.stopServer()
                serverManager.startProxy()
                val started = serverManager.startServer()
                val ready = started && serverManager.waitForServer(timeoutMs = 60_000)
                runOnUiThread {
                    if (ready) {
                        webView.visibility = View.VISIBLE
                        webView.loadUrl("http://127.0.0.1:${CodexServerManager.SERVER_PORT}/")
                        toast("工作台已重启")
                    } else {
                        toast("工作台重启失败，请重新打开 App")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "重启工作台失败: ${e.message}")
                runOnUiThread { toast("工作台重启失败：${e.message}") }
            }
        }.start()
    }

    // 并行解压的分片完成数（用于解压步骤的进度条细分）
    private val extractionDoneShards = java.util.concurrent.atomic.AtomicInteger(0)

    private fun runSetup() {
        extractionDoneShards.set(0)

        // Step 0: 路径自愈 —— 镜像 shebang 写死 /data/user/0/…，个别设备 filesDir
        // 返回 /data/data/… 形式时补齐符号链接，保证 #! 可解析
        ensurePrefixPathAlias()

        // Step 1: 解压内置环境（成品镜像，4 分片并行，全部离线、一次成型）
        beginStep(0)
        updateDetail("并行解压内置运行环境…")
        if (serverManager.needsInstall()) {
            updateDetail("首次使用：4 线程并行解压 Node.js / Codex 运行环境（无需联网）…")
            val ok = serverManager.installRuntimeFromAssets { msg ->
                if (msg.startsWith("完成，")) {
                    extractionDoneShards.incrementAndGet()
                    updateDetail(msg)
                } else {
                    updateDetail(msg)
                }
            }
            if (!ok) {
                throw RuntimeException("内置运行环境解压失败。点「重试」会重新解压（约 1 分钟）；若反复失败，请到「诊断与环境」查看存储空间是否充足")
            }
        }
        serverManager.ensureFullAccessConfig()
        serverManager.ensureDefaultWorkspace()
        endStep()

        // Step 2: 网络代理（原生二进制的 DNS/TLS 桥，本地进程）
        beginStep(1)
        if (!serverManager.startProxy()) {
            throw RuntimeException("本地网络代理启动失败。请点「重试」；若反复失败，可重启手机后再打开")
        }
        endStep()

        // Step 2: 检查 API 配置（不强制、不弹框：进工作台后点右上角 ⚙ 设置）
        beginStep(2)
        if (!serverManager.isLoggedIn()) {
            updateStatus("未配置 API Key — 进入后点右上角 ⚙ 设置")
        } else {
            updateStatus("已配置 API（${serverManager.getConfiguredProvider() ?: "unknown"}）")
        }
        endStep()

        // Step 3: 启动工作台（验证转为后台，不再阻断进入）
        beginStep(3)
        updateStatus(getString(R.string.status_starting_server))
        val started = serverManager.startServer()
        if (!started) {
            throw RuntimeException("工作台启动失败。请点「重试」；若反复失败，请到「诊断与环境」重置环境")
        }
        endStep()

        updateStatus(getString(R.string.status_waiting_server))
        val ready = serverManager.waitForServer(timeoutMs = 90_000)
        if (!ready) {
            throw RuntimeException("工作台启动超时（90 秒未就绪）。请点「重试」；若反复失败，建议到「诊断与环境」重置环境")
        }

        // 关键路径完成 → 立刻进入工作台
        setupFinished = true
        runOnUiThread {
            settingsBtn.visibility = View.VISIBLE
            showLoading(false)
            webView.loadUrl("http://127.0.0.1:${CodexServerManager.SERVER_PORT}/")
            if (!serverManager.isLoggedIn()) {
                toast(getString(R.string.settings_no_key_hint))
            }
        }

        // 后台验证：失败/超时只记录与提示，不弹阻断框
        if (serverManager.isLoggedIn()) {
            Thread {
                val ok = serverManager.healthCheck({ msg -> Log.d(TAG, "[health-bg] $msg") }, 30_000)
                Log.i(TAG, "后台 API 验证结果: $ok")
                if (!ok) {
                    toast("API 验证未通过：请检查 Key 或网络（不影响界面）")
                }
            }.start()
        }
    }

    private fun toast(message: String) {
        runOnUiThread {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * ⚙ 设置菜单：配置 API Key / 诊断与环境。
     */
    private fun showSettingsMenu() {
        runOnUiThread {
            val items = arrayOf("配置 API Key", "诊断与环境")
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> showApiConfigDialog { provider, apiKey ->
                            applyApiConfig(provider, apiKey)
                        }
                        1 -> showDiagnostics()
                    }
                }
                .show()
        }
    }

    /**
     * 诊断与环境：运行时版本 / 就绪状态 / 磁盘占用 / 一键重置。
     * 帮助用户自排查（出问题不用卸载重装）。
     */
    private fun showDiagnostics() {
        val paths = BootstrapInstaller.getPaths(this)
        val usrDir = java.io.File(paths.prefixDir)
        val homeDir = java.io.File(paths.homeDir)

        val runtimeVersion = try {
            java.io.File(usrDir, ".runtime-version").readText().trim()
        } catch (_: Exception) {
            "（无）"
        }
        val ready = serverManager.isRuntimeReady()
        val usrSize = formatSize(dirSize(usrDir))
        val homeSize = formatSize(dirSize(homeDir))
        val provider = serverManager.getConfiguredProvider() ?: "未配置"
        val serverRunning = serverManager.isRunning
        val freeMb = try {
            val stat = android.os.StatFs(this.filesDir.absolutePath)
            stat.availableBytes / 1024 / 1024
        } catch (_: Exception) {
            -1L
        }

        val text = buildString {
            append("运行环境版本：").append(runtimeVersion).append('\n')
            append("环境就绪：").append(if (ready) "是" else "否").append('\n')
            append("工作台运行中：").append(if (serverRunning) "是" else "否").append('\n')
            append("模型服务商：").append(provider).append('\n')
            append("环境占用：").append(usrSize).append('\n')
            append("用户数据：").append(homeSize).append('\n')
            append("存储剩余：").append(if (freeMb >= 0) "${freeMb} MB" else "未知").append('\n')
            if (freeMb in 0 until 500) {
                append("⚠ 存储偏低（<500MB），解压环境可能失败，建议先清理\n")
            }
        }

        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("诊断与环境")
                .setMessage(text)
                .setPositiveButton("重置环境") { _, _ ->
                    confirmResetEnvironment()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** 二次确认后删除 usr/（保留 home/ 用户数据与 API Key），并重走首次 setup。 */
    private fun confirmResetEnvironment() {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("重置环境？")
                .setMessage("将删除内置运行环境并重新解压（并行解压，约 1 分钟内）。用户数据（代码、配置、API Key）保留。")
                .setPositiveButton(R.string.ok) { _, _ ->
                    toast("正在重置环境…")
                    Thread {
                        try {
                            serverManager.stopServer()
                            BootstrapInstaller.deleteRecursive(
                                java.io.File(BootstrapInstaller.getPaths(this).prefixDir),
                            )
                            Log.i(TAG, "环境已重置，重走 setup")
                        } catch (e: Exception) {
                            Log.w(TAG, "重置失败: ${e.message}")
                        }
                        runOnUiThread {
                            startSetupFlow()
                        }
                    }.start()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun dirSize(dir: java.io.File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else String.format(Locale.US, "%.0f MB", mb)
    }

    /**
     * 弹出「模型服务商 + API Key」设置对话框（工作台右上角 ⚙ 触发）。
     * 非阻塞：用户在弹窗里点「确定」且 Key 非空时回调 [onSaved]。
     * 点「取消」或留空不保存（Key 为空视为取消）。
     */
    private fun showApiConfigDialog(onSaved: (provider: String, apiKey: String) -> Unit) {
        val providers = arrayOf("openai", "deepseek", "qwen", "glm")
        val currentProvider = serverManager.getConfiguredProvider()
        val currentKey = serverManager.getConfiguredApiKey() ?: ""

        runOnUiThread {
            val input = EditText(this).apply {
                hint = getString(R.string.api_key_hint)
                setSingleLine(true)
                setText(currentKey)
            }
            val providerSpinner = android.widget.Spinner(this)
            val adapter = android.widget.ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                providers,
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            providerSpinner.adapter = adapter
            if (currentProvider != null) {
                val idx = providers.indexOf(currentProvider)
                if (idx >= 0) providerSpinner.setSelection(idx)
            }

            val label = TextView(this).apply {
                text = "模型服务商"
                setTextColor(0xFF94A3B8.toInt())
                textSize = 13f
            }
            val keyLabel = TextView(this).apply {
                text = "API Key"
                setTextColor(0xFF94A3B8.toInt())
                textSize = 13f
            }

            val padding = (24 * resources.displayMetrics.density).toInt()
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
                addView(label)
                addView(providerSpinner)
                addView(keyLabel)
                addView(input)
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setMessage(R.string.api_key_message)
                .setView(container)
                .setCancelable(true)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val provider = providerSpinner.selectedItem?.toString() ?: ""
                    val key = input.text.toString().trim()
                    if (key.isNotBlank() && provider.isNotBlank()) {
                        onSaved(provider, key)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /**
     * 保存 provider + API Key 到 Codex 配置，并重启工作台使新密钥立即生效。
     */
    private fun applyApiConfig(provider: String, apiKey: String) {
        val ok = serverManager.configureProvider(provider, apiKey)
        if (!ok) {
            toast("API 配置保存失败，请重试")
            return
        }
        toast(getString(R.string.settings_saved_restart))
        Thread {
            try {
                serverManager.stopServer()
                serverManager.startProxy()
                val started = serverManager.startServer()
                val ready = started && serverManager.waitForServer(timeoutMs = 60_000)
                runOnUiThread {
                    if (ready) {
                        webView.loadUrl("http://127.0.0.1:${CodexServerManager.SERVER_PORT}/")
                        toast(getString(R.string.settings_saved_ok))
                    } else {
                        toast(getString(R.string.settings_restart_fail))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "重启工作台失败: ${e.message}")
                runOnUiThread { toast(getString(R.string.settings_restart_fail)) }
            }
        }.start()
    }

    // ── UI helpers ────────────────────────────────────────────────

    /**
     * 错误对话框：重试 / 诊断与环境 / 复制错误信息 / 退出。
     * 失败时刻必须能直达排障入口（诊断页、复制信息反馈），否则用户只能反复重试。
     */
    private fun showError(message: String) {
        val items = arrayOf(
            "重试",
            "诊断与环境",
            "复制错误信息",
            "退出",
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startSetupFlow()
                    1 -> showDiagnostics()
                    2 -> copyErrorInfo(message)
                    3 -> finish()
                }
            }
            .setCancelable(false)
            .show()
    }

    /** 复制一条包含版本/机型/错误/存储余量的诊断信息，方便用户反馈。 */
    private fun copyErrorInfo(message: String) {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        val freeMb = try {
            val stat = android.os.StatFs(filesDir.absolutePath)
            stat.availableBytes / 1024 / 1024
        } catch (_: Exception) {
            -1
        }
        val text = buildString {
            append("App: Mobilecode v").append(versionName).append('\n')
            append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("系统: Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(')').append('\n')
            append("存储剩余: ").append(if (freeMb >= 0) "${freeMb} MB" else "未知").append('\n')
            append("错误: ").append(message).append('\n')
        }
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Mobilecode 错误信息", text))
        toast("错误信息已复制，可粘贴到反馈中")
    }

    private fun showLoading(show: Boolean) {
        if (show) {
            webView.visibility = View.GONE
            loadingOverlay.alpha = 1f
            loadingOverlay.visibility = View.VISIBLE
        } else {
            tickHandler.removeCallbacks(tickRunnable)
            loadingOverlay.animate().alpha(0f).setDuration(300).withEndAction {
                loadingOverlay.visibility = View.GONE
            }.start()
            webView.visibility = View.VISIBLE
            webView.animate().alpha(1f).setDuration(400).start()
        }
    }

    private fun setStatus(text: String, detail: String? = null) {
        statusText.text = text
        if (detail != null) {
            statusDetail.text = detail
            statusDetail.visibility = View.VISIBLE
        } else {
            statusDetail.visibility = View.GONE
        }
    }

    private fun updateStatus(text: String, detail: String? = null) {
        runOnUiThread { setStatus(text, detail) }
    }

    private fun updateDetail(text: String) {
        runOnUiThread {
            statusDetail.text = text
            statusDetail.visibility = View.VISIBLE
        }
    }
}