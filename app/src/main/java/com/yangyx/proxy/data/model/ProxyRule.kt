package com.yangyx.proxy.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class RuleAction {
    PROXY,
    DIRECT,
    REJECT
}

@Entity(tableName = "proxy_rules")
data class ProxyRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isEnabled: Boolean = true,
    val priority: Int = 0,
    val action: RuleAction = RuleAction.PROXY,
    val targetProxyId: Long? = null, // null = active default proxy server
    val targetPackages: List<String> = emptyList(), // e.g. ["com.android.chrome"]
    val targetDomains: List<String> = emptyList(), // e.g. ["*.google.com", "github.com"]
    val targetIps: List<String> = emptyList(),     // e.g. ["192.168.1.0/24", "8.8.8.8"]
    val targetPorts: List<String> = emptyList(),    // e.g. ["80", "443", "8080-8090"]
    val ignoreIfProxyDown: Boolean = false          // auto-bypass rule when proxy is unreachable/down
)
