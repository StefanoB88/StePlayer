package com.steplayer.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/** Human names for codecs, used in playback messages ("il video HEVC 10 bit 3840×2160", "Audio DTS"). */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object MediaNames {
    fun codec(f: Format): String = when (f.sampleMimeType) {
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_VP8 -> "VP8"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital (AC-3)"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus (E-AC-3)"
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
        MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS-HD"
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        else -> f.sampleMimeType?.substringAfter('/')?.uppercase() ?: "sconosciuto"
    }

    /** "il video HEVC 10 bit HDR 3840×2160" / "l'audio DTS". */
    fun describe(f: Format): String {
        if (MimeTypes.isAudio(f.sampleMimeType)) return "l'audio ${codec(f)}"
        val parts = mutableListOf("il video", codec(f))
        val codecs = f.codecs?.lowercase().orEmpty()
        if (codecs.startsWith("hvc1.2") || codecs.startsWith("hev1.2") || codecs.contains(".10.")) parts += "10 bit"
        val transfer = f.colorInfo?.colorTransfer
        if (transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG) parts += "HDR"
        if (f.width > 0 && f.height > 0) parts += "${f.width}×${f.height}"
        return parts.joinToString(" ")
    }
}
