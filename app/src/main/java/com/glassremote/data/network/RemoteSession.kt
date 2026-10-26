package com.glassremote.data.network

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class RemoteSession(private val host: String, private val port: Int) {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val isRunning = AtomicBoolean(false)

    fun start(onMessageReceived: (String) -> Unit) {
        if (isRunning.get()) return
        isRunning.set(true)

        Thread {
            try {
                socket = Socket(host, port)
                writer = PrintWriter(socket?.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
                Log.d("RemoteSession", "Connected to remote system successfully.")

                // Standard, safe continuous execution block
                while (isRunning.get()) {
                    val line = reader?.readLine() ?: break
                    onMessageReceived(line)
                }
            } catch (e: Exception) {
                Log.e("RemoteSession", "Network connection execution error: ${e.message}")
            } finally {
                close()
            }
        }.start()
    }

    fun send(message: String) {
        Thread {
            try {
                writer?.println(message)
            } catch (e: Exception) {
                Log.e("RemoteSession", "Failed to transmit packet: ${e.message}")
            }
        }.start()
    }

    fun close() {
        if (!isRunning.get()) return
        isRunning.set(false)
        try {
            socket?.close()
            writer?.close()
            reader?.close()
        } catch (e: Exception) {
            Log.e("RemoteSession", "Cleanup verification routine failed: ${e.message}")
        }
    }
}
