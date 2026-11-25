package com.netshare.everyproxy

import android.util.Base64
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HttpProxyServer(
    private val config: ProxyConfig,
    private val onLog: (String) -> Unit,
    private val onBytes: (Long) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        pool.execute {
            try {
                val bindAddr = InetAddress.getByName(config.bindHost)
                serverSocket = ServerSocket(config.httpPort, 128, bindAddr)
                onLog("[HTTP] Serveur démarré sur ${config.bindHost}:${config.httpPort} (PAC actif)")
                while (running.get()) {
                    val client = serverSocket?.accept() ?: break
                    pool.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running.get()) onLog("[HTTP][ERREUR] ${e.message}")
            }
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        val clientIp = client.inetAddress?.hostAddress ?: "inconnu"
        if (ClientTracker.isBlocked(clientIp)) {
            try { client.close() } catch (_: Exception) {}
            return
        }
        ClientTracker.onConnect(clientIp)
        try {
            client.tcpNoDelay = true
            client.keepAlive = true
            client.soTimeout = 30000

            val input = BufferedInputStream(client.getInputStream(), 65536)
            val output = client.getOutputStream()

            val headerBytes = readHeaders(input) ?: return
            val headerText = String(headerBytes, Charsets.ISO_8859_1)
            val lines = headerText.split("
")
            if (lines.isEmpty()) return

            val parts = lines[0].split(" ")
            if (parts.size < 3) return
            val method = parts[0]
            val target = parts[1]

            // Serve /proxy.pac directly for zero-config clients
            if (method.equals("GET", true) && (target == "/proxy.pac" || target.endsWith("/proxy.pac"))) {
                val lanIp = NetworkUtils.getBestShareableIp()
                val pacBody = "function FindProxyForURL(url, host) { return "PROXY $lanIp:${config.httpPort}; DIRECT"; }
"
                val resp = "HTTP/1.1 200 OK
" +
                    "Content-Type: application/x-ns-proxy-autoconfig
" +
                    "Content-Length: ${pacBody.toByteArray().size}
" +
                    "Connection: close

" + pacBody
                output.write(resp.toByteArray(Charsets.UTF_8))
                output.flush()
                onLog("[HTTP] PAC servi à $clientIp")
                return
            }

            // Auth check
            if (config.authEnabled) {
                val authLine = lines.firstOrNull { it.startsWith("Proxy-Authorization:", true) }
                if (!checkAuth(authLine)) {
                    val resp = "HTTP/1.1 407 Proxy Authentication Required
" +
                        "Proxy-Authenticate: Basic realm="NetShareProxy"
" +
                        "Connection: close

"
                    output.write(resp.toByteArray())
                    output.flush()
                    return
                }
            }

            if (method.equals("CONNECT", true)) {
                val hostPort = target.split(":")
                val host = hostPort[0]
                val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443

                if (config.adBlockEnabled && ClientTracker.isAdDomain(host)) {
                    output.write("HTTP/1.1 403 Forbidden
Connection: close

".toByteArray())
                    output.flush()
                    onLog("[BLOQUÉ] $host ($clientIp)")
                    return
                }

                val remote = Socket()
                remote.tcpNoDelay = true
                remote.keepAlive = true
                remote.connect(InetSocketAddress(host, port), 15000)

                output.write("HTTP/1.1 200 Connection Established

".toByteArray())
                output.flush()
                onLog("[HTTPS] CONNECT $host:$port ($clientIp)")

                relayBidirectional(client, input, remote, clientIp)
            } else {
                val uri = URI(target)
                val host = uri.host ?: lines.firstOrNull { it.startsWith("Host:", true) }
                    ?.substringAfter(":")?.trim()?.substringBefore(":") ?: return
                val port = if (uri.port != -1) uri.port else 80

                if (config.adBlockEnabled && ClientTracker.isAdDomain(host)) {
                    output.write("HTTP/1.1 403 Forbidden
Connection: close

".toByteArray())
                    output.flush()
                    onLog("[BLOQUÉ] $host ($clientIp)")
                    return
                }

                val remote = Socket()
                remote.tcpNoDelay = true
                remote.keepAlive = true
                remote.connect(InetSocketAddress(host, port), 15000)

                val remoteOut = remote.getOutputStream()
                remoteOut.write(headerBytes)
                remoteOut.flush()
                onLog("[HTTP] $method $host:$port ($clientIp)")

                relayBidirectional(client, input, remote, clientIp)
            }
        } catch (_: Exception) {
        } finally {
            ClientTracker.onDisconnect(clientIp)
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun checkAuth(authLine: String?): Boolean {
        if (authLine == null) return false
        val token = authLine.substringAfter("Basic ", "").trim()
        val expected = Base64.encodeToString("${config.username}:${config.password}".toByteArray(), Base64.NO_WRAP)
        return token == expected
    }

    private fun readHeaders(input: InputStream): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null
            buf.write(b)
            state = when {
                state == 0 && b == ''.code -> 1
                state == 1 && b == '
'.code -> 2
                state == 2 && b == ''.code -> 3
                state == 3 && b == '
'.code -> return buf.toByteArray()
                else -> 0
            }
            if (buf.size() > 65536) return null
        }
    }

    private fun relayBidirectional(client: Socket, clientIn: InputStream, remote: Socket, clientIp: String) {
        val t1 = pool.submit { pipe(clientIn, remote.getOutputStream(), clientIp) }
        val t2 = pool.submit { pipe(remote.getInputStream(), client.getOutputStream(), clientIp) }
        try { t1.get() } catch (_: Exception) {}
        try { t2.get() } catch (_: Exception) {}
        try { remote.close() } catch (_: Exception) {}
    }

    private fun pipe(input: InputStream, output: OutputStream, clientIp: String) {
        val buffer = ByteArray(65536)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                output.write(buffer, 0, n)
                output.flush()
                onBytes(n.toLong())
                ClientTracker.onBytes(clientIp, n.toLong())
            }
        } catch (_: Exception) {}
    }
}
