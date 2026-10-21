package org.openbyddisplay

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeUpdateTest {
    @Test fun nativeGenerationHasSeparateEndpointAndBoundedKind() {
        assertEquals(InstrumentUpdateProtocol.Kind.NATIVE, InstrumentUpdatePackage.kind("song.obg"))
        assertEquals(47793, InstrumentUpdateProtocol.port(InstrumentUpdateProtocol.Kind.NATIVE))
        assertEquals(47785, InstrumentUpdateProtocol.port(InstrumentUpdateProtocol.Kind.FIRMWARE))
        assertEquals(16L * 1024 * 1024 + 208 + 16 * 128, InstrumentUpdateProtocol.Kind.NATIVE.maxBytes)
    }

    @Test fun nativeCapabilityCannotEnableLegacyFirmwareFlash() {
        val info = JSONObject("""{"ok":true,"update_protocol":3,"receive_available":true,"native_generation_available":true,"target":"song-plus-dmi-2021","baseline_sha256":"${"1".repeat(64)}","public_key_sha256":"${"2".repeat(64)}"}""")
        val capability = InstrumentUpdateProtocol.capability(info)
        assertTrue(capability.allows(InstrumentUpdateProtocol.Kind.NATIVE))
        assertFalse(capability.allows(InstrumentUpdateProtocol.Kind.FIRMWARE))
        assertFalse(capability.allows(InstrumentUpdateProtocol.Kind.COMPONENTS))
    }

    @Test fun pendingTrialIsNotSuccessOrReplaceable() {
        val state = JSONObject("""{"kind":"native_generation","update_id":"${"a".repeat(32)}","remote":{"stage":"awaiting_confirmation","applied":false}}""")
        assertFalse(InstrumentUpdateStore.canReplace(state))
        assertFalse(InstrumentUpdateProtocol.finished(state.getJSONObject("remote")))
        assertTrue(InstrumentUpdateProtocol.summary(state.getJSONObject("remote")).contains("验证"))
    }
}
