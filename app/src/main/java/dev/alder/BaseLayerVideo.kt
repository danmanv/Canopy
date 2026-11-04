package dev.alder

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/**
 * Dolby Vision profile 7 (UHD Blu-ray REMUXes) on a TV whose Dolby Vision decoder doesn't list it plays as its HDR10
 * base layer on the plain HEVC decoder. Handed to the Dolby Vision decoder anyway, as Media3 does (its fallback covers
 * profiles 4 and 8 only), the Skyworth switched to its Dolby Vision picture mode and the film came out very dark.
 */
internal class BaseLayerVideoRenderer(builder: Builder) : MediaCodecVideoRenderer(builder) {
    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        super.supportsFormat(mediaCodecSelector, baseLayer(format))

    override fun onInputFormatChanged(formatHolder: FormatHolder) =
        super.onInputFormatChanged(formatHolder.apply { format = format?.let { baseLayer(it) } })

    companion object {
        /** Whether a Dolby Vision decoder here takes profile 7, checked once (the codec list takes a few ms). */
        private val profile7: Boolean by lazy {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && "video/dolby-vision" in it.supportedTypes.map(String::lowercase) }
                .any { c -> c.getCapabilitiesForType("video/dolby-vision").profileLevels.any { it.profile == CodecProfileLevel.DolbyVisionProfileDvheDtb } }
        }

        fun baseLayer(format: Format): Format =
            if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && isProfile7(format.codecs) && !profile7)
                format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).build()
            else format

        /** "dvhe.07.06" or "dvh1.07.06": the profile is the number after the first dot. */
        fun isProfile7(codecs: String?): Boolean =
            codecs?.split('.')?.let { it.size >= 2 && it[0] in setOf("dvhe", "dvh1") && it[1].toIntOrNull() == 7 } == true
    }
}

/** Builds [BaseLayerVideoRenderer] in place of the factory's own MediaCodecVideoRenderer, with the same settings. */
internal fun baseLayerRenderer(context: Context, builder: (MediaCodecVideoRenderer.Builder) -> MediaCodecVideoRenderer.Builder) =
    BaseLayerVideoRenderer(builder(MediaCodecVideoRenderer.Builder(context)))
