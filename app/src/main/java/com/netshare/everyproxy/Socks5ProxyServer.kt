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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serveur Proxy SOCKS5 conforme aux RFC 1928 et RFC 1929 :
 * - Supporte l'accès sans authentification (0x00) ou avec identifiant/mot de passe (0x02)
 * - Supporte les adresses IPv4 (0x01), Noms de domaine (0x03) et IPv6 (0x04)
 * - Commande CONNECT (0x01) pour relayer tout le trafic TCP
 */
class Socks5ProxyServer(
    private val bindAddress: String,
    private val port: Int,
    private val authEnabled: Boolean,
    private val username: String,
    private val password: String,
    private val onLog: (String) -> Unit,
    private val bytesTransferred: AtomicLong,
    private val activeConnections: AtomicInteger = AtomicInteger(0)
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
                onLog("[SOCKS5] Serveur démarré sur $bindAddress:$port (Auth: ${if (authEnabled) "Oui" else "Non"})")

                while (running.get()) {
                    val clientSocket = serverSocket?.accept() ?: break
                    executor?.execute { handleClient(clientSocket) }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    onLog("[SOCKS5] Erreur serveur : ${e.message}")
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
        onLog("[SOCKS5] Serveur arrêté.")
    }

    fun isRunning(): Boolean = running.get()

    private fun handleClient(client: Socket) {
        activeConnections.incrementAndGet()
        try {
        client.use { clientSocket ->
            clientSocket.tcpNoDelay = true
            clientSocket.keepAlive = true
            clientSocket.sendBufferSize = 65536
            clientSocket.receiveBufferSize = 65536
            clientSocket.soTimeout = 30000
            val input = DataInputStream(clientSocket.getInputStream())
            val output = clientSocket.getOutputStream()

            // 1. Handshake initial SOCKS5
            val version = input.readByte().toInt()
            if (version != 5) return

            val nMethods = input.readUnsignedByte()
            val methods = ByteArray(nMethods)
            input.readFully(methods)

            if (authEnabled) {
                if (!methods.contains(0x02.toByte())) {
                    output.write(byteArrayOf(0x05, 0xFF.toByte()))
                    output.flush()
                    return
                }
                output.write(byteArrayOf(0x05, 0x02))
                output.flush()

                // Sous-négociation RFC 1929 (Username/Password)
                val authVer = input.readByte().toInt()
                if (authVer != 1) return
                val uLen = input.readUnsignedByte()
                val uBytes = ByteArray(uLen)
                input.readFully(uBytes)
                val pLen = input.readUnsignedByte()
                val pBytes = ByteArray(pLen)
                input.readFully(pBytes)

                val providedUser = String(uBytes, Charsets.UTF_8)
                val providedPass = String(pBytes, Charsets.UTF_8)

                if (providedUser != username || providedPass != password) {
                    output.write(byteArrayOf(0x01, 0x01)) // Échec d'authentification
                    output.flush()
                    onLog("[SOCKS5] Échec auth depuis ${clientSocket.inetAddress.hostAddress}")
                    return
                }
                output.write(byteArrayOf(0x01, 0x00)) // Succès
                output.flush()
            } else {
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()
            }

            // 2. Lecture de la requête de connexion SOCKS5
            val reqVer = input.readByte().toInt()
            if (reqVer != 5) return
            val cmd = input.readByte().toInt()
            input.readByte() // RSV (0x00)
            val atyp = input.readByte().toInt()

            if (cmd != 1) { // Seule la commande CONNECT (0x01) est supportée
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                return
            }

            val targetHost: String = when (atyp) {
                1 -> { // IPv4
                    val addrBytes = ByteArray(4)
                    input.readFully(addrBytes)
                    InetAddress.getByAddress(addrBytes).hostAddress ?: return
                }
                3 -> { // Nom de domaine
                    val domainLen = input.readUnsignedByte()
                    val domainBytes = ByteArray(domainLen)
                    input.readFully(domainBytes)
                    String(domainBytes, Charsets.UTF_8)
                }
                4 -> { // IPv6
                    val addrBytes = ByteArray(16)
                    input.readFully(addrBytes)
                    InetAddress.getByAddress(addrBytes).hostAddress ?: return
                }
                else -> return
            }

            val targetPort = input.readUnsignedShort()
            onLog("[SOCKS5] CONNECT $targetHost:$targetPort (${clientSocket.inetAddress.hostAddress})")

            val remoteSocket = Socket()
            remoteSocket.tcpNoDelay = true
            remoteSocket.keepAlive = true
            remoteSocket.sendBufferSize = 65536
            remoteSocket.receiveBufferSize = 65536
            try {
                remoteSocket.connect(InetSocketAddress(targetHost, targetPort), 15000)
                remoteSocket.soTimeout = 60000

                // Réponse de succès SOCKS5
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()

                val remoteIn = remoteSocket.getInputStream()
                val remoteOut = remoteSocket.getOutputStream()

                val t1 = Thread { relay(input, remoteOut) }
                val t2 = Thread { relay(remoteIn, output) }
                t1.start()
                t2.start()
                t1.join()
                t2.join()
            } catch (e: Exception) {
                output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
            } finally {
                runCatching { remoteSocket.close() }
            }
        }
        } finally {
            activeConnections.decrementAndGet()
        }
    }

    private fun relay(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(65536)
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
