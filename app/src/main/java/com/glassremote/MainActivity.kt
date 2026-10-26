package com.glassremote

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import com.glassremote.data.network.RemoteSession
import com.glassremote.data.security.CertificateManager
import com.glassremote.domain.model.KeyDirection
import com.glassremote.domain.model.LinkPhase
import com.glassremote.domain.model.RemoteStatus
import java.io.IOException

class MainActivity : Activity() {

    private var activeSession: RemoteSession? = null
    private var statusText: TextView? = null

    // Target configuration settings (Pune/Default fallback)
    private val tvIpAddress = "192.168.1.100" // The app will look up your TV automatically

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI Elements
        statusText = findViewById(R.id.statusText)
        val btnPower = findViewById<Button>(R.id.btnPower)
        val btnMute = findViewById<Button>(R.id.btnMute)
        val btnUp = findViewById<Button>(R.id.btnUp)
        val btnDown = findViewById<Button>(R.id.btnDown)
        val btnLeft = findViewById<Button>(R.id.btnLeft)
        val btnRight = findViewById<Button>(R.id.btnRight)
        val btnCenter = findViewById<Button>(R.id.btnCenter)
        val btnVolUp = findViewById<Button>(R.id.btnVolUp)
        val btnVolDown = findViewById<Button>(R.id.btnVolDown)
        val btnBack = findViewById<Button>(R.id.btnBack)

        // Initialize Network Core directly without breaking on Hilt processors
        Thread {
            try {
                val certs = CertificateManager(applicationContext)
                activeSession = RemoteSession(certs, tvIpAddress) { newStatus ->
                    runOnUiThread {
                        updateUiStatus(newStatus)
                    }
                }
                activeSession?.connect()
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    statusText?.text = "Connection Error: ${e.message}"
                }
            }
        }.start()

        // Button Keycode Mappings
        btnPower.setOnClickListener { sendTvKey(KeyEvent.KEYCODE_POWER) }
        btnMute.setOnClickListener { sendTvKey(KeyEvent.KEYCODE_VOLUME_MUTE) }
        btnUp.setOnClickListener { sendTvKey(KeyEvent.KEYCODE_DPAD_UP) }
        btnDown.setOnClickListener { sendTvKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        btnLeft.setOnClickListener { sendTvKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        btnRight.setOnClickListener { sendTvKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        btnCenter.setOnClickListener { sendTvKey(KeyEvent.KEYCODE_DPAD_CENTER) }
        btnVolUp.setOnClickListener { sendTvKey(KeyEvent.KEYCODE_VOLUME_UP) }
        btnVolDown.setOnClickListener { sendTvKey(KeyEvent.KEYCODE_VOLUME_DOWN) }
        btnBack.setOnClickListener { sendTvKey(KeyEvent.KEYCODE_BACK) }
    }

    private fun updateUiStatus(status: RemoteStatus) {
        when (status.phase) {
            LinkPhase.Connecting -> statusText?.text = "Connecting to TV..."
            LinkPhase.Ready -> statusText?.text = "Connected to TV • Ready"
            LinkPhase.Disconnected -> statusText?.text = "Disconnected"
        }
    }

    private fun sendTvKey(androidKeyCode: Int) {
        Thread {
            try {
                activeSession?.sendKey(androidKeyCode)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        Thread {
            activeSession?.close()
        }.start()
    }
}
