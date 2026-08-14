package com.yangyx.proxy.data.repository

import android.content.Context
import android.content.Intent
import com.yangyx.proxy.data.db.AppDatabase
import com.yangyx.proxy.data.model.ConnectionLog
import com.yangyx.proxy.data.model.EngineMode
import com.yangyx.proxy.data.model.ProxyRule
import com.yangyx.proxy.data.model.ProxyServer
import com.yangyx.proxy.data.model.ProxyType
import com.yangyx.proxy.data.model.RuleAction
import com.yangyx.proxy.engine.KernelSuManager
import com.yangyx.proxy.engine.LocalTransparentProxyServer
import com.yangyx.proxy.engine.RootShellManager
import com.yangyx.proxy.engine.Socks5Tester
import com.yangyx.proxy.engine.VpnProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProxyRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    val proxyDao = db.proxyDao()
    val ruleDao = db.proxyRuleDao()
    val logDao = db.connectionLogDao()

    val allProxies: Flow<List<ProxyServer>> = proxyDao.getAllProxies()
    val allRules: Flow<List<ProxyRule>> = ruleDao.getAllRules()
    val activeProxy: Flow<ProxyServer?> = proxyDao.getActiveProxy()
    val recentLogs: Flow<List<ConnectionLog>> = logDao.getRecentLogs(150)

    private val repositoryScope = CoroutineScope(Dispatchers.IO)

    private val prefs = context.getSharedPreferences("proxifier_prefs", Context.MODE_PRIVATE)

    private val _engineMode = MutableStateFlow(
        runCatching {
            EngineMode.valueOf(prefs.getString("engine_mode", EngineMode.VPN_SERVICE.name) ?: EngineMode.VPN_SERVICE.name)
        }.getOrDefault(EngineMode.VPN_SERVICE)
    )
    val engineMode: StateFlow<EngineMode> = _engineMode

    private val _isBootAutoStart = MutableStateFlow(
        prefs.getBoolean("boot_auto_start", true)
    )
    val isBootAutoStart: StateFlow<Boolean> = _isBootAutoStart

    private val _showHexInLogs = MutableStateFlow(
        prefs.getBoolean("show_hex_in_logs", false)
    )
    val showHexInLogs: StateFlow<Boolean> = _showHexInLogs

    fun setShowHexInLogs(show: Boolean) {
        _showHexInLogs.value = show
        prefs.edit().putBoolean("show_hex_in_logs", show).apply()
    }

    private val _isKernelSuRunning = MutableStateFlow(false)
    val isKernelSuRunning: StateFlow<Boolean> = LocalTransparentProxyServer.isRunning

    private val _lastEngineError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = combine(
        VpnProxyService.lastError,
        LocalTransparentProxyServer.lastError,
        _lastEngineError,
        _engineMode
    ) { vpnErr, ksuErr, engineErr, mode ->
        if (mode == EngineMode.VPN_SERVICE) vpnErr else (ksuErr ?: engineErr)
    }.stateIn(repositoryScope, SharingStarted.Eagerly, null)

    fun clearLastError() {
        VpnProxyService.clearLastError()
        LocalTransparentProxyServer.clearLastError()
        _lastEngineError.value = null
    }

    val isEngineRunning: StateFlow<Boolean> = combine(
        VpnProxyService.isRunning,
        LocalTransparentProxyServer.isRunning,
        _engineMode
    ) { vpnRunning, transparentRunning, mode ->
        if (mode == EngineMode.VPN_SERVICE) vpnRunning else transparentRunning
    }.stateIn(repositoryScope, SharingStarted.Eagerly, false)

    fun isProxyRunning(): Boolean {
        return when (_engineMode.value) {
            EngineMode.VPN_SERVICE -> VpnProxyService.isRunning.value
            EngineMode.KERNELSU_TRANSPARENT -> LocalTransparentProxyServer.isRunning.value
        }
    }

    val bytesUpSec: StateFlow<Long> = combine(
        VpnProxyService.bytesUpSec,
        LocalTransparentProxyServer.bytesUpSec,
        _engineMode
    ) { vpnUp, ksuUp, mode -> if (mode == EngineMode.VPN_SERVICE) vpnUp else ksuUp }
        .stateIn(repositoryScope, SharingStarted.Eagerly, 0L)

    val bytesDownSec: StateFlow<Long> = combine(
        VpnProxyService.bytesDownSec,
        LocalTransparentProxyServer.bytesDownSec,
        _engineMode
    ) { vpnDown, ksuDown, mode -> if (mode == EngineMode.VPN_SERVICE) vpnDown else ksuDown }
        .stateIn(repositoryScope, SharingStarted.Eagerly, 0L)

    val totalBytesUp: StateFlow<Long> = combine(
        VpnProxyService.totalBytesUp,
        LocalTransparentProxyServer.totalBytesUp,
        _engineMode
    ) { vpnUp, ksuUp, mode -> if (mode == EngineMode.VPN_SERVICE) vpnUp else ksuUp }
        .stateIn(repositoryScope, SharingStarted.Eagerly, 0L)

    val totalBytesDown: StateFlow<Long> = combine(
        VpnProxyService.totalBytesDown,
        LocalTransparentProxyServer.totalBytesDown,
        _engineMode
    ) { vpnDown, ksuDown, mode -> if (mode == EngineMode.VPN_SERVICE) vpnDown else ksuDown }
        .stateIn(repositoryScope, SharingStarted.Eagerly, 0L)

    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable

    private val _isKernelSuAvailable = MutableStateFlow(false)
    val isKernelSuAvailable: StateFlow<Boolean> = _isKernelSuAvailable

    private val _rootShellLog = MutableStateFlow("")
    val rootShellLog: StateFlow<String> = _rootShellLog

    suspend fun checkSystemRoot() = withContext(Dispatchers.IO) {
        val root = RootShellManager.checkRootAccess()
        val ksu = RootShellManager.checkKernelSu()
        _isRootAvailable.value = root
        _isKernelSuAvailable.value = ksu

        if (root || ksu) {
            _rootShellLog.value = "Root access verified: ${if (ksu) "KernelSU Detected" else "Standard Root (su) Detected"}"
            // If proxy is not running when app opens, flush iptables to ensure no leftover rules block internet
            if (!_isKernelSuRunning.value && _engineMode.value == EngineMode.KERNELSU_TRANSPARENT) {
                KernelSuManager.flushRules()
            }
        } else {
            _rootShellLog.value = "No root access detected. Running in standard VpnService mode."
        }
    }

    suspend fun initializeDefaultData() = withContext(Dispatchers.IO) {
        val existingProxies = allProxies.first()
        if (existingProxies.isEmpty()) {
            val defaultProxy1 = ProxyServer(
                name = "US High-Speed Node",
                type = ProxyType.SOCKS5,
                host = "104.28.16.88",
                port = 1080,
                enableUdp = true,
                isActive = true,
                colorTagHex = "#3B82F6",
                latencyMs = 45
            )
            val defaultProxy2 = ProxyServer(
                name = "Hong Kong Premium SOCKS5",
                type = ProxyType.SOCKS5,
                host = "47.242.10.12",
                port = 1080,
                enableUdp = true,
                isActive = false,
                colorTagHex = "#10B981",
                latencyMs = 28
            )
            val defaultProxy3 = ProxyServer(
                name = "Tokyo Fast Proxy",
                type = ProxyType.SOCKS5,
                host = "139.162.80.20",
                port = 1080,
                enableUdp = true,
                isActive = false,
                colorTagHex = "#8B5CF6",
                latencyMs = 62
            )

            proxyDao.insertProxy(defaultProxy1)
            proxyDao.insertProxy(defaultProxy2)
            proxyDao.insertProxy(defaultProxy3)
        }

        val existingRules = allRules.first()
        if (existingRules.isEmpty()) {
            val rule1 = ProxyRule(
                name = "Bypass Local LAN & Private IPs",
                isEnabled = true,
                priority = 1,
                action = RuleAction.DIRECT,
                targetIps = listOf("127.0.0.1/32", "192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12")
            )
            val rule2 = ProxyRule(
                name = "Google & GitHub Services",
                isEnabled = true,
                priority = 2,
                action = RuleAction.PROXY,
                targetDomains = listOf("*.google.com", "*.github.com", "*.githubusercontent.com", "t.me")
            )
            val rule3 = ProxyRule(
                name = "Block Known Trackers",
                isEnabled = true,
                priority = 3,
                action = RuleAction.REJECT,
                targetDomains = listOf("*.doubleclick.net", "*.analytics.google.com")
            )

            ruleDao.insertRule(rule1)
            ruleDao.insertRule(rule2)
            ruleDao.insertRule(rule3)
        }
    }

    fun setEngineMode(mode: EngineMode) {
        _engineMode.value = mode
        prefs.edit().putString("engine_mode", mode.name).apply()
    }

    fun setBootAutoStart(enabled: Boolean) {
        _isBootAutoStart.value = enabled
        prefs.edit().putBoolean("boot_auto_start", enabled).apply()
    }

    suspend fun setActiveProxy(id: Long) = withContext(Dispatchers.IO) {
        proxyDao.setActiveProxy(id)
    }

    suspend fun testProxyLatency(proxy: ProxyServer) = withContext(Dispatchers.IO) {
        val latency = Socks5Tester.testLatency(proxy)
        proxyDao.updateLatency(proxy.id, latency)
        latency
    }

    suspend fun testAllProxiesLatency(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val list = allProxies.first()
        var successCount = 0
        var timeoutCount = 0
        for (item in list) {
            val latency = Socks5Tester.testLatency(item)
            proxyDao.updateLatency(item.id, latency)
            if (latency > 0) successCount++ else timeoutCount++
        }
        Pair(successCount, timeoutCount)
    }

    suspend fun moveRuleUp(rule: ProxyRule) = withContext(Dispatchers.IO) {
        val list = allRules.first().sortedBy { it.priority }.toMutableList()
        val index = list.indexOfFirst { it.id == rule.id }
        if (index > 0) {
            val item = list.removeAt(index)
            list.add(index - 1, item)
            list.forEachIndexed { i, r ->
                ruleDao.updateRule(r.copy(priority = i + 1))
            }
        }
    }

    suspend fun moveRuleDown(rule: ProxyRule) = withContext(Dispatchers.IO) {
        val list = allRules.first().sortedBy { it.priority }.toMutableList()
        val index = list.indexOfFirst { it.id == rule.id }
        if (index >= 0 && index < list.size - 1) {
            val item = list.removeAt(index)
            list.add(index + 1, item)
            list.forEachIndexed { i, r ->
                ruleDao.updateRule(r.copy(priority = i + 1))
            }
        }
    }

    suspend fun applyRootRules() = withContext(Dispatchers.IO) {
        _lastEngineError.value = null
        val activeP = proxyDao.getActiveProxySync()
        val allP = proxyDao.getAllProxiesSync()
        val rules = ruleDao.getEnabledRulesSync()

        try {
            val intent = Intent(context, com.yangyx.proxy.service.KernelSuBootService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        LocalTransparentProxyServer.start(
            context = context,
            port = 1080,
            proxy = activeP,
            proxies = allP,
            rules = rules,
            onLog = { log ->
                repositoryScope.launch {
                    logDao.insertLog(log)
                }
            }
        )

        val result = KernelSuManager.applyRules(context, activeP, rules)
        _isKernelSuRunning.value = (result.exitCode == 0)
        _rootShellLog.value = "Exit Code ${result.exitCode}\nSTDOUT:\n${result.output}\nSTDERR:\n${result.error}"

        if (result.exitCode != 0) {
            val errDetail = result.error.ifEmpty { result.output }
            _lastEngineError.value = "Root/KernelSU iptables规则应用失败 (Exit Code ${result.exitCode}): $errDetail"
            LocalTransparentProxyServer.stop()
        } else {
            _lastEngineError.value = null
        }
        result
    }

    suspend fun forceResetAll() = withContext(Dispatchers.IO) {
        clearLastError()
        VpnProxyService.stop(context)
        try {
            val intent = Intent(context, com.yangyx.proxy.service.KernelSuBootService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        LocalTransparentProxyServer.stop()
        _isKernelSuRunning.value = false
        try {
            KernelSuManager.flushRules()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        com.yangyx.proxy.service.ProxyTileService.requestTileUpdate(context)
    }

    suspend fun flushRootRules() = withContext(Dispatchers.IO) {
        try {
            val intent = Intent(context, com.yangyx.proxy.service.KernelSuBootService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        LocalTransparentProxyServer.stop()
        val result = KernelSuManager.flushRules()
        _isKernelSuRunning.value = false
        _rootShellLog.value = "Flushed Root Rules.\nSTDOUT: ${result.output}\nSTDERR: ${result.error}"
        result
    }

    suspend fun toggleProxyEngine(enable: Boolean) = withContext(Dispatchers.IO) {
        clearLastError()
        if (enable) {
            when (_engineMode.value) {
                EngineMode.VPN_SERVICE -> {
                    LocalTransparentProxyServer.stop()
                    if (_isRootAvailable.value) {
                        KernelSuManager.flushRules()
                    }
                    _isKernelSuRunning.value = false
                    VpnProxyService.start(context)
                }
                EngineMode.KERNELSU_TRANSPARENT -> {
                    VpnProxyService.stop(context)
                    applyRootRules()
                }
            }
        } else {
            VpnProxyService.stop(context)
            try {
                val intent = Intent(context, com.yangyx.proxy.service.KernelSuBootService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            LocalTransparentProxyServer.stop()
            _isKernelSuRunning.value = false
            try {
                KernelSuManager.flushRules()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        com.yangyx.proxy.service.ProxyTileService.requestTileUpdate(context)
    }
}
