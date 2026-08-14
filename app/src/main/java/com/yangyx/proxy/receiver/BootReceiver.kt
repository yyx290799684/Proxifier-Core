package com.yangyx.proxy.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.yangyx.proxy.data.model.EngineMode
import com.yangyx.proxy.data.repository.ProxyRepository
import com.yangyx.proxy.service.KernelSuBootService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val repository = ProxyRepository(context.applicationContext)
                    val isBootAutoStart = repository.isBootAutoStart.value
                    val engineMode = repository.engineMode.value

                    if (isBootAutoStart && engineMode == EngineMode.KERNELSU_TRANSPARENT) {
                        val serviceIntent = Intent(context, KernelSuBootService::class.java)
                        ContextCompat.startForegroundService(context, serviceIntent)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
