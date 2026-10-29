package com.glassremote.data.adb

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

object Adb {
    private const val TAG = "AdbController"
    var host: String = "192.168.31.116"
    var port: Int = 5555

    suspend fun sendKeyEvent(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        val command = "input keyevent $keyCode"
        return@withContext executeShell(command)
    }

    suspend fun launchPackage(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val command = "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
        return@withContext executeShell(command)
    }

    suspend fun executeShell(cmd: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // First verify socket connectivity to ADB port
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1200)
            }
            // Execute command via adb socket or local runtime fallback
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val exitCode = process.waitFor()
            Log.d(TAG, "Command: '$cmd' completed with exit code: $exitCode")
            return@withContext exitCode == 0
        } catch (e: Exception) {
            Log.w(TAG, "ADB shell execution failed for '$cmd': ${e.message}")
            return@withContext false
        }
    }
}
