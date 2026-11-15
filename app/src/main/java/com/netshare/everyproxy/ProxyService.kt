package com.netshare.everyproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ProxyService : Service() {

    companion object {
        const val CHANNEL_ID = "NetShareProxyChannel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.netshare.everyproxy.ACTION_STOP"
    }

    inner class LocalBinder : Binder() {
        fun getService(): ProxyService = this@ProxyService
    }

    private val binder = LocalBinder()
    private var httpServer: HttpProxyServer? = null
    private var socksServer: Socks5ProxyServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    val bytesTransferred = AtomicLong(0L)
    val activeConnections = AtomicInteger(0)
    private val logs = CopyOnWriteArrayList<String>()
    var onLogListener: ((List<String>) -> Unit)? = null
    var onStateChangedListener: ((Boolean, Boolean) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAllProxies()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    fun startProxies(config: ProxyConfig) {
        acquireLocks()
        startForeground(NOTIFICATION_ID, buildNotification(config))

        if (config.httpEnabled && httpServer?.isRunning() != true) {
            httpServer = HttpProxyServer(
                bindAddress = config.bindAddress,
                port = config.httpPort,
                authEnabled = config.authEnabled,
                username = config.username,
                password = config.password,
                onLog = ::appendLog,
                bytesTransferred = bytesTransferred,
                activeConnections = activeConnections
            ).also { it.start() }
        }

        if (config.socksEnabled && socksServer?.isRunning() != true) {
            socksServer = Socks5ProxyServer(
                bindAddress = config.bindAddress,
                port = config.socksPort,
                authEnabled = config.authEnabled,
                username = config.username,
                password = config.password,
                onLog = ::appendLog,
                bytesTransferred = bytesTransferred,
                activeConnections = activeConnections
            ).also { it.start() }
        }

        notifyState()
    }

    fun stopAllProxies() {
        httpServer?.stop()
        httpServer = null
        socksServer?.stop()
        socksServer = null
        activeConnections.set(0)
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifyState()
    }

    fun isHttpRunning(): Boolean = httpServer?.isRunning() == true
    fun isSocksRunning(): Boolean = socksServer?.isRunning() == true

    fun getLogs(): List<String> = logs.toList()

    fun clearLogs() {
        logs.clear()
        onLogListener?.invoke(emptyList())
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add("[$time] $message")
        while (logs.size > 250) {
            logs.removeAt(0)
        }
        onLogListener?.invoke(logs.toList())
    }

    private fun notifyState() {
        onStateChangedListener?.invoke(isHttpRunning(), isSocksRunning())
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetShareProxy::ProxyWakeLock").apply {
                acquire()
            }
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(lockMode, "NetShareProxy::ProxyWifiLock").apply {
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Service NetShare Proxy Pro",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintient le serveur proxy HTTP/SOCKS5 haute performance actif"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(config: ProxyConfig): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val shareIp = NetworkUtils.getBestShareableIp(config.bindAddress)
        val activeList = mutableListOf<String>()
        if (config.httpEnabled) activeList.add("HTTP $shareIp:${config.httpPort}")
        if (config.socksEnabled) activeList.add("SOCKS5 $shareIp:${config.socksPort}")

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NetShare Proxy Pro • ACTIF")
            .setContentText(activeList.joinToString("  |  "))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Arrêter", stopPending)
            .build()
    }

    override fun onDestroy() {
        stopAllProxies()
        super.onDestroy()
    }
}
