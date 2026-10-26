package com.glassremote

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.glassremote.data.repository.TvRepositoryImpl
import com.glassremote.domain.model.KeyDirection
import com.glassremote.domain.model.TvDevice
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var tvRepository: TvRepositoryImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind layout views
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

        // Set Click Listeners to transmit keycode payloads down the live socket pipeline
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

    private fun sendTvKey(androidKeyCode: Int) {
        lifecycleScope.launch {
            try {
                tvRepository.sendKey(androidKeyCode, KeyDirection.SHORT)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
