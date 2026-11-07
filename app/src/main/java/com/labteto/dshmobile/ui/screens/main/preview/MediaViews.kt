package com.labteto.dshmobile.ui.screens.main.preview

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DetentCounter
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.rememberHapticTicker
import com.labteto.dshmobile.ui.screens.main.PreviewTab
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.delay
import java.io.File

/** The part of a platform player the controls need. */
private interface PlayerHandle {
    val position: Int
    fun play()
    fun pause()
    fun seekTo(millis: Int)
}

/** What a player is doing, as state the controls read. */
@Stable
private class PlayerState {
    var ready by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var handle: PlayerHandle? = null
}

/**
 * Plays a video or an audio file with the platform player.
 *
 * The bytes go to a cache file first, because the platform players read from a path, not from
 * memory. The file is deleted, and the player released, as soon as this leaves the screen, and
 * playback pauses when the app is no longer in front. It starts paused: opening a preview should
 * never make a sound by itself.
 */
@Composable
internal fun MediaSurface(
    bytes: ByteArray,
    type: FileType,
    tab: PreviewTab,
    surface: PreviewSurface,
    actions: PreviewActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    // The platform players read a path, so the bytes go to a cache file that lives as long as the player.
    var file by remember(bytes) { mutableStateOf<File?>(null) }
    LaunchedEffect(bytes) { file = PreviewTempFiles.write(context, bytes, type.extension) }
    DisposableEffect(bytes) { onDispose { file?.delete() } }
    val ready = file
    if (ready == null) {
        ImageSkeleton(modifier)
        return
    }
    val state = remember(ready) { PlayerState() }
    LaunchedEffect(state.duration) {
        if (state.duration > 0) tab.detail = formatClock(state.duration)
    }
    // Pause when the app goes behind another: audio that keeps playing from a hidden preview is a bug.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                state.handle?.pause()
                state.playing = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (state.failed) {
        NoticeCard(
            icon = if (type.kind == PreviewKind.Video) PreviewIcons.Film else PreviewIcons.Music,
            title = stringResource(R.string.preview_cant_show_title),
            body = stringResource(R.string.preview_media_unsupported),
            modifier = modifier,
            action = { SaveCopyButton(actions) },
        )
        return
    }
    PreviewFrame(modifier, framed = surface == PreviewSurface.Inline) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (type.kind == PreviewKind.Video) {
                    VideoStage(ready, state)
                } else {
                    AudioStage(ready, state, tab.path)
                }
            }
            PlayerControls(state)
        }
    }
}

@Composable
private fun VideoStage(file: File, state: PlayerState) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            VideoView(context).apply {
                state.handle = object : PlayerHandle {
                    override val position: Int get() = currentPosition
                    override fun play() = start()
                    override fun pause() = this@apply.pause()
                    override fun seekTo(millis: Int) = this@apply.seekTo(millis)
                }
                setOnPreparedListener { player ->
                    state.duration = player.duration.toLong()
                    state.ready = true
                    // Show the first frame instead of a black rectangle.
                    seekTo(1)
                }
                setOnCompletionListener {
                    state.playing = false
                    state.position = state.duration
                }
                setOnErrorListener { _, _, _ ->
                    state.failed = true
                    true
                }
                setVideoPath(file.path)
            }
        },
        onRelease = {
            state.handle = null
            it.stopPlayback()
        },
    )
}

