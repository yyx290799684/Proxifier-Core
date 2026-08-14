package com.yangyx.proxy.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import com.yangyx.proxy.data.model.AppInfo
import com.yangyx.proxy.data.model.ProxyRule
import com.yangyx.proxy.data.model.ProxyServer
import com.yangyx.proxy.data.model.RuleAction
import com.yangyx.proxy.ui.theme.CyberPrimary
import com.yangyx.proxy.ui.theme.DirectColor
import com.yangyx.proxy.ui.theme.ProxyColor
import com.yangyx.proxy.ui.theme.RejectColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesScreen(
    rules: List<ProxyRule>,
    proxies: List<ProxyServer> = emptyList(),
    installedApps: List<AppInfo>,
    isLoadingApps: Boolean,
    onToggleRule: (ProxyRule) -> Unit,
    onMoveRuleUp: (ProxyRule) -> Unit = {},
    onMoveRuleDown: (ProxyRule) -> Unit = {},
    onSaveRule: (ProxyRule) -> Unit,
    onDeleteRule: (ProxyRule) -> Unit,
    onReloadApps: () -> Unit = {}
) {
    var editingRule by remember { mutableStateOf<ProxyRule?>(null) }
    var showRuleDialog by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var tempPackagesForPicker by remember { mutableStateOf<List<String>>(emptyList()) }

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
                        text = "分流规则 Routing Rules (${rules.size})",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            itemsIndexed(rules, key = { _, item -> item.id }) { index, rule ->
                var isExpanded by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rule_item_${rule.id}"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        text = "#${index + 1}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = when (rule.action) {
                                        RuleAction.PROXY -> ProxyColor.copy(alpha = 0.2f)
                                        RuleAction.DIRECT -> DirectColor.copy(alpha = 0.2f)
                                        RuleAction.REJECT -> RejectColor.copy(alpha = 0.2f)
                                    }
                                ) {
                                    Text(
                                        text = when (rule.action) {
                                            RuleAction.PROXY -> "代理 PROXY"
                                            RuleAction.DIRECT -> "直连 DIRECT"
                                            RuleAction.REJECT -> "拦截 BLOCK"
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = when (rule.action) {
                                            RuleAction.PROXY -> ProxyColor
                                            RuleAction.DIRECT -> DirectColor
                                            RuleAction.REJECT -> RejectColor
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                if (rule.action == RuleAction.PROXY && rule.ignoreIfProxyDown) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Text(
                                            text = "节点失效自动忽略",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = rule.name,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }

                            Switch(
                                checked = rule.isEnabled,
                                onCheckedChange = { onToggleRule(rule) }
                            )
                        }

                        // Target Proxy Node indicator if action is PROXY
                        if (rule.action == RuleAction.PROXY) {
                            Spacer(modifier = Modifier.height(6.dp))
                            val targetProxyName = if (rule.targetProxyId == null) {
                                "默认当前活动节点 (Default Active)"
                            } else {
                                proxies.find { it.id == rule.targetProxyId }?.let { "${it.name} (${it.type})" }
                                    ?: "关联节点 ID:${rule.targetProxyId}"
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "指定节点: $targetProxyName",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val allAppChips = rule.targetPackages
                        val allDomainChips = rule.targetDomains
                        val allIpChips = rule.targetIps
                        val totalCount = allAppChips.size + allDomainChips.size + allIpChips.size

                        val maxPreviewCount = 6
                        val needCollapse = totalCount > maxPreviewCount

                        // App packages chips
                        if (rule.targetPackages.isNotEmpty()) {
                            Text("关联应用 (Target Apps):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                val displayedApps = if (needCollapse && !isExpanded) allAppChips.take(maxPreviewCount) else allAppChips
                                displayedApps.forEach { pkg ->
                                    val appLabel = installedApps.find { it.packageName == pkg }?.appName ?: pkg
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(12.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(appLabel, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }

                        // Target Domains / IPs chips
                        if (rule.targetDomains.isNotEmpty() || rule.targetIps.isNotEmpty()) {
                            Text("域名 / IP 匹配:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                val remainingSlots = if (needCollapse && !isExpanded) (maxPreviewCount - allAppChips.size).coerceAtLeast(0) else Int.MAX_VALUE
                                var countUsed = 0

                                allDomainChips.forEach { domain ->
                                    if (countUsed < remainingSlots) {
                                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                            Text(domain, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                        }
                                        countUsed++
                                    }
                                }
                                allIpChips.forEach { ip ->
                                    if (countUsed < remainingSlots) {
                                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                            Text(ip, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                        }
                                        countUsed++
                                    }
                                }
                            }
                        }

                        if (needCollapse) {
                            TextButton(
                                onClick = { isExpanded = !isExpanded },
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Text(
                                    text = if (isExpanded) "收起规则条目" else "展开显示全部规则条目 (共 ${totalCount} 条)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row {
                                IconButton(
                                    onClick = { onMoveRuleUp(rule) },
                                    enabled = index > 0
                                ) {
                                    Icon(
                                        Icons.Default.ArrowUpward,
                                        contentDescription = "上移规则",
                                        modifier = Modifier.size(20.dp),
                                        tint = if (index > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                    )
                                }
                                IconButton(
                                    onClick = { onMoveRuleDown(rule) },
                                    enabled = index < rules.size - 1
                                ) {
                                    Icon(
                                        Icons.Default.ArrowDownward,
                                        contentDescription = "下移规则",
                                        modifier = Modifier.size(20.dp),
                                        tint = if (index < rules.size - 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                    )
                                }
                            }

                            Row {
                                IconButton(onClick = {
                                    editingRule = rule
                                    showRuleDialog = true
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "编辑规则", modifier = Modifier.size(20.dp))
                                }
                                IconButton(onClick = { onDeleteRule(rule) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除规则", tint = RejectColor, modifier = Modifier.size(20.dp))
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
                editingRule = null
                showRuleDialog = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .testTag("add_rule_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Rule")
        }
    }

    if (showRuleDialog) {
        RuleEditDialog(
            rule = editingRule,
            proxies = proxies,
            onDismiss = { showRuleDialog = false },
            onOpenAppPicker = { currentPkgs ->
                tempPackagesForPicker = currentPkgs
                onReloadApps()
                showAppPicker = true
            },
            onSave = { saved ->
                onSaveRule(saved)
                editingRule = null
                showRuleDialog = false
            }
        )
    }

    if (showAppPicker) {
        AppPickerSheet(
            installedApps = installedApps,
            isLoading = isLoadingApps,
            initialSelectedPackages = tempPackagesForPicker,
            onDismiss = { showAppPicker = false },
            onConfirmSelection = { selected ->
                editingRule = (editingRule ?: ProxyRule(name = "应用规则")).copy(targetPackages = selected)
                showAppPicker = false
            },
            onReloadApps = onReloadApps
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditDialog(
    rule: ProxyRule?,
    proxies: List<ProxyServer>,
    onDismiss: () -> Unit,
    onOpenAppPicker: (List<String>) -> Unit,
    onSave: (ProxyRule) -> Unit
) {
    var name by remember(rule?.id) { mutableStateOf(rule?.name ?: "") }
    var action by remember(rule?.id) { mutableStateOf(rule?.action ?: RuleAction.PROXY) }
    var targetProxyId by remember(rule?.id) { mutableStateOf<Long?>(rule?.targetProxyId) }
    var targetDomainsInput by remember(rule?.id) { mutableStateOf(rule?.targetDomains?.joinToString("; ") ?: "") }
    var targetIpsInput by remember(rule?.id) { mutableStateOf(rule?.targetIps?.joinToString("; ") ?: "") }
    var selectedPackages by remember(rule?.id) { mutableStateOf(rule?.targetPackages ?: emptyList()) }
    var ignoreIfProxyDown by remember(rule?.id) { mutableStateOf(rule?.ignoreIfProxyDown ?: false) }

    androidx.compose.runtime.LaunchedEffect(rule?.targetPackages) {
        if (rule?.targetPackages != null) {
            selectedPackages = rule.targetPackages
        }
    }

    var expandedProxyDropdown by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule == null) "新建分流规则" else "编辑分流规则") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("规则名称 (Rule Name)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text("规则行为 (Action):", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = action == RuleAction.PROXY, onClick = { action = RuleAction.PROXY })
                        Text("代理")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = action == RuleAction.DIRECT, onClick = { action = RuleAction.DIRECT })
                        Text("直连")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = action == RuleAction.REJECT, onClick = { action = RuleAction.REJECT })
                        Text("拦截")
                    }
                }

                // If action is PROXY, allow selecting WHICH Proxy Server!
                if (action == RuleAction.PROXY) {
                    Text("选择关联代理服务器:", style = MaterialTheme.typography.labelLarge)
                    ExposedDropdownMenuBox(
                        expanded = expandedProxyDropdown,
                        onExpandedChange = { expandedProxyDropdown = !expandedProxyDropdown },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val currentLabel = if (targetProxyId == null) {
                            "默认活动节点 (Default Active Proxy)"
                        } else {
                            proxies.find { it.id == targetProxyId }?.let { "${it.name} (${it.type} • ${it.host}:${it.port})" }
                                ?: "指定节点 ID: $targetProxyId"
                        }

                        OutlinedTextField(
                            value = currentLabel,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedProxyDropdown) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )

                        ExposedDropdownMenu(
                            expanded = expandedProxyDropdown,
                            onDismissRequest = { expandedProxyDropdown = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("默认当前活动节点 (Default Active Proxy)") },
                                onClick = {
                                    targetProxyId = null
                                    expandedProxyDropdown = false
                                }
                            )

                            proxies.forEach { proxy ->
                                DropdownMenuItem(
                                    text = { Text("${proxy.name} (${proxy.type} • ${proxy.host}:${proxy.port})") },
                                    onClick = {
                                        targetProxyId = proxy.id
                                        expandedProxyDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { onOpenAppPicker(selectedPackages) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Apps, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (selectedPackages.isEmpty()) "选择目标应用 (Select Apps)"
                        else "已选择 ${selectedPackages.size} 个应用"
                    )
                }

                if (action == RuleAction.PROXY) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("节点失效时自动忽略本规则", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            Text("如果关联节点连通性失败，自动转为直连/后退分流", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = ignoreIfProxyDown,
                            onCheckedChange = { ignoreIfProxyDown = it }
                        )
                    }
                }

                OutlinedTextField(
                    value = targetDomainsInput,
                    onValueChange = { targetDomainsInput = it },
                    label = { Text("域名列表 (分号分隔, 如 *.google.com; *.baidu.com)") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = targetIpsInput,
                    onValueChange = { targetIpsInput = it },
                    label = { Text("IP / 网段 (分号分隔, 如 192.168.1.0/24; 10.0.0.0/8)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        val domains = targetDomainsInput.split(";", ",").map { it.trim() }.filter { it.isNotBlank() }
                        val ips = targetIpsInput.split(";", ",").map { it.trim() }.filter { it.isNotBlank() }
                        onSave(
                            ProxyRule(
                                id = rule?.id ?: 0,
                                name = name,
                                action = action,
                                targetProxyId = if (action == RuleAction.PROXY) targetProxyId else null,
                                targetPackages = selectedPackages,
                                targetDomains = domains,
                                targetIps = ips,
                                ignoreIfProxyDown = ignoreIfProxyDown
                            )
                        )
                    }
                },
                modifier = Modifier.testTag("save_rule_button")
            ) {
                Text("保存规则")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
