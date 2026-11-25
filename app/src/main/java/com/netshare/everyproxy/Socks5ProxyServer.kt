package com.netshare.everyproxy

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class Socks5ProxyServer(
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
                serverSocket = ServerSocket(config.socksPort, 128, bindAddr)
                onLog("[SOCKS5] Serveur démarré sur ${config.bindHost}:${config.socksPort}")
                while (running.get()) {
                    val client = serverSocket?.accept() ?: break
                    pool.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running.get()) onLog("[SOCKS5][ERREUR] ${e.message}")
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

            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()

            val ver = input.readUnsignedByte()
            if (ver != 5) return
            val nMethods = input.readUnsignedByte()
            val methods = ByteArray(nMethods)
            input.readFully(methods)

            if (config.authEnabled) {
                output.write(byteArrayOf(5, 2))
                output.flush()
                input.readUnsignedByte() // auth version
                val uLen = input.readUnsignedByte()
                val uBytes = ByteArray(uLen)
                input.readFully(uBytes)
                val pLen = input.readUnsignedByte()
                val pBytes = ByteArray(pLen)
                input.readFully(pBytes)
                val ok = String(uBytes) == config.username && String(pBytes) == config.password
                output.write(byteArrayOf(1, if (ok) 0 else 1))
                output.flush()
                if (!ok) return
            } else {
                output.write(byteArrayOf(5, 0))
                output.flush()
            }

            input.readUnsignedByte() // ver
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte() // rsv
            val atyp = input.readUnsignedByte()
            if (cmd != 1) return

            val host = when (atyp) {
                1 -> {
                    val b = ByteArray(4)
                    input.readFully(b)
                    InetAddress.getByAddress(b).hostAddress ?: return
                }
                3 -> {
                    val len = input.readUnsignedByte()
                    val b = ByteArray(len)
                    input.readFully(b)
                    String(b)
                }
                4 -> {
                    val b = ByteArray(16)
                    input.readFully(b)
                    InetAddress.getByAddress(b).hostAddress ?: return
                }
                else -> return
            }
            val port = input.readUnsignedShort()

            if (config.adBlockEnabled && ClientTracker.isAdDomain(host)) {
                output.write(byteArrayOf(5, 2, 0, 1, 0, 0, 0, 0, 0, 0))
                output.flush()
                onLog("[BLOQUÉ] SOCKS5 $host ($clientIp)")
                return
            }

            val remote = Socket()
            remote.tcpNoDelay = true
            remote.keepAlive = true
            remote.connect(InetSocketAddress(host, port), 15000)

            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
            onLog("[SOCKS5] $host:$port ($clientIp)")

            val t1 = pool.submit { pipe(input, remote.getOutputStream(), clientIp) }
            val t2 = pool.submit { pipe(remote.getInputStream(), output, clientIp) }
            try { t1.get() } catch (_: Exception) {}
            try { t2.get() } catch (_: Exception) {}
            try { remote.close() } catch (_: Exception) {}
        } catch (_: Exception) {
        } finally {
            ClientTracker.onDisconnect(clientIp)
            try { client.close() } catch (_: Exception) {}
        }
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
