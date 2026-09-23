package com.lj.blackplayer

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Dim = Color(0xFF7A7A7A)
private val Faint = Color(0xFF2A2A2A)

@Composable
fun PlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color.White, onPrimary = Color.Black,
            background = Color.Black, onBackground = Color.White,
            surface = Color.Black, onSurface = Color.White,
        ),
        content = content
    )
}

@Composable
fun PlayerScreen(controller: Player?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentController by rememberUpdatedState(controller)

    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var index by remember { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var meta by remember { mutableStateOf(MediaMetadata.EMPTY) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var folderName by remember { mutableStateOf(Prefs.source(ctx)?.let(Library::label)) }
    var scanning by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // Mirror player state into Compose state.
    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        fun sync(timelineChanged: Boolean) {
            if (timelineChanged) items = List(c.mediaItemCount) { c.getMediaItemAt(it) }
            index = if (c.mediaItemCount > 0) c.currentMediaItemIndex else -1
            playing = c.playWhenReady &&
                (c.playbackState == Player.STATE_READY || c.playbackState == Player.STATE_BUFFERING)
            shuffle = c.shuffleModeEnabled
            repeat = c.repeatMode
            meta = c.mediaMetadata
            duration = c.duration.coerceAtLeast(0)
            position = c.currentPosition
        }
        sync(true)
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) =
                sync(events.contains(Player.EVENT_TIMELINE_CHANGED))
        }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }

    // Tick the progress bar while playing.
    LaunchedEffect(controller, playing) {
        val c = controller ?: return@LaunchedEffect
        while (playing) {
            position = c.currentPosition
            delay(500)
        }
    }

    // After a restart the service is empty: reload the saved folder and resume where we left off.
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        if (c.mediaItemCount > 0 || scanning) return@LaunchedEffect
        val source = Prefs.source(ctx) ?: return@LaunchedEffect
        scanning = true
        val tracks = withContext(Dispatchers.IO) {
            runCatching { Library.scan(ctx, source) }.getOrDefault(emptyList())
        }
        scanning = false
        val start = tracks.indexOfFirst { it.uri.toString() == Prefs.lastTrack(ctx) }
        load(c, tracks, start.coerceAtLeast(0), if (start >= 0) Prefs.lastPosition(ctx) else 0, play = false)
    }

    fun useSource(source: String) {
        // Drop access to the previously picked folder.
        Prefs.source(ctx)?.takeIf { it != source && it != Library.DOWNLOADS }?.let { old ->
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(
                    Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        Prefs.setSource(ctx, source)
        folderName = Library.label(source)
        scanning = true
        scope.launch {
            val tracks = withContext(Dispatchers.IO) {
                runCatching { Library.scan(ctx, source) }.getOrDefault(emptyList())
            }
            // The controller may still be reconnecting after the picker closed.
            val c = snapshotFlow { currentController }.filterNotNull().first()
            scanning = false
            val start = if (c.shuffleModeEnabled && tracks.isNotEmpty()) tracks.indices.random() else 0
            load(c, tracks, start, 0, play = true)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        useSource(uri.toString())
    }

    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) useSource(Library.DOWNLOADS)
        else Toast.makeText(ctx, "Allow music access to play from Downloads", Toast.LENGTH_LONG).show()
    }

    fun openDownloads() {
        val granted = ContextCompat.checkSelfPermission(ctx, Library.audioPermission) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) useSource(Library.DOWNLOADS) else audioPermission.launch(Library.audioPermission)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        // Header: folder name + change folder
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = folderName?.let { "$it  ·  ${items.size}" } ?: "Black Player",
                color = Dim, fontSize = 13.sp, letterSpacing = 1.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(painterResource(R.drawable.ic_folder), "Music source", tint = Dim)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = Color(0xFF111111)
                ) {
                    DropdownMenuItem(
                        text = { Text("Choose folder…", color = Color.White) },
                        onClick = { menuOpen = false; picker.launch(null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Downloads", color = Color.White) },
                        onClick = { menuOpen = false; openDownloads() }
                    )
                }
            }
        }

        // Track list
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                scanning -> Text("Scanning…", color = Dim)
                items.isEmpty() -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (folderName != null) Text("No music found", color = Dim)
                    OutlinedButton(onClick = { picker.launch(null) }) {
                        Text("Choose music folder", color = Color.White)
                    }
                    OutlinedButton(onClick = { openDownloads() }) {
                        Text("Play from Downloads", color = Color.White)
                    }
                }
                else -> TrackList(items, index) { i ->
                    controller?.run {
                        seekTo(i, 0)
                        prepare()
                        play()
                    }
                }
            }
        }

        HorizontalDivider(color = Faint)

        // Now playing
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            val current = items.getOrNull(index)
            Text(
                text = meta.title?.toString() ?: current?.mediaMetadata?.displayTitle?.toString() ?: "—",
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(meta.artist, meta.albumTitle).joinToString("  ·  "),
                color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )

            SeekBar(position, duration) { controller?.seekTo(it); position = it }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { controller?.shuffleModeEnabled = !shuffle }) {
                    Icon(
                        painterResource(R.drawable.ic_shuffle), "Shuffle",
                        tint = if (shuffle) Color.White else Faint
                    )
                }
                IconButton(onClick = { controller?.seekToPrevious() }) {
                    Icon(painterResource(R.drawable.ic_prev), "Previous", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { controller?.let { togglePlay(it) } },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                        if (playing) "Pause" else "Play",
                        tint = Color.Black, modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(onClick = { controller?.seekToNext() }) {
                    Icon(painterResource(R.drawable.ic_next), "Next", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = {
                    controller?.repeatMode = when (repeat) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }) {
                    Icon(
                        painterResource(if (repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat),
                        "Repeat",
                        tint = if (repeat == Player.REPEAT_MODE_OFF) Faint else Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackList(items: List<MediaItem>, current: Int, onClick: (Int) -> Unit) {
    val listState = rememberLazyListState()
    // Keep the playing track in view when it changes.
    LaunchedEffect(current) {
        if (current < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == current }) listState.animateScrollToItem(current)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(items, key = { i, item -> "$i:${item.mediaId}" }) { i, item ->
            val active = i == current
            Text(
                text = item.mediaMetadata.displayTitle?.toString() ?: item.mediaId,
                color = if (active) Color.White else Dim,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(i) }
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shown = if (dragging) dragValue else fraction

    Column(Modifier.padding(top = 8.dp)) {
        Slider(
            value = shown,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                onSeek((dragValue * duration).toLong())
                dragging = false
            },
            enabled = duration > 0,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Faint,
                disabledThumbColor = Faint,
                disabledInactiveTrackColor = Faint,
            ),
            modifier = Modifier.height(32.dp)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime((shown * duration).toLong()), color = Dim, fontSize = 12.sp)
            Text(formatTime(duration), color = Dim, fontSize = 12.sp)
        }
    }
}

private fun togglePlay(p: Player) {
    if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) {
        p.pause()
        return
    }
    if (p.mediaItemCount == 0) return
    if (p.playbackState == Player.STATE_IDLE) p.prepare()
    if (p.playbackState == Player.STATE_ENDED) p.seekTo(0, 0)
    p.play()
}

private fun load(p: Player, tracks: List<Track>, start: Int, positionMs: Long, play: Boolean) {
    if (tracks.isEmpty()) {
        p.clearMediaItems()
        return
    }
    p.setMediaItems(tracks.map { it.toMediaItem() }, start, positionMs)
    p.prepare()
    if (play) p.play()
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
