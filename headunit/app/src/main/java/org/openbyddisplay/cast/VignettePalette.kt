package org.openbyddisplay.cast

/** Select by the effective Configuration theme, including Auto's temporary overrides. */
internal object VignettePalette {
    const val DARK_RGB = 0x000000
    const val LIGHT_RGB = 0xDDE3E8

    fun fromUiMode(uiMode: Int, previous: Int = DARK_RGB): Int = when (uiMode and 0x30) {
        0x10 -> LIGHT_RGB
        0x20 -> DARK_RGB
        else -> previous
    }

    fun withAlpha(rgb: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (rgb and 0x00FFFFFF)
}
