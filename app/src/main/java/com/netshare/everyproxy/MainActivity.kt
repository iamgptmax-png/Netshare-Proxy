package com.netshare.everyproxy

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : AppCompatActivity() {

    private lateinit var tvVpnStatus: TextView
    private lateinit var tvStatusBadge: TextView
    private lateinit var tabDashboard: View
    private lateinit var tabDevices: View
    private lateinit var tabNetwork: View
    private lateinit var bottomNav: BottomNavigationView

    // Dashboard views
    private lateinit var btnPower: LinearLayout
    private lateinit var tvPowerLabel: TextView
    private lateinit var tvPowerSub: TextView
    private lateinit var tvHeroAddress: TextView
    private lateinit var tvHeroProtocols: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvTotalData: TextView
    private lateinit var tvActiveClients: TextView
    private lateinit var sparklineView: SparklineView

    // Devices & QR views
    private lateinit var ivQrCode: ImageView
    private lateinit var tvPacUrl: TextView
    private lateinit var clientsContainer: LinearLayout
    private lateinit var tvNoClients: TextView

    // Network & Logs views
    private lateinit var spinnerIp: Spinner
    private lateinit var switchHttp: MaterialSwitch
    private lateinit var etHttpPort: EditText
    private lateinit var switchSocks: MaterialSwitch
    private lateinit var etSocksPort: EditText
    private lateinit var switchAdBlock: MaterialSwitch
    private lateinit var switchAuth: MaterialSwitch
    private lateinit var layoutAuthFields: LinearLayout
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var tvLogs: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var ipOptions = listOf("0.0.0.0 (Toutes les interfaces)")

    private val uiUpdater = object : Runnable {
        override fun run() {
            refreshUiState()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        loadPrefs()
        setupIpSpinner()
        setupListeners()
        requestNotifPermissionIfNeeded()
        updateQrAndPac()
    }

    override fun onResume() {
        super.onResume()
        handler.post(uiUpdater)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(uiUpdater)
    }

    private fun bindViews() {
        tvVpnStatus = findViewById(R.id.tvVpnStatus)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tabDashboard = findViewById(R.id.tabDashboard)
        tabDevices = findViewById(R.id.tabDevices)
        tabNetwork = findViewById(R.id.tabNetwork)
        bottomNav = findViewById(R.id.bottomNav)

        btnPower = findViewById(R.id.btnPower)
        tvPowerLabel = findViewById(R.id.tvPowerLabel)
        tvPowerSub = findViewById(R.id.tvPowerSub)
        tvHeroAddress = findViewById(R.id.tvHeroAddress)
        tvHeroProtocols = findViewById(R.id.tvHeroProtocols)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvTotalData = findViewById(R.id.tvTotalData)
        tvActiveClients = findViewById(R.id.tvActiveClients)
        sparklineView = findViewById(R.id.sparklineView)

        ivQrCode = findViewById(R.id.ivQrCode)
        tvPacUrl = findViewById(R.id.tvPacUrl)
        clientsContainer = findViewById(R.id.clientsContainer)
        tvNoClients = findViewById(R.id.tvNoClients)

        spinnerIp = findViewById(R.id.spinnerIp)
        switchHttp = findViewById(R.id.switchHttp)
        etHttpPort = findViewById(R.id.etHttpPort)
        switchSocks = findViewById(R.id.switchSocks)
        etSocksPort = findViewById(R.id.etSocksPort)
        switchAdBlock = findViewById(R.id.switchAdBlock)
        switchAuth = findViewById(R.id.switchAuth)
        layoutAuthFields = findViewById(R.id.layoutAuthFields)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        tvLogs = findViewById(R.id.tvLogs)
    }

    private fun setupListeners() {
        bottomNav.setOnItemSelectedListener { item ->
            tabDashboard.visibility = if (item.itemId == R.id.nav_dashboard) View.VISIBLE else View.GONE
            tabDevices.visibility = if (item.itemId == R.id.nav_devices) View.VISIBLE else View.GONE
            tabNetwork.visibility = if (item.itemId == R.id.nav_network) View.VISIBLE else View.GONE
            if (item.itemId == R.id.nav_devices) updateQrAndPac()
            true
        }

        btnPower.setOnClickListener {
            savePrefs()
            val intent = Intent(this, ProxyService::class.java)
            if (ProxyService.isRunning) {
                intent.action = ProxyService.ACTION_STOP
                startService(intent)
            } else {
                intent.action = ProxyService.ACTION_START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
            handler.postDelayed({ refreshUiState() }, 250)
        }

        findViewById<MaterialButton>(R.id.btnCopyProxy).setOnClickListener {
            val ip = NetworkUtils.getBestShareableIp()
            val port = etHttpPort.text.toString().toIntOrNull() ?: 8080
            copyToClipboard("Proxy", "$ip:$port")
        }

        findViewById<MaterialButton>(R.id.btnCopyPac).setOnClickListener {
            copyToClipboard("PAC URL", tvPacUrl.text.toString())
        }

        findViewById<MaterialButton>(R.id.btnBatteryBoost).setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "Ouvrez les paramètres batterie manuellement", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.btnRefreshIp).setOnClickListener {
            setupIpSpinner()
            updateQrAndPac()
            Toast.makeText(this, "Interfaces actualisées", Toast.LENGTH_SHORT).show()
        }

        switchAuth.setOnCheckedChangeListener { _, isChecked ->
            layoutAuthFields.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        findViewById<MaterialButton>(R.id.btnClearLogs).setOnClickListener {
            ProxyService.logs.clear()
            tvLogs.text = "[NetShare v3.0.0] Journaux effacés.\n"
        }

        findViewById<MaterialButton>(R.id.btnShareLogs).setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Logs NetShare Proxy Pro v3.0.0")
                putExtra(Intent.EXTRA_TEXT, ProxyService.logs.joinToString("\n"))
            }
            startActivity(Intent.createChooser(shareIntent, "Exporter les logs"))
        }
    }

    private fun setupIpSpinner() {
        ipOptions = NetworkUtils.getLocalIpAddresses()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ipOptions)
        spinnerIp.adapter = adapter
    }

    private fun refreshUiState() {
        val running = ProxyService.isRunning
        val lanIp = NetworkUtils.getBestShareableIp()
        val httpPort = etHttpPort.text.toString().toIntOrNull() ?: 8080
        val socksPort = etSocksPort.text.toString().toIntOrNull() ?: 1080
        val vpn = NetworkUtils.getActiveVpnInterface()

        tvVpnStatus.text = if (vpn != null) "v3.0.0 • VPN actif ($vpn)" else "v3.0.0 • Aucun tunnel VPN détecté"
        tvStatusBadge.text = if (running) "EN LIGNE" else "ARRÊTÉ"
        tvStatusBadge.setBackgroundResource(if (running) R.drawable.bg_pill_on else R.drawable.bg_pill_off)
        tvStatusBadge.setTextColor(Color.parseColor(if (running) "#6EE7B7" else "#FDA4AF"))

        btnPower.setBackgroundResource(if (running) R.drawable.bg_power_on else R.drawable.bg_power_off)
        tvPowerLabel.text = if (running) "ACTIF" else "DÉMARRER"
        tvPowerSub.text = if (running) "Appuyer pour arrêter" else "Appuyer pour lancer"

        tvHeroAddress.text = "$lanIp : $httpPort"
        tvHeroProtocols.text = "HTTP :$httpPort • SOCKS5 :$socksPort"

        tvSpeed.text = NetworkUtils.formatSpeed(ProxyService.currentSpeedBps)
        tvTotalData.text = NetworkUtils.formatBytes(ProxyService.totalBytes.get())
        tvActiveClients.text = "${ClientTracker.totalClientsCount()}"

        sparklineView.setSamples(ProxyService.speedHistory)

        if (tabNetwork.visibility == View.VISIBLE) {
            tvLogs.text = ProxyService.logs.takeLast(60).joinToString("\n")
        }

        if (tabDevices.visibility == View.VISIBLE) {
            refreshClientsList()
        }
    }

    private fun refreshClientsList() {
        clientsContainer.removeAllViews()
        val list = ClientTracker.clients.values.sortedByDescending { it.lastSeenMs }
        tvNoClients.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        for (c in list) {
            val blocked = ClientTracker.isBlocked(c.ip)
            val card = MaterialCardView(this).apply {
                setCardBackgroundColor(Color.parseColor("#111A2E"))
                radius = 32f
                strokeColor = Color.parseColor(if (blocked) "#F43F5E" else "#1E293B")
                strokeWidth = 2
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 16
                layoutParams = lp
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(32, 24, 32, 24)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvIp = TextView(this).apply {
                text = c.ip
                setTextColor(Color.parseColor("#F8FAFC"))
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val tvMeta = TextView(this).apply {
                text = "${NetworkUtils.formatBytes(c.bytes.get())} • ${c.activeSockets.get()} sockets"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
            }
            info.addView(tvIp)
            info.addView(tvMeta)

            val btnBlock = MaterialButton(this).apply {
                text = if (blocked) "Débloquer" else "Bloquer"
                textSize = 11f
                setOnClickListener {
                    ClientTracker.toggleBlock(c.ip)
                    refreshClientsList()
                }
            }

            row.addView(info)
            row.addView(btnBlock)
            card.addView(row)
            clientsContainer.addView(card)
        }
    }

    private fun updateQrAndPac() {
        val lanIp = NetworkUtils.getBestShareableIp()
        val httpPort = etHttpPort.text.toString().toIntOrNull() ?: 8080
        val pacUrl = "http://$lanIp:$httpPort/proxy.pac"
        tvPacUrl.text = pacUrl
        try {
            val matrix = QRCodeWriter().encode(pacUrl, BarcodeFormat.QR_CODE, 400, 400)
            val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.RGB_565)
            for (x in 0 until 400) {
                for (y in 0 until 400) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            ivQrCode.setImageBitmap(bmp)
        } catch (_: Exception) {}
    }

    private fun savePrefs() {
        val selected = spinnerIp.selectedItem?.toString() ?: "0.0.0.0"
        val bindHost = selected.substringBefore(" ").trim()
        getSharedPreferences("netshare_v3", Context.MODE_PRIVATE).edit()
            .putString("bindHost", bindHost)
            .putBoolean("httpEnabled", switchHttp.isChecked)
            .putInt("httpPort", etHttpPort.text.toString().toIntOrNull() ?: 8080)
            .putBoolean("socksEnabled", switchSocks.isChecked)
            .putInt("socksPort", etSocksPort.text.toString().toIntOrNull() ?: 1080)
            .putBoolean("adBlockEnabled", switchAdBlock.isChecked)
            .putBoolean("authEnabled", switchAuth.isChecked)
            .putString("username", etUsername.text.toString())
            .putString("password", etPassword.text.toString())
            .apply()
    }

    private fun loadPrefs() {
        val p = getSharedPreferences("netshare_v3", Context.MODE_PRIVATE)
        switchHttp.isChecked = p.getBoolean("httpEnabled", true)
        etHttpPort.setText(p.getInt("httpPort", 8080).toString())
        switchSocks.isChecked = p.getBoolean("socksEnabled", true)
        etSocksPort.setText(p.getInt("socksPort", 1080).toString())
        switchAdBlock.isChecked = p.getBoolean("adBlockEnabled", true)
        switchAuth.isChecked = p.getBoolean("authEnabled", false)
        layoutAuthFields.visibility = if (switchAuth.isChecked) View.VISIBLE else View.GONE
        etUsername.setText(p.getString("username", ""))
        etPassword.setText(p.getString("password", ""))
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copié : $text", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}
