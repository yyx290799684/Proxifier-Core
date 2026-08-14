package com.yangyx.proxy.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yangyx.proxy.data.model.ConnectionLog
import com.yangyx.proxy.ui.theme.DirectColor
import com.yangyx.proxy.ui.theme.ProxyColor
import com.yangyx.proxy.ui.theme.RejectColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(
    logs: List<ConnectionLog>,
    showHexInLogs: Boolean = false,
    onToggleShowHex: (Boolean) -> Unit = {},
    onClearLogs: () -> Unit
) {
    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf("ALL") }
    var selectedLogForDetail by remember { mutableStateOf<ConnectionLog?>(null) }

    val filteredLogs = remember(logs, selectedFilter) {
        when (selectedFilter) {
            "PROXIED" -> logs.filter { it.actionApplied.startsWith("PROXY") }
            "DIRECT" -> logs.filter { it.actionApplied == "DIRECT" }
            "BLOCKED" -> logs.filter { it.actionApplied == "REJECT" || it.actionApplied == "BLOCK" }
            else -> logs
        }
    }

    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "已复制诊断日志到剪贴板", Toast.LENGTH_SHORT).show()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "抓包与流量诊断日志 (${filteredLogs.size})",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Row {
                        IconButton(
                            onClick = {
                                if (logs.isEmpty()) {
                                    Toast.makeText(context, "暂无可导出的日志", Toast.LENGTH_SHORT).show()
                                    return@IconButton
                                }
                                val report = buildString {
                                    appendLine("=== Proxifier Android 抓包诊断报告 ===")
                                    appendLine("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                                    appendLine("总日志数: ${logs.size}")
                                    appendLine()
                                    logs.take(50).forEachIndexed { i, log ->
                                        appendLine("[$i] Time: ${timeFormatter.format(Date(log.timestamp))}")
                                        if (log.appName.isNotBlank()) {
                                            appendLine("    App: ${log.appName} (${log.packageName})")
                                        }
                                        appendLine("    Target: ${log.destHost}:${log.destPort} [Protocol: ${log.protocol}]")
                                        appendLine("    Action: ${log.actionApplied} | Proxy: ${log.proxyNameUsed ?: "None"}")
                                        appendLine("    Status: ${log.status}")
                                        if (!log.handshakeDetail.isNullOrBlank()) appendLine("    Handshake: ${log.handshakeDetail}")
                                        if (!log.detailError.isNullOrBlank()) appendLine("    Error: ${log.detailError}")
                                        if (showHexInLogs && !log.packetHex.isNullOrBlank()) appendLine("    Hex: ${log.packetHex}")
                                        appendLine("----------------------------------------")
                                    }
                                }
                                copyToClipboard("PacketDiagnosticsReport", report)
                            },
                            modifier = Modifier.testTag("export_logs_button")
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "导出诊断报告")
                        }
                        IconButton(
                            onClick = onClearLogs,
                            modifier = Modifier.testTag("clear_logs_button")
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "清空日志")
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = selectedFilter == "ALL", onClick = { selectedFilter = "ALL" }, label = { Text("全部") })
                        FilterChip(selected = selectedFilter == "PROXIED", onClick = { selectedFilter = "PROXIED" }, label = { Text("代理") })
                        FilterChip(selected = selectedFilter == "DIRECT", onClick = { selectedFilter = "DIRECT" }, label = { Text("直连") })
                        FilterChip(selected = selectedFilter == "BLOCKED", onClick = { selectedFilter = "BLOCKED" }, label = { Text("已拦截") })
                    }

                    FilterChip(
                        selected = showHexInLogs,
                        onClick = { onToggleShowHex(!showHexInLogs) },
                        label = { Text(if (showHexInLogs) "Hex 报文: 显示" else "Hex 报文: 隐藏") },
                        modifier = Modifier.testTag("toggle_hex_chip")
                    )
                }
            }

            if (filteredLogs.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂无流量分流及抓包记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                items(filteredLogs, key = { it.id }) { log ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedLogForDetail = log },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (log.status == "FAILED_HANDSHAKE") MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = timeFormatter.format(Date(log.timestamp)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (log.appName.isNotBlank()) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = log.appName,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                    if (log.protocol.isNotBlank()) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                text = log.protocol,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = when {
                                        log.status == "FAILED_HANDSHAKE" -> RejectColor.copy(alpha = 0.2f)
                                        log.actionApplied.startsWith("PROXY") -> ProxyColor.copy(alpha = 0.2f)
                                        log.actionApplied == "DIRECT" -> DirectColor.copy(alpha = 0.2f)
                                        else -> RejectColor.copy(alpha = 0.2f)
                                    }
                                ) {
                                    Text(
                                        text = if (log.status == "FAILED_HANDSHAKE") "握手失败" else log.actionApplied,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when {
                                            log.status == "FAILED_HANDSHAKE" -> RejectColor
                                            log.actionApplied.startsWith("PROXY") -> ProxyColor
                                            log.actionApplied == "DIRECT" -> DirectColor
                                            else -> RejectColor
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${log.destHost}:${log.destPort}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (!log.detailError.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "错误: ${log.detailError}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else if (showHexInLogs && !log.packetHex.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Hex: ${log.packetHex}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    // Diagnostic Detail Dialog
    selectedLogForDetail?.let { log ->
        AlertDialog(
            onDismissRequest = { selectedLogForDetail = null },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text("抓包与协议诊断详情", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (log.appName.isNotBlank()) {
                        item {
                            Text("应用信息:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            Text("${log.appName} (${log.packageName})", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item {
                        Text("目标地址 & 协议:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        Text("${log.destHost}:${log.destPort} [${log.protocol}]", style = MaterialTheme.typography.bodyMedium)
                    }
                    item {
                        Text("匹配策略与代理:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        Text("${log.actionApplied} -> ${log.proxyNameUsed ?: "直连"}", style = MaterialTheme.typography.bodyMedium)
                    }
                    item {
                        Text("状态:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = log.status,
                            color = if (log.status == "FAILED_HANDSHAKE") RejectColor else ProxyColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (!log.handshakeDetail.isNullOrBlank()) {
                        item {
                            Text("握手/诊断日志:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = log.handshakeDetail,
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                        }
                    }
                    if (!log.detailError.isNullOrBlank()) {
                        item {
                            Text("失败原因:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = log.detailError,
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                    if (showHexInLogs && !log.packetHex.isNullOrBlank()) {
                        item {
                            Text("数据包原始报文 (Payload Hex & ASCII):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = log.packetHex,
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        val singleReport = buildString {
                            appendLine("=== 单条抓包诊断日志 ===")
                            appendLine("Time: ${timeFormatter.format(Date(log.timestamp))}")
                            appendLine("App: ${log.appName} (${log.packageName})")
                            appendLine("Target: ${log.destHost}:${log.destPort} [Protocol: ${log.protocol}]")
                            appendLine("Action: ${log.actionApplied} | Proxy: ${log.proxyNameUsed ?: "None"}")
                            appendLine("Status: ${log.status}")
                            if (!log.handshakeDetail.isNullOrBlank()) appendLine("Handshake: ${log.handshakeDetail}")
                            if (!log.detailError.isNullOrBlank()) appendLine("Error: ${log.detailError}")
                            if (!log.packetHex.isNullOrBlank()) appendLine("Hex: ${log.packetHex}")
                        }
                        copyToClipboard("SingleLogDiagnostics", singleReport)
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("复制本条日志")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedLogForDetail = null }) {
                    Text("关闭")
                }
            }
        )
    }
}

