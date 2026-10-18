package org.openbyddisplay.cast

/** TCP ports used by the self-developed cluster cast path over USB NCM. */
object CastPorts {
    const val STATUS = 47777
    const val VIDEO = 47782
    const val RECEIVER_CONTROL = 47783
    const val HMI = 47785
    const val NAV_OVERLAY = 47786
    const val MUSIC_OVERLAY = 47787
    const val DEFAULT_HOST = "192.168.77.1"
    const val HEADUNIT_HOST = "192.168.77.2"
}
