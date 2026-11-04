package dev.alder

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.view.Display

/** What this TV can decode and show, checked once per launch (the codec list takes a few ms). */
class Device private constructor(val hevc: Boolean, val av1: Boolean, val dolbyVision: Boolean, val hdr: Boolean, val height: Int) {
    companion object {
        @Volatile private var known: Device? = null
        @Volatile private var touchKnown: Boolean? = null

        /**
         * A phone or tablet: a touchscreen and no TV launcher. The same screens run there, held sideways, with taps and
         * swipes standing in for the remote (Screen's touch handling).
         */
        fun touch(context: Context): Boolean = touchKnown ?: context.packageManager.let { pm ->
            !pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) && pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        }.also { touchKnown = it }

        /**
         * On a phone, the density that makes its short side [PHONE_DP] dp: a little under a 1080p TV's 540, so the
         * screens laid out for the TV still fit while their words and buttons are larger for a finger; null on a TV,
         * or where the phone's own density is already that small or smaller.
         */
        fun tvDensity(context: Context): Int? {
            if (!touch(context)) return null
            val short = if (android.os.Build.VERSION.SDK_INT >= 30)
                context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds.let { minOf(it.width(), it.height()) }
            else context.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }
            val dpi = short * android.util.DisplayMetrics.DENSITY_DEFAULT / PHONE_DP
            return dpi.takeIf { it in 1 until context.resources.configuration.densityDpi }
        }

        const val PHONE_DP = 480

        fun of(context: Context): Device = known ?: run {
            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
            val types = decoders.flatMap { it.supportedTypes.asList() }.map { it.lowercase() }.toSet()
            // Dolby Vision means profile 5 at 4K (web releases with no HDR10 base). Having a Dolby Vision decoder isn't
            // enough: the test TV has four and none takes dvhe.05.06, so those files never showed a picture.
            val dv5 = decoders.filter { "video/dolby-vision" in it.supportedTypes }.any { c ->
                c.getCapabilitiesForType("video/dolby-vision").profileLevels.any {
                    it.profile == CodecProfileLevel.DolbyVisionProfileDvheStn && it.level >= CodecProfileLevel.DolbyVisionLevelUhd24
                }
            }
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            val hdr = display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
            // The biggest mode the TV offers, not the one in use: the launcher often runs at 1080p on a 4K panel.
            // Some TVs list only the 1080p mode the launcher runs in, which put every 4K release under the 1080p ones. An
            // HDR screen with a 4K decoder is taken as 4K. ponytail: guesses a 1080p HDR panel with a 4K decoder wrong.
            val uhdDecoder = decoders.any { c ->
                c.supportedTypes.any { it.equals("video/hevc", true) } &&
                    c.getCapabilitiesForType("video/hevc").videoCapabilities?.isSizeSupported(3840, 2160) == true
            }
            val modes = display.supportedModes.maxOf { minOf(it.physicalWidth, it.physicalHeight) }
            val height = if (modes < 2160 && hdr && uhdDecoder) 2160 else modes
            Device("video/hevc" in types, "video/av01" in types, dv5, hdr, height)
                .also { known = it }
        }
    }
}
