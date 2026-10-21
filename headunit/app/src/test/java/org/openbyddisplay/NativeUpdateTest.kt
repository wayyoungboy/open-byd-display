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

    @Test fun refreshingInfoCannotReplacePinnedTaskIdentity() {
        val state = JSONObject().put("target", "song-plus-dmi-2021").put("baseline_sha256", "1".repeat(64))
            .put("capability", JSONObject().put("public_key_sha256", "2".repeat(64)))
        val info = JSONObject().put("ok", true).put("update_protocol", 3).put("native_generation_available", true)
            .put("target", "song-plus-dmi-2021").put("baseline_sha256", "1".repeat(64))
            .put("public_key_sha256", "3".repeat(64))
        assertTrue(runCatching { InstrumentUpdateProtocol.nativeIdentity(state, info) }.isFailure)
        info.put("public_key_sha256", "2".repeat(64))
        InstrumentUpdateProtocol.nativeIdentity(state, info)
        info.put("target", "tang-plus-dmi-2021")
        assertTrue(runCatching { InstrumentUpdateProtocol.nativeIdentity(state, info) }.isFailure)
        assertEquals("2".repeat(64), state.getJSONObject("capability").getString("public_key_sha256"))
    }

    @Test fun nativeTrialAllowsVideoForHealthButNeverAnotherMutation() {
        val id = "a".repeat(32)
        val remote = JSONObject().put("ok", true).put("kind", "native_generation").put("update_id", id)
            .put("stage", "awaiting_confirmation").put("applied", false)
        val state = JSONObject().put("kind", "native_generation").put("host", "192.168.77.1")
            .put("update_id", id).put("apply_attempted", true).put("remote", remote)
        assertTrue(InstrumentUpdateStore.awaitingNative(state, "192.168.77.1"))
        assertTrue(InstrumentUpdateStore.canCast(state, "192.168.77.1"))
        assertFalse(InstrumentUpdateStore.canReplace(state))
        assertFalse(InstrumentUpdateStore.canCast(state, "127.0.0.1"))
        for (stage in listOf("ready", "applying", "recovery_required", "failed")) {
            remote.put("stage", stage)
            assertFalse(InstrumentUpdateStore.canCast(state, "192.168.77.1"))
        }
        remote.put("stage", "awaiting_confirmation").put("update_id", "b".repeat(32))
        assertFalse(InstrumentUpdateStore.canCast(state, "192.168.77.1"))
        remote.put("update_id", id); state.put("recovery_blocked", true)
        assertFalse(InstrumentUpdateStore.canCast(state, "192.168.77.1"))
    }

    @Test fun trialStatusReadCanCoexistWithCastButNeverReleaseItsOwnership() {
        try {
            assertTrue(InstrumentOperation.acquire("cast"))
            assertTrue(InstrumentOperation.acquireUpdateStatus())
            assertFalse(InstrumentOperation.acquire("update"))
            assertFalse(InstrumentOperation.acquire("usb"))
            assertFalse(InstrumentOperation.acquireUpdateStatus())
            InstrumentOperation.releaseUpdateStatus()
            assertFalse(InstrumentOperation.acquire("update"))
            InstrumentOperation.release("cast")
            assertTrue(InstrumentOperation.acquireUpdateStatus())
            assertFalse(InstrumentOperation.acquire("cast"))
        } finally {
            InstrumentOperation.releaseUpdateStatus(); InstrumentOperation.release("cast")
        }
    }
}
