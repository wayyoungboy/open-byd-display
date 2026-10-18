package org.openbyddisplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.openbyddisplay.cast.CastProfile
import org.openbyddisplay.cast.CastProtocol
import org.openbyddisplay.cast.Lhf2Framing
import org.openbyddisplay.cast.MusicOverlayProtocol
import org.openbyddisplay.cast.NavOverlayProtocol
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CastProtocolTest {
    @Test fun encodeRejectsCommercialAuthFields() {
        try {
            CastProtocol.encode(JSONObject().put("op", "cast.status").put("token", "x"))
            fail("expected reject")
        } catch (_: IllegalArgumentException) {
        }
        try {
            CastProtocol.encode(JSONObject().put("op", "cast.status").put("client_time", 1))
            fail("expected reject")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun castStartCarriesProfileAndRevision() {
        val body = CastProtocol.castStart("obd-1", "epoch", 2, 1)
        assertEquals("cast.start", body.getString("op"))
        assertEquals(1, body.getInt("profile_id"))
        assertEquals(2, body.getInt("expected_revision"))
        assertFalse(body.has("token"))
    }

    @Test fun profileSelectUsesAreaNotVendorNumbers() {
        val profiles = CastProfile.parseList(
            JSONArray()
                .put(JSONObject("""{"profile_id":3,"theme_id":4,"x":410,"y":0,"width":1100,"height":720}"""))
                .put(JSONObject("""{"profile_id":1,"theme_id":4,"x":0,"y":0,"width":1920,"height":720}"""))
                .put(JSONObject("""{"profile_id":2,"theme_id":4,"x":0,"y":0,"width":760,"height":720}""")),
        )
        assertEquals(1, CastProfile.select(profiles, fullWidth = true).profileId)
        assertEquals(2, CastProfile.select(profiles, fullWidth = false).profileId)
    }

    @Test fun bvl1StreamBodyAndLhf2FrameRoundTripCrc() {
        val handshake = Lhf2Framing.streamBody(1920, 720, 30)
        assertTrue(handshake.copyOfRange(0, 4).contentEquals("BVL1".toByteArray()))
        val jsonLen = ByteBuffer.wrap(handshake, 4, 4).order(ByteOrder.BIG_ENDIAN).int
        val json = handshake.copyOfRange(8, 8 + jsonLen).toString(Charsets.UTF_8)
        assertEquals("""{"op":"stream"}""", json)
        val payload = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        val frame = Lhf2Framing.frame(payload, 7, 1234, true)
        assertTrue(frame.copyOfRange(0, 4).contentEquals("LHF2".toByteArray()))
        val header = ByteBuffer.wrap(frame, 0, 24).order(ByteOrder.BIG_ENDIAN)
        header.position(4)
        assertEquals(payload.size, header.int)
        assertEquals(7, header.int)
        assertEquals(1234, header.int)
        assertEquals(1, header.int)
        assertEquals(Lhf2Framing.crc32(payload), header.int)
    }

    @Test fun lrcTimelineAndNavPayload() {
        val timeline = MusicOverlayProtocol.parseLrc("[00:01.00]hello\n[00:02.50]world\n")
        assertEquals(2, timeline.size)
        assertEquals(listOf("hello", "world"), MusicOverlayProtocol.linesAt(timeline, 2500, 2))
        val nav = NavOverlayProtocol.guidance("环路", 120, speedLimit = 80)
        assertEquals(120, nav.getInt("distance_m"))
        assertEquals(80, nav.getInt("speed_limit"))
    }
}
