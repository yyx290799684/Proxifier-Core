package com.yangyx.proxy.engine

import com.yangyx.proxy.data.model.ProxyServer
import com.yangyx.proxy.data.model.ProxyType
import com.yangyx.proxy.data.model.cleanHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object Socks5Tester {

    /**
     * Measures TCP handshake or SOCKS5 greeting latency in milliseconds.
     * Returns -1 if connection failed or timed out.
     */
    suspend fun testLatency(server: ProxyServer, timeoutMs: Int = 3000): Int = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var socket: Socket? = null
        try {
            val hostStr = server.cleanHost
            if (hostStr.isEmpty()) return@withContext -1

            val inetAddress = InetAddress.getByName(hostStr)
            val socketAddress = InetSocketAddress(inetAddress, server.port)

            socket = Socket()
            socket.connect(socketAddress, timeoutMs)

            if (server.type == ProxyType.SOCKS5) {
                // Perform SOCKS5 greeting: VER = 0x05, NMETHODS = 0x01, METHOD = 0x00 (NO AUTH)
                val out: OutputStream = socket.getOutputStream()
                val inStream: InputStream = socket.getInputStream()

                if (!server.username.isNullOrEmpty()) {
                    // Method 0x02 (USER/PASS)
                    out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
                } else {
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                }
                out.flush()

                socket.soTimeout = timeoutMs
                val response = ByteArray(2)
                val readBytes = inStream.read(response)
                if (readBytes < 2 || response[0] != 0x05.toByte()) {
                    return@withContext -1
                }
            }

            val elapsed = (System.currentTimeMillis() - startTime).toInt()
            elapsed.coerceAtLeast(1)
        } catch (e: Exception) {
            -1
        } finally {
            runCatching { socket?.close() }
        }
    }
}
