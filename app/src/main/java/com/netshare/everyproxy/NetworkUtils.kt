package com.netshare.everyproxy

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun getLocalIpAddresses(): List<String> {
        val result = mutableListOf("0.0.0.0 (Toutes les interfaces)")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        result.add("$ip (${nif.name})")
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }

    fun getBestShareableIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var fallback = "192.168.43.1"
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("wg") || name.startsWith("rmnet")) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (name.contains("wlan") || name.contains("ap") || name.contains("swlan")) {
                            return ip
                        }
                        fallback = ip
                    }
                }
            }
            return fallback
        } catch (_: Exception) {
            return "192.168.43.1"
        }
    }

    fun getActiveVpnInterface(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val n = nif.name.lowercase()
                if (n.startsWith("tun") || n.startsWith("wg") || n.startsWith("ppp")) {
                    return nif.name
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes o"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f Ko", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.2f Mo", mb)
        return String.format("%.2f Go", mb / 1024.0)
    }

    fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"
}
