package com.lj.blackplayer

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Owns the player. Media3 turns the session into the media notification,
 * lock-screen and Bluetooth/headset controls automatically.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true // handle audio focus: pause for calls, duck for notifications
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.shuffleModeEnabled = Prefs.shuffle(this)
        // Repeat-one is easy to forget and makes the player look stuck on one track, so it only lasts a session.
        player.repeatMode = Prefs.repeat(this).let { if (it == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else it }
        player.setPlaybackSpeed(Prefs.speed(this))
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = saveState()

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveState()
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) =
                Prefs.setShuffle(this@PlaybackService, enabled)

            override fun onRepeatModeChanged(mode: Int) = Prefs.setRepeat(this@PlaybackService, mode)

            override fun onPlaybackParametersChanged(params: PlaybackParameters) =
                Prefs.setSpeed(this@PlaybackService, params.speed)

            // Skip files that fail to decode instead of stopping playback.
            override fun onPlayerError(error: PlaybackException) {
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // URIs are stripped when items are sent from a controller; restore them from the media id.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { it.buildUpon().setUri(it.mediaId).build() }.toMutableList()
                )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveState()
        session?.release()
        player.release()
        session = null
        super.onDestroy()
    }

    private fun saveState() {
        if (player.mediaItemCount == 0) return
        Prefs.setLast(this, player.currentMediaItem?.mediaId, player.currentPosition)
    }
}
