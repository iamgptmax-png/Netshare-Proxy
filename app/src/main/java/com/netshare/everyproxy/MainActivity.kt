package com.netshare.everyproxy

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.ArrayAdapter
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

    private val statsRunnable = object : Runnable {
        override fun run() {
            val bytes = proxyService?.bytesTransferred?.get() ?: 0L
            binding.tvTrafficStats.text = "Données relayées : ${formatBytes(bytes)}"
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ProxyService.LocalBinder
            proxyService = binder.getService()
            isBound = true

            proxyService?.onLogListener = { logLines ->
                runOnUiThread {
                    binding.tvLogs.text = logLines.joinToString("\n")
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
            binding.tvLogs.text = proxyService?.getLogs()?.joinToString("\n") ?: ""
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

        requestNotificationPermissionIfNeeded()
        refreshNetworkInterfaces()

        binding.btnRefreshIps.setOnClickListener {
            refreshNetworkInterfaces()
        }

        binding.btnToggleProxy.setOnClickListener {
            val running = (proxyService?.isHttpRunning() == true) || (proxyService?.isSocksRunning() == true)
            if (running) {
                proxyService?.stopAllProxies()
            } else {
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

        proxyService?.startProxies(config)
    }

    private fun updateStatusUi(httpRunning: Boolean, socksRunning: Boolean) {
        val anyRunning = httpRunning || socksRunning
        binding.btnToggleProxy.text = if (anyRunning) "ARRÊTER LE SERVEUR PROXY" else "DÉMARRER LE SERVEUR PROXY"
        binding.tvStatusBadge.text = if (anyRunning) {
            "Statut : ACTIF (HTTP: ${if (httpRunning) "ON" else "OFF"} | SOCKS5: ${if (socksRunning) "ON" else "OFF"})"
        } else {
            "Statut : ARRÊTÉ"
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
