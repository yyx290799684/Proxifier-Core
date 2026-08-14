package com.yangyx.proxy.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.yangyx.proxy.data.repository.ProxyRepository
import com.yangyx.proxy.engine.KernelSuManager
import com.yangyx.proxy.engine.LocalTransparentProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class KernelSuBootService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLocks()
        startForegroundNotification()
    }

    private fun acquireWakeLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Proxifier:RootProxyWakeLock")?.apply {
                acquire(10 * 60 * 60 * 1000L) // 10 hours max
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Proxifier:RootProxyWifiLock")?.apply {
                acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repository = ProxyRepository(applicationContext)

        serviceScope.launch {
            try {
                val activeProxy = repository.proxyDao.getActiveProxySync()
                val activeRules = repository.ruleDao.getEnabledRulesSync()
                val allProxies = repository.proxyDao.getAllProxiesSync()

                LocalTransparentProxyServer.start(
                    context = applicationContext,
                    port = 1080,
                    proxy = activeProxy,
                    proxies = allProxies,
                    rules = activeRules,
                    onLog = { log ->
                        serviceScope.launch {
                            repository.logDao.insertLog(log)
                        }
                    }
                )

                KernelSuManager.applyRules(applicationContext, activeProxy, activeRules)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLocks()
        serviceScope.launch {
            KernelSuManager.flushRules()
            LocalTransparentProxyServer.stop()
        }
        serviceJob.cancel()
    }

    private fun startForegroundNotification() {
        val channelId = "ksu_transparent_proxy_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Root 透明代理后台保活服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "KernelSU / Root 模式后台透明代理分流引擎"
            }
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Proxifier Root 透明代理已启动")
            .setContentText("已锁定 CPU 及网络后台保活，常驻接管网络流量")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(2001, notification)
    }
}
