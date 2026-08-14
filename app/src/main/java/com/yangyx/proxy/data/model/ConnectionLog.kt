package com.yangyx.proxy.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connection_logs")
data class ConnectionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val appName: String,
    val packageName: String,
    val destHost: String,
    val destPort: Int,
    val actionApplied: String, // "PROXY (US Node)", "DIRECT", "REJECT"
    val proxyNameUsed: String? = null,
    val bytesUploaded: Long = 0,
    val bytesDownloaded: Long = 0,
    val status: String = "CONNECTED", // "CONNECTED", "ACTIVE", "CLOSED", "REJECTED", "FAILED_HANDSHAKE"
    val packetHex: String? = null,
    val handshakeDetail: String? = null,
    val protocol: String = "TCP",
    val detailError: String? = null
)
