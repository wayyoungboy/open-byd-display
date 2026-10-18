package org.openbyddisplay.cast

import org.junit.Assert.*
import org.junit.Test

class VignettePaletteTest {
    @Test fun effectiveThemeSelectsTheFeatherColorForAnyDisplayType() {
        assertEquals(VignettePalette.LIGHT_RGB, VignettePalette.fromUiMode(0x11))
        assertEquals(VignettePalette.DARK_RGB, VignettePalette.fromUiMode(0x21))
        assertEquals(VignettePalette.LIGHT_RGB, VignettePalette.fromUiMode(0x13))
    }

    @Test fun undefinedConfigurationRetainsTheLastTheme() {
        assertEquals(VignettePalette.LIGHT_RGB, VignettePalette.fromUiMode(0, VignettePalette.LIGHT_RGB))
        assertEquals(VignettePalette.DARK_RGB, VignettePalette.fromUiMode(0x31, VignettePalette.DARK_RGB))
    }

    @Test fun transparentAndOpaqueStopsHaveTheSameTint() {
        for (rgb in listOf(VignettePalette.DARK_RGB, VignettePalette.LIGHT_RGB)) {
            for (alpha in listOf(0, 200, 210, 220, 230, 255)) {
                val color = VignettePalette.withAlpha(rgb, alpha)
                assertEquals(rgb, color and 0x00FFFFFF)
                assertEquals(alpha, color ushr 24)
            }
        }
    }
}
