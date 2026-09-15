package com.codex.mobile

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 完整模型配置与移动端设置页（v0.8.0）。
 *
 * 替代原先简陋的 2 字段 AlertDialog：提供服务商选择、模型下拉（预设 + 自定义）、
 * API Key、自定义 Base URL（OpenAI 兼容代理 / 自建 / 本地）、端点类型（仅自定义）、
 * 外观主题、诊断与重置。保存后通过 prefs 标志让 MainActivity 重启工作台并生效。
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "mobilecode"
        const val FLAG_RELOAD = "mc_reload"

        private val THEMES = arrayOf("system", "light", "dark")
        private val THEME_LABELS = arrayOf("跟随系统", "浅色", "深色")
    }

    private lateinit var serverManager: CodexServerManager
    private lateinit var prefs: SharedPreferences

    private lateinit var providerSpinner: Spinner
    private lateinit var modelSpinner: Spinner
    private lateinit var customModelLayout: TextInputLayout
    private lateinit var customModelInput: TextInputEditText
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var baseUrlInput: TextInputEditText
    private lateinit var baseUrlLayout: TextInputLayout
    private lateinit var endpointGroup: RadioGroup
    private lateinit var endpointResponses: RadioButton
    private lateinit var diagText: TextView

    /** 模型下拉中「自定义模型…」项的下标。 */
    private var customModelIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()

        serverManager = CodexServerManager(this)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        setContentView(R.layout.activity_settings)

        providerSpinner = findViewById(R.id.providerSpinner)
        modelSpinner = findViewById(R.id.modelSpinner)
        customModelLayout = findViewById(R.id.customModelLayout)
        customModelInput = findViewById(R.id.customModelInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        baseUrlInput = findViewById(R.id.baseUrlInput)
        baseUrlLayout = findViewById(R.id.baseUrlLayout)
        endpointGroup = findViewById(R.id.endpointGroup)
        endpointResponses = findViewById(R.id.endpointResponses)
        diagText = findViewById(R.id.diagText)

        setupProviders()
        setupTheme()
        loadCurrent()
        refreshModelList()
        refreshProviderDependent()
        refreshDiagnostics()

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.saveBtn).setOnClickListener { onSave() }
        findViewById<Button>(R.id.testBtn).setOnClickListener { onTest() }
        findViewById<Button>(R.id.resetBtn).setOnClickListener { onReset() }
    }

    /** 依据持久化的主题偏好设置全局夜间模式（影响工作台 WebView 的暗色适配）。 */
    private fun applyTheme() {
        when (prefs.getString("theme", "system")) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun setupProviders() {
        val summaries = serverManager.getAllProviders()
        val labels = summaries.map { it.label }.toTypedArray()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        providerSpinner.adapter = adapter
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                refreshModelList()
                refreshProviderDependent()
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    private fun setupTheme() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, THEME_LABELS)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val themeSpinner = findViewById<Spinner>(R.id.themeSpinner)
        themeSpinner.adapter = adapter
        val current = prefs.getString("theme", "system") ?: "system"
        themeSpinner.setSelection(THEMES.indexOf(current).coerceAtLeast(0))
        themeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val choice = THEMES.getOrNull(pos) ?: "system"
                prefs.edit().putString("theme", choice).apply()
                applyTheme()
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    /** 预填已保存的服务商 / 模型 / Key / Base URL / 端点类型。 */
    private fun loadCurrent() {
        val configured = serverManager.getConfiguredProvider()
        val summaries = serverManager.getAllProviders()
        val idx = summaries.indexOfFirst { it.id == configured }.coerceAtLeast(0)
        providerSpinner.setSelection(idx)

        apiKeyInput.setText(serverManager.getConfiguredApiKey() ?: "")
        val base = serverManager.getConfiguredBaseUrl()
        baseUrlInput.setText(base ?: "")
    }

    /** 依据当前选中服务商重排模型下拉（预设 + 末尾「自定义模型…」）。 */
    private fun refreshModelList() {
        val summary = currentSummary() ?: return
        val items = ArrayList(summary.models)
        customModelIndex = items.size
        items.add(getString(R.string.settings_model_custom))
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter = adapter

        // 预选：已保存模型若在列表内则选中，否则落到「自定义」并回填文本
        val savedModel = serverManager.getConfiguredModel() ?: summary.defaultModel
        val sel = summary.models.indexOf(savedModel).coerceAtLeast(-1)
        if (sel >= 0) {
            modelSpinner.setSelection(sel)
            customModelLayout.visibility = View.GONE
        } else {
            modelSpinner.setSelection(customModelIndex)
            customModelInput.setText(savedModel)
            customModelLayout.visibility = View.VISIBLE
        }

        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                customModelLayout.visibility =
                    if (pos == customModelIndex) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    /** 端点类型（仅自定义供应商可见）；其余供应商隐藏。 */
    private fun refreshProviderDependent() {
        val summary = currentSummary()
        val isCustom = summary?.customOnly == true
        endpointGroup.visibility = if (isCustom) View.VISIBLE else View.GONE
        if (isCustom) {
            val t = serverManager.getCustomEndpointType()
            endpointResponses.isChecked = t == "responses"
            endpointGroup.findViewById<RadioButton>(R.id.endpointChat).isChecked = t != "responses"
        }
    }

    private fun currentSummary(): ProviderSummary? {
        val pos = providerSpinner.selectedItemPosition
        return serverManager.getAllProviders().getOrNull(pos)
    }

    private fun currentProviderId(): String = currentSummary()?.id ?: "openai"

    private fun onSave() {
        val summary = currentSummary() ?: return
        val providerId = summary.id

        val model: String = if (modelSpinner.selectedItemPosition == customModelIndex) {
            customModelInput.text?.toString()?.trim() ?: ""
        } else {
            modelSpinner.selectedItem.toString()
        }
        val apiKey = apiKeyInput.text?.toString()?.trim() ?: ""
        if (apiKey.isBlank()) {
            toast(getString(R.string.settings_key_required))
            return
        }
        val baseUrl = baseUrlInput.text?.toString()?.trim()?.ifBlank { null }

        val endpointType = if (summary.customOnly) {
            if (endpointResponses.isChecked) "responses" else "chat"
        } else {
            "chat"
        }

        if (summary.customOnly && baseUrl.isNullOrBlank()) {
            toast("自定义供应商需填写 Base URL")
            return
        }

        val ok = serverManager.configureProvider(providerId, model, apiKey, baseUrl, endpointType, startBridge = false)
        if (!ok) {
            toast("保存失败，请重试")
            return
        }
        // 通知 MainActivity 重启工作台以加载新配置
        prefs.edit().putString(FLAG_RELOAD, "1").apply()
        toast(getString(R.string.settings_saved_toast))
        finish()
    }

    private fun onTest() {
        val testBtn = findViewById<Button>(R.id.testBtn)
        testBtn.isEnabled = false
        toast(getString(R.string.settings_test_running))
        Thread {
            val ok = try {
                serverManager.healthCheck({ }, 30_000)
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                testBtn.isEnabled = true
                toast(if (ok) getString(R.string.settings_test_ok) else getString(R.string.settings_test_fail))
            }
        }.start()
    }

    private fun onReset() {
        AlertDialog.Builder(this)
            .setTitle("重置运行环境？")
            .setMessage("将删除内置运行环境并重新解压（约 1 分钟内）。用户数据（代码、配置、API Key）保留。")
            .setPositiveButton(R.string.ok) { _, _ ->
                toast("正在重置环境…")
                Thread {
                    try {
                        serverManager.stopServer()
                        com.codex.mobile.BootstrapInstaller.deleteRecursive(
                            java.io.File(com.codex.mobile.BootstrapInstaller.getPaths(this).prefixDir),
                        )
                    } catch (e: Exception) {
                        android.util.Log.w("SettingsActivity", "reset failed: ${e.message}")
                    }
                    prefs.edit().putString(FLAG_RELOAD, "1").apply()
                    runOnUiThread { finish() }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshDiagnostics() {
        val paths = com.codex.mobile.BootstrapInstaller.getPaths(this)
        val runtimeVersion = try {
            java.io.File(paths.prefixDir, ".runtime-version").readText().trim()
        } catch (_: Exception) { "（无）" }
        val ready = serverManager.isRuntimeReady()
        val provider = serverManager.getConfiguredProvider() ?: "未配置"
        val model = serverManager.getConfiguredModel() ?: "—"
        val freeMb = try {
            val stat = android.os.StatFs(filesDir.absolutePath)
            stat.availableBytes / 1024 / 1024
        } catch (_: Exception) { -1L }
        diagText.text = buildString {
            append("运行环境版本：$runtimeVersion\n")
            append("环境就绪：${if (ready) "是" else "否"}\n")
            append("工作台运行中：${if (serverManager.isRunning) "是" else "否"}\n")
            append("服务商：$provider\n")
            append("模型：$model\n")
            append("存储剩余：${if (freeMb >= 0) "${freeMb} MB" else "未知"}")
        }
    }

    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
}