/** A music note and the file's name; the sound is the content. */
@Composable
private fun AudioStage(file: File, state: PlayerState, name: String) {
    val context = LocalContext.current
    val colors = DsTheme.colors
    DisposableEffect(file) {
        val focus = AudioFocus(context) { state.handle?.pause(); state.playing = false }
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            player.setDataSource(file.path)
            player.setOnPreparedListener {
                state.duration = it.duration.toLong()
                state.ready = true
            }
            player.setOnCompletionListener {
                state.playing = false
                state.position = state.duration
                focus.abandon()
            }
            player.setOnErrorListener { _, _, _ ->
                state.failed = true
                true
            }
            player.prepareAsync()
            state.handle = object : PlayerHandle {
                override val position: Int get() = runCatching { player.currentPosition }.getOrDefault(0)
                override fun play() {
                    if (focus.request()) player.start()
                }
                override fun pause() {
                    runCatching { player.pause() }
                    focus.abandon()
                }
                override fun seekTo(millis: Int) = player.seekTo(millis)
            }
        } catch (_: Exception) {
            state.failed = true
        }
        onDispose {
            state.handle = null
            focus.abandon()
            runCatching { player.release() }
        }
    }
    Column(
        Modifier.padding(DsSpacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        Box(
            Modifier.size(88.dp).clip(DsShapes.dialog).background(colors.bgLayer3),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PreviewIcons.Music, contentDescription = null, tint = colors.accent, modifier = Modifier.size(44.dp))
        }
        Text(
            name.substringAfterLast('/').substringAfterLast('\\'),
            style = DsType.rowTitle,
            color = colors.labelPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Takes and gives back the phone's audio focus, and pauses when someone else takes it. */
private class AudioFocus(context: Context, private val onLost: () -> Unit) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLost()
        }
        .build()
    private var held = false

    fun request(): Boolean {
        if (held) return true
        held = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    fun abandon() {
        if (held) manager.abandonAudioFocusRequest(request)
        held = false
    }
}

@Composable
private fun PlayerControls(state: PlayerState) {
    val colors = DsTheme.colors
    // The seek bar ticks every 5% of the length while it is dragged, and settles on release.
    val ticker = rememberHapticTicker()
    val seekDetents = remember { DetentCounter(0.05f) }
    LaunchedEffect(state.playing) {
        while (state.playing) {
            state.position = state.handle?.position?.toLong() ?: state.position
            delay(250)
        }
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val total = state.duration.coerceAtLeast(1L)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DsIconButton(
            icon = if (state.playing) PreviewIcons.Pause else PreviewIcons.Play,
            contentDescription = stringResource(if (state.playing) R.string.preview_pause else R.string.preview_play),
            onClick = {
                val handle = state.handle ?: return@DsIconButton
                if (state.playing) {
                    handle.pause()
                    state.playing = false
                } else {
                    // At the end, play again from the start.
                    if (state.position >= state.duration - 250) handle.seekTo(0)
                    handle.play()
                    state.playing = true
                }
            },
            enabled = state.ready,
            tint = colors.labelPrimary,
        )
        val seekLabel = stringResource(R.string.preview_seek)
        Slider(
            value = dragging ?: (state.position.toFloat() / total).coerceIn(0f, 1f),
            onValueChange = { fraction ->
                if (dragging == null) {
                    ticker.reset()
                    seekDetents.reset()
                }
                if (seekDetents.crossed(fraction) > 0) ticker.cross()
                dragging = fraction
            },
            onValueChangeFinished = {
                dragging?.let { fraction ->
                    ticker.settle()
                    val target = (fraction * total).toLong()
                    state.handle?.seekTo(target.toInt())
                    state.position = target
                }
                dragging = null
            },
            enabled = state.ready,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.borderL3,
                disabledThumbColor = colors.labelDimmed,
                disabledActiveTrackColor = colors.borderL3,
                disabledInactiveTrackColor = colors.borderL2,
            ),
            modifier = Modifier.weight(1f).semantics { contentDescription = seekLabel },
        )
        val shown = dragging?.let { (it * total).toLong() } ?: state.position
        Text(
            formatClock(shown) + " / " + formatClock(state.duration),
            style = DsType.mono12,
            color = colors.labelSecondary,
            maxLines = 1,
            modifier = Modifier.padding(start = DsSpacing.xsmall, end = DsSpacing.small),
        )
    }
}
