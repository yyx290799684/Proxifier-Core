package com.yangyx.proxy.engine

import android.os.Build
import android.system.Os
import com.yangyx.proxy.data.model.ConnectionLog
import com.yangyx.proxy.data.model.ProxyRule
import com.yangyx.proxy.data.model.ProxyServer
import com.yangyx.proxy.data.model.ProxyType
import com.yangyx.proxy.data.model.RuleAction
import com.yangyx.proxy.data.model.cleanHost
import com.yangyx.proxy.data.model.isOnline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong

object LocalTransparentProxyServer {

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    var activeProxy: ProxyServer? = null
    var allProxiesList: List<ProxyServer> = emptyList()
    var activeRules: List<ProxyRule> = emptyList()
    var onLogCallback: ((ConnectionLog) -> Unit)? = null

    val bytesUpSec = MutableStateFlow(0L)
    val bytesDownSec = MutableStateFlow(0L)
    val totalBytesUp = MutableStateFlow(0L)
    val totalBytesDown = MutableStateFlow(0L)

    private val currentUp = AtomicLong(0)
    private val currentDown = AtomicLong(0)

    var appContext: android.content.Context? = null

    fun clearLastError() {
        _lastError.value = null
    }

    fun start(
        context: android.content.Context? = null,
        port: Int = 1080,
        proxy: ProxyServer?,
        proxies: List<ProxyServer> = emptyList(),
        rules: List<ProxyRule>,
        onLog: (ConnectionLog) -> Unit
    ) {
        stop()
        _lastError.value = null

        bypassHiddenApiRestrictions()
        appContext = context?.applicationContext
        activeProxy = proxy
        allProxiesList = proxies
        activeRules = rules
        onLogCallback = onLog

        val oldJob = serverJob
        serverJob = scope.launch {
            oldJob?.cancelAndJoin()

            try {
                // Ensure old socket is completely closed before binding
                runCatching { serverSocket?.close() }
                serverSocket = null

                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", port), 128)
                serverSocket = socket

                _isRunning.value = true
                appContext?.let { com.yangyx.proxy.service.ProxyTileService.requestTileUpdate(it) }

                // Speed monitoring loop
                launch {
                    while (_isRunning.value) {
                        val up = currentUp.getAndSet(0)
                        val down = currentDown.getAndSet(0)
                        bytesUpSec.value = up
                        bytesDownSec.value = down
                        totalBytesUp.value += up
                        totalBytesDown.value += down
                        kotlinx.coroutines.delay(1000)
                    }
                }

                while (_isRunning.value && serverSocket?.isClosed == false) {
                    val clientSocket = try {
                        serverSocket?.accept() ?: break
                    } catch (e: Exception) {
                        if (!_isRunning.value || e is CancellationException || (e is SocketException && e.message?.contains("closed", ignoreCase = true) == true)) {
                            break
                        } else {
                            throw e
                        }
                    }
                    launch {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException && _isRunning.value) {
                    val isClosedSocket = e is SocketException && e.message?.contains("closed", ignoreCase = true) == true
                    if (!isClosedSocket) {
                        e.printStackTrace()
                        _lastError.value = "透明代理监听服务启动失败 (${e.javaClass.simpleName}): ${e.localizedMessage ?: e.message}"
                    }
                }
            } finally {
                _isRunning.value = false
                runCatching { serverSocket?.close() }
                serverSocket = null
                appContext?.let { com.yangyx.proxy.service.ProxyTileService.requestTileUpdate(it) }
            }
        }
    }

    fun stop() {
        _isRunning.value = false
        appContext?.let { com.yangyx.proxy.service.ProxyTileService.requestTileUpdate(it) }
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun handleClient(clientSocket: Socket) {
        try {
            clientSocket.soTimeout = 10000

            val clientPort = clientSocket.port
            val originalDst = getOriginalDestination(clientSocket)
            val destIp = originalDst?.hostString ?: ""
            val originalPort = originalDst?.port ?: 0

            val inStream = clientSocket.getInputStream()

            val buffer = ByteArray(2048)
            val readLen = inStream.read(buffer)
            if (readLen <= 0) {
                clientSocket.close()
                return
            }

            val ctx = appContext
            val appPackages = if (ctx != null) SocketUidResolver.getPackagesForPort(ctx, clientPort, destIp, originalPort) else emptyList()
            val mainPackage = appPackages.firstOrNull() ?: ""
            val displayAppName = if (ctx != null && appPackages.isNotEmpty()) SocketUidResolver.getAppNameForPackage(ctx, mainPackage) else ""

            val parsedSni = parseSniOrHost(buffer, readLen, originalPort)
            val sniDomain = parsedSni?.first
            val destPort = parsedSni?.second ?: originalPort

            val destHost = if (!sniDomain.isNullOrBlank()) sniDomain else destIp

            val packetHex = bytesToHex(buffer, readLen)
            val protocolName = when {
                readLen >= 3 && buffer[0] == 0x03.toByte() && buffer[1] == 0x00.toByte() -> "TPKT"
                readLen >= 5 && buffer[0] == 0x16.toByte() && buffer[1] == 0x03.toByte() -> "TLS / HTTPS"
                readLen >= 4 && (buffer[0] == 'G'.toByte() || buffer[0] == 'P'.toByte() || buffer[0] == 'C'.toByte()) -> "HTTP"
                else -> "TCP"
            }

            if (destHost.isBlank() || destHost == "127.0.0.1" || destHost == "0.0.0.0") {
                val fdObj = getSocketFd(clientSocket)
                val diagDetail = buildString {
                    append("SO_ORIGINAL_DST: ")
                    if (originalDst != null) {
                        append("${originalDst.hostString}:${originalDst.port}")
                    } else {
                        append("NULL/Failed")
                    }
                    append(" | FD: ")
                    if (fdObj != null && fdObj.valid()) {
                        append("Valid")
                    } else {
                        append("NULL(${clientSocket.javaClass.simpleName})")
                    }
                    append(" | ClientPort: $clientPort")
                    append(" | ReadLen: $readLen B")
                    append(" | SNI: ${sniDomain ?: "None"}")
                }

                onLogCallback?.invoke(
                    ConnectionLog(
                        appName = displayAppName,
                        packageName = mainPackage,
                        destHost = if (destHost.isNotBlank()) destHost else "Unknown",
                        destPort = destPort,
                        actionApplied = "NONE",
                        proxyNameUsed = null,
                        status = "FAILED_HANDSHAKE",
                        packetHex = packetHex,
                        handshakeDetail = diagDetail,
                        protocol = protocolName,
                        detailError = "Cannot resolve original destination IP for redirected socket (SO_ORIGINAL_DST returned empty and conntrack has no mapping for client port $clientPort)"
                    )
                )
                clientSocket.close()
                return
            }

            val ruleMatch = matchRule(destHost, destIp, destPort, appPackages)
            val actionName = when (ruleMatch.action) {
                RuleAction.DIRECT -> "DIRECT"
                RuleAction.REJECT -> "REJECT"
                RuleAction.PROXY -> "PROXY (${ruleMatch.targetProxy?.name ?: "Default"})"
            }

            val targetProxy = if (ruleMatch.action == RuleAction.PROXY) (ruleMatch.targetProxy ?: activeProxy) else null
            val handshakeLog = "SO_ORIGINAL_DST: $destIp:$originalPort | Protocol: $protocolName | Proxy: ${targetProxy?.name ?: "Direct"}"

            if (ruleMatch.action == RuleAction.REJECT) {
                onLogCallback?.invoke(
                    ConnectionLog(
                        appName = displayAppName,
                        packageName = mainPackage,
                        destHost = destHost,
                        destPort = destPort,
                        actionApplied = actionName,
                        proxyNameUsed = targetProxy?.name,
                        status = "REJECTED",
                        packetHex = packetHex,
                        handshakeDetail = handshakeLog,
                        protocol = protocolName,
                        detailError = "Blocked by rule: ${ruleMatch.matchedRuleName}"
                    )
                )
                clientSocket.close()
                return
            }

            val targetSocket = Socket()
            val connectHost = if (destIp.isNotBlank() && destIp != "0.0.0.0") destIp else destHost
            var handshakeErrorMsg: String? = null

            val connected = if (ruleMatch.action == RuleAction.DIRECT) {
                try {
                    targetSocket.connect(InetSocketAddress(connectHost, destPort), 8000)
                    true
                } catch (e: Exception) {
                    handshakeErrorMsg = "Direct connect failed: ${e.localizedMessage}"
                    false
                }
            } else {
                val proxy = targetProxy
                if (proxy != null) {
                    try {
                        targetSocket.connect(InetSocketAddress(proxy.cleanHost, proxy.port), 8000)
                        val ok = performProxyHandshake(targetSocket, proxy, destHost, destPort)
                        if (ok) {
                            true
                        } else {
                            handshakeErrorMsg = "Proxy handshake failed with ${proxy.type} ${proxy.cleanHost}:${proxy.port}"
                            false
                        }
                    } catch (e: Exception) {
                        handshakeErrorMsg = "Connect proxy ${proxy.cleanHost}:${proxy.port} failed: ${e.localizedMessage}"
                        false
                    }
                } else {
                    handshakeErrorMsg = "No active proxy configured"
                    false
                }
            }

            onLogCallback?.invoke(
                ConnectionLog(
                    appName = displayAppName,
                    packageName = mainPackage,
                    destHost = destHost,
                    destPort = destPort,
                    actionApplied = actionName,
                    proxyNameUsed = targetProxy?.name,
                    status = if (connected) "CONNECTED" else "FAILED_HANDSHAKE",
                    packetHex = packetHex,
                    handshakeDetail = handshakeLog,
                    protocol = protocolName,
                    detailError = handshakeErrorMsg
                )
            )

            if (!connected) {
                clientSocket.close()
                targetSocket.close()
                return
            }

            targetSocket.getOutputStream().write(buffer, 0, readLen)
            targetSocket.getOutputStream().flush()

            clientSocket.soTimeout = 0
            targetSocket.soTimeout = 0

            relaySockets(clientSocket, targetSocket)

        } catch (e: Exception) {
            runCatching { clientSocket.close() }
        }
    }

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

    private fun bypassHiddenApiRestrictions() {
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
            val setHiddenApiExemptions = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            val vmRuntime = getRuntime.invoke(null)
            setHiddenApiExemptions.invoke(vmRuntime, arrayOf("L"))
        } catch (e: Throwable) {
            // Ignored
        }
    }

    private fun getSocketFd(socket: Socket): FileDescriptor? {
        bypassHiddenApiRestrictions()

        // 1. Direct method calls on socket
        for (mName in listOf("getFileDescriptor$", "getFileDescriptor", "getFD$")) {
            runCatching {
                val m = socket.javaClass.getMethod(mName)
                m.isAccessible = true
                val fd = m.invoke(socket) as? FileDescriptor
                if (fd != null && fd.valid()) return fd
            }
            runCatching {
                val m = Socket::class.java.getDeclaredMethod(mName)
                m.isAccessible = true
                val fd = m.invoke(socket) as? FileDescriptor
                if (fd != null && fd.valid()) return fd
            }
        }

        // 2. Search all zero-param methods and fields returning FileDescriptor on socket
        var sClass: Class<*>? = socket.javaClass
        while (sClass != null && sClass != Any::class.java) {
            for (m in sClass.declaredMethods) {
                if (m.returnType == FileDescriptor::class.java && m.parameterCount == 0) {
                    runCatching {
                        m.isAccessible = true
                        val fd = m.invoke(socket) as? FileDescriptor
                        if (fd != null && fd.valid()) return fd
                    }
                }
            }
            for (f in sClass.declaredFields) {
                if (f.type == FileDescriptor::class.java) {
                    runCatching {
                        f.isAccessible = true
                        val fd = f.get(socket) as? FileDescriptor
                        if (fd != null && fd.valid()) return fd
                    }
                }
            }
            sClass = sClass.superclass
        }

        // 3. Search via impl (SocketImpl / NioSocketImpl / PlatformSocketImpl / DelegatingSocketImpl)
        runCatching {
            val implField = Socket::class.java.getDeclaredField("impl")
            implField.isAccessible = true
            val socketImpl = implField.get(socket)
            if (socketImpl != null) {
                var implClass: Class<*>? = socketImpl.javaClass
                while (implClass != null && implClass != Any::class.java) {
                    for (m in implClass.declaredMethods) {
                        if (m.returnType == FileDescriptor::class.java && m.parameterCount == 0) {
                            runCatching {
                                m.isAccessible = true
                                val fd = m.invoke(socketImpl) as? FileDescriptor
                                if (fd != null && fd.valid()) return fd
                            }
                        }
                    }
                    for (f in implClass.declaredFields) {
                        if (f.type == FileDescriptor::class.java) {
                            runCatching {
                                f.isAccessible = true
                                val fd = f.get(socketImpl) as? FileDescriptor
                                if (fd != null && fd.valid()) return fd
                            }
                        }
                    }
                    implClass = implClass.superclass
                }
            }
        }

        // 4. Search via channel (SocketChannel)
        runCatching {
            val channelMethod = socket.javaClass.getMethod("getChannel")
            val channel = channelMethod.invoke(socket)
            if (channel != null) {
                var chClass: Class<*>? = channel.javaClass
                while (chClass != null && chClass != Any::class.java) {
                    for (m in chClass.declaredMethods) {
                        if (m.returnType == FileDescriptor::class.java && m.parameterCount == 0) {
                            runCatching {
                                m.isAccessible = true
                                val fd = m.invoke(channel) as? FileDescriptor
                                if (fd != null && fd.valid()) return fd
                            }
                        }
                    }
                    for (f in chClass.declaredFields) {
                        if (f.type == FileDescriptor::class.java) {
                            runCatching {
                                f.isAccessible = true
                                val fd = f.get(channel) as? FileDescriptor
                                if (fd != null && fd.valid()) return fd
                            }
                        }
                    }
                    chClass = chClass.superclass
                }
            }
        }

        return null
    }

    private fun parseSockaddrBytes(bytes: ByteArray, level: Int): InetSocketAddress? {
        if (bytes.size < 8) return null
        val family = bytes[0].toInt() and 0xFF
        val port = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        if (port !in 1..65535) return null

        var ip: String? = null
        if (family == 2 || level == 0) { // AF_INET (2)
            ip = "${bytes[4].toInt() and 0xFF}.${bytes[5].toInt() and 0xFF}.${bytes[6].toInt() and 0xFF}.${bytes[7].toInt() and 0xFF}"
        } else if (family == 10 || family == 28 || level == 41) { // AF_INET6
            if (bytes.size >= 24 && bytes[18] == 0xFF.toByte() && bytes[19] == 0xFF.toByte()) {
                ip = "${bytes[20].toInt() and 0xFF}.${bytes[21].toInt() and 0xFF}.${bytes[22].toInt() and 0xFF}.${bytes[23].toInt() and 0xFF}"
            } else if (bytes.size >= 24) {
                try {
                    val addrBytes = bytes.sliceArray(8..23)
                    var host = java.net.InetAddress.getByAddress(addrBytes).hostAddress
                    if (host != null && host.startsWith("::ffff:")) host = host.substring(7)
                    ip = host
                } catch (ignored: Exception) {}
            }
        }

        if (!ip.isNullOrBlank() && ip != "0.0.0.0" && ip != "127.0.0.1") {
            return InetSocketAddress(ip, port)
        }
        return null
    }

    private val SOCKADDR_STR_REGEX = Regex("""(?:/)?([0-9a-fA-F.:]+)[,:\s]+(?:port=)?(\d+)""")

    private fun parseAddressObject(res: Any?, level: Int): InetSocketAddress? {
        if (res == null) return null
        if (res is InetSocketAddress) {
            var ip = res.hostString ?: res.address?.hostAddress ?: ""
            if (ip.startsWith("::ffff:")) ip = ip.substring(7)
            val port = res.port
            if (ip.isNotBlank() && ip != "0.0.0.0" && ip != "127.0.0.1") {
                return InetSocketAddress(ip, port)
            }
        }
        if (res is java.net.InetAddress) {
            var ip = res.hostAddress ?: ""
            if (ip.startsWith("::ffff:")) ip = ip.substring(7)
            if (ip.isNotBlank() && ip != "0.0.0.0" && ip != "127.0.0.1") {
                return InetSocketAddress(ip, 0)
            }
        }
        if (res is ByteArray && res.size >= 8) {
            val sa = parseSockaddrBytes(res, level)
            if (sa != null) return sa
        }

        try {
            val clazz = res.javaClass
            var ipStr = ""
            var portVal = 0

            for (name in listOf("ipAddress", "address", "host", "inetAddress")) {
                runCatching {
                    val f = clazz.getDeclaredField(name).apply { isAccessible = true }
                    val v = f.get(res)
                    if (v is java.net.InetAddress) {
                        ipStr = v.hostAddress ?: ""
                    } else if (v is String) {
                        ipStr = v
                    }
                }
                if (ipStr.isNotBlank()) break

                runCatching {
                    val m = clazz.getDeclaredMethod(name).apply { isAccessible = true }
                    val v = m.invoke(res)
                    if (v is java.net.InetAddress) {
                        ipStr = v.hostAddress ?: ""
                    } else if (v is String) {
                        ipStr = v
                    }
                }
                if (ipStr.isNotBlank()) break
            }

            for (name in listOf("port", "getPort")) {
                runCatching {
                    val f = clazz.getDeclaredField(name).apply { isAccessible = true }
                    portVal = (f.get(res) as? Int) ?: 0
                }
                if (portVal > 0) break

                runCatching {
                    val m = clazz.getDeclaredMethod(name).apply { isAccessible = true }
                    portVal = (m.invoke(res) as? Int) ?: 0
                }
                if (portVal > 0) break
            }

            if (ipStr.startsWith("::ffff:")) ipStr = ipStr.substring(7)
            if (ipStr.startsWith("/")) ipStr = ipStr.substring(1)
            if (ipStr.isNotBlank() && ipStr != "0.0.0.0" && ipStr != "127.0.0.1") {
                return InetSocketAddress(ipStr, portVal)
            }
        } catch (ignored: Throwable) {}

        try {
            val str = res.toString()
            val match = SOCKADDR_STR_REGEX.find(str)
            if (match != null) {
                var ip = match.groupValues[1]
                if (ip.startsWith("::ffff:")) ip = ip.substring(7)
                val p = match.groupValues[2].toIntOrNull() ?: 0
                if (ip.isNotBlank() && ip != "0.0.0.0" && ip != "127.0.0.1") {
                    return InetSocketAddress(ip, p)
                }
            }
        } catch (ignored: Throwable) {}

        return null
    }

    private fun tryGetsockoptDestination(fd: FileDescriptor, level: Int, option: Int): InetSocketAddress? {
        val osCandidates = mutableListOf<Any>()
        try { osCandidates.add(Class.forName("android.system.Os")) } catch (ignored: Throwable) {}
        try {
            val libcoreClass = Class.forName("libcore.io.Libcore")
            val osField = libcoreClass.getDeclaredField("os")
            osField.isAccessible = true
            val osObj = osField.get(null)
            if (osObj != null) osCandidates.add(osObj)
        } catch (ignored: Throwable) {}

        for (target in osCandidates) {
            val clazz = if (target is Class<*>) target else target.javaClass
            val instance = if (target is Class<*>) null else target

            for (m in clazz.declaredMethods + clazz.methods) {
                if (!m.name.contains("getsockopt", ignoreCase = true)) continue
                m.isAccessible = true
                try {
                    val types = m.parameterTypes
                    if (types.isEmpty() || types[0] != FileDescriptor::class.java) continue

                    // 1. Signature (FileDescriptor, int, int) -> SocketAddress / InetSocketAddress / StructSockaddr / InetAddress / ByteArray
                    if (types.size == 3 && types[1] == Int::class.javaPrimitiveType && types[2] == Int::class.javaPrimitiveType) {
                        val res = m.invoke(instance, fd, level, option) ?: continue
                        val parsed = parseAddressObject(res, level)
                        if (parsed != null) return parsed
                    }

                    // 2. Signature (FileDescriptor, int, int, int) -> byte[] / object
                    if (types.size == 4 && types[1] == Int::class.javaPrimitiveType && types[2] == Int::class.javaPrimitiveType && types[3] == Int::class.javaPrimitiveType) {
                        val res = m.invoke(instance, fd, level, option, 28)
                        val parsed = parseAddressObject(res, level)
                        if (parsed != null) return parsed
                    }

                    // 3. Signature (FileDescriptor, int, int, byte[]) -> void / int
                    if (types.size == 4 && types[1] == Int::class.javaPrimitiveType && types[2] == Int::class.javaPrimitiveType && types[3] == ByteArray::class.java) {
                        val buf = ByteArray(28)
                        m.invoke(instance, fd, level, option, buf)
                        val parsed = parseAddressObject(buf, level)
                        if (parsed != null) return parsed
                    }
                } catch (ignored: Throwable) {}
            }
        }
        return null
    }

    private fun getOriginalDestination(socket: Socket): InetSocketAddress? {
        bypassHiddenApiRestrictions()
        val fd = getSocketFd(socket)
        var sockOptDst: InetSocketAddress? = null

        if (fd != null && fd.valid()) {
            val SO_ORIGINAL_DST = 80
            val levels = intArrayOf(0, 41) // SOL_IP (0), SOL_IPV6 (41)

            for (level in levels) {
                val dst = tryGetsockoptDestination(fd, level, SO_ORIGINAL_DST)
                if (dst != null) {
                    if (dst.port > 0) return dst
                    sockOptDst = dst
                    break
                }
            }
        }

        val conntrackDst = getConntrackOriginalDst(socket.port)
        if (conntrackDst != null) {
            return conntrackDst
        }

        return sockOptDst
    }

    private val CONNTRACK_REGEX = Regex("""src=(\S+)\s+dst=(\S+)\s+sport=(\d+)\s+dport=(\d+)""")

    private fun getConntrackOriginalDst(clientPort: Int): InetSocketAddress? {
        if (clientPort <= 0) return null
        val conntrackFiles = listOf("/proc/net/nf_conntrack", "/proc/net/ip_conntrack")

        for (attempt in 0..2) {
            if (attempt > 0) {
                try { Thread.sleep(15) } catch (ignored: Exception) {}
            }

            for (filePath in conntrackFiles) {
                val file = java.io.File(filePath)
                if (file.exists()) {
                    try {
                        file.useLines { lines ->
                            for (line in lines) {
                                if (!line.contains("sport=$clientPort")) continue
                                val matches = CONNTRACK_REGEX.findAll(line).toList()
                                for (match in matches) {
                                    val srcPort = match.groupValues[3].toIntOrNull()
                                    if (srcPort == clientPort) {
                                        val dstIp = match.groupValues[2]
                                        val dport = match.groupValues[4].toIntOrNull() ?: continue
                                        if (dstIp != "127.0.0.1" && dstIp != "0.0.0.0" && dport > 0) {
                                            return InetSocketAddress(dstIp, dport)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (ignored: Exception) {}
                }
            }

            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/net/nf_conntrack 2>/dev/null || cat /proc/net/ip_conntrack 2>/dev/null"))
                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (!l.contains("sport=$clientPort")) continue
                    val matches = CONNTRACK_REGEX.findAll(l).toList()
                    for (match in matches) {
                        val srcPort = match.groupValues[3].toIntOrNull()
                        if (srcPort == clientPort) {
                            val dstIp = match.groupValues[2]
                            val dport = match.groupValues[4].toIntOrNull() ?: continue
                            process.destroy()
                            if (dstIp != "127.0.0.1" && dstIp != "0.0.0.0" && dport > 0) {
                                return InetSocketAddress(dstIp, dport)
                            }
                        }
                    }
                }
                process.waitFor()
            } catch (ignored: Exception) {}
        }

        return null
    }

    private fun parseSniOrHost(buffer: ByteArray, len: Int, defaultPort: Int): Pair<String, Int>? {
        if (len > 5 && buffer[0] == 0x16.toByte() && buffer[1] == 0x03.toByte()) {
            val sni = extractTlsSni(buffer, len)
            if (!sni.isNullOrBlank()) {
                return Pair(sni, defaultPort)
            }
        }

        val text = String(buffer, 0, len.coerceAtMost(1024), Charsets.US_ASCII)
        val hostLine = text.lines().firstOrNull { it.startsWith("Host:", ignoreCase = true) }
        if (hostLine != null) {
            val rawHost = hostLine.substringAfter(":").trim()
            if (rawHost.contains(":")) {
                val parts = rawHost.split(":")
                return Pair(parts[0], parts[1].toIntOrNull() ?: defaultPort)
            }
            return Pair(rawHost, defaultPort)
        }

        return null
    }

    private fun extractTlsSni(buffer: ByteArray, len: Int): String? {
        try {
            var pos = 5
            if (pos >= len) return null
            val handshakeType = buffer[pos].toInt() and 0xFF
            if (handshakeType != 1) return null
            pos += 38
            if (pos >= len) return null

            val sessionIdLen = buffer[pos].toInt() and 0xFF
            pos += 1 + sessionIdLen
            if (pos >= len) return null

            val cipherSuitesLen = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
            pos += 2 + cipherSuitesLen
            if (pos >= len) return null

            val compMethodsLen = buffer[pos].toInt() and 0xFF
            pos += 1 + compMethodsLen
            if (pos >= len) return null

            if (pos + 2 > len) return null
            val extensionsLen = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
            pos += 2

            val extensionsEnd = (pos + extensionsLen).coerceAtMost(len)
            while (pos + 4 <= extensionsEnd) {
                val extType = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
                val extLen = ((buffer[pos + 2].toInt() and 0xFF) shl 8) or (buffer[pos + 3].toInt() and 0xFF)
                pos += 4

                if (extType == 0) {
                    if (pos + 5 <= extensionsEnd) {
                        val serverNameLen = ((buffer[pos + 3].toInt() and 0xFF) shl 8) or (buffer[pos + 4].toInt() and 0xFF)
                        pos += 5
                        if (pos + serverNameLen <= extensionsEnd) {
                            return String(buffer, pos, serverNameLen, Charsets.US_ASCII)
                        }
                    }
                }
                pos += extLen
            }
        } catch (e: Exception) {
            // Ignore parse errors
        }
        return null
    }

    private fun isDomainMatch(host: String, pattern: String): Boolean {
        val h = host.lowercase().trim()
        val p = pattern.lowercase().trim()
        if (h.isBlank() || p.isBlank()) return false
        if (h == p) return true
        if (p.startsWith("*.")) {
            val domain = p.substring(2)
            if (h == domain || h.endsWith(".$domain")) return true
        } else if (p.startsWith(".")) {
            val domain = p.substring(1)
            if (h == domain || h.endsWith(".$domain")) return true
        } else {
            if (h == p || h.endsWith(".$p")) return true
        }
        val regexStr = p.replace(".", "\\.").replace("*", ".*")
        return runCatching { h.matches(Regex(regexStr)) }.getOrDefault(false)
    }

    private fun isIpInCidr(ipStr: String, cidrStr: String): Boolean {
        val ip = ipStr.trim()
        val cidr = cidrStr.trim()
        if (ip.isBlank() || cidr.isBlank()) return false
        if (ip == cidr) return true
        if (!cidr.contains("/")) {
            return ip == cidr
        }
        return try {
            val parts = cidr.split("/")
            val baseIp = parts[0]
            val prefix = parts[1].toInt()
            val ipNum = ipToLong(ip)
            val baseNum = ipToLong(baseIp)
            if (ipNum == null || baseNum == null) false
            else {
                val mask = if (prefix == 0) 0L else (-1L shl (32 - prefix)) and 0xFFFFFFFFL
                (ipNum and mask) == (baseNum and mask)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun ipToLong(ip: String): Long? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        return try {
            (parts[0].toLong() shl 24) or
            (parts[1].toLong() shl 16) or
            (parts[2].toLong() shl 8) or
            parts[3].toLong()
        } catch (e: Exception) {
            null
        }
    }

    private fun matchRule(destHost: String, destIp: String, destPort: Int, appPackages: List<String>): MatchResult {
        val currentRules = activeRules.filter { it.isEnabled }.sortedBy { it.priority }
        for (rule in currentRules) {
            val hasDomains = rule.targetDomains.isNotEmpty()
            val hasIps = rule.targetIps.isNotEmpty()
            val hasPorts = rule.targetPorts.isNotEmpty()

            val portOk = !hasPorts || rule.targetPorts.any { pStr ->
                if (pStr.contains("-")) {
                    val range = pStr.split("-")
                    val start = range.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                    val end = range.getOrNull(1)?.trim()?.toIntOrNull() ?: 65535
                    destPort in start..end
                } else {
                    pStr.trim().toIntOrNull() == destPort
                }
            }

            val hasPackages = rule.targetPackages.isNotEmpty()

            val matchedDomain = hasDomains && rule.targetDomains.any { domain ->
                val d = domain.trim()
                d.isNotBlank() && (d == "*" || d == ".*" || d == "all" || isDomainMatch(destHost, d))
            }

            val matchedIp = hasIps && rule.targetIps.any { ip ->
                val i = ip.trim()
                i.isNotBlank() && (i == "*" || i == "0.0.0.0/0" || (destIp.isNotBlank() && isIpInCidr(destIp, i)) || isIpInCidr(destHost, i))
            }

            val matchedPackage = hasPackages && appPackages.any { pkg -> rule.targetPackages.contains(pkg) }

            val targetMatch = if (hasDomains || hasIps || hasPackages) {
                matchedDomain || matchedIp || matchedPackage
            } else {
                true // Catch-all fallback rule
            }

            if (portOk && targetMatch) {
                val targetProxy = getProxyForRule(rule)
                if (rule.action == RuleAction.PROXY && rule.ignoreIfProxyDown) {
                    val isProxyAlive = targetProxy != null && targetProxy.cleanHost.isNotBlank() && targetProxy.port > 0 && targetProxy.isOnline != false
                    if (!isProxyAlive) {
                        continue // Proxy is down/invalid, bypass rule
                    }
                }
                return MatchResult(rule.action, targetProxy, rule.name)
            }
        }
        return MatchResult(RuleAction.PROXY, activeProxy, "Default Fallback")
    }

    private fun getProxyForRule(rule: ProxyRule): ProxyServer? {
        if (rule.action != RuleAction.PROXY) return null
        if (rule.targetProxyId != null) {
            val found = allProxiesList.find { it.id == rule.targetProxyId }
            if (found != null) return found
        }
        return activeProxy
    }

    private data class MatchResult(
        val action: RuleAction,
        val targetProxy: ProxyServer?,
        val matchedRuleName: String?
    )

    private fun performProxyHandshake(socket: Socket, proxy: ProxyServer, destHost: String, destPort: Int): Boolean {
        return if (proxy.type == ProxyType.HTTP) {
            performHttpProxyHandshake(socket, proxy, destHost, destPort)
        } else {
            performSocks5Handshake(socket, proxy, destHost, destPort)
        }
    }

    private fun performSocks5Handshake(socket: Socket, proxy: ProxyServer, destHost: String, destPort: Int): Boolean {
        return try {
            socket.soTimeout = 5000
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
            if (inStream.read(resp) < 2 || resp[0] != 0x05.toByte()) return false

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
                if (inStream.read(authResp) < 2 || authResp[1] != 0x00.toByte()) return false
            } else if (resp[1] != 0x00.toByte()) return false

            // Check if destHost is an IPv4 address string
            val ipParts = destHost.split(".")
            if (ipParts.size == 4 && ipParts.all { it.trim().toIntOrNull() in 0..255 }) {
                // Send ATYP = 0x01 (IPv4)
                val connReq = ByteArray(10)
                connReq[0] = 0x05
                connReq[1] = 0x01 // CONNECT
                connReq[2] = 0x00
                connReq[3] = 0x01 // ATYP = IPv4
                connReq[4] = ipParts[0].trim().toInt().toByte()
                connReq[5] = ipParts[1].trim().toInt().toByte()
                connReq[6] = ipParts[2].trim().toInt().toByte()
                connReq[7] = ipParts[3].trim().toInt().toByte()
                connReq[8] = ((destPort ushr 8) and 0xFF).toByte()
                connReq[9] = (destPort and 0xFF).toByte()

                out.write(connReq)
                out.flush()
            } else {
                // Send ATYP = 0x03 (Domain name)
                val domainBytes = destHost.toByteArray()
                val connReq = ByteArray(7 + domainBytes.size)
                connReq[0] = 0x05
                connReq[1] = 0x01 // CONNECT
                connReq[2] = 0x00
                connReq[3] = 0x03 // ATYP = Domain
                connReq[4] = domainBytes.size.toByte()
                System.arraycopy(domainBytes, 0, connReq, 5, domainBytes.size)
                connReq[5 + domainBytes.size] = ((destPort ushr 8) and 0xFF).toByte()
                connReq[6 + domainBytes.size] = (destPort and 0xFF).toByte()

                out.write(connReq)
                out.flush()
            }

            // Read response
            val head = ByteArray(4)
            if (inStream.read(head) < 4 || head[1] != 0x00.toByte()) return false

            // Read rest of SOCKS5 reply depending on ATYP to avoid over-reading target data
            val atyp = head[3].toInt() and 0xFF
            when (atyp) {
                0x01 -> { // IPv4: 4 bytes IP + 2 bytes port = 6 bytes
                    val rest = ByteArray(6)
                    inStream.read(rest)
                }
                0x03 -> { // Domain: 1 byte len + N bytes domain + 2 bytes port
                    val lenByte = inStream.read()
                    if (lenByte > 0) {
                        val rest = ByteArray(lenByte + 2)
                        inStream.read(rest)
                    }
                }
                0x04 -> { // IPv6: 16 bytes IP + 2 bytes port = 18 bytes
                    val rest = ByteArray(18)
                    inStream.read(rest)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun performHttpProxyHandshake(socket: Socket, proxy: ProxyServer, destHost: String, destPort: Int): Boolean {
        return try {
            socket.soTimeout = 5000
            val out = socket.getOutputStream()
            val inStream = socket.getInputStream()

            val reqBuilder = StringBuilder()
            reqBuilder.append("CONNECT $destHost:$destPort HTTP/1.1\r\n")
            reqBuilder.append("Host: $destHost:$destPort\r\n")

            if (!proxy.username.isNullOrEmpty()) {
                val userPass = "${proxy.username}:${proxy.password ?: ""}"
                val encoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    java.util.Base64.getEncoder().encodeToString(userPass.toByteArray())
                } else {
                    android.util.Base64.encodeToString(userPass.toByteArray(), android.util.Base64.NO_WRAP)
                }
                reqBuilder.append("Proxy-Authorization: Basic $encoded\r\n")
            }
            reqBuilder.append("User-Agent: ProxifierAndroid/1.0\r\n\r\n")

            out.write(reqBuilder.toString().toByteArray())
            out.flush()

            // Read byte-by-byte until \r\n\r\n to avoid overbuffering server response
            val headerBuf = ByteArray(2048)
            var pos = 0
            while (pos < headerBuf.size) {
                val b = inStream.read()
                if (b == -1) return false
                headerBuf[pos++] = b.toByte()
                if (pos >= 4 &&
                    headerBuf[pos - 4] == 13.toByte() && headerBuf[pos - 3] == 10.toByte() &&
                    headerBuf[pos - 2] == 13.toByte() && headerBuf[pos - 1] == 10.toByte()
                ) {
                    break
                }
            }

            val headerText = String(headerBuf, 0, pos, Charsets.US_ASCII)
            val firstLine = headerText.lines().firstOrNull() ?: ""
            firstLine.contains("200")
        } catch (e: Exception) {
            false
        }
    }

    private fun relaySockets(client: Socket, target: Socket) {
        scope.launch {
            runCatching {
                val buf = ByteArray(16384)
                val inS = client.getInputStream()
                val outS = target.getOutputStream()
                while (_isRunning.value && !client.isClosed && !target.isClosed) {
                    val r = inS.read(buf)
                    if (r <= 0) break
                    outS.write(buf, 0, r)
                    outS.flush()
                    currentUp.addAndGet(r.toLong())
                }
            }
            runCatching { client.close() }
            runCatching { target.close() }
        }

        scope.launch {
            runCatching {
                val buf = ByteArray(16384)
                val inS = target.getInputStream()
                val outS = client.getOutputStream()
                while (_isRunning.value && !client.isClosed && !target.isClosed) {
                    val r = inS.read(buf)
                    if (r <= 0) break
                    outS.write(buf, 0, r)
                    outS.flush()
                    currentDown.addAndGet(r.toLong())
                }
            }
            runCatching { client.close() }
            runCatching { target.close() }
        }
    }
}
