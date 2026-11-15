package com.netshare.everyproxy

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.netshare.everyproxy.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var proxyService: ProxyService? = null
    private var isBound = false
    private var interfaces = listOf<NetworkInterfaceInfo>()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastBytesTotal = 0L

    private val statsRunnable = object : Runnable {
        override fun run() {
            val totalBytes = proxyService?.bytesTransferred?.get() ?: 0L
            val activeConns = proxyService?.activeConnections?.get() ?: 0
            val deltaBytes = (totalBytes - lastBytesTotal).coerceAtLeast(0L)
            lastBytesTotal = totalBytes

            binding.tvTrafficStats.text = formatBytes(totalBytes)
            binding.tvLiveSpeed.text = "${formatBytes(deltaBytes)}/s"
            binding.tvActiveConns.text = activeConns.toString()

            uiHandler.postDelayed(this, 1000L)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ProxyService.LocalBinder
            proxyService = binder.getService()
            isBound = true
            lastBytesTotal = proxyService?.bytesTransferred?.get() ?: 0L

            proxyService?.onLogListener = { logLines ->
                runOnUiThread {
                    binding.tvLogs.text = if (logLines.isEmpty()) {
                        "En attente de connexions..."
                    } else {
                        logLines.joinToString("\n")
                    }
                    binding.scrollLogs.post {
                        binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
            proxyService?.onStateChangedListener = { httpRunning, socksRunning ->
                runOnUiThread {
                    updateStatusUi(httpRunning, socksRunning)
                }
            }

            updateStatusUi(
                proxyService?.isHttpRunning() == true,
                proxyService?.isSocksRunning() == true
            )
            val currentLogs = proxyService?.getLogs() ?: emptyList()
            binding.tvLogs.text = if (currentLogs.isEmpty()) "Prêt à démarrer..." else currentLogs.joinToString("\n")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            proxyService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.statusBarColor = Color.parseColor("#0B0F19")
        window.navigationBarColor = Color.parseColor("#0B0F19")

        requestNotificationPermissionIfNeeded()
        loadSavedPreferences()
        refreshNetworkInterfaces()

        binding.switchAuth.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutAuthFields.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.btnRefreshIps.setOnClickListener {
            refreshNetworkInterfaces()
            Toast.makeText(this, "Interfaces réseau actualisées", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyEndpoint.setOnClickListener {
            copyActiveEndpointToClipboard()
        }

        binding.btnGuideClient.setOnClickListener {
            showClientSetupDialog()
        }

        binding.btnBatteryOpt.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        binding.btnToggleProxy.setOnClickListener {
            val running = (proxyService?.isHttpRunning() == true) || (proxyService?.isSocksRunning() == true)
            if (running) {
                proxyService?.stopAllProxies()
            } else {
                savePreferences()
                startSelectedProxies()
            }
        }

        binding.btnClearLogs.setOnClickListener {
            proxyService?.clearLogs()
        }

        Intent(this, ProxyService::class.java).also { intent ->
            startService(intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        uiHandler.post(statsRunnable)
    }

    private fun refreshNetworkInterfaces() {
        interfaces = NetworkUtils.getAvailableInterfaces()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            interfaces
        )
        binding.spinnerInterfaces.adapter = adapter
        updateEndpointBanner()
    }

    private fun getSelectedShareIp(): String {
        val selectedInterface = interfaces.getOrNull(binding.spinnerInterfaces.selectedItemPosition)
        val rawIp = selectedInterface?.ipAddress ?: "0.0.0.0"
        return NetworkUtils.getBestShareableIp(rawIp)
    }

    private fun updateEndpointBanner() {
        val shareIp = getSelectedShareIp()
        val httpPort = binding.etHttpPort.text.toString().toIntOrNull() ?: 8080
        val socksPort = binding.etSocksPort.text.toString().toIntOrNull() ?: 1080
        binding.tvShareEndpoint.text = "HTTP $shareIp:$httpPort  •  SOCKS5 $shareIp:$socksPort"
    }

    private fun copyActiveEndpointToClipboard() {
        val shareIp = getSelectedShareIp()
        val httpPort = binding.etHttpPort.text.toString().toIntOrNull() ?: 8080
        val socksPort = binding.etSocksPort.text.toString().toIntOrNull() ?: 1080
        val clipText = "Proxy HTTP: $shareIp:$httpPort | SOCKS5: $shareIp:$socksPort"

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NetShare Proxy", clipText))
        Toast.makeText(this, "Copié : $clipText", Toast.LENGTH_SHORT).show()
    }

    private fun showClientSetupDialog() {
        val shareIp = getSelectedShareIp()
        val httpPort = binding.etHttpPort.text.toString().toIntOrNull() ?: 8080
        val socksPort = binding.etSocksPort.text.toString().toIntOrNull() ?: 1080

        val message = "1. Activez votre VPN et votre Point d'accès Wi-Fi (ou Partage USB) sur ce téléphone.\n" +
            "2. Connectez votre autre appareil (PC, téléphone, console) au Wi-Fi de ce téléphone.\n" +
            "3. Dans les options Wi-Fi de l'appareil client, choisissez Proxy -> Manuel :\n\n" +
            "• Hôte / Adresse IP : " + shareIp + "\n" +
            "• Port HTTP/HTTPS : " + httpPort + "\n" +
            "• Port SOCKS5 : " + socksPort

        AlertDialog.Builder(this)
            .setTitle("Configuration de l'appareil client")
            .setMessage(message)
            .setPositiveButton("Copier l'IP ($shareIp)") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("IP Proxy", shareIp))
                Toast.makeText(this, "IP $shareIp copiée !", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                runCatching {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }.onFailure {
                    Toast.makeText(this, "Ouvrez les paramètres de batterie pour désactiver la mise en veille.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Mode haute performance actif (Batterie déjà non restreinte)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSavedPreferences() {
        val prefs = getSharedPreferences("netshare_prefs", Context.MODE_PRIVATE)
        binding.switchHttp.isChecked = prefs.getBoolean("http_enabled", true)
        binding.etHttpPort.setText(prefs.getInt("http_port", 8080).toString())
        binding.switchSocks.isChecked = prefs.getBoolean("socks_enabled", true)
        binding.etSocksPort.setText(prefs.getInt("socks_port", 1080).toString())
        val auth = prefs.getBoolean("auth_enabled", false)
        binding.switchAuth.isChecked = auth
        binding.layoutAuthFields.visibility = if (auth) View.VISIBLE else View.GONE
        binding.etUsername.setText(prefs.getString("username", ""))
        binding.etPassword.setText(prefs.getString("password", ""))
    }

    private fun savePreferences() {
        val prefs = getSharedPreferences("netshare_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("http_enabled", binding.switchHttp.isChecked)
            .putInt("http_port", binding.etHttpPort.text.toString().toIntOrNull() ?: 8080)
            .putBoolean("socks_enabled", binding.switchSocks.isChecked)
            .putInt("socks_port", binding.etSocksPort.text.toString().toIntOrNull() ?: 1080)
            .putBoolean("auth_enabled", binding.switchAuth.isChecked)
            .putString("username", binding.etUsername.text.toString().trim())
            .putString("password", binding.etPassword.text.toString())
            .apply()
    }

    private fun startSelectedProxies() {
        val selectedInterface = interfaces.getOrNull(binding.spinnerInterfaces.selectedItemPosition)
        val bindIp = selectedInterface?.ipAddress ?: "0.0.0.0"
        val httpPort = binding.etHttpPort.text.toString().toIntOrNull() ?: 8080
        val socksPort = binding.etSocksPort.text.toString().toIntOrNull() ?: 1080

        val config = ProxyConfig(
            bindAddress = bindIp,
            httpEnabled = binding.switchHttp.isChecked,
            httpPort = httpPort,
            socksEnabled = binding.switchSocks.isChecked,
            socksPort = socksPort,
            authEnabled = binding.switchAuth.isChecked,
            username = binding.etUsername.text.toString().trim(),
            password = binding.etPassword.text.toString()
        )

        updateEndpointBanner()
        proxyService?.startProxies(config)
    }

    private fun updateStatusUi(httpRunning: Boolean, socksRunning: Boolean) {
        val anyRunning = httpRunning || socksRunning
        updateEndpointBanner()

        if (anyRunning) {
            binding.btnToggleProxy.text = "ARRÊTER LE SERVEUR PROXY"
            binding.btnToggleProxy.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
            binding.btnToggleProxy.setTextColor(Color.WHITE)
            binding.tvStatusBadge.text = "● ACTIF  (HTTP: ${if (httpRunning) "ON" else "OFF"} | SOCKS5: ${if (socksRunning) "ON" else "OFF"})"
            binding.tvStatusBadge.setTextColor(Color.parseColor("#00E676"))
            binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_pill_on)
        } else {
            binding.btnToggleProxy.text = "DÉMARRER LE SERVEUR PROXY"
            binding.btnToggleProxy.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
            binding.btnToggleProxy.setTextColor(Color.parseColor("#06120E"))
            binding.tvStatusBadge.text = "● SERVEUR ARRÊTÉ"
            binding.tvStatusBadge.setTextColor(Color.parseColor("#FF5252"))
            binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_pill_off)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(statsRunnable)
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}
