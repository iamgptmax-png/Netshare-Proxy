package com.netshare.everyproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ProxyService : Service() {

    companion object {
        const val CHANNEL_ID = "netshare_proxy_v3"
        const val NOTIF_ID = 3001
        const val ACTION_START = "com.netshare.everyproxy.START"
        const val ACTION_STOP = "com.netshare.everyproxy.STOP"

        @Volatile var isRunning: Boolean = false
        val totalBytes = AtomicLong(0)
        @Volatile var currentSpeedBps: Long = 0
        val speedHistory = LongArray(60)
        val logs = CopyOnWriteArrayList<String>()
        private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun addLog(msg: String) {
            val line = "[${timeFmt.format(Date())}] $msg"
            logs.add(line)
            if (logs.size > 200) logs.removeAt(0)
        }
    }

    private var httpServer: HttpProxyServer? = null
    private var socksServer: Socks5ProxyServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ticker: ScheduledExecutorService? = null
    private var lastBytesSnapshot = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProxy()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val prefs = getSharedPreferences("netshare_v3", Context.MODE_PRIVATE)
                val cfg = ProxyConfig(
                    bindHost = prefs.getString("bindHost", "0.0.0.0") ?: "0.0.0.0",
                    httpEnabled = prefs.getBoolean("httpEnabled", true),
                    httpPort = prefs.getInt("httpPort", 8080),
                    socksEnabled = prefs.getBoolean("socksEnabled", true),
                    socksPort = prefs.getInt("socksPort", 1080),
                    authEnabled = prefs.getBoolean("authEnabled", false),
                    username = prefs.getString("username", "") ?: "",
                    password = prefs.getString("password", "") ?: "",
                    adBlockEnabled = prefs.getBoolean("adBlockEnabled", true)
                )
                startProxy(cfg)
            }
        }
        return START_STICKY
    }

    private fun startProxy(config: ProxyConfig) {
        if (isRunning) return
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Démarrage du serveur..."))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetShareProxy::WakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L)

        isRunning = true
        lastBytesSnapshot = totalBytes.get()

        if (config.httpEnabled) {
            httpServer = HttpProxyServer(config, { addLog(it) }, { totalBytes.addAndGet(it) })
            httpServer?.start()
        }
        if (config.socksEnabled) {
            socksServer = Socks5ProxyServer(config, { addLog(it) }, { totalBytes.addAndGet(it) })
            socksServer?.start()
        }

        ticker = Executors.newSingleThreadScheduledExecutor()
        ticker?.scheduleAtFixedRate({
            val now = totalBytes.get()
            val delta = (now - lastBytesSnapshot).coerceAtLeast(0L)
            lastBytesSnapshot = now
            currentSpeedBps = delta
            System.arraycopy(speedHistory, 1, speedHistory, 0, speedHistory.size - 1)
            speedHistory[speedHistory.size - 1] = delta

            val ip = NetworkUtils.getBestShareableIp()
            val text = "$ip:${config.httpPort} • ${NetworkUtils.formatSpeed(delta)} • ${ClientTracker.totalClientsCount()} clients"
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun stopProxy() {
        isRunning = false
        currentSpeedBps = 0
        ticker?.shutdownNow()
        ticker = null
        httpServer?.stop()
        httpServer = null
        socksServer?.stop()
        socksServer = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        addLog("[SYSTÈME] Serveur proxy arrêté.")
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "NetShare Proxy Pro", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NetShare Proxy Pro v3.0")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_qs_proxy)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }
}
