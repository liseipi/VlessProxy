package com.vlessproxy.ui

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vlessproxy.R
import com.vlessproxy.databinding.ActivityMainBinding
import com.vlessproxy.model.ConfigRepository
import com.vlessproxy.model.VlessConfig
import com.vlessproxy.service.ProxyService
import android.net.VpnService
import com.vlessproxy.service.VlessVpnService

private var vpnService: VlessVpnService? = null
private var vpnBound = false

// 增加 VPN 权限请求 launcher（在 notifPermLauncher 旁边添加）
private val vpnPermLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == RESULT_OK) {
        startVpnService()
    } else {
        Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
    }
}

// VPN service 绑定连接
private val vpnServiceConn = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        vpnService = (binder as VlessVpnService.LocalBinder).getService()
        vpnBound = true
        updateUi()
    }
    override fun onServiceDisconnected(name: ComponentName) {
        vpnService = null
        vpnBound = false
        updateUi()
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: ConfigRepository
    private lateinit var adapter: ConfigAdapter

    private var configs = mutableListOf<VlessConfig>()
    private var activeIndex = 0
    private var proxyService: ProxyService? = null
    private var serviceBound = false

    // ── Service binding ───────────────────────────────────────────────────────

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            proxyService = (binder as ProxyService.LocalBinder).getService()
            serviceBound = true
            updateUi()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            proxyService = null
            serviceBound = false
            updateUi()
        }
    }

    // ── Notification permission (Android 13+) ─────────────────────────────────

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled silently */ }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        repo = ConfigRepository(this)
        configs = repo.loadConfigs()
        activeIndex = repo.activeIndex

        setupRecyclerView()
        setupButtons()
        requestNotificationPermission()

        // Bind to service if already running
        bindService(Intent(this, VlessVpnService::class.java), vpnServiceConn, 0)

        // Handle vless:// intent
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    override fun onDestroy() {
//        if (serviceBound) unbindService(serviceConn)
        if (vpnBound) unbindService(vpnServiceConn)
        super.onDestroy()
    }

    // ── Menu ──────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add_manual -> { showEditDialog(null, -1); true }
            R.id.action_paste_uri  -> { showPasteUriDialog(); true }
            R.id.action_settings   -> { showSettingsDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = ConfigAdapter(
            configs = configs,
            activeIndex = activeIndex,
            onSelect = { idx ->
                activeIndex = idx
                repo.activeIndex = idx
                adapter.setActive(idx)
                updateUi()
            },
            onEdit = { idx -> showEditDialog(configs[idx], idx) },
            onDelete = { idx -> confirmDelete(idx) },
            onShare = { idx -> shareUri(configs[idx]) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun setupButtons() {
        binding.fabToggle.setOnClickListener { toggleProxy() }
        binding.btnImportClipboard.setOnClickListener { importFromClipboard() }
    }

    // ── vless:// intent handling ──────────────────────────────────────────────

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme?.lowercase() != "vless") return
        val cfg = VlessConfig.fromUri(uri.toString()) ?: run {
            Toast.makeText(this, "Invalid vless:// URI", Toast.LENGTH_SHORT).show()
            return
        }
        importConfig(cfg)
    }

    // ── Proxy toggle ──────────────────────────────────────────────────────────

    private fun toggleProxy() {
        val running = vpnService?.isRunning == true
        if (running) {
            startService(VlessVpnService.stopIntent(this))
        } else {
            val cfg = configs.getOrNull(activeIndex)
            if (cfg == null) {
                Toast.makeText(this, "No configuration selected", Toast.LENGTH_SHORT).show()
                return
            }
            // 请求 VPN 权限
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermLauncher.launch(intent)
            } else {
                startVpnService()
            }
        }
        binding.fabToggle.postDelayed({ updateUi() }, 500)
    }

    private fun startVpnService() {
        val cfg = configs.getOrNull(activeIndex) ?: return
        val svcIntent = VlessVpnService.startIntent(this, cfg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svcIntent)
        else
            startService(svcIntent)
        bindService(Intent(this, VlessVpnService::class.java), vpnServiceConn, Context.BIND_AUTO_CREATE)
    }

    // ── UI update ─────────────────────────────────────────────────────────────

    private fun updateUi() {
//        val running = proxyService?.isRunning == true
        val running = vpnService?.isRunning == true
        val cfg = configs.getOrNull(activeIndex)

        binding.fabToggle.text = if (running) "Stop Proxy" else "Start Proxy"
        binding.fabToggle.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)

        if (running && cfg != null) {
            binding.statusCard.setCardBackgroundColor(getColor(R.color.green_surface))
            binding.tvStatus.text = "Running"
            binding.tvStatusDetail.text =
                "SOCKS5 / HTTP → 127.0.0.1:${cfg.listenPort}\n" +
                "Remote: ${cfg.server}:${cfg.port}"
        } else {
            binding.statusCard.setCardBackgroundColor(getColor(R.color.surface_variant))
            binding.tvStatus.text = "Stopped"
            binding.tvStatusDetail.text = cfg?.let { "${it.name} · ${it.server}:${it.port}" }
                ?: "No configuration selected"
        }

        binding.fabToggle.isEnabled = configs.isNotEmpty()
    }

    // ── Import / clipboard ────────────────────────────────────────────────────

    private fun importFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val cfg = VlessConfig.fromUri(text)
        if (cfg == null) {
            Toast.makeText(this, "Not a valid vless:// URI", Toast.LENGTH_SHORT).show()
            return
        }
        importConfig(cfg)
    }

    private fun showPasteUriDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = "vless://uuid@host:port?..."
            isSingleLine = false
            minLines = 3
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Import vless:// URI")
            .setView(editText)
            .setPositiveButton("Import") { _, _ ->
                val text = editText.text.toString().trim()
                val cfg = VlessConfig.fromUri(text)
                if (cfg != null) importConfig(cfg)
                else Toast.makeText(this, "Invalid URI", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importConfig(cfg: VlessConfig) {
        // Ask for listen port
        val portEdit = android.widget.EditText(this).apply {
            setText(cfg.listenPort.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Import: ${cfg.name}")
            .setMessage("Server: ${cfg.server}:${cfg.port}\nUUID: ${cfg.uuid.take(8)}…")
            .setView(portEdit)
            .setPositiveButton("Import") { _, _ ->
                val port = portEdit.text.toString().toIntOrNull() ?: 1080
                val finalCfg = cfg.copy(listenPort = port)
                configs.add(finalCfg)
                repo.saveConfigs(configs)
                adapter.notifyItemInserted(configs.lastIndex)
                Toast.makeText(this, "Imported: ${finalCfg.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Edit dialog ───────────────────────────────────────────────────────────

    private fun showEditDialog(existing: VlessConfig?, idx: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_config, null)
        val etName       = dialogView.findViewById<android.widget.EditText>(R.id.etName)
        val etUuid       = dialogView.findViewById<android.widget.EditText>(R.id.etUuid)
        val etServer     = dialogView.findViewById<android.widget.EditText>(R.id.etServer)
        val etPort       = dialogView.findViewById<android.widget.EditText>(R.id.etPort)
        val etPath       = dialogView.findViewById<android.widget.EditText>(R.id.etPath)
        val etSni        = dialogView.findViewById<android.widget.EditText>(R.id.etSni)
        val etWsHost     = dialogView.findViewById<android.widget.EditText>(R.id.etWsHost)
        val etSecurity   = dialogView.findViewById<android.widget.EditText>(R.id.etSecurity)
        val etListenPort = dialogView.findViewById<android.widget.EditText>(R.id.etListenPort)

        existing?.let {
            etName.setText(it.name)
            etUuid.setText(it.uuid)
            etServer.setText(it.server)
            etPort.setText(it.port.toString())
            etPath.setText(it.path)
            etSni.setText(it.sni)
            etWsHost.setText(it.wsHost)
            etSecurity.setText(it.security)
            etListenPort.setText(it.listenPort.toString())
        }

        val title = if (idx < 0) "Add Configuration" else "Edit Configuration"
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val cfg = VlessConfig(
                    name       = etName.text.toString().trim().ifBlank { etServer.text.toString() },
                    uuid       = etUuid.text.toString().trim(),
                    server     = etServer.text.toString().trim(),
                    port       = etPort.text.toString().toIntOrNull() ?: 443,
                    path       = etPath.text.toString().trim().ifBlank { "/" },
                    sni        = etSni.text.toString().trim(),
                    wsHost     = etWsHost.text.toString().trim(),
                    security   = etSecurity.text.toString().trim().lowercase(),
                    listenPort = etListenPort.text.toString().toIntOrNull() ?: 1080
                )
                if (!cfg.isValid()) {
                    Toast.makeText(this, "Invalid configuration", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (idx < 0) {
                    configs.add(cfg)
                    adapter.notifyItemInserted(configs.lastIndex)
                } else {
                    configs[idx] = cfg
                    adapter.notifyItemChanged(idx)
                }
                repo.saveConfigs(configs)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Delete / Share ────────────────────────────────────────────────────────

    private fun confirmDelete(idx: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete")
            .setMessage("Delete \"${configs[idx].name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                configs.removeAt(idx)
                repo.saveConfigs(configs)
                adapter.notifyItemRemoved(idx)
                if (activeIndex >= configs.size) {
                    activeIndex = maxOf(0, configs.size - 1)
                    repo.activeIndex = activeIndex
                    adapter.setActive(activeIndex)
                }
                updateUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareUri(cfg: VlessConfig) {
        val uri = cfg.toUri()
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("vless://", uri))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private fun showSettingsDialog() {
        val autoStart = repo.autoStart
        val checkBox = android.widget.CheckBox(this).apply {
            text = "Auto-start on boot"
            isChecked = autoStart
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Settings")
            .setView(checkBox)
            .setPositiveButton("OK") { _, _ ->
                repo.autoStart = checkBox.isChecked
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Permission ────────────────────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
