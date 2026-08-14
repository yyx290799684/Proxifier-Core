package com.yangyx.proxy.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.yangyx.proxy.MainActivity
import com.yangyx.proxy.R
import com.yangyx.proxy.data.db.AppDatabase
import com.yangyx.proxy.data.model.EngineMode
import com.yangyx.proxy.engine.KernelSuManager
import com.yangyx.proxy.engine.LocalTransparentProxyServer
import com.yangyx.proxy.engine.VpnProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.N)
class ProxyTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        val toggleRunnable = Runnable {
            val prefs = applicationContext.getSharedPreferences("proxifier_prefs", Context.MODE_PRIVATE)
            val modeName = prefs.getString("engine_mode", EngineMode.VPN_SERVICE.name) ?: EngineMode.VPN_SERVICE.name
            val engineMode = runCatching { EngineMode.valueOf(modeName) }.getOrDefault(EngineMode.VPN_SERVICE)

            val isRunning = when (engineMode) {
                EngineMode.VPN_SERVICE -> VpnProxyService.isRunning.value
                EngineMode.KERNELSU_TRANSPARENT -> LocalTransparentProxyServer.isRunning.value
            }

            if (isRunning) {
                // Turn Off Proxy
                VpnProxyService.stop(applicationContext)
                try {
                    val ksuIntent = Intent(applicationContext, KernelSuBootService::class.java)
                    applicationContext.stopService(ksuIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                LocalTransparentProxyServer.stop()

                serviceScope.launch {
                    try {
                        KernelSuManager.flushRules()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    updateTileState()
                }
                updateTileState()
            } else {
                // Turn On Proxy
                if (engineMode == EngineMode.VPN_SERVICE) {
                    val vpnPrepareIntent = VpnService.prepare(applicationContext)
                    if (vpnPrepareIntent != null) {
                        // Open MainActivity to grant VPN permission
                        val intent = Intent(applicationContext, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("EXTRA_START_VPN_FROM_TILE", true)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            val pendingIntent = PendingIntent.getActivity(
                                applicationContext,
                                0,
                                intent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            startActivityAndCollapse(pendingIntent)
                        } else {
                            @Suppress("DEPRECATION")
                            startActivityAndCollapse(intent)
                        }
                    } else {
                        // Directly start VPN foreground service synchronously during tile click
                        VpnProxyService.start(applicationContext)
                        updateTileState()
                    }
                } else {
                    // KernelSU Mode: Start foreground boot service synchronously
                    val ksuIntent = Intent(applicationContext, KernelSuBootService::class.java)
                    try {
                        ContextCompat.startForegroundService(applicationContext, ksuIntent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // Apply rules asynchronously
                    serviceScope.launch {
                        try {
                            val db = AppDatabase.getInstance(applicationContext)
                            val activeP = db.proxyDao().getActiveProxySync()
                            val allP = db.proxyDao().getAllProxiesSync()
                            val rules = db.proxyRuleDao().getEnabledRulesSync()

                            LocalTransparentProxyServer.start(
                                context = applicationContext,
                                port = 1080,
                                proxy = activeP,
                                proxies = allP,
                                rules = rules,
                                onLog = { log ->
                                    serviceScope.launch {
                                        db.connectionLogDao().insertLog(log)
                                    }
                                }
                            )

                            KernelSuManager.applyRules(applicationContext, activeP, rules)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        updateTileState()
                    }
                    updateTileState()
                }
            }
        }

        if (isLocked) {
            unlockAndRun(toggleRunnable)
        } else {
            toggleRunnable.run()
        }
    }

    private fun updateTileState() {
        val prefs = applicationContext.getSharedPreferences("proxifier_prefs", Context.MODE_PRIVATE)
        val modeName = prefs.getString("engine_mode", EngineMode.VPN_SERVICE.name) ?: EngineMode.VPN_SERVICE.name
        val engineMode = runCatching { EngineMode.valueOf(modeName) }.getOrDefault(EngineMode.VPN_SERVICE)

        val isRunning = when (engineMode) {
            EngineMode.VPN_SERVICE -> VpnProxyService.isRunning.value
            EngineMode.KERNELSU_TRANSPARENT -> LocalTransparentProxyServer.isRunning.value
        }

        serviceScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val activeProxy = db.proxyDao().getActiveProxySync()

            withContext(Dispatchers.Main) {
                val tile = qsTile ?: return@withContext
                tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.label = getString(R.string.qs_tile_label)
                tile.icon = Icon.createWithResource(applicationContext, R.drawable.ic_qs_tile_proxy)

                val proxyName = activeProxy?.name ?: "未选择节点"
                val modeText = if (engineMode == EngineMode.KERNELSU_TRANSPARENT) "Root" else "VPN"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = if (isRunning) "运行中 · $proxyName ($modeText)" else "未运行 · 点击开启"
                }

                tile.updateTile()
            }
        }
    }

    companion object {
        fun requestTileUpdate(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestListeningState(context, ComponentName(context, ProxyTileService::class.java))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
