package com.netshare.everyproxy

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class NetworkInterfaceInfo(
    val name: String,
    val ipAddress: String,
    val label: String
) {
    override fun toString(): String = "$ipAddress ($label - $name)"
}

object NetworkUtils {

    /**
     * Détecte toutes les interfaces réseau IPv4 disponibles sur l'appareil :
     * - 0.0.0.0 (Toutes les interfaces)
     * - wlan0 / wlan1 / ap0 (Wi-Fi et Point d'accès mobile)
     * - rndis0 / usb0 / ncm0 (Partage de connexion USB)
     * - tun0 / ppp0 (Tunnel VPN actif)
     */
    fun getAvailableInterfaces(): List<NetworkInterfaceInfo> {
        val result = mutableListOf<NetworkInterfaceInfo>()
        result.add(NetworkInterfaceInfo("all", "0.0.0.0", "Toutes les interfaces"))

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in interfaces) {
                if (!nif.isUp) continue
                val addresses = Collections.list(nif.inetAddresses)
                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val label = classifyInterface(nif.name, ip)
                        result.add(NetworkInterfaceInfo(nif.name, ip, label))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        result.add(NetworkInterfaceInfo("lo", "127.0.0.1", "Localhost uniquement"))
        return result
    }

    fun getBestShareableIp(selectedIp: String): String {
        if (selectedIp != "0.0.0.0" && selectedIp != "127.0.0.1") {
            return selectedIp
        }
        val all = getAvailableInterfaces()
        val hotspotOrUsb = all.firstOrNull {
            it.ipAddress != "0.0.0.0" && it.ipAddress != "127.0.0.1" &&
            (it.label.contains("Point d'accès") || it.label.contains("USB") || it.label.contains("Wi-Fi"))
        }
        return hotspotOrUsb?.ipAddress
            ?: all.firstOrNull { it.ipAddress != "0.0.0.0" && it.ipAddress != "127.0.0.1" }?.ipAddress
            ?: "192.168.43.1"
    }

    private fun classifyInterface(name: String, ip: String): String {
        val lower = name.lowercase()
        return when {
            lower.startsWith("ap") || lower.contains("wlan1") || ip.startsWith("192.168.43.") || ip.startsWith("192.168.49.") ->
                "Point d'accès / Wi-Fi Direct"
            lower.startsWith("wlan") ->
                "Wi-Fi local"
            lower.startsWith("rndis") || lower.startsWith("usb") || lower.startsWith("ncm") || ip.startsWith("192.168.42.") ->
                "Partage USB"
            lower.startsWith("tun") || lower.startsWith("tap") || lower.startsWith("ppp") || lower.startsWith("wg") ->
                "Interface VPN"
            lower.startsWith("rmnet") || lower.startsWith("ccmni") ->
                "Données mobiles"
            else ->
                "Réseau local"
        }
    }
}
