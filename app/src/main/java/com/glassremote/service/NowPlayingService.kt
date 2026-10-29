package com.glassremote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.glassremote.MainActivity

class NowPlayingService : Service() {

    private var mediaSession: MediaSession? = null
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val CHANNEL_ID = "tv_now_playing_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_UPDATE_PLAYING = "com.glassremote.ACTION_UPDATE_PLAYING"
        const val ACTION_STOP_SERVICE = "com.glassremote.ACTION_STOP_SERVICE"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_IS_PLAYING = "extra_is_playing"

        fun update(context: Context, title: String, artist: String, isPlaying: Boolean) {
            val intent = Intent(context, NowPlayingService::class.java).apply {
                action = ACTION_UPDATE_PLAYING
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, NowPlayingService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        mediaSession = MediaSession(this, "GlassRemoteMediaSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    com.glassremote.data.adb.Adb.sendKeyEvent(126) // KEYCODE_MEDIA_PLAY
                    updatePlaybackState(true)
                }

                override fun onPause() {
                    com.glassremote.data.adb.Adb.sendKeyEvent(127) // KEYCODE_MEDIA_PAUSE
                    updatePlaybackState(false)
                }

                override fun onSkipToNext() {
                    com.glassremote.data.adb.Adb.sendKeyEvent(87) // KEYCODE_MEDIA_NEXT
                }

                override fun onSkipToPrevious() {
                    com.glassremote.data.adb.Adb.sendKeyEvent(88) // KEYCODE_MEDIA_PREVIOUS
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                mediaSession?.isActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_PLAYING -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Live TV"
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: "Living Room TV"
                val isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, true)

                updateMetadata(title, artist)
                updatePlaybackState(isPlaying)
                val notification = buildNotification(title, artist, isPlaying)
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        return START_STICKY
    }

    private fun updateMetadata(title: String, artist: String) {
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, "Xiaomi TV")
            .build()
        mediaSession?.setMetadata(metadata)
    }

    private fun updatePlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS

        val playbackState = PlaybackState.Builder()
            .setActions(actions)
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    private fun buildNotification(title: String, artist: String, isPlaying: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("Xiaomi TV")
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(isPlaying)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TV Now Playing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows currently playing TV media on phone lockscreen"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        mediaSession?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
