package com.netshare.everyproxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object ClientTracker {
    data class ClientStat(
        val ip: String,
        val bytes: AtomicLong = AtomicLong(0),
        val activeSockets: AtomicInteger = AtomicInteger(0),
        @Volatile var lastSeenMs: Long = System.currentTimeMillis()
    )

    val clients = ConcurrentHashMap<String, ClientStat>()
    val blockedIps = ConcurrentHashMap.newKeySet<String>()

    private val adKeywords = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.", "ads.facebook.com", "an.facebook.com",
        "ads.tiktok.com", "analytics.tiktok.com", "app-measurement.com",
        "adnxs.com", "taboola.com", "outbrain.com", "criteo.com",
        "unityads.unity3d.com", "applovin.com", "mopub.com", "vungle.com"
    )

    fun isBlocked(ip: String): Boolean = blockedIps.contains(ip)

    fun toggleBlock(ip: String): Boolean {
        return if (blockedIps.contains(ip)) {
            blockedIps.remove(ip)
            false
        } else {
            blockedIps.add(ip)
            true
        }
    }

    fun isAdDomain(host: String): Boolean {
        val h = host.lowercase()
        return adKeywords.any { h.contains(it) }
    }

    fun onConnect(ip: String) {
        val stat = clients.getOrPut(ip) { ClientStat(ip) }
        stat.activeSockets.incrementAndGet()
        stat.lastSeenMs = System.currentTimeMillis()
    }

    fun onDisconnect(ip: String) {
        clients[ip]?.activeSockets?.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    fun onBytes(ip: String, count: Long) {
        if (count <= 0) return
        val stat = clients.getOrPut(ip) { ClientStat(ip) }
        stat.bytes.addAndGet(count)
        stat.lastSeenMs = System.currentTimeMillis()
    }

    fun totalActiveSockets(): Int = clients.values.sumOf { it.activeSockets.get() }
    fun totalClientsCount(): Int = clients.size
}
