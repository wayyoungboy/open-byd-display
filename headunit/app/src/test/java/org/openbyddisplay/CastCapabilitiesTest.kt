package org.openbyddisplay

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.openbyddisplay.cast.CastCapabilities

class CastCapabilitiesTest {
    @Test fun centerBoundsWithoutHmiExplainsTangStyleChrome() {
        val caps = CastCapabilities.parse(
            JSONObject()
                .put("bounds", org.json.JSONArray(listOf(410, 0, 1100, 720)))
                .put("physical_width", 1920)
                .put("physical_height", 720)
                .put("hmi_control", false)
                .put("native_overlay", false)
                .put("display_id", 2),
        )
        assertFalse(caps.coversPhysical)
        assertFalse(caps.hmiControl)
        val note = caps.fullscreenNote(wantFullLayout = true)
        assertTrue(note.contains("不会隐藏原厂"))
        assertTrue(note.contains("标定小于物理屏") || note.contains("周围原厂"))
        assertTrue(caps.summarize().contains("否"))
    }

    @Test fun fullPhysicalWithoutHmiStillWarnsOemOverlay() {
        val caps = CastCapabilities.parse(
            JSONObject()
                .put("bounds", org.json.JSONArray(listOf(0, 0, 1920, 720)))
                .put("physical_width", 1920)
                .put("physical_height", 720)
                .put("hmi_control", false),
        )
        assertTrue(caps.coversPhysical)
        val note = caps.fullscreenNote(true)
        assertTrue(note.contains("hmi_control=false") || note.contains("不会切换原厂"))
    }

    @Test fun profileAreaSelectionStillPrefersLargestForFullWidth() {
        // Regression: Tang factory defaults use profile 1 as 1920×720, profile 3 as 1100×720.
        val profiles = org.openbyddisplay.cast.CastProfile.parseList(
            org.json.JSONArray()
                .put(JSONObject("""{"profile_id":3,"theme_id":4,"x":410,"y":0,"width":1100,"height":720}"""))
                .put(JSONObject("""{"profile_id":1,"theme_id":4,"x":0,"y":0,"width":1920,"height":720}""")),
        )
        assertEquals(1, org.openbyddisplay.cast.CastProfile.select(profiles, true).profileId)
    }
}
