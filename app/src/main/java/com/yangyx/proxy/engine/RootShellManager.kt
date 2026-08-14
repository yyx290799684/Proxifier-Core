package com.yangyx.proxy.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

data class ShellResult(
    val exitCode: Int,
    val output: String,
    val error: String
)

object RootShellManager {

    /**
     * Checks whether Root or KernelSU is accessible on the device.
     */
    suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        val res = execute("id")
        res.exitCode == 0 && res.output.contains("uid=0")
    }

    /**
     * Checks if KernelSU is specifically present on the device.
     */
    suspend fun checkKernelSu(): Boolean = withContext(Dispatchers.IO) {
        // KernelSU creates /data/adb/ksu or has specific ksu binary / sysfs interface
        val ksuDirExists = File("/data/adb/ksu").exists()
        val ksuModuleDir = File("/data/adb/modules").exists()
        if (ksuDirExists) return@withContext true

        val res = execute("ksu --version")
        if (res.exitCode == 0) return@withContext true

        // Fallback: test if root access is present
        checkRootAccess()
    }

    /**
     * Executes shell command under root (`su`).
     */
    suspend fun execute(cmd: String): ShellResult = withContext(Dispatchers.IO) {
        var process: Process? = null
        var os: DataOutputStream? = null
        var stdoutReader: BufferedReader? = null
        var stderrReader: BufferedReader? = null

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode = -1

        try {
            process = Runtime.getRuntime().exec("su")
            os = DataOutputStream(process.outputStream)
            os.writeBytes("$cmd\n")
            os.writeBytes("exit\n")
            os.flush()

            stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (stdoutReader.readLine().also { line = it } != null) {
                stdout.append(line).append("\n")
            }

            stderrReader = BufferedReader(InputStreamReader(process.errorStream))
            while (stderrReader.readLine().also { line = it } != null) {
                stderr.append(line).append("\n")
            }

            exitCode = process.waitFor()
        } catch (e: Exception) {
            // Fallback for non-root environment execution
            try {
                process = Runtime.getRuntime().exec(cmd)
                stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (stdoutReader.readLine().also { line = it } != null) {
                    stdout.append(line).append("\n")
                }
                exitCode = process.waitFor()
            } catch (ex: Exception) {
                stderr.append(e.localizedMessage ?: "Command failed")
            }
        } finally {
            runCatching { os?.close() }
            runCatching { stdoutReader?.close() }
            runCatching { stderrReader?.close() }
            runCatching { process?.destroy() }
        }

        ShellResult(
            exitCode = exitCode,
            output = stdout.toString().trim(),
            error = stderr.toString().trim()
        )
    }
}
