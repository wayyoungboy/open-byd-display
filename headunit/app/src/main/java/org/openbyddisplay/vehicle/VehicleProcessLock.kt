package org.openbyddisplay.vehicle

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/** Hold the file, channel and lock for the entire job, including long OEM calls. */
internal class VehicleProcessLock private constructor(private val file: RandomAccessFile, private val lock: FileLock) : AutoCloseable {
    override fun close() { try { lock.release() } finally { file.close() } }
    companion object {
        fun acquire(path: File): VehicleProcessLock {
            val file = RandomAccessFile(path, "rw")
            try { return VehicleProcessLock(file, file.channel.tryLock() ?: error("已有 USB helper 运行")) }
            catch (t: Throwable) { file.close(); throw t }
        }
    }
}
