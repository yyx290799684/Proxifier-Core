package com.yangyx.proxy.engine

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import java.io.File
import java.net.InetSocketAddress

object SocketUidResolver {

    fun getUidForLocalPort(port: Int): Int? {
        if (port <= 0) return null
        val portHex = String.format("%04X", port)
        val procFiles = listOf("/proc/net/tcp", "/proc/net/tcp6")

        for (filePath in procFiles) {
            val file = File(filePath)
            if (!file.exists() || !file.canRead()) continue
            try {
                file.useLines { lines ->
                    for (line in lines) {
                        val tokens = line.trim().split("\\s+".toRegex())
                        if (tokens.size >= 10) {
                            val localAddr = tokens[1]
                            if (localAddr.endsWith(":$portHex", ignoreCase = true)) {
                                val uid = tokens[7].toIntOrNull()
                                if (uid != null && uid >= 0) {
                                    return uid
                                }
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {
            }
        }
        return null
    }

    fun getPackagesForPort(context: Context, port: Int, destIp: String = "", destPort: Int = 0): List<String> {
        var uid: Int? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && destIp.isNotBlank() && destPort > 0) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) {
                    val localAddr = InetSocketAddress("0.0.0.0", port)
                    val remoteAddr = InetSocketAddress(destIp, destPort)
                    val foundUid = cm.getConnectionOwnerUid(OsConstants.IPPROTO_TCP, localAddr, remoteAddr)
                    if (foundUid != -1) {
                        uid = foundUid
                    }
                }
            } catch (ignored: Exception) {
            }
        }

        if (uid == null) {
            uid = getUidForLocalPort(port)
        }

        if (uid == null) return emptyList()

        return try {
            val pm = context.packageManager
            val pkgs = pm.getPackagesForUid(uid)
            pkgs?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getAppNameForPackage(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
