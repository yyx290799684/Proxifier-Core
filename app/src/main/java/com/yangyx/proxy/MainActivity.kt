package com.yangyx.proxy

import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yangyx.proxy.ui.screens.DashboardScreen
import com.yangyx.proxy.ui.screens.KernelSuScreen
import com.yangyx.proxy.ui.screens.LogsScreen
import com.yangyx.proxy.ui.screens.ProxyListScreen
import com.yangyx.proxy.ui.screens.RulesScreen
import com.yangyx.proxy.ui.theme.MyApplicationTheme
import com.yangyx.proxy.ui.viewmodel.MainViewModel

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "仪表盘", Icons.Default.Dashboard)
    object Proxies : Screen("proxies", "代理节点", Icons.Default.Dns)
    object Rules : Screen("rules", "分流规则", Icons.Default.AltRoute)
    object KernelSu : Screen("ksu", "KernelSU", Icons.Default.Terminal)
    object Logs : Screen("logs", "日志", Icons.Default.ReceiptLong)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.toggleProxyEngine()
        } else {
            Toast.makeText(this, "VPN权限授权未同意，无法启动代理服务", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleToggleEngine() {
        val currentEngineMode = viewModel.engineMode.value
        val isRunning = viewModel.isVpnRunning.value
        if (currentEngineMode == com.yangyx.proxy.data.model.EngineMode.VPN_SERVICE && !isRunning) {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                vpnLauncher.launch(prepareIntent)
            } else {
                viewModel.toggleProxyEngine()
            }
        } else {
            viewModel.toggleProxyEngine()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent?.getBooleanExtra("EXTRA_START_VPN_FROM_TILE", false) == true) {
            handleToggleEngine()
        }

        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }

                val proxies by viewModel.proxies.collectAsStateWithLifecycle()
                val rules by viewModel.rules.collectAsStateWithLifecycle()
                val activeProxy by viewModel.activeProxy.collectAsStateWithLifecycle()
                val logs by viewModel.logs.collectAsStateWithLifecycle()
                val engineMode by viewModel.engineMode.collectAsStateWithLifecycle()

                val isVpnRunning by viewModel.isVpnRunning.collectAsStateWithLifecycle()
                val lastError by viewModel.lastError.collectAsStateWithLifecycle()
                val bytesUpSec by viewModel.bytesUpSec.collectAsStateWithLifecycle()
                val bytesDownSec by viewModel.bytesDownSec.collectAsStateWithLifecycle()
                val totalBytesUp by viewModel.totalBytesUp.collectAsStateWithLifecycle()
                val totalBytesDown by viewModel.totalBytesDown.collectAsStateWithLifecycle()

                val isRootAvailable by viewModel.isRootAvailable.collectAsStateWithLifecycle()
                val isKernelSuAvailable by viewModel.isKernelSuAvailable.collectAsStateWithLifecycle()
                val rootShellLog by viewModel.rootShellLog.collectAsStateWithLifecycle()
                val isBootAutoStart by viewModel.isBootAutoStart.collectAsStateWithLifecycle()
                val showHexInLogs by viewModel.showHexInLogs.collectAsStateWithLifecycle()

                val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
                val isLoadingApps by viewModel.isLoadingApps.collectAsStateWithLifecycle()
                val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()

                LaunchedEffect(userMessage) {
                    userMessage?.let {
                        snackbarHostState.showSnackbar(it)
                        viewModel.clearUserMessage()
                    }
                }

                val items = listOf(
                    Screen.Dashboard,
                    Screen.Proxies,
                    Screen.Rules,
                    Screen.KernelSu,
                    Screen.Logs
                )

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Dashboard.route

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = "Proxifier Core",
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = TopAppBarDefaults.topAppBarColors()
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            modifier = Modifier
                                .navigationBarsPadding()
                                .testTag("bottom_navigation_bar")
                        ) {
                            items.forEach { screen ->
                                NavigationBarItem(
                                    icon = { Icon(screen.icon, contentDescription = screen.title) },
                                    label = { Text(screen.title) },
                                    selected = currentRoute == screen.route,
                                    onClick = {
                                        if (currentRoute != screen.route) {
                                            navController.navigate(screen.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    inclusive = false
                                                }
                                                launchSingleTop = true
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Dashboard.route,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        composable(Screen.Dashboard.route) {
                            DashboardScreen(
                                activeProxy = activeProxy,
                                engineMode = engineMode,
                                isVpnRunning = isVpnRunning,
                                isRootAvailable = isRootAvailable,
                                isKernelSuAvailable = isKernelSuAvailable,
                                bytesUpSec = bytesUpSec,
                                bytesDownSec = bytesDownSec,
                                totalBytesUp = totalBytesUp,
                                totalBytesDown = totalBytesDown,
                                recentLogs = logs,
                                lastError = lastError,
                                onToggleEngine = { handleToggleEngine() },
                                onSelectMode = { viewModel.setEngineMode(it) },
                                onNavigateProxies = { navController.navigate(Screen.Proxies.route) },
                                onNavigateRules = { navController.navigate(Screen.Rules.route) },
                                onClearLastError = { viewModel.clearLastError() },
                                onForceReset = { viewModel.forceResetAll() }
                            )
                        }

                        composable(Screen.Proxies.route) {
                            ProxyListScreen(
                                proxies = proxies,
                                onSelectActive = { viewModel.selectActiveProxy(it) },
                                onTestLatency = { viewModel.testLatency(it) },
                                onTestAll = { viewModel.testAllLatencies() },
                                onSaveProxy = { viewModel.saveProxyServer(it) },
                                onDeleteProxy = { viewModel.deleteProxyServer(it) }
                            )
                        }

                        composable(Screen.Rules.route) {
                            RulesScreen(
                                rules = rules,
                                proxies = proxies,
                                installedApps = installedApps,
                                isLoadingApps = isLoadingApps,
                                onToggleRule = { viewModel.toggleRuleEnabled(it) },
                                onMoveRuleUp = { viewModel.moveRuleUp(it) },
                                onMoveRuleDown = { viewModel.moveRuleDown(it) },
                                onSaveRule = { viewModel.saveRule(it) },
                                onDeleteRule = { viewModel.deleteRule(it) },
                                onReloadApps = { viewModel.reloadInstalledApps() }
                            )
                        }

                        composable(Screen.KernelSu.route) {
                            KernelSuScreen(
                                isRootAvailable = isRootAvailable,
                                isKernelSuAvailable = isKernelSuAvailable,
                                rootShellLog = rootShellLog,
                                isBootAutoStart = isBootAutoStart,
                                onToggleBootAutoStart = { viewModel.setBootAutoStart(it) },
                                onApplyRules = { viewModel.applyRootRules() },
                                onFlushRules = { viewModel.flushRootRules() },
                                onExportModule = { onExportDone ->
                                    viewModel.exportKernelSuModule(onExportDone)
                                }
                            )
                        }

                        composable(Screen.Logs.route) {
                            LogsScreen(
                                logs = logs,
                                showHexInLogs = showHexInLogs,
                                onToggleShowHex = { viewModel.setShowHexInLogs(it) },
                                onClearLogs = { viewModel.clearLogs() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("EXTRA_START_VPN_FROM_TILE", false)) {
            handleToggleEngine()
        }
    }
}
