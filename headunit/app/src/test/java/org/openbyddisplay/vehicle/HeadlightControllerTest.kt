package org.openbyddisplay.vehicle

import org.junit.Assert.*
import org.junit.Test

class HeadlightControllerTest {
    private class System : HeadlightIo {
        var state = HeadlightSnapshot(0, 0, 1, 1)
        var failAfterChange = false
        var rejectAfterChange = false
        val writes = mutableListOf<Int>()
        override fun read() = state
        override fun naturalMode() = 1
        override fun activate(mode: Int): Boolean {
            writes += mode
            state = state.copy(actual = mode)
            if (failAfterChange) { failAfterChange = false; error("mutated then failed") }
            if (rejectAfterChange) { rejectAfterChange = false; return false }
            return true
        }
    }
    private fun started(system: System): HeadlightFollowController = HeadlightFollowController(system).also { it.poll(); it.poll() }
    @Test fun normalStopRestoresNaturalAutoAndIsIdempotent() {
        val system = System()
        val controller = started(system)
        assertEquals(2, system.state.actual)
        assertEquals(HeadlightRestoration.RESTORED, controller.finish())
        assertEquals(1, system.state.actual)
        controller.finish()
        assertEquals(listOf(2, 1), system.writes)
    }
    @Test fun unknownNativeFeedbackDoesNotEraseOwnershipOrClaimRecovery() {
        val system = System()
        val controller = started(system)
        system.state = system.state.copy(native = -1)
        controller.poll()
        assertEquals(HeadlightRestoration.UNCONFIRMED, controller.finish())
        assertEquals(listOf(2), system.writes)
        assertFalse(HeadlightProtocol.confirmed("ERROR UNCONFIRMED unavailable"))
    }
    @Test fun temporaryUnknownFeedbackCanRecoverOnceReadableAgain() {
        val system = System()
        val controller = started(system)
        system.state = system.state.copy(native = -1)
        controller.poll()
        system.state = system.state.copy(native = 0)
        assertEquals(HeadlightRestoration.RESTORED, controller.finish())
    }
    @Test fun anActivationWhichMutatesThenThrowsOrReturnsFalseStillRestores() {
        for (throwing in listOf(true, false)) {
            val system = System().apply { failAfterChange = throwing; rejectAfterChange = !throwing }
            val controller = HeadlightFollowController(system)
            controller.poll()
            try { controller.poll(); fail() } catch (_: IllegalStateException) { }
            assertEquals(2, system.state.actual)
            assertEquals(HeadlightRestoration.RESTORED, controller.finish())
            assertEquals(listOf(2, 1), system.writes)
        }
    }
    @Test fun confirmedManualChoiceWinsAndNoAttemptedWriteIsReportedAsUnchanged() {
        val system = System()
        val controller = started(system)
        system.state = system.state.copy(native = 1)
        controller.poll()
        assertEquals(HeadlightRestoration.YIELDED, controller.finish())
        assertEquals(listOf(2), system.writes)
        assertEquals(HeadlightRestoration.UNCHANGED, HeadlightFollowController(System()).finish())
    }
    @Test fun unknownAndroidPreferenceOrActualThemeCannotClearAPendingRestoration() {
        for (unreadable in listOf(HeadlightSnapshot(0, -1, 2, 1), HeadlightSnapshot(0, 0, -1, 1))) {
            val system = System()
            val controller = started(system)
            system.state = unreadable
            assertEquals(HeadlightRestoration.UNCONFIRMED, controller.finish())
        }
    }
}
