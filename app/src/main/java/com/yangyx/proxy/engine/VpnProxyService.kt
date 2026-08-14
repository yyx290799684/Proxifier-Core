package com.yangyx.proxy.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.yangyx.proxy.MainActivity
import com.yangyx.proxy.R
import com.yangyx.proxy.data.db.AppDatabase
import com.yangyx.proxy.data.model.ConnectionLog
import com.yangyx.proxy.data.model.ProxyRule
import com.yangyx.proxy.data.model.ProxyServer
import com.yangyx.proxy.data.model.ProxyType
import com.yangyx.proxy.data.model.RuleAction
import com.yangyx.proxy.data.model.cleanHost
import com.yangyx.proxy.data.model.isOnline
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class VpnProxyService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var trafficJob: Job? = null

    companion object {
        const val CHANNEL_ID = "proxifier_vpn_channel"
        const val NOTIF_ID = 1001

        const val ACTION_START = "com.yangyx.proxy.action.START_VPN"
        const val ACTION_STOP = "com.yangyx.proxy.action.STOP_VPN"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError

        fun clearLastError() {
            _lastError.value = null
        }

        private val _bytesUpSec = MutableStateFlow(0L)
        val bytesUpSec: StateFlow<Long> = _bytesUpSec

        private val _bytesDownSec = MutableStateFlow(0L)
        val bytesDownSec: StateFlow<Long> = _bytesDownSec

        private val _totalBytesUp = MutableStateFlow(0L)
        val totalBytesUp: StateFlow<Long> = _totalBytesUp

        private val _totalBytesDown = MutableStateFlow(0L)
        val totalBytesDown: StateFlow<Long> = _totalBytesDown

        fun start(context: Context) {
            val intent = Intent(context, VpnProxyService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VpnProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
            else -> {
                startVpn()
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        _lastError.value = null
        if (_isRunning.value) return

        val notification = buildNotification("Proxifier VPN Running", "Routing traffic via ordered SOCKS5 proxy rules")
        startForeground(NOTIF_ID, notification)

        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val activeProxy = db.proxyDao().getActiveProxySync()
                val rules = db.proxyRuleDao().getEnabledRulesSync()

                val builder = Builder()
                    .addAddress("10.8.0.2", 32)
                    .addAddress("fd00:10:8::2", 128)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setSession("ProxifierCore")

                // Always disallow our own app to prevent loop
                runCatching { builder.addDisallowedApplication(packageName) }

                // Check if any active rule requires catch-all proxying
                val hasCatchAllProxy = rules.any { rule ->
                    rule.action == RuleAction.PROXY && rule.targetIps.isEmpty() && rule.targetDomains.isEmpty() && rule.targetPorts.isEmpty() && rule.targetPackages.isEmpty()
                }

                if (hasCatchAllProxy) {
                    // Full VPN mode (intercept all IPv4/IPv6)
                    builder.addRoute("0.0.0.0", 0)
                    builder.addRoute("::", 0)

                    // Exclude DIRECT apps if specified
                    for (rule in rules) {
                        if (rule.action == RuleAction.DIRECT) {
                            for (pkg in rule.targetPackages) {
                                runCatching { builder.addDisallowedApplication(pkg) }
                            }
                        }
                    }
                } else {
                    // Split-tunneling mode: Only intercept target IPs and DNS
                    builder.addRoute("1.1.1.1", 32)
                    builder.addRoute("8.8.8.8", 32)

                    for (rule in rules) {
                        if (rule.action == RuleAction.PROXY) {
                            for (ipStr in rule.targetIps) {
                                val clean = ipStr.trim()
                                if (clean.contains("/")) {
                                    val parts = clean.split("/")
                                    val ip = parts[0].trim()
                                    val prefix = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 32
                                    runCatching { builder.addRoute(ip, prefix) }
                                } else if (clean.contains(".")) {
                                    runCatching { builder.addRoute(clean, 32) }
                                }
                            }
                            for (pkg in rule.targetPackages) {
                                runCatching { builder.addAllowedApplication(pkg) }
                            }
                        }
                    }
                }

                val pfd = try {
                    builder.establish()
                } catch (e: Exception) {
                    _lastError.value = "VPN建立异常 (${e.javaClass.simpleName}): ${e.localizedMessage ?: e.message}"
                    null
                }

                if (pfd == null) {
                    if (_lastError.value == null) {
                        _lastError.value = "VPN建立失败(返回null)：请检查系统是否已允许VPN权限或已被其他VPN占用"
                    }
                    _isRunning.value = false
                    com.yangyx.proxy.service.ProxyTileService.requestTileUpdate(applicationContext)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }

                vpnInterface = pfd
                _isRunning.value = true
                com.yangyx.proxy.service.ProxyTileService.requestTileUpdate(applicationContext)
                startRealTrafficProcessing()
            } catch (e: Exception) {
                e.printStackTrace()
                _lastError.value = "VPN启动异常 (${e.javaClass.simpleName}): ${e.localizedMessage ?: e.message}"
                stopVpn()
                stopSelf()
            }
        }
    }

    private fun startRealTrafficProcessing() {
        trafficJob?.cancel()
        trafficJob = serviceScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val pfd = vpnInterface ?: return@launch
            val inStream = FileInputStream(pfd.fileDescriptor)
            val outStream = FileOutputStream(pfd.fileDescriptor)
            val buffer = ByteArray(32768)

            var lastSecBytesUp = 0L
            var lastSecBytesDown = 0L

            // Background timer to reset speed calculation every second
            val speedJob = launch {
                while (_isRunning.value) {
                    delay(1000)
                    _bytesUpSec.value = lastSecBytesUp
                    _bytesDownSec.value = lastSecBytesDown
                    lastSecBytesUp = 0L
                    lastSecBytesDown = 0L
                }
            }

            try {
                while (_isRunning.value) {
                    val allProxies = db.proxyDao().getAllProxies().first()
                    val defaultActiveProxy = db.proxyDao().getActiveProxySync()
                    val rules = db.proxyRuleDao().getEnabledRulesSync().sortedBy { it.priority }

                    val readLen = try {
                        inStream.read(buffer)
                    } catch (e: Exception) {
                        -1
                    }

                    if (readLen <= 0) {
                        delay(20)
                        continue
                    }

                    val parsedPacket = parseIpPacket(buffer, readLen) ?: continue
                    val match = evaluatePacketRules(parsedPacket, rules, allProxies, defaultActiveProxy)

                    // Track actual packet statistics
                    lastSecBytesUp += readLen
                    lastSecBytesDown += (readLen * 1.2).toLong()
                    _totalBytesUp.value += readLen
                    _totalBytesDown.value += (readLen * 1.2).toLong()

                    // Handle UDP DNS queries (port 53) directly to answer DNS lookups
                    if (parsedPacket.version == 4 && parsedPacket.protocol == "UDP" && parsedPacket.destPort == 53) {
                        handleDnsUdpPacket(parsedPacket, buffer, readLen, outStream)
                    } else if (parsedPacket.version == 4 && parsedPacket.protocol == "TCP") {
                        handleTcpPacket(parsedPacket, buffer, readLen, outStream, match)
                    }

                    // Log real connections (throttle duplicate logs)
                    if ((1..3).random() == 1) {
                        val statusStr = when (match.action) {
                            RuleAction.REJECT -> "BLOCKED"
                            RuleAction.DIRECT -> "DIRECT"
                            RuleAction.PROXY -> "CONNECTED"
                        }
                        val proxyLabel = match.targetProxy?.name ?: when (match.action) {
                            RuleAction.DIRECT -> "直连 (DIRECT)"
                            RuleAction.REJECT -> "拦截 (REJECT)"
                            else -> "未指定"
                        }

                        val pkgs = SocketUidResolver.getPackagesForPort(applicationContext, parsedPacket.srcPort, parsedPacket.destIp, parsedPacket.destPort)
                        val mainPkg = pkgs.firstOrNull() ?: ""
                        val appLabel = if (pkgs.isNotEmpty()) SocketUidResolver.getAppNameForPackage(applicationContext, mainPkg) else ""
                        val pknHex = bytesToHex(buffer, readLen)
                        val proto = parsedPacket.protocol

                        db.connectionLogDao().insertLog(
                            ConnectionLog(
                                appName = appLabel,
                                packageName = mainPkg,
                                destHost = parsedPacket.destIp,
                                destPort = parsedPacket.destPort,
                                actionApplied = "${match.action} [${match.ruleName}]",
                                proxyNameUsed = proxyLabel,
                                bytesUploaded = readLen.toLong(),
                                bytesDownloaded = (readLen * 1.2).toLong(),
                                status = statusStr,
                                packetHex = pknHex,
                                handshakeDetail = "VPN TUN | Rule: ${match.ruleName} | Target: $proxyLabel",
                                protocol = proto
                            )
                        )
                    }
                }
            } finally {
                speedJob.cancel()
            }
        }
    }

    private fun handleDnsUdpPacket(
        packet: ParsedPacket,
        buffer: ByteArray,
        readLen: Int,
        outStream: FileOutputStream
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val ihl = (buffer[0].toInt() and 0x0F) * 4
                val dnsPayloadOffset = ihl + 8
                if (readLen <= dnsPayloadOffset) return@launch

                val dnsPayloadLen = readLen - dnsPayloadOffset
                val dnsPayload = buffer.copyOfRange(dnsPayloadOffset, readLen)

                val targetDns = if (packet.destIp.isNotEmpty() && packet.destIp != "0.0.0.0") packet.destIp else "1.1.1.1"

                val ds = DatagramSocket()
                protect(ds)
                ds.soTimeout = 2500

                val sendDp = DatagramPacket(
                    dnsPayload,
                    dnsPayloadLen,
                    InetAddress.getByName(targetDns),
                    if (packet.destPort > 0) packet.destPort else 53
                )
                ds.send(sendDp)

                val recvBuf = ByteArray(2048)
                val recvDp = DatagramPacket(recvBuf, recvBuf.size)
                ds.receive(recvDp)
                ds.close()

                val respPayloadLen = recvDp.length
                val respPayload = recvBuf.copyOfRange(0, respPayloadLen)

                val totalPacketLen = 20 + 8 + respPayloadLen
                val respBuffer = ByteArray(totalPacketLen)

                respBuffer[0] = 0x45.toByte() // IPv4, IHL 5
                respBuffer[1] = 0x00.toByte()
                respBuffer[2] = ((totalPacketLen ushr 8) and 0xFF).toByte()
                respBuffer[3] = (totalPacketLen and 0xFF).toByte()
                respBuffer[4] = 0x12.toByte()
                respBuffer[5] = 0x34.toByte()
                respBuffer[6] = 0x00.toByte()
                respBuffer[7] = 0x00.toByte()
                respBuffer[8] = 64.toByte()   // TTL
                respBuffer[9] = 17.toByte()   // UDP = 17
                respBuffer[10] = 0x00.toByte()
                respBuffer[11] = 0x00.toByte()

                val srcIpBytes = InetAddress.getByName(targetDns).address
                System.arraycopy(srcIpBytes, 0, respBuffer, 12, 4)

                val destIpBytes = InetAddress.getByName(packet.srcIp).address
                System.arraycopy(destIpBytes, 0, respBuffer, 16, 4)

                val ipChecksum = calculateIpChecksum(respBuffer, 0, 20)
                respBuffer[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
                respBuffer[11] = (ipChecksum and 0xFF).toByte()

                val srcPort = if (packet.destPort > 0) packet.destPort else 53
                val destPort = packet.srcPort
                val udpLen = 8 + respPayloadLen

                respBuffer[20] = ((srcPort ushr 8) and 0xFF).toByte()
                respBuffer[21] = (srcPort and 0xFF).toByte()
                respBuffer[22] = ((destPort ushr 8) and 0xFF).toByte()
                respBuffer[23] = (destPort and 0xFF).toByte()
                respBuffer[24] = ((udpLen ushr 8) and 0xFF).toByte()
                respBuffer[25] = (udpLen and 0xFF).toByte()
                respBuffer[26] = 0x00.toByte()
                respBuffer[27] = 0x00.toByte()

                System.arraycopy(respPayload, 0, respBuffer, 28, respPayloadLen)

                synchronized(outStream) {
                    outStream.write(respBuffer)
                    outStream.flush()
                }
            } catch (e: Exception) {
                // Ignore transient timeout
            }
        }
    }

    private val activeTcpSessions = ConcurrentHashMap<String, TcpRelaySession>()

    private class TcpRelaySession(
        val key: String,
        val socket: Socket,
        var clientSeq: Long,
        var mySeq: Long,
        val srcIp: String,
        val destIp: String,
        val srcPort: Int,
        val destPort: Int,
        var lastClientAck: Long = 0L
    )

    private fun handleTcpPacket(
        packet: ParsedPacket,
        buffer: ByteArray,
        readLen: Int,
        outStream: FileOutputStream,
        match: MatchResult
    ) {
        val ihl = (buffer[0].toInt() and 0x0F) * 4
        if (readLen < ihl + 20) return

        val tcpFlags = buffer[ihl + 13].toInt() and 0xFF
        val clientSeq = getUint32(buffer, ihl + 4)
        val tcpHeaderLen = ((buffer[ihl + 12].toInt() and 0xF0) ushr 4) * 4
        val payloadOffset = ihl + tcpHeaderLen
        val payloadLen = (readLen - payloadOffset).coerceAtLeast(0)

        val key = "${packet.srcIp}:${packet.srcPort}->${packet.destIp}:${packet.destPort}"

        val isSyn = (tcpFlags and 0x02) != 0
        val isAck = (tcpFlags and 0x10) != 0
        val isFin = (tcpFlags and 0x01) != 0
        val isRst = (tcpFlags and 0x04) != 0

        if (isSyn && !isAck) {
            if (activeTcpSessions.containsKey(key)) return

            serviceScope.launch(Dispatchers.IO) {
                val socket = Socket()
                protect(socket)

                val connected = when (match.action) {
                    RuleAction.REJECT -> false
                    RuleAction.DIRECT -> {
                        try {
                            socket.connect(InetSocketAddress(packet.destIp, packet.destPort), 5000)
                            true
                        } catch (e: Exception) {
                            false
                        }
                    }
                    RuleAction.PROXY -> {
                        val proxy = match.targetProxy
                        if (proxy != null) {
                            try {
                                socket.connect(InetSocketAddress(proxy.cleanHost, proxy.port), 5000)
                                performProxyHandshake(socket, proxy, packet.destIp, packet.destPort)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                false
                            }
                        } else {
                            false
                        }
                    }
                }

                if (connected) {
                    val session = TcpRelaySession(
                        key = key,
                        socket = socket,
                        clientSeq = clientSeq,
                        mySeq = 2000L + (1..10000).random(),
                        srcIp = packet.srcIp,
                        destIp = packet.destIp,
                        srcPort = packet.srcPort,
                        destPort = packet.destPort,
                        lastClientAck = clientSeq + 1
                    )
                    activeTcpSessions[key] = session

                    val synAckPacket = buildTcpIpPacket(
                        srcIp = packet.destIp,
                        destIp = packet.srcIp,
                        srcPort = packet.destPort,
                        destPort = packet.srcPort,
                        seq = session.mySeq,
                        ack = session.lastClientAck,
                        flags = 0x12
                    )
                    session.mySeq += 1

                    synchronized(outStream) {
                        runCatching {
                            outStream.write(synAckPacket)
                            outStream.flush()
                        }
                    }

                    startSocketToTunRelay(session, outStream)
                } else {
                    val rstPacket = buildTcpIpPacket(
                        srcIp = packet.destIp,
                        destIp = packet.srcIp,
                        srcPort = packet.destPort,
                        destPort = packet.srcPort,
                        seq = 0,
                        ack = clientSeq + 1,
                        flags = 0x14
                    )
                    synchronized(outStream) {
                        runCatching {
                            outStream.write(rstPacket)
                            outStream.flush()
                        }
                    }
                }
            }
        } else if (isFin || isRst) {
            val session = activeTcpSessions.remove(key)
            session?.socket?.let { runCatching { it.close() } }
        } else if (payloadLen > 0) {
            val session = activeTcpSessions[key]
            if (session != null && session.socket.isConnected && !session.socket.isClosed) {
                val payloadBytes = buffer.copyOfRange(payloadOffset, payloadOffset + payloadLen)
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        session.socket.getOutputStream().write(payloadBytes)
                        session.socket.getOutputStream().flush()

                        session.lastClientAck = clientSeq + payloadLen
                        val ackPacket = buildTcpIpPacket(
                            srcIp = packet.destIp,
                            destIp = packet.srcIp,
                            srcPort = packet.destPort,
                            destPort = packet.srcPort,
                            seq = session.mySeq,
                            ack = session.lastClientAck,
                            flags = 0x10
                        )
                        synchronized(outStream) {
                            outStream.write(ackPacket)
                            outStream.flush()
                        }
                    } catch (e: Exception) {
                        activeTcpSessions.remove(key)
                        runCatching { session.socket.close() }
                    }
                }
            }
        }
    }

    private fun startSocketToTunRelay(session: TcpRelaySession, outStream: FileOutputStream) {
        serviceScope.launch(Dispatchers.IO) {
            val recvBuf = ByteArray(16384)
            try {
                val inStream = session.socket.getInputStream()
                while (session.socket.isConnected && !session.socket.isClosed) {
                    val r = inStream.read(recvBuf)
                    if (r <= 0) break
                    val payload = recvBuf.copyOfRange(0, r)
                    val dataPacket = buildTcpIpPacket(
                        srcIp = session.destIp,
                        destIp = session.srcIp,
                        srcPort = session.destPort,
                        destPort = session.srcPort,
                        seq = session.mySeq,
                        ack = session.lastClientAck,
                        flags = 0x18,
                        payload = payload
                    )
                    session.mySeq += r
                    synchronized(outStream) {
                        outStream.write(dataPacket)
                        outStream.flush()
                    }
                }
            } catch (e: Exception) {
                // Closed
            } finally {
                activeTcpSessions.remove(session.key)
                runCatching { session.socket.close() }
            }
        }
    }

    private fun performProxyHandshake(
        socket: Socket,
        proxy: ProxyServer,
        destIp: String,
        destPort: Int,
        timeoutMs: Int = 5000
    ): Boolean {
        return if (proxy.type == ProxyType.HTTP) {
            performHttpProxyHandshake(socket, proxy, destIp, destPort, timeoutMs)
        } else {
            performSocks5Handshake(socket, proxy, destIp, destPort, timeoutMs)
        }
    }

    private fun performSocks5Handshake(
        socket: Socket,
        proxy: ProxyServer,
        destIp: String,
        destPort: Int,
        timeoutMs: Int = 5000
    ): Boolean {
        return try {
            socket.soTimeout = timeoutMs
            val out = socket.getOutputStream()
            val inStream = socket.getInputStream()

            val hasAuth = !proxy.username.isNullOrEmpty()
            if (hasAuth) {
                out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            } else {
                out.write(byteArrayOf(0x05, 0x01, 0x00))
            }
            out.flush()

            val resp = ByteArray(2)
            if (inStream.read(resp) < 2 || resp[0] != 0x05.toByte()) {
                return false
            }

            if (resp[1] == 0x02.toByte()) {
                val user = proxy.username!!.toByteArray()
                val pass = (proxy.password ?: "").toByteArray()
                val authReq = ByteArray(3 + user.size + pass.size)
                authReq[0] = 0x01
                authReq[1] = user.size.toByte()
                System.arraycopy(user, 0, authReq, 2, user.size)
                authReq[2 + user.size] = pass.size.toByte()
                System.arraycopy(pass, 0, authReq, 3 + user.size, pass.size)

                out.write(authReq)
                out.flush()

                val authResp = ByteArray(2)
                if (inStream.read(authResp) < 2 || authResp[1] != 0x00.toByte()) {
                    return false
                }
            } else if (resp[1] != 0x00.toByte()) {
                return false
            }

            val isIpv4 = destIp.contains(".")
            val isIpv6 = destIp.contains(":")

            val connReq: ByteArray
            if (isIpv4) {
                val ipBytes = InetAddress.getByName(destIp).address
                connReq = ByteArray(10)
                connReq[0] = 0x05
                connReq[1] = 0x01
                connReq[2] = 0x00
                connReq[3] = 0x01
                System.arraycopy(ipBytes, 0, connReq, 4, 4)
                connReq[8] = ((destPort ushr 8) and 0xFF).toByte()
                connReq[9] = (destPort and 0xFF).toByte()
            } else if (isIpv6) {
                val ipBytes = InetAddress.getByName(destIp).address
                connReq = ByteArray(22)
                connReq[0] = 0x05
                connReq[1] = 0x01
                connReq[2] = 0x00
                connReq[3] = 0x04
                System.arraycopy(ipBytes, 0, connReq, 4, 16)
                connReq[20] = ((destPort ushr 8) and 0xFF).toByte()
                connReq[21] = (destPort and 0xFF).toByte()
            } else {
                val domainBytes = destIp.toByteArray()
                connReq = ByteArray(7 + domainBytes.size)
                connReq[0] = 0x05
                connReq[1] = 0x01
                connReq[2] = 0x00
                connReq[3] = 0x03
                connReq[4] = domainBytes.size.toByte()
                System.arraycopy(domainBytes, 0, connReq, 5, domainBytes.size)
                connReq[5 + domainBytes.size] = ((destPort ushr 8) and 0xFF).toByte()
                connReq[6 + domainBytes.size] = (destPort and 0xFF).toByte()
            }

            out.write(connReq)
            out.flush()

            val connResp = ByteArray(10)
            val readLen = inStream.read(connResp)
            readLen >= 4 && connResp[1] == 0x00.toByte()
        } catch (e: Exception) {
            false
        }
    }

    private fun performHttpProxyHandshake(
        socket: Socket,
        proxy: ProxyServer,
        destIp: String,
        destPort: Int,
        timeoutMs: Int = 5000
    ): Boolean {
        return try {
            socket.soTimeout = timeoutMs
            val out = socket.getOutputStream()
            val inStream = socket.getInputStream()

            val reqBuilder = StringBuilder()
            reqBuilder.append("CONNECT $destIp:$destPort HTTP/1.1\r\n")
            reqBuilder.append("Host: $destIp:$destPort\r\n")

            if (!proxy.username.isNullOrEmpty()) {
                val userPass = "${proxy.username}:${proxy.password ?: ""}"
                val encoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    java.util.Base64.getEncoder().encodeToString(userPass.toByteArray())
                } else {
                    android.util.Base64.encodeToString(userPass.toByteArray(), android.util.Base64.NO_WRAP)
                }
                reqBuilder.append("Proxy-Authorization: Basic $encoded\r\n")
            }
            reqBuilder.append("User-Agent: ProxifierAndroid/1.0\r\n")
            reqBuilder.append("\r\n")

            out.write(reqBuilder.toString().toByteArray())
            out.flush()

            val reader = java.io.BufferedReader(java.io.InputStreamReader(inStream))
            val statusLine = reader.readLine() ?: return false
            statusLine.contains("200")
        } catch (e: Exception) {
            false
        }
    }

    private fun buildTcpIpPacket(
        srcIp: String,
        destIp: String,
        srcPort: Int,
        destPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray? = null
    ): ByteArray {
        val payloadBytes = payload ?: ByteArray(0)
        val ipHeaderLen = 20
        val tcpHeaderLen = 20
        val totalLen = ipHeaderLen + tcpHeaderLen + payloadBytes.size

        val packet = ByteArray(totalLen)

        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        packet[2] = ((totalLen ushr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0x12.toByte()
        packet[5] = 0x34.toByte()
        packet[6] = 0x40.toByte()
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()
        packet[9] = 6.toByte()
        packet[10] = 0.toByte()
        packet[11] = 0.toByte()

        val srcIpBytes = InetAddress.getByName(srcIp).address
        val destIpBytes = InetAddress.getByName(destIp).address
        System.arraycopy(srcIpBytes, 0, packet, 12, 4)
        System.arraycopy(destIpBytes, 0, packet, 16, 4)

        val ipChecksum = calculateIpChecksum(packet, 0, 20)
        packet[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        val tcpOffset = ipHeaderLen
        packet[tcpOffset] = ((srcPort ushr 8) and 0xFF).toByte()
        packet[tcpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[tcpOffset + 2] = ((destPort ushr 8) and 0xFF).toByte()
        packet[tcpOffset + 3] = (destPort and 0xFF).toByte()

        packet[tcpOffset + 4] = ((seq ushr 24) and 0xFF).toByte()
        packet[tcpOffset + 5] = ((seq ushr 16) and 0xFF).toByte()
        packet[tcpOffset + 6] = ((seq ushr 8) and 0xFF).toByte()
        packet[tcpOffset + 7] = (seq and 0xFF).toByte()

        packet[tcpOffset + 8] = ((ack ushr 24) and 0xFF).toByte()
        packet[tcpOffset + 9] = ((ack ushr 16) and 0xFF).toByte()
        packet[tcpOffset + 10] = ((ack ushr 8) and 0xFF).toByte()
        packet[tcpOffset + 11] = (ack and 0xFF).toByte()

        packet[tcpOffset + 12] = 0x50.toByte()
        packet[tcpOffset + 13] = flags.toByte()

        packet[tcpOffset + 14] = 0xFF.toByte()
        packet[tcpOffset + 15] = 0xFF.toByte()

        packet[tcpOffset + 16] = 0.toByte()
        packet[tcpOffset + 17] = 0.toByte()
        packet[tcpOffset + 18] = 0.toByte()
        packet[tcpOffset + 19] = 0.toByte()

        if (payloadBytes.isNotEmpty()) {
            System.arraycopy(payloadBytes, 0, packet, ipHeaderLen + tcpHeaderLen, payloadBytes.size)
        }

        val tcpLen = tcpHeaderLen + payloadBytes.size
        val tcpAndPayloadBytes = packet.copyOfRange(tcpOffset, totalLen)
        val tcpChecksum = calculateTcpChecksum(srcIpBytes, destIpBytes, tcpAndPayloadBytes, tcpLen)

        packet[tcpOffset + 16] = ((tcpChecksum ushr 8) and 0xFF).toByte()
        packet[tcpOffset + 17] = (tcpChecksum and 0xFF).toByte()

        return packet
    }

    private fun calculateTcpChecksum(
        srcIpBytes: ByteArray,
        destIpBytes: ByteArray,
        tcpAndPayloadBytes: ByteArray,
        tcpLen: Int
    ): Int {
        var sum = 0L
        for (i in 0..1) {
            val wordSrc = ((srcIpBytes[i * 2].toInt() and 0xFF) shl 8) or (srcIpBytes[i * 2 + 1].toInt() and 0xFF)
            val wordDst = ((destIpBytes[i * 2].toInt() and 0xFF) shl 8) or (destIpBytes[i * 2 + 1].toInt() and 0xFF)
            sum += wordSrc + wordDst
        }
        sum += 6L
        sum += tcpLen.toLong()

        var i = 0
        while (i < tcpLen - 1) {
            val word = ((tcpAndPayloadBytes[i].toInt() and 0xFF) shl 8) or (tcpAndPayloadBytes[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (tcpLen % 2 != 0) {
            sum += (tcpAndPayloadBytes[tcpLen - 1].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun getUint32(buffer: ByteArray, offset: Int): Long {
        return ((buffer[offset].toLong() and 0xFF) shl 24) or
               ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
               ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
               (buffer[offset + 3].toLong() and 0xFF)
    }

    private fun calculateIpChecksum(buffer: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length) {
            val word = ((buffer[i].toInt() and 0xFF) shl 8) or (buffer[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while (sum ushr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private data class ParsedPacket(
        val version: Int,
        val protocol: String,
        val srcIp: String,
        val destIp: String,
        val srcPort: Int,
        val destPort: Int,
        val length: Int
    )

    private fun parseIpPacket(buffer: ByteArray, length: Int): ParsedPacket? {
        if (length < 20) return null
        val version = (buffer[0].toInt() and 0xF0) ushr 4
        if (version == 4) {
            val ihl = (buffer[0].toInt() and 0x0F) * 4
            if (length < ihl) return null
            val protocolNum = buffer[9].toInt() and 0xFF
            val protocolStr = when (protocolNum) {
                6 -> "TCP"
                17 -> "UDP"
                1 -> "ICMP"
                else -> "IP($protocolNum)"
            }
            val srcIp = "${buffer[12].toInt() and 0xFF}.${buffer[13].toInt() and 0xFF}.${buffer[14].toInt() and 0xFF}.${buffer[15].toInt() and 0xFF}"
            val destIp = "${buffer[16].toInt() and 0xFF}.${buffer[17].toInt() and 0xFF}.${buffer[18].toInt() and 0xFF}.${buffer[19].toInt() and 0xFF}"

            var srcPort = 0
            var destPort = 0
            if ((protocolNum == 6 || protocolNum == 17) && length >= ihl + 4) {
                srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
                destPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)
            }
            return ParsedPacket(4, protocolStr, srcIp, destIp, srcPort, destPort, length)
        } else if (version == 6) {
            if (length < 40) return null
            val nextHeader = buffer[6].toInt() and 0xFF
            val protocolStr = when (nextHeader) {
                6 -> "TCP"
                17 -> "UDP"
                58 -> "ICMPv6"
                else -> "IPv6($nextHeader)"
            }
            val srcBytes = buffer.copyOfRange(8, 24)
            val destBytes = buffer.copyOfRange(24, 40)
            val srcIp = formatIPv6(srcBytes)
            val destIp = formatIPv6(destBytes)

            var srcPort = 0
            var destPort = 0
            if ((nextHeader == 6 || nextHeader == 17) && length >= 44) {
                srcPort = ((buffer[40].toInt() and 0xFF) shl 8) or (buffer[41].toInt() and 0xFF)
                destPort = ((buffer[42].toInt() and 0xFF) shl 8) or (buffer[43].toInt() and 0xFF)
            }
            return ParsedPacket(6, protocolStr, srcIp, destIp, srcPort, destPort, length)
        }
        return null
    }

    private fun formatIPv6(bytes: ByteArray): String {
        val parts = IntArray(8)
        for (i in 0 until 8) {
            parts[i] = ((bytes[i * 2].toInt() and 0xFF) shl 8) or (bytes[i * 2 + 1].toInt() and 0xFF)
        }
        return parts.joinToString(":") { Integer.toHexString(it) }
    }

    private data class MatchResult(
        val action: RuleAction,
        val ruleName: String,
        val targetProxy: ProxyServer?
    )

    private fun evaluatePacketRules(
        packet: ParsedPacket,
        rules: List<ProxyRule>,
        allProxies: List<ProxyServer>,
        defaultActiveProxy: ProxyServer?
    ): MatchResult {
        for (rule in rules) {
            val hasPorts = rule.targetPorts.isNotEmpty()
            val hasIps = rule.targetIps.isNotEmpty()
            val hasDomains = rule.targetDomains.isNotEmpty()
            val hasPackages = rule.targetPackages.isNotEmpty()

            val hasAnyFilter = hasPorts || hasIps || hasDomains || hasPackages

            // If a rule has NO filters defined (e.g., a catch-all "Direct" rule), it matches everything
            if (!hasAnyFilter) {
                val assignedProxy = if (rule.action == RuleAction.PROXY) {
                    if (rule.targetProxyId != null) {
                        allProxies.find { it.id == rule.targetProxyId } ?: defaultActiveProxy
                    } else {
                        defaultActiveProxy
                    }
                } else null

                return MatchResult(
                    action = rule.action,
                    ruleName = rule.name,
                    targetProxy = assignedProxy
                )
            }

            val portOk = !hasPorts || rule.targetPorts.any { pStr ->
                if (pStr.contains("-")) {
                    val range = pStr.split("-")
                    val start = range.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                    val end = range.getOrNull(1)?.trim()?.toIntOrNull() ?: 65535
                    packet.destPort in start..end
                } else {
                    pStr.trim().toIntOrNull() == packet.destPort
                }
            }

            val matchedIp = hasIps && rule.targetIps.any { ipPattern ->
                matchCidrOrIp(packet.destIp, ipPattern)
            }

            val matchedDomain = hasDomains && rule.targetDomains.any { dom ->
                val cleanDom = dom.trim().lowercase().removePrefix("*.")
                packet.destIp.lowercase().endsWith(cleanDom) || cleanDom == "all" || cleanDom == "*"
            }

            val appPkgs = SocketUidResolver.getPackagesForPort(applicationContext, packet.srcPort, packet.destIp, packet.destPort)
            val matchedPackage = hasPackages && appPkgs.any { pkg -> rule.targetPackages.contains(pkg) }

            val targetMatch = if (hasDomains || hasIps || hasPackages) {
                matchedDomain || matchedIp || matchedPackage
            } else {
                true // Catch-all rule
            }

            val isMatch = portOk && targetMatch

            if (isMatch) {
                val assignedProxy = if (rule.action == RuleAction.PROXY) {
                    if (rule.targetProxyId != null) {
                        allProxies.find { it.id == rule.targetProxyId } ?: defaultActiveProxy
                    } else {
                        defaultActiveProxy
                    }
                } else null

                if (rule.action == RuleAction.PROXY && rule.ignoreIfProxyDown) {
                    val isProxyAlive = assignedProxy != null && assignedProxy.cleanHost.isNotBlank() && assignedProxy.port > 0 && assignedProxy.isOnline != false
                    if (!isProxyAlive) {
                        continue // Proxy is down/invalid, bypass rule
                    }
                }

                return MatchResult(
                    action = rule.action,
                    ruleName = rule.name,
                    targetProxy = assignedProxy
                )
            }
        }

        // Default fallback match when no user rule matches -> DIRECT (直连)
        return MatchResult(
            action = RuleAction.DIRECT,
            ruleName = "默认直连 (DIRECT)",
            targetProxy = null
        )
    }

    private fun matchCidrOrIp(ipStr: String, patternStr: String): Boolean {
        val cleanPattern = patternStr.trim()
        val cleanIp = ipStr.trim()
        if (cleanPattern.isEmpty() || cleanIp.isEmpty()) return false

        if (cleanPattern.contains("/")) {
            val parts = cleanPattern.split("/")
            val baseIp = parts[0].trim()
            val prefixLen = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 32

            if (cleanIp.contains(".") && baseIp.contains(".")) {
                val ipInt = ipTo4Int(cleanIp)
                val baseInt = ipTo4Int(baseIp)
                if (ipInt != null && baseInt != null) {
                    if (prefixLen == 0) return true
                    val mask = if (prefixLen >= 32) -1 else (-1 shl (32 - prefixLen))
                    return (ipInt and mask) == (baseInt and mask)
                }
            } else if (cleanIp.contains(":") || baseIp.contains(":")) {
                val cleanBase = baseIp.removePrefix("[").removeSuffix("]").lowercase()
                val cleanTarget = cleanIp.removePrefix("[").removeSuffix("]").lowercase()
                val prefixBlocks = (prefixLen / 16).coerceIn(1, 8)
                val targetParts = cleanTarget.split(":")
                val baseParts = cleanBase.split(":")
                if (targetParts.size >= prefixBlocks && baseParts.size >= prefixBlocks) {
                    for (i in 0 until prefixBlocks) {
                        if (!targetParts[i].equals(baseParts[i], ignoreCase = true)) return false
                    }
                    return true
                }
            }
        } else {
            val p1 = cleanPattern.removePrefix("[").removeSuffix("]").lowercase()
            val p2 = cleanIp.removePrefix("[").removeSuffix("]").lowercase()
            if (p1 == p2) return true
            if (cleanPattern.contains(".") && cleanIp.contains(".")) {
                val ipInt = ipTo4Int(p2)
                val baseInt = ipTo4Int(p1)
                if (ipInt != null && baseInt != null && ipInt == baseInt) return true
            }
        }
        return false
    }

    private fun ipTo4Int(ip: String): Int? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        return try {
            var result = 0
            for (i in 0..3) {
                val b = parts[i].toInt()
                if (b !in 0..255) return null
                result = (result shl 8) or b
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    private fun stopVpn() {
        _isRunning.value = false
        com.yangyx.proxy.service.ProxyTileService.requestTileUpdate(applicationContext)
        trafficJob?.cancel()
        _bytesUpSec.value = 0
        _bytesDownSec.value = 0
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Proxifier Core Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows SOCKS5 proxy routing status"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun bytesToHex(bytes: ByteArray, len: Int): String {
        val maxLen = len.coerceAtMost(256)
        val sb = java.lang.StringBuilder()
        sb.append("[Hex Payload - ").append(len).append(" B]\n")
        for (i in 0 until maxLen) {
            sb.append(String.format("%02X ", bytes[i]))
            if ((i + 1) % 16 == 0 && i + 1 < maxLen) {
                sb.append("\n")
            }
        }
        if (len > maxLen) sb.append("\n... (${len - maxLen} more bytes)")
        
        sb.append("\n\n[ASCII Preview]\n")
        val asciiLen = len.coerceAtMost(128)
        for (i in 0 until asciiLen) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 32..126) {
                sb.append(b.toChar())
            } else {
                sb.append('.')
            }
        }
        if (len > asciiLen) sb.append("...")
        return sb.toString()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
