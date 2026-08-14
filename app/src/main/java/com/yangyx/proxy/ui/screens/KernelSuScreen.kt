package com.yangyx.proxy.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.yangyx.proxy.ui.theme.CyberPrimary
import com.yangyx.proxy.ui.theme.DirectColor
import com.yangyx.proxy.ui.theme.RejectColor
import java.io.File

@Composable
fun KernelSuScreen(
    isRootAvailable: Boolean,
    isKernelSuAvailable: Boolean,
    rootShellLog: String,
    isBootAutoStart: Boolean = true,
    onToggleBootAutoStart: (Boolean) -> Unit = {},
    onApplyRules: () -> Unit,
    onFlushRules: () -> Unit,
    onExportModule: ((File) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var exportedZipFile by remember { mutableStateOf<File?>(null) }
    var showExportSuccessDialog by remember { mutableStateOf(false) }

    if (showExportSuccessDialog && exportedZipFile != null) {
        val targetFile = exportedZipFile!!
        AlertDialog(
            onDismissRequest = { showExportSuccessDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = DirectColor,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            },
            title = {
                Text(
                    text = "KernelSU 模块导出成功",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "刷机模块 ZIP 已生成并保存至手机系统的 下载 (Download) 目录：",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = targetFile.absolutePath,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(10.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Text(
                        text = "操作指引：打开 KernelSU 或 Magisk 管理器 -> 点击【模块】页面 -> 选择【从本地安装】 -> 选择上述 Download 文件夹中的 Proxifier_KernelSU_Module.zip 即可刷入使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        runCatching {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                targetFile
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "分享 KernelSU 模块文件"))
                        }
                    }
                ) {
                    Text("分享 / 发送模块")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportSuccessDialog = false }) {
                    Text("我知道了")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }

        // Root Status Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRootAvailable) DirectColor.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isRootAvailable) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isRootAvailable) DirectColor else RejectColor,
                        modifier = Modifier.padding(end = 16.dp)
                    )

                    Column {
                        Text(
                            text = when {
                                isKernelSuAvailable -> "已检测到 KernelSU"
                                isRootAvailable -> "已获取 Root (su) 权限"
                                else -> "未检测到 Root 权限"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isRootAvailable) DirectColor else RejectColor
                        )

                        Text(
                            text = when {
                                isRootAvailable -> "可以直接应用 Root 透明代理 iptables 规则。"
                                else -> "Root 模式不可用，请使用免 Root 的 VPN 模式。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Boot Auto-Start & Background 保活 Card
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Autorenew,
                            contentDescription = null,
                            tint = CyberPrimary,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Column {
                            Text(
                                text = "开机自启动与后台保活",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "系统开机自动后台开启透明代理服务并挂载 iptables 规则，无需手动打开 App",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = isBootAutoStart,
                        onCheckedChange = onToggleBootAutoStart,
                        modifier = Modifier.testTag("boot_auto_start_switch")
                    )
                }
            }
        }

        // Direct Root Controls
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Root 透明代理控制",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onApplyRules,
                            enabled = isRootAvailable,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("apply_root_rules_button")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("应用 iptables")
                        }

                        OutlinedButton(
                            onClick = onFlushRules,
                            enabled = isRootAvailable,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("flush_root_rules_button")
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, tint = RejectColor)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("清除 iptables")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            onExportModule { zipFile ->
                                exportedZipFile = zipFile
                                showExportSuccessDialog = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_ksu_module_button")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导出 KernelSU 模块 (ZIP)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "✨ 模块已升级为【一劳永逸动态桥接版】：在 KernelSU / Magisk 刷入一次模块后，后续在 App 内调整代理规则或切换节点，点击【应用 iptables】即可实时生效，无需重复导出刷机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = CyberPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "网络协议说明 (RDP / UDP 流量)",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "1. Root 透明代理模式依赖 iptables -t nat REDIRECT 重定向，仅截获 TCP 流量。\n2. Windows RDP 客户端默认优先使用 UDP 3389 传输以优化帧率。若需通过代理连接 RDP，请在 RDP 应用设置中选择“禁用 UDP”或“仅使用 TCP 连接”；或将 App 引擎模式切换为 VPN 模式 (VpnService) 以同时接管 TCP 和 UDP 全量流量。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Shell Output Log Terminal
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Shell 终端输出控制台",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = rootShellLog.ifEmpty { "暂无执行的 Shell 命令控制台日志。" },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}
