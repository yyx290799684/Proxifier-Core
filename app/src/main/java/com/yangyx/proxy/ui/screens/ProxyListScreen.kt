package com.yangyx.proxy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yangyx.proxy.data.model.ProxyServer
import com.yangyx.proxy.data.model.ProxyType
import com.yangyx.proxy.data.model.displayHost
import com.yangyx.proxy.ui.theme.DirectColor
import com.yangyx.proxy.ui.theme.RejectColor

@Composable
fun ProxyListScreen(
    proxies: List<ProxyServer>,
    onSelectActive: (ProxyServer) -> Unit,
    onTestLatency: (ProxyServer) -> Unit,
    onTestAll: () -> Unit,
    onSaveProxy: (ProxyServer) -> Unit,
    onDeleteProxy: (ProxyServer) -> Unit
) {
    var editingProxy by remember { mutableStateOf<ProxyServer?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "代理节点 (${proxies.size})",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onTestAll,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("test_all_latencies_button")
                    ) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = "一键测速",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "一键测速",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            items(proxies, key = { it.id }) { proxy ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("proxy_item_${proxy.id}"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Router,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = proxy.name,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${proxy.type} • ${proxy.displayHost}:${proxy.port}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Latency Badge
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = when {
                                    proxy.latencyMs == null -> MaterialTheme.colorScheme.surfaceVariant
                                    proxy.latencyMs < 0 -> RejectColor.copy(alpha = 0.2f)
                                    proxy.latencyMs < 120 -> DirectColor.copy(alpha = 0.2f)
                                    else -> Color(0xFFF59E0B).copy(alpha = 0.2f)
                                },
                                modifier = Modifier.clickable { onTestLatency(proxy) }
                            ) {
                                Text(
                                    text = when {
                                        proxy.latencyMs == null -> "测速"
                                        proxy.latencyMs < 0 -> "超时"
                                        else -> "${proxy.latencyMs} ms"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = when {
                                        proxy.latencyMs == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                        proxy.latencyMs < 0 -> RejectColor
                                        proxy.latencyMs < 120 -> DirectColor
                                        else -> Color(0xFFD97706)
                                    },
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = if (proxy.enableUdp) "支持 UDP" else "仅 TCP",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                if (!proxy.username.isNullOrEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = "需身份认证",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Row {
                                IconButton(onClick = {
                                    editingProxy = proxy
                                    showDialog = true
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit Proxy", modifier = Modifier.size(20.dp))
                                }
                                IconButton(onClick = { onDeleteProxy(proxy) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete Proxy", tint = RejectColor, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }

        FloatingActionButton(
            onClick = {
                editingProxy = null
                showDialog = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .testTag("add_proxy_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Proxy")
        }
    }

    if (showDialog) {
        ProxyEditDialog(
            proxy = editingProxy,
            onDismiss = { showDialog = false },
            onSave = { saved ->
                onSaveProxy(saved)
                showDialog = false
            }
        )
    }
}

@Composable
fun ProxyEditDialog(
    proxy: ProxyServer?,
    onDismiss: () -> Unit,
    onSave: (ProxyServer) -> Unit
) {
    var name by remember { mutableStateOf(proxy?.name ?: "") }
    var host by remember { mutableStateOf(proxy?.host ?: "") }
    var port by remember { mutableStateOf(proxy?.port?.toString() ?: "1080") }
    var username by remember { mutableStateOf(proxy?.username ?: "") }
    var password by remember { mutableStateOf(proxy?.password ?: "") }
    var proxyType by remember { mutableStateOf(proxy?.type ?: ProxyType.SOCKS5) }
    var enableUdp by remember { mutableStateOf(proxy?.enableUdp ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (proxy == null) "添加代理服务器" else "编辑代理服务器") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("节点名称 (Server Name)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = proxyType == ProxyType.SOCKS5,
                            onClick = { proxyType = ProxyType.SOCKS5 }
                        )
                        Text("SOCKS5")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = proxyType == ProxyType.HTTP,
                            onClick = { proxyType = ProxyType.HTTP }
                        )
                        Text("HTTP")
                    }
                }

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("服务器地址 Host / IP") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("端口 Port (例: 1080)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名 Username (可选)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码 Password (可选)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = enableUdp,
                        onCheckedChange = { enableUdp = it }
                    )
                    Text("启用 UDP 转发 (UDP Relay)")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && host.isNotBlank()) {
                        val parsedPort = port.toIntOrNull() ?: 1080
                        onSave(
                            ProxyServer(
                                id = proxy?.id ?: 0,
                                name = name,
                                type = proxyType,
                                host = host,
                                port = parsedPort,
                                username = username.ifBlank { null },
                                password = password.ifBlank { null },
                                enableUdp = enableUdp,
                                isActive = proxy?.isActive ?: false,
                                latencyMs = proxy?.latencyMs
                            )
                        )
                    }
                },
                modifier = Modifier.testTag("save_proxy_button")
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
