package com.yangyx.proxy.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yangyx.proxy.data.model.AppInfo
import com.yangyx.proxy.data.model.ConnectionLog
import com.yangyx.proxy.data.model.EngineMode
import com.yangyx.proxy.data.model.ProxyRule
import com.yangyx.proxy.data.model.ProxyServer
import com.yangyx.proxy.data.model.ProxyType
import com.yangyx.proxy.data.model.RuleAction
import com.yangyx.proxy.data.repository.ProxyRepository
import com.yangyx.proxy.engine.KernelSuManager
import com.yangyx.proxy.engine.VpnProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val repository = ProxyRepository(application)

    val proxies: StateFlow<List<ProxyServer>> = repository.allProxies
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rules: StateFlow<List<ProxyRule>> = repository.allRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeProxy: StateFlow<ProxyServer?> = repository.activeProxy
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val logs: StateFlow<List<ConnectionLog>> = repository.recentLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val engineMode: StateFlow<EngineMode> = repository.engineMode
    val isBootAutoStart: StateFlow<Boolean> = repository.isBootAutoStart
    val showHexInLogs: StateFlow<Boolean> = repository.showHexInLogs
    val isRootAvailable: StateFlow<Boolean> = repository.isRootAvailable
    val isKernelSuAvailable: StateFlow<Boolean> = repository.isKernelSuAvailable
    val rootShellLog: StateFlow<String> = repository.rootShellLog

    val isVpnRunning: StateFlow<Boolean> = repository.isEngineRunning
    val lastError: StateFlow<String?> = repository.lastError
    val bytesUpSec: StateFlow<Long> = repository.bytesUpSec
    val bytesDownSec: StateFlow<Long> = repository.bytesDownSec
    val totalBytesUp: StateFlow<Long> = repository.totalBytesUp
    val totalBytesDown: StateFlow<Long> = repository.totalBytesDown

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    init {
        viewModelScope.launch {
            repository.initializeDefaultData()
            repository.checkSystemRoot()
            loadInstalledApps()
        }
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    fun clearLastError() {
        repository.clearLastError()
    }

    fun forceResetAll() {
        viewModelScope.launch {
            repository.forceResetAll()
        }
    }

    fun setEngineMode(mode: EngineMode) {
        repository.setEngineMode(mode)
    }

    fun setBootAutoStart(enabled: Boolean) {
        repository.setBootAutoStart(enabled)
    }

    fun setShowHexInLogs(show: Boolean) {
        repository.setShowHexInLogs(show)
    }

    fun toggleProxyEngine() {
        viewModelScope.launch {
            val isCurrentlyActive = isVpnRunning.value
            repository.toggleProxyEngine(!isCurrentlyActive)
        }
    }

    fun selectActiveProxy(proxy: ProxyServer) {
        viewModelScope.launch {
            repository.setActiveProxy(proxy.id)
            if (isVpnRunning.value) {
                // Restart engine to apply new active node
                repository.toggleProxyEngine(true)
            }
        }
    }

    fun testLatency(proxy: ProxyServer) {
        viewModelScope.launch {
            val latency = repository.testProxyLatency(proxy)
            _userMessage.value = if (latency > 0) "Ping ${proxy.name}: ${latency}ms" else "Connection timeout for ${proxy.name}"
        }
    }

    fun testAllLatencies() {
        viewModelScope.launch {
            _userMessage.value = "正在对所有节点进行一键测速..."
            val (success, timeout) = repository.testAllProxiesLatency()
            _userMessage.value = "一键测速完成：$success 个可用${if (timeout > 0) "，$timeout 个超时" else ""}"
        }
    }

    fun saveProxyServer(proxy: ProxyServer) {
        viewModelScope.launch {
            repository.proxyDao.insertProxy(proxy)
            _userMessage.value = "Saved Proxy: ${proxy.name}"
        }
    }

    fun deleteProxyServer(proxy: ProxyServer) {
        viewModelScope.launch {
            repository.proxyDao.deleteProxy(proxy)
            _userMessage.value = "Deleted ${proxy.name}"
        }
    }

    fun saveRule(rule: ProxyRule) {
        viewModelScope.launch {
            repository.ruleDao.insertRule(rule)
            _userMessage.value = "Saved Rule: ${rule.name}"
        }
    }

    fun deleteRule(rule: ProxyRule) {
        viewModelScope.launch {
            repository.ruleDao.deleteRule(rule)
            _userMessage.value = "Deleted Rule: ${rule.name}"
        }
    }

    fun toggleRuleEnabled(rule: ProxyRule) {
        viewModelScope.launch {
            repository.ruleDao.setRuleEnabled(rule.id, !rule.isEnabled)
        }
    }

    fun moveRuleUp(rule: ProxyRule) {
        viewModelScope.launch {
            repository.moveRuleUp(rule)
        }
    }

    fun moveRuleDown(rule: ProxyRule) {
        viewModelScope.launch {
            repository.moveRuleDown(rule)
        }
    }

    fun applyRootRules() {
        viewModelScope.launch {
            _userMessage.value = "Applying Root iptables Rules..."
            repository.applyRootRules()
        }
    }

    fun flushRootRules() {
        viewModelScope.launch {
            _userMessage.value = "Flushing Root iptables Rules..."
            repository.flushRootRules()
        }
    }

    fun exportKernelSuModule(onExportDone: (File) -> Unit) {
        viewModelScope.launch {
            _userMessage.value = "正在构建 KernelSU 刷机模块 ZIP..."
            val activeP = activeProxy.value
            val rulesList = rules.value
            val cacheZip = KernelSuManager.generateModuleZip(getApplication(), activeP, rulesList)
            val downloadZip = runCatching {
                KernelSuManager.copyToDownloadsDirectory(getApplication(), cacheZip)
            }.getOrDefault(cacheZip)

            _userMessage.value = "KernelSU 模块已导出至 Download 目录: ${downloadZip.absolutePath}"
            onExportDone(downloadZip)
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.logDao.clearLogs()
            _userMessage.value = "Connection logs cleared."
        }
    }

    fun reloadInstalledApps() {
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingApps.value = true
            val context = getApplication<Application>()
            val pm = context.packageManager
            val appMap = LinkedHashMap<String, AppInfo>()

            // Method 1: getInstalledApplications
            runCatching {
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                for (appInfo in apps) {
                    val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrNull() ?: appInfo.packageName
                    val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                    appMap[appInfo.packageName] = AppInfo(
                        appName = label,
                        packageName = appInfo.packageName,
                        iconDrawable = icon,
                        isSystemApp = isSystem
                    )
                }
            }

            // Method 2: getInstalledPackages fallback
            if (appMap.isEmpty()) {
                runCatching {
                    val pkgs = pm.getInstalledPackages(0)
                    for (pkg in pkgs) {
                        val appInfo = pkg.applicationInfo ?: continue
                        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrNull() ?: appInfo.packageName
                        val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
                        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                        appMap[appInfo.packageName] = AppInfo(
                            appName = label,
                            packageName = appInfo.packageName,
                            iconDrawable = icon,
                            isSystemApp = isSystem
                        )
                    }
                }
            }

            // Method 3: queryIntentActivities for Launcher category
            runCatching {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
                for (ri in resolveInfos) {
                    val pkgName = ri.activityInfo.packageName
                    if (!appMap.containsKey(pkgName)) {
                        val label = ri.loadLabel(pm).toString().ifEmpty { pkgName }
                        val icon = ri.loadIcon(pm)
                        val isSystem = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                        appMap[pkgName] = AppInfo(
                            appName = label,
                            packageName = pkgName,
                            iconDrawable = icon,
                            isSystemApp = isSystem
                        )
                    }
                }
            }

            val appList = appMap.values.distinctBy { it.packageName }.sortedBy { it.appName.lowercase() }
            _installedApps.value = appList
            _isLoadingApps.value = false
        }
    }
}
