package com.glassremote.data.adb

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

object Adb {
    private const val TAG = "Adb"
    var host: String = "192.168.31.116"
    var port: Int = 5555

    suspend fun sendKeyEvent(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        val command = "input keyevent $keyCode"
        return@withContext executeShell(command)
    }

    suspend fun sendText(text: String): Boolean = withContext(Dispatchers.IO) {
        val escaped = text.replace(" ", "%s").replace("\"", "\\\"")
        val command = "input text \"$escaped\""
        return@withContext executeShell(command)
    }

    suspend fun launchPackage(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val command = "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
        return@withContext executeShell(command)
    }

    suspend fun executeShell(cmd: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1000)
                val out: OutputStream = socket.getOutputStream()
                out.write("shell:$cmd\n".toByteArray())
                out.flush()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Direct ADB command failed ($cmd): ${e.message}")
            // Fallback to local shell execution if ADB daemon is configured locally
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                process.waitFor() == 0
            } catch (ex: Exception) {
                false
            }
        }
    }
}
