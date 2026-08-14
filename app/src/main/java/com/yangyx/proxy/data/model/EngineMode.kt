package com.yangyx.proxy.data.model

enum class EngineMode(val title: String, val description: String) {
    VPN_SERVICE(
        title = "VPN Service (Non-Root)",
        description = "Uses Android VpnService API for local traffic interception without root."
    ),
    KERNELSU_TRANSPARENT(
        title = "KernelSU / Root Transparent",
        description = "Uses iptables / KernelSU root transparent proxy redirect rules for low latency."
    )
}
