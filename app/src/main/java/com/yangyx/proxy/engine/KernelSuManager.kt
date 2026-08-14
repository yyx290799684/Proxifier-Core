package com.yangyx.proxy.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.yangyx.proxy.data.model.ProxyRule
import com.yangyx.proxy.data.model.ProxyServer
import com.yangyx.proxy.data.model.RuleAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object KernelSuManager {

    /**
     * Applies iptables transparent proxy rules on root and syncs dynamic rules script to system storage.
     */
    suspend fun applyRules(
        context: Context,
        server: ProxyServer?,
        rules: List<ProxyRule>,
        localListenPort: Int = 1080
    ): ShellResult = withContext(Dispatchers.IO) {
        val script = buildIptablesScript(context, server, rules, localListenPort)

        // Write script to local cache file first
        val localScriptFile = File(context.cacheDir, "rules.sh")
        runCatching {
            localScriptFile.writeText(script, Charsets.UTF_8)
        }

        val syncAndExecScript = """
            mkdir -p /data/adb/proxifier
            cp "${localScriptFile.absolutePath}" /data/adb/proxifier/rules.sh
            chmod 755 /data/adb/proxifier/rules.sh
            sh /data/adb/proxifier/rules.sh
        """.trimIndent()

        RootShellManager.execute(syncAndExecScript)
    }

    /**
     * Flushes and cleans up Proxifier iptables chains.
     */
    suspend fun flushRules(): ShellResult = withContext(Dispatchers.IO) {
        val cleanScript = """
            while iptables -w -t nat -D OUTPUT -j PROXIFIER_OUT 2>/dev/null; do :; done
            while iptables -w -t nat -D PREROUTING -j PROXIFIER_OUT 2>/dev/null; do :; done
            iptables -w -t nat -F PROXIFIER_OUT >/dev/null 2>&1 || true
            iptables -w -t nat -X PROXIFIER_OUT >/dev/null 2>&1 || true
            
            while ip6tables -w -t nat -D OUTPUT -j PROXIFIER_OUT 2>/dev/null; do :; done
            while ip6tables -w -t nat -D PREROUTING -j PROXIFIER_OUT 2>/dev/null; do :; done
            ip6tables -w -t nat -F PROXIFIER_OUT >/dev/null 2>&1 || true
            ip6tables -w -t nat -X PROXIFIER_OUT >/dev/null 2>&1 || true

            mkdir -p /data/adb/proxifier
            echo '#!/system/bin/sh' > /data/adb/proxifier/rules.sh
            echo 'while iptables -w -t nat -D OUTPUT -j PROXIFIER_OUT 2>/dev/null; do :; done' >> /data/adb/proxifier/rules.sh
            echo 'while iptables -w -t nat -D PREROUTING -j PROXIFIER_OUT 2>/dev/null; do :; done' >> /data/adb/proxifier/rules.sh
            echo 'iptables -w -t nat -F PROXIFIER_OUT >/dev/null 2>&1 || true' >> /data/adb/proxifier/rules.sh
            echo 'iptables -w -t nat -X PROXIFIER_OUT >/dev/null 2>&1 || true' >> /data/adb/proxifier/rules.sh
            chmod 755 /data/adb/proxifier/rules.sh

            echo "Proxifier rules flushed successfully"
        """.trimIndent()
        RootShellManager.execute(cleanScript)
    }

    /**
     * Generates bash script for iptables transparent proxy routing.
     */
    fun buildIptablesScript(
        context: Context,
        server: ProxyServer?,
        rules: List<ProxyRule>,
        localListenPort: Int = 1080
    ): String {
        val pm = context.packageManager
        val sb = StringBuilder()

        sb.appendLine("#!/system/bin/sh")
        sb.appendLine("# Proxifier Core - KernelSU / Root Transparent Proxy Rules")
        sb.appendLine("while iptables -w -t nat -D OUTPUT -j PROXIFIER_OUT 2>/dev/null; do :; done")
        sb.appendLine("while iptables -w -t nat -D PREROUTING -j PROXIFIER_OUT 2>/dev/null; do :; done")
        sb.appendLine("iptables -w -t nat -N PROXIFIER_OUT >/dev/null 2>&1 || true")
        sb.appendLine("iptables -w -t nat -F PROXIFIER_OUT >/dev/null 2>&1 || true")
        sb.appendLine("while ip6tables -w -t nat -D OUTPUT -j PROXIFIER_OUT 2>/dev/null; do :; done")
        sb.appendLine("while ip6tables -w -t nat -D PREROUTING -j PROXIFIER_OUT 2>/dev/null; do :; done")
        sb.appendLine("ip6tables -w -t nat -N PROXIFIER_OUT >/dev/null 2>&1 || true")
        sb.appendLine("ip6tables -w -t nat -F PROXIFIER_OUT >/dev/null 2>&1 || true")
        sb.appendLine()
        sb.appendLine("supolicy --live 'allow untrusted_app proc_net file { read open getattr }' >/dev/null 2>&1 || true")
        sb.appendLine("supolicy --live 'allow untrusted_app proc_net dir { read open search getattr }' >/dev/null 2>&1 || true")
        sb.appendLine("supolicy --live 'allow untrusted_app_all proc_net file { read open getattr }' >/dev/null 2>&1 || true")
        sb.appendLine("supolicy --live 'allow untrusted_app_all proc_net dir { read open search getattr }' >/dev/null 2>&1 || true")
        sb.appendLine("magiskpolicy --live 'allow untrusted_app proc_net file { read open getattr }' >/dev/null 2>&1 || true")
        sb.appendLine("magiskpolicy --live 'allow untrusted_app proc_net dir { read open search getattr }' >/dev/null 2>&1 || true")
        sb.appendLine("magiskpolicy --live 'allow untrusted_app_all proc_net file { read open getattr }' >/dev/null 2>&1 || true")
        sb.appendLine("magiskpolicy --live 'allow untrusted_app_all proc_net dir { read open search getattr }' >/dev/null 2>&1 || true")
        sb.appendLine("chmod 777 /proc/net >/dev/null 2>&1 || true")
        sb.appendLine("chmod 666 /proc/net/nf_conntrack /proc/net/ip_conntrack /proc/net/tcp /proc/net/tcp6 /proc/net/udp >/dev/null 2>&1 || true")
        sb.appendLine()

        // Bypass Self Application UID to prevent infinite proxy loop
        val myUid = context.applicationInfo.uid
        sb.appendLine("# Bypass Proxy App UID ($myUid)")
        sb.appendLine("iptables -w -t nat -A PROXIFIER_OUT -m owner --uid-owner $myUid -j RETURN")
        sb.appendLine()

        // Bypass Local System Special Ranges and Proxy Server Host
        sb.appendLine("# Bypass Local System Ranges")
        sb.appendLine("iptables -w -t nat -A PROXIFIER_OUT -d 0.0.0.0/8 -j RETURN")
        sb.appendLine("iptables -w -t nat -A PROXIFIER_OUT -d 127.0.0.0/8 -j RETURN")
        sb.appendLine("iptables -w -t nat -A PROXIFIER_OUT -d 169.254.0.0/16 -j RETURN")
        sb.appendLine("iptables -w -t nat -A PROXIFIER_OUT -d 224.0.0.0/4 -j RETURN")
        sb.appendLine("iptables -w -t nat -A PROXIFIER_OUT -d 240.0.0.0/4 -j RETURN")

        if (server != null && server.host.matches(Regex("^[0-9.]+$"))) {
            sb.appendLine("iptables -w -t nat -A PROXIFIER_OUT -d ${server.host} -j RETURN")
        }

        // Apply rules in priority order
        val activeRules = rules.filter { it.isEnabled }
        sb.appendLine()
        sb.appendLine("# Active App & IP Routing Rules")

        for (rule in activeRules) {
            sb.appendLine("# Rule: ${rule.name} [Action: ${rule.action}]")

            val uids = mutableListOf<Int>()
            for (pkg in rule.targetPackages) {
                runCatching {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    uids.add(appInfo.uid)
                }
            }

            for (uid in uids) {
                when (rule.action) {
                    RuleAction.DIRECT -> {
                        sb.appendLine("iptables -w -t nat -A PROXIFIER_OUT -m owner --uid-owner $uid -j RETURN")
                    }
                    RuleAction.REJECT -> {
                        sb.appendLine("iptables -w -t nat -A PROXIFIER_OUT -m owner --uid-owner $uid -p tcp -j REDIRECT --to-ports $localListenPort")
                    }
                    RuleAction.PROXY -> {
                        sb.appendLine("iptables -w -t nat -A PROXIFIER_OUT -m owner --uid-owner $uid -p tcp -j REDIRECT --to-ports $localListenPort")
                    }
                }
            }

            for (ip in rule.targetIps) {
                if (ip.isNotBlank()) {
                    when (rule.action) {
                        RuleAction.DIRECT -> sb.appendLine("iptables -w -t nat -A PROXIFIER_OUT -d $ip -j RETURN")
                        RuleAction.REJECT -> sb.appendLine("iptables -w -t nat -A PROXIFIER_OUT -d $ip -p tcp -j REDIRECT --to-ports $localListenPort")
                        RuleAction.PROXY -> sb.appendLine("iptables -w -t nat -A PROXIFIER_OUT -d $ip -p tcp -j REDIRECT --to-ports $localListenPort")
                    }
                }
            }

            if (uids.isEmpty() && rule.targetIps.none { it.isNotBlank() }) {
                if (rule.targetDomains.isEmpty() || rule.targetDomains.any { it == "*" || it == ".*" || it == "all" }) {
                    sb.appendLine("# Catch-all / Fallback rule: Traffic redirected to LocalTransparentProxyServer on port $localListenPort for [${rule.action}] matching")
                } else {
                    sb.appendLine("# Domain rule (${rule.targetDomains.joinToString()}): Traffic redirected to LocalTransparentProxyServer on port $localListenPort for SNI domain matching")
                }
            }
        }

        // Default fallback: Redirect remaining TCP traffic to proxy port
        sb.appendLine()
        sb.appendLine("# Default Fallback Redirect")
        sb.appendLine("iptables -w -t nat -A PROXIFIER_OUT -p tcp -j REDIRECT --to-ports $localListenPort")
        sb.appendLine("iptables -w -t nat -D OUTPUT -j PROXIFIER_OUT 2>/dev/null || true")
        sb.appendLine("iptables -w -t nat -A OUTPUT -j PROXIFIER_OUT")
        sb.appendLine("iptables -w -t nat -D PREROUTING -j PROXIFIER_OUT 2>/dev/null || true")
        sb.appendLine("iptables -w -t nat -A PREROUTING -j PROXIFIER_OUT 2>/dev/null || true")
        sb.appendLine("ip6tables -w -t nat -D OUTPUT -j PROXIFIER_OUT 2>/dev/null || true")
        sb.appendLine("ip6tables -w -t nat -A OUTPUT -j PROXIFIER_OUT 2>/dev/null || true")
        sb.appendLine("ip6tables -w -t nat -D PREROUTING -j PROXIFIER_OUT 2>/dev/null || true")
        sb.appendLine("ip6tables -w -t nat -A PREROUTING -j PROXIFIER_OUT 2>/dev/null || true")
        sb.appendLine("echo 'Proxifier iptables rules applied successfully'")

        return sb.toString()
    }

    /**
     * Generates a flashable KernelSU / Magisk Dynamic Module ZIP file (一劳永逸版).
     * The module bridges boot initialization to /data/adb/proxifier/rules.sh and the App service,
     * so rule changes in the App take effect instantly without reinstalling the module.
     */
    suspend fun generateModuleZip(
        context: Context,
        server: ProxyServer?,
        rules: List<ProxyRule>
    ): File = withContext(Dispatchers.IO) {
        val moduleProp = """
            id=proxifier_ksu_core
            name=Proxifier Dynamic Proxy Module
            version=v2.0.0
            versionCode=200
            author=Proxifier Team
            description=KernelSU / Magisk 动态透明代理框架 (一劳永逸版：刷入一次即可，后续在App内修改规则自动生效)
        """.trimIndent()

        val serviceSh = """
            #!/system/bin/sh
            MODDIR=${'$'}{0%/*}
            
            # Wait for system boot completion
            until [ "${'$'}(getprop sys.boot_completed)" = "1" ]; do
                sleep 2
            done
            
            mkdir -p /data/adb/proxifier

            # Execute dynamic rules script if initialized
            if [ -f /data/adb/proxifier/rules.sh ]; then
                sh /data/adb/proxifier/rules.sh > /data/adb/proxifier/boot.log 2>&1
            fi

            # Trigger App Background Service
            am start-foreground-service -n com.yangyx.proxy/.service.KernelSuBootService 2>/dev/null || am startservice -n com.yangyx.proxy/.service.KernelSuBootService 2>/dev/null
        """.trimIndent()

        val actionSh = """
            #!/system/bin/sh
            MODDIR=${'$'}{0%/*}
            
            echo "Executing Proxifier Dynamic Rules..."
            mkdir -p /data/adb/proxifier

            if [ -f /data/adb/proxifier/rules.sh ]; then
                sh /data/adb/proxifier/rules.sh
                echo "Rules executed successfully!"
            else
                echo "No dynamic rules saved yet. Launching Proxifier App..."
            fi

            am start-foreground-service -n com.yangyx.proxy/.service.KernelSuBootService 2>/dev/null || am startservice -n com.yangyx.proxy/.service.KernelSuBootService 2>/dev/null
        """.trimIndent()

        val bridgeRulesSh = """
            #!/system/bin/sh
            if [ -f /data/adb/proxifier/rules.sh ]; then
                exec sh /data/adb/proxifier/rules.sh "${'$'}@"
            else
                echo "Proxifier Dynamic Rules Engine - Please open Proxifier App to configure rules."
            fi
        """.trimIndent()

        // Also save current rules into cache/rules.sh and push to /data/adb/proxifier/rules.sh right now if root is available
        val initialScript = buildIptablesScript(context, server, rules)
        val localScriptFile = File(context.cacheDir, "rules.sh")
        runCatching {
            localScriptFile.writeText(initialScript, Charsets.UTF_8)
            RootShellManager.execute("""
                mkdir -p /data/adb/proxifier
                cp "${localScriptFile.absolutePath}" /data/adb/proxifier/rules.sh
                chmod 755 /data/adb/proxifier/rules.sh
            """.trimIndent())
        }

        val outputZip = File(context.cacheDir, "Proxifier_KernelSU_Module.zip")
        if (outputZip.exists()) outputZip.delete()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZip))).use { zipOut ->
            fun addFile(fileName: String, content: String) {
                val entry = ZipEntry(fileName)
                zipOut.putNextEntry(entry)
                zipOut.write(content.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()
            }

            addFile("module.prop", moduleProp)
            addFile("service.sh", serviceSh)
            addFile("action.sh", actionSh)
            addFile("rules.sh", bridgeRulesSh)
        }

        outputZip
    }

    /**
     * Copies generated ZIP file to the system Downloads directory and notifies MediaScanner.
     */
    fun copyToDownloadsDirectory(context: Context, cacheZipFile: File): File {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
        val targetFile = File(downloadDir, "Proxifier_KernelSU_Module.zip")
        cacheZipFile.inputStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }

        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf("application/zip"),
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return targetFile
    }
}
