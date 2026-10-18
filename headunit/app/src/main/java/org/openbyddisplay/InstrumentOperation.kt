package org.openbyddisplay

/** Single process ownership: update mutations and capture cannot overlap. */
object InstrumentOperation {
    private var owner: String? = null
    @Synchronized fun acquire(operation: String): Boolean {
        if (owner != null) return false
        owner = operation; return true
    }
    @Synchronized fun release(operation: String) { if (owner == operation) owner = null }
}
