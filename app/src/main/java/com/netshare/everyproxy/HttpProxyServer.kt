package com.netshare.everyproxy

import android.util.Base64
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Serveur Proxy HTTP/HTTPS complet avec support :
 * - Tunneling HTTPS via la méthode CONNECT
 * - Requêtes HTTP standard (GET, POST, PUT, DELETE, HEAD, OPTIONS)
 * - Authentification Basic (Proxy-Authorization) optionnelle
 * - Compteurs d'octets échangés en temps réel
 */
class HttpProxyServer(
    private val bindAddress: String,
    private val port: Int,
    private val authEnabled: Boolean,
    private val username: String,
    private val password: String,
    private val onLog: (String) -> Unit,
    private val bytesTransferred: AtomicLong
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null

    fun start() {
        if (running.getAndSet(true)) return
        executor = Executors.newCachedThreadPool()

        executor?.execute {
            try {
                val inetAddr = InetAddress.getByName(bindAddress)
                serverSocket = ServerSocket(port, 128, inetAddr).apply {
                    reuseAddress = true
                }
                onLog("[HTTP] Serveur démarré sur $bindAddress:$port (Auth: ${if (authEnabled) "Oui" else "Non"})")

                while (running.get()) {
                    val clientSocket = serverSocket?.accept() ?: break
                    executor?.execute { handleClient(clientSocket) }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    onLog("[HTTP] Erreur serveur : ${e.message}")
                }
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        executor?.shutdownNow()
        onLog("[HTTP] Serveur arrêté.")
    }

    fun isRunning(): Boolean = running.get()

    private fun handleClient(client: Socket) {
        client.use { clientSocket ->
            clientSocket.soTimeout = 30000
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            val reader = BufferedReader(InputStreamReader(clientIn, Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            if (requestLine.isBlank()) return

            val parts = requestLine.split(" ")
            if (parts.size < 3) return
            val method = parts[0].uppercase()
            val rawUrl = parts[1]
            val httpVersion = parts[2]

            val headers = mutableListOf<String>()
            var hostHeader: String? = null
            var proxyAuthHeader: String? = null

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                headers.add(line)
                if (line.startsWith("Host:", ignoreCase = true)) {
                    hostHeader = line.substring(5).trim()
                } else if (line.startsWith("Proxy-Authorization:", ignoreCase = true)) {
                    proxyAuthHeader = line.substring(20).trim()
                }
            }

            if (authEnabled && !verifyBasicAuth(proxyAuthHeader)) {
                val response = "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                        "Proxy-Authenticate: Basic realm=\"NetShareProxy\"\r\n" +
                        "Connection: close\r\n\r\n"
                clientOut.write(response.toByteArray(Charsets.ISO_8859_1))
                clientOut.flush()
                onLog("[HTTP] Accès refusé (407) depuis ${clientSocket.inetAddress.hostAddress}")
                return
            }

            if (method == "CONNECT") {
                // Tunneling HTTPS (CONNECT host:port HTTP/1.1)
                val hostPort = rawUrl.split(":")
                val targetHost = hostPort[0]
                val targetPort = if (hostPort.size > 1) hostPort[1].toIntOrNull() ?: 443 else 443

                onLog("[HTTPS] CONNECT $targetHost:$targetPort (${clientSocket.inetAddress.hostAddress})")

                val remoteSocket = Socket()
                try {
                    remoteSocket.connect(InetSocketAddress(targetHost, targetPort), 15000)
                    remoteSocket.soTimeout = 60000

                    val established = "HTTP/1.1 200 Connection Established\r\n\r\n"
                    clientOut.write(established.toByteArray(Charsets.ISO_8859_1))
                    clientOut.flush()

                    val remoteIn = remoteSocket.getInputStream()
                    val remoteOut = remoteSocket.getOutputStream()

                    val t1 = Thread { relay(clientIn, remoteOut) }
                    val t2 = Thread { relay(remoteIn, clientOut) }
                    t1.start()
                    t2.start()
                    t1.join()
                    t2.join()
                } catch (e: Exception) {
                    val err = "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n"
                    runCatching { clientOut.write(err.toByteArray()) }
                } finally {
                    runCatching { remoteSocket.close() }
                }
            } else {
                // Requête HTTP standard
                val (targetHost, targetPort, relativePath) = resolveHttpTarget(rawUrl, hostHeader)
                if (targetHost.isEmpty()) return

                onLog("[HTTP] $method $targetHost:$targetPort$relativePath")

                val remoteSocket = Socket()
                try {
                    remoteSocket.connect(InetSocketAddress(targetHost, targetPort), 15000)
                    remoteSocket.soTimeout = 30000
                    val remoteIn = remoteSocket.getInputStream()
                    val remoteOut = remoteSocket.getOutputStream()

                    val requestBuilder = StringBuilder()
                    requestBuilder.append("$method $relativePath $httpVersion\r\n")
                    for (header in headers) {
                        if (!header.startsWith("Proxy-", ignoreCase = true)) {
                            requestBuilder.append(header).append("\r\n")
                        }
                    }
                    requestBuilder.append("Connection: close\r\n\r\n")

                    val headerBytes = requestBuilder.toString().toByteArray(Charsets.ISO_8859_1)
                    remoteOut.write(headerBytes)
                    remoteOut.flush()
                    bytesTransferred.addAndGet(headerBytes.size.toLong())

                    relay(remoteIn, clientOut)
                } catch (e: Exception) {
                    val err = "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n"
                    runCatching { clientOut.write(err.toByteArray()) }
                } finally {
                    runCatching { remoteSocket.close() }
                }
            }
        }
    }

    private fun verifyBasicAuth(proxyAuthHeader: String?): Boolean {
        if (proxyAuthHeader == null || !proxyAuthHeader.startsWith("Basic ", ignoreCase = true)) {
            return false
        }
        return try {
            val base64Part = proxyAuthHeader.substring(6).trim()
            val decoded = String(Base64.decode(base64Part, Base64.DEFAULT), Charsets.UTF_8)
            decoded == "$username:$password"
        } catch (e: Exception) {
            false
        }
    }

    private fun resolveHttpTarget(rawUrl: String, hostHeader: String?): Triple<String, Int, String> {
        return try {
            if (rawUrl.startsWith("http://", ignoreCase = true)) {
                val uri = URI(rawUrl)
                val host = uri.host ?: ""
                val port = if (uri.port != -1) uri.port else 80
                val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") +
                        (if (uri.rawQuery != null) "?${uri.rawQuery}" else "")
                Triple(host, port, path)
            } else {
                val hostParts = (hostHeader ?: "").split(":")
                val host = hostParts[0]
                val port = if (hostParts.size > 1) hostParts[1].toIntOrNull() ?: 80 else 80
                Triple(host, port, rawUrl.ifEmpty { "/" })
            }
        } catch (e: Exception) {
            Triple("", 80, "/")
        }
    }

    private fun relay(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16384)
        try {
            while (running.get()) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                output.flush()
                bytesTransferred.addAndGet(read.toLong())
            }
        } catch (_: Exception) {}
    }
}
