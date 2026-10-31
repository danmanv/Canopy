package dev.alder

import android.content.Context

/**
 * A colour scheme for the whole app. [accent] is the focus line, progress and the open page's underline;
 * [second] is for values and the far end of a bar; [lights] are the three glows in the Settings sky.
 */
class Theme(val id: String, val name: String, val base: Int, val text: Int, val dim: Int, val accent: Int, val second: Int, val lights: IntArray) {
    /** A shade lighter than [base], for glass and the top of the sky. */
    val glass = mix(base, text, 0.04f)
    /** A shade darker than [base], for the foot of the sky. */
    val deep = mix(base, 0xFF000000.toInt(), 0.45f)

    companion object {
        const val DEFAULT = "tangerine"
        val all = listOf(
            Theme("aurora", "Aurora", base = 0xFF070B16.toInt(), text = 0xFFF2F5FF.toInt(), dim = 0xD4DCF0, accent = 0xFF7CF2D0.toInt(), second = 0xFFBFE9FF.toInt(),
                lights = intArrayOf(0x387CF2D0, 0x409B8CFF, 0x2E3C6EBE)),
            Theme("ember", "Ember", base = 0xFF0C0A10.toInt(), text = 0xFFFFF6EA.toInt(), dim = 0xB8AD9E, accent = 0xFFFFB547.toInt(), second = 0xFFFFD9A8.toInt(),
                lights = intArrayOf(0x3DFFB547.toInt(), 0x38FF7850.toInt(), 0x408C4628.toInt())),
            Theme("violet", "Violet", base = 0xFF0A0816.toInt(), text = 0xFFF6F2FF.toInt(), dim = 0xB4ABCC, accent = 0xFFA78BFA.toInt(), second = 0xFFF0ABFC.toInt(),
                lights = intArrayOf(0x52A78BFA.toInt(), 0x33F0ABFC.toInt(), 0x4C503CB4.toInt())),
            Theme("arctic", "Arctic", base = 0xFF070B14.toInt(), text = 0xFFF2F6FF.toInt(), dim = 0xA7B2C8, accent = 0xFF6FB8FF.toInt(), second = 0xFFDCEEFF.toInt(),
                lights = intArrayOf(0x4C6FB8FF.toInt(), 0x24C8DCFF.toInt(), 0x4C2846A0.toInt())),
            Theme("crimson", "Crimson", base = 0xFF0D0809.toInt(), text = 0xFFFFF3F4.toInt(), dim = 0xC4ADB0, accent = 0xFFFF4D5E.toInt(), second = 0xFFFFB3BD.toInt(),
                lights = intArrayOf(0x3DFF4D5E.toInt(), 0x24FF9678.toInt(), 0x4C781428.toInt())),
            Theme("platinum", "Platinum", base = 0xFF09090B.toInt(), text = 0xFFF5F5F7.toInt(), dim = 0xA8A8B0, accent = 0xFFE8D3A6.toInt(), second = 0xFFFFFFFF.toInt(),
                lights = intArrayOf(0x29E8D3A6.toInt(), 0x12FFFFFF.toInt(), 0x405A5A6E.toInt())),
            Theme("sunset", "Sunset", base = 0xFF120A14.toInt(), text = 0xFFFFF3F1.toInt(), dim = 0xC2AAB4, accent = 0xFFFF7A59.toInt(), second = 0xFFFF5C8A.toInt(),
                lights = intArrayOf(0x42FF7A59.toInt(), 0x2EFF5C8A.toInt(), 0x4C6A1F4A.toInt())),
            Theme("cobalt", "Cobalt", base = 0xFF060818.toInt(), text = 0xFFF1F3FF.toInt(), dim = 0xA6ACC8, accent = 0xFF3D6BFF.toInt(), second = 0xFFA9B8FF.toInt(),
                lights = intArrayOf(0x423D6BFF.toInt(), 0x2EA9B8FF.toInt(), 0x4C1A2A90.toInt())),
            Theme("rosegold", "Rose Gold", base = 0xFF100C0D.toInt(), text = 0xFFFFF5F2.toInt(), dim = 0xBFAEAB, accent = 0xFFE8A598.toInt(), second = 0xFFF6D1C1.toInt(),
                lights = intArrayOf(0x42E8A598.toInt(), 0x2EF6D1C1.toInt(), 0x4C6A3A35.toInt())),
            Theme("magenta", "Magenta", base = 0xFF0E0711.toInt(), text = 0xFFFFF1F8.toInt(), dim = 0xC4A9BC, accent = 0xFFFF3DA5.toInt(), second = 0xFFFF9FD2.toInt(),
                lights = intArrayOf(0x42FF3DA5.toInt(), 0x2EFF9FD2.toInt(), 0x4C7A1050.toInt())),
            Theme("tangerine", "Tangerine", base = 0xFF0B0A0A.toInt(), text = 0xFFFFF7EF.toInt(), dim = 0xB9ADA2, accent = 0xFFFF8A1F.toInt(), second = 0xFFFFD08A.toInt(),
                lights = intArrayOf(0x42FF8A1F.toInt(), 0x2EFFD08A.toInt(), 0x4C7A3A10.toInt())),
            Theme("ultraviolet", "Ultraviolet", base = 0xFF07061A.toInt(), text = 0xFFF3F2FF.toInt(), dim = 0xA9A6CC, accent = 0xFF7B5CFF.toInt(), second = 0xFF5CA8FF.toInt(),
                lights = intArrayOf(0x427B5CFF.toInt(), 0x2E5CA8FF.toInt(), 0x4C2A1A8A.toInt())),
            Theme("goldnoir", "Gold Noir", base = 0xFF0A0907.toInt(), text = 0xFFFBF6EA.toInt(), dim = 0xB5AC98, accent = 0xFFD4AF37.toInt(), second = 0xFFF3E3B0.toInt(),
                lights = intArrayOf(0x42D4AF37.toInt(), 0x2EF3E3B0.toInt(), 0x4C5A4A1A.toInt())),
            Theme("cherry", "Cherry Cola", base = 0xFF14070B.toInt(), text = 0xFFFFF3F0.toInt(), dim = 0xC4A8AC, accent = 0xFFE2445C.toInt(), second = 0xFFF7B267.toInt(),
                lights = intArrayOf(0x42E2445C.toInt(), 0x2EF7B267.toInt(), 0x4C6A1528.toInt())),
            Theme("lilac", "Lilac Frost", base = 0xFF0D0C14.toInt(), text = 0xFFF6F4FF.toInt(), dim = 0xADAABD, accent = 0xFFC8B6FF.toInt(), second = 0xFFFFFFFF.toInt(),
                lights = intArrayOf(0x42C8B6FF.toInt(), 0x2EFFFFFF.toInt(), 0x4C4A4080.toInt())),
            Theme("neon", "Neon Pop", base = 0xFF08070F.toInt(), text = 0xFFF7F3FF.toInt(), dim = 0xABA6C0, accent = 0xFFFF2E88.toInt(), second = 0xFF4D7CFF.toInt(),
                lights = intArrayOf(0x42FF2E88.toInt(), 0x2E4D7CFF.toInt(), 0x4C5A1060.toInt())),
        )
        /** The scheme in use. Screens read it as they draw, so a change shows on screens opened after it. */
        @Volatile var now: Theme = all.first { it.id == DEFAULT }
            private set

        private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        fun load(c: Context) { now = all.firstOrNull { it.id == prefs(c).getString("theme", DEFAULT) } ?: now }
        fun choose(c: Context, theme: Theme) {
            prefs(c).edit().putString("theme", theme.id).apply()
            now = theme
        }
    }
}

/** The scheme's background colour at [alpha] (0 to 255), for shades that fade art into it. */
fun baseAt(alpha: Int): Int = (alpha shl 24) or (Theme.now.base and 0xFFFFFF)

/** [color] with its alpha replaced by [alpha] (0 to 255). */
fun withAlpha(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0xFFFFFF)

private fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(shift: Int) = ((a shr shift and 0xFF) * (1 - t) + (b shr shift and 0xFF) * t).toInt() shl shift
    return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
}
