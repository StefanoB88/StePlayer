package com.steplayer.app.player

import android.content.Context
import android.graphics.Color
import android.os.Handler
import androidx.annotation.OptIn
import androidx.compose.runtime.Immutable
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.steplayer.shared.settings.SubtitleBackground
import com.steplayer.shared.settings.SubtitleColor
import com.steplayer.shared.settings.SubtitleEdge
import com.steplayer.shared.settings.SubtitleStyle
import java.util.ArrayList

/** A/V sync offsets, read on the playback thread. */
class SyncOffsets {
    /** Positive = audio later than video. */
    @Volatile var audioDelayUs: Long = 0
    /** Positive = subtitles later. */
    @Volatile var subtitleDelayUs: Long = 0
}

/**
 * Renderers with lip-sync and subtitle-sync support.
 * - Audio: Media3 has no offset API, so the audio sink reports a shifted clock; video follows it.
 * - Subtitles: the text renderer is driven with a shifted position.
 */
@OptIn(UnstableApi::class)
class SyncRenderersFactory(context: Context, private val offsets: SyncOffsets) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
        val sink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
        return object : ForwardingAudioSink(sink) {
            override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
                val pos = super.getCurrentPositionUs(sourceEnded)
                if (pos == AudioSink.CURRENT_POSITION_NOT_SET) return pos
                return (pos + offsets.audioDelayUs).coerceAtLeast(0)
            }
        }
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val inner = ArrayList<Renderer>()
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, inner)
        inner.forEach { out += OffsetRenderer(it, offsets) }
    }
}

/** Delegates everything to [inner] but renders at `position - subtitleDelay`. */
@OptIn(UnstableApi::class)
private class OffsetRenderer(private val inner: Renderer, private val offsets: SyncOffsets) : Renderer by inner {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) =
        inner.render(positionUs - offsets.subtitleDelayUs, elapsedRealtimeUs)
}

/** Live diagnostics for the stats overlay. */
@Immutable
data class PlayerStats(
    val resolution: String = "—",
    val fps: String = "—",
    val videoCodec: String = "—",
    val audioCodec: String = "—",
    val bitrateKbps: Long? = null,
    val bufferSec: Float = 0f,
    val droppedFrames: Int = 0,
    val stalls: Int = 0,
    val networkKbps: Long? = null,
    val bufferProfile: String = "",
)

@OptIn(UnstableApi::class)
fun SubtitleView.applyStyle(style: SubtitleStyle) {
    val fg = if (style.color == SubtitleColor.YELLOW) Color.YELLOW else Color.WHITE
    val bg = when (style.background) {
        SubtitleBackground.NONE -> Color.TRANSPARENT
        SubtitleBackground.SEMI_TRANSPARENT -> Color.argb(150, 0, 0, 0)
        SubtitleBackground.OPAQUE -> Color.BLACK
    }
    val edge = when (style.edge) {
        SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
        SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubtitleEdge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
    }
    setStyle(CaptionStyleCompat(fg, bg, Color.TRANSPARENT, edge, Color.BLACK, null))
    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.scale)
    setBottomPaddingFraction(style.bottomMarginPercent / 100f)
}
