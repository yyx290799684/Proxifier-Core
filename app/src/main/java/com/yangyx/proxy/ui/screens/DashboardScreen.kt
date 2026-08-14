package com.yangyx.proxy.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yangyx.proxy.data.model.ConnectionLog
import com.yangyx.proxy.data.model.EngineMode
import com.yangyx.proxy.data.model.ProxyServer
import com.yangyx.proxy.ui.theme.CyberPrimary
import com.yangyx.proxy.ui.theme.CyberSecondary
import com.yangyx.proxy.ui.theme.DirectColor
import com.yangyx.proxy.ui.theme.ProxyColor
import com.yangyx.proxy.ui.theme.RejectColor

import com.yangyx.proxy.data.model.displayHost

@Composable
fun DashboardScreen(
    activeProxy: ProxyServer?,
    engineMode: EngineMode,
    isVpnRunning: Boolean,
    isRootAvailable: Boolean,
    isKernelSuAvailable: Boolean,
    bytesUpSec: Long,
    bytesDownSec: Long,
    totalBytesUp: Long,
    totalBytesDown: Long,
    recentLogs: List<ConnectionLog>,
    lastError: String? = null,
    onToggleEngine: () -> Unit,
    onSelectMode: (EngineMode) -> Unit,
    onNavigateProxies: () -> Unit,
    onNavigateRules: () -> Unit,
    onClearLastError: () -> Unit = {},
    onForceReset: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isVpnRunning) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val buttonColor by animateColorAsState(
        targetValue = if (isVpnRunning) DirectColor else MaterialTheme.colorScheme.surfaceVariant,
        label = "btnColor"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }

        if (!lastError.isNullOrEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("error_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Error",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "代理服务启动/运行异常",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = lastError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = onForceReset,
                                modifier = Modifier.testTag("btn_force_reset")
                            ) {
                                Text("强制清理残留网络")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = onClearLastError,
                                modifier = Modifier.testTag("btn_dismiss_error")
                            ) {
                                Text("知道了")
                            }
                        }
                    }
                }
            }
        }

        // Mode Selector Pills
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "工作模式 (Working Mode)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectMode(EngineMode.VPN_SERVICE) }
                                .testTag("mode_vpn_pill"),
                            color = if (engineMode == EngineMode.VPN_SERVICE) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.Security,
                                    contentDescription = null,
                                    tint = if (engineMode == EngineMode.VPN_SERVICE) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "VPN模式 (免Root)",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (engineMode == EngineMode.VPN_SERVICE) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectMode(EngineMode.KERNELSU_TRANSPARENT) }
                                .testTag("mode_ksu_pill"),
                            color = if (engineMode == EngineMode.KERNELSU_TRANSPARENT) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.Router,
                                    contentDescription = null,
                                    tint = if (engineMode == EngineMode.KERNELSU_TRANSPARENT) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "KernelSU (Root透明)",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (engineMode == EngineMode.KERNELSU_TRANSPARENT) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // Power Toggle Button Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .scale(pulseScale)
                            .size(110.dp)
                            .clip(CircleShape)
                            .background(buttonColor)
                            .clickable { onToggleEngine() }
                            .testTag("power_toggle_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            contentDescription = "切换代理状态",
                            modifier = Modifier.size(54.dp),
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (isVpnRunning) "已开启连接" else "服务已停止",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        ),
                        color = if (isVpnRunning) DirectColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = if (isVpnRunning) "代理内核正在接管并分流网络流量"
                        else "点击按钮启动 ${if (engineMode == EngineMode.VPN_SERVICE) "VPN模式" else "KernelSU模式"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Quick Settings Tile Hint
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Widgets,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "已支持下拉控制栏快捷磁贴：可在系统控制栏添加『Proxifier 开关』磁铁，快捷一键开关代理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Speed Metrics Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Upload
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(CyberPrimary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = CyberPrimary)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("上传速度", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatSpeed(bytesUpSec), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text("累计: ${formatBytes(totalBytesUp)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Download
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(DirectColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = DirectColor)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("下载速度", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatSpeed(bytesDownSec), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text("累计: ${formatBytes(totalBytesDown)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Rule-Based Routing Control Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateRules() },
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Router, contentDescription = null, tint = CyberSecondary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("分流路由模式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "按分流规则智能匹配代理节点",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "应用、域名及 IP 匹配规则自动分发",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedButton(onClick = onNavigateRules) {
                        Text("规则管理")
                    }
                }
            }
        }

        // Recent Activity Mini Feed
        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("实时流量日志", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text(
                        text = "查看规则",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onNavigateRules() }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (recentLogs.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂无抓取到的连接日志", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    recentLogs.take(5).forEach { log ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(log.appName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                    Text("${log.destHost}:${log.destPort}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = when {
                                        log.actionApplied.startsWith("PROXY") -> ProxyColor.copy(alpha = 0.2f)
                                        log.actionApplied == "DIRECT" -> DirectColor.copy(alpha = 0.2f)
                                        else -> RejectColor.copy(alpha = 0.2f)
                                    }
                                ) {
                                    Text(
                                        text = log.actionApplied,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when {
                                            log.actionApplied.startsWith("PROXY") -> ProxyColor
                                            log.actionApplied == "DIRECT" -> DirectColor
                                            else -> RejectColor
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024f * 1024f))
        bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024f)
        else -> "$bytesPerSec B/s"
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024f * 1024f * 1024f))
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }
}
