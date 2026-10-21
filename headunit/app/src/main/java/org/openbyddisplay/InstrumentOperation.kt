package org.openbyddisplay

/** Single process ownership: update mutations and capture cannot overlap. */
object InstrumentOperation {
    private var owner: String? = null
    private var updateStatus = false
    @Synchronized fun acquire(operation: String): Boolean {
        if (owner != null || updateStatus) return false
        owner = operation; return true
    }
    @Synchronized fun release(operation: String) { if (owner == operation) owner = null }
    /** Native trial observation only; it cannot change or release the video owner. */
    @Synchronized fun acquireUpdateStatus(): Boolean {
        if (updateStatus || owner != null && owner != "cast") return false
        updateStatus = true; return true
    }
    @Synchronized fun releaseUpdateStatus() { updateStatus = false }
}
