package com.lj.blackplayer

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

/**
 * Owns the player. Media3 turns the session into the media notification,
 * lock-screen and Bluetooth/headset controls automatically. It is also a library service,
 * so Android Auto can browse the tracks of the chosen source and play them.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val io = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    /** The last scan of the current source, so a track picked in the car can bring its whole list along. */
    @Volatile
    private var tracks: List<Track> = emptyList()

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
        session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
            // URIs are stripped when items are sent from a controller; restore them from the media id.
            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                mediaItems.map { it.buildUpon().setUri(it.mediaId).build() }.toMutableList()
            )

            // A single track picked in another app (Android Auto) plays within its list, like a tap in ours.
            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long
            ): ListenableFuture<MediaItemsWithStartPosition> {
                val list = tracks
                val picked = mediaItems.singleOrNull()?.mediaId
                val index = list.indexOfFirst { it.uri.toString() == picked }
                if (controller.packageName == packageName || index < 0) {
                    return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
                }
                return Futures.immediateFuture(
                    MediaItemsWithStartPosition(list.map { it.toMediaItem() }, index, startPositionMs)
                )
            }

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> =
                Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Black Player"), params))

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = when (parentId) {
                ROOT -> Futures.immediateFuture(
                    LibraryResult.ofItemList(listOf(folder(TRACKS, sourceLabel())), params)
                )
                TRACKS -> io.submit<LibraryResult<ImmutableList<MediaItem>>> {
                    val all = scan()
                    val from = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
                    val to = (from.toLong() + pageSize).coerceAtMost(all.size.toLong()).toInt()
                    LibraryResult.ofItemList(all.subList(from, to).map { it.toBrowsableItem() }, params)
                }
                else -> Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }

            // Pressing play in the car (or on a headset) with nothing loaded continues where the app left off.
            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo
            ): ListenableFuture<MediaItemsWithStartPosition> = io.submit<MediaItemsWithStartPosition> {
                val list = scan()
                val start = list.indexOfFirst { it.uri.toString() == Prefs.lastTrack(this@PlaybackService) }
                MediaItemsWithStartPosition(
                    list.map { it.toMediaItem() },
                    start.coerceAtLeast(0),
                    if (start >= 0) Prefs.lastPosition(this@PlaybackService) else 0
                )
            }
        })
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveState()
        session?.release()
        player.release()
        io.shutdown()
        session = null
        super.onDestroy()
    }

    private fun saveState() {
        if (player.mediaItemCount == 0) return
        Prefs.setLast(this, player.currentMediaItem?.mediaId, player.currentPosition)
    }

    private fun scan(): List<Track> {
        val source = Prefs.source(this) ?: return emptyList()
        return runCatching { Library.scan(this, source) }.getOrDefault(emptyList()).also { tracks = it }
    }

    private fun sourceLabel(): String = Prefs.source(this)?.let(Library::label) ?: "No folder chosen"

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    // The car shows the title, which our own items leave to the file's tags.
    private fun Track.toBrowsableItem(): MediaItem = toMediaItem().let { item ->
        item.buildUpon().setMediaMetadata(
            item.mediaMetadata.buildUpon()
                .setTitle(name)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        ).build()
    }

    private companion object {
        const val ROOT = "root"
        const val TRACKS = "tracks"
    }
}
