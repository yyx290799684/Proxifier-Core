package com.yangyx.proxy.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ProxyType {
    SOCKS5,
    HTTP
}

@Entity(tableName = "proxy_servers")
data class ProxyServer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: ProxyType = ProxyType.SOCKS5,
    val host: String,
    val port: Int = 1080,
    val username: String? = null,
    val password: String? = null,
    val enableUdp: Boolean = true,
    val isActive: Boolean = false,
    val latencyMs: Int? = null, // -1 for timeout/fail, null for untested
    val colorTagHex: String = "#3B82F6"
)

val ProxyServer.cleanHost: String
    get() {
        val trimmed = host.trim()
        return if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

val ProxyServer.displayHost: String
    get() {
        val ch = cleanHost
        return if (ch.contains(":")) "[$ch]" else ch
    }

val ProxyServer.isOnline: Boolean
    get() = latencyMs != null && latencyMs >= 0

