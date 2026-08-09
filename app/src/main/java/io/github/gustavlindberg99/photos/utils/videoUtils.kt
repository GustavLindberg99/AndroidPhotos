package io.github.gustavlindberg99.photos.utils

import java.io.File
import java.io.RandomAccessFile
import java.util.Date

/**
 * Patches the creation and modification dates of an MP4 file.
 *
 * @param file  The file to patch.
 * @param date  The date to set the creation and modification dates to.
 */
public fun patchMp4Dates(file: File, date: Date?) {
    val secondsSince1904 = if (date == null) 0L else (date.time / 1000L) + 2082844800L
    RandomAccessFile(file, "rw").use { raf ->
        patchBoxes(raf, 0, raf.length(), secondsSince1904)
    }
}

/**
 * Patches the creation and modification dates of an MP4 file.
 *
 * @param raf       The random access file to patch.
 * @param start     The start position of the boxes to patch.
 * @param end       The end position of the boxes to patch.
 * @param seconds   The number of seconds since 1904 to set the creation and modification dates to.
 */
private fun patchBoxes(raf: RandomAccessFile, start: Long, end: Long, seconds: Long) {
    var pos = start
    while (pos < end - 8) {
        raf.seek(pos)
        var boxSize = raf.readInt().toLong() and 0xFFFFFFFFL
        val typeBytes = ByteArray(4)
        raf.read(typeBytes)
        val type = String(typeBytes, Charsets.US_ASCII)
        var headerSize = 8L
        if (boxSize == 1L) {
            boxSize = raf.readLong()
            headerSize = 16L
        }
        else if (boxSize == 0L) {
            boxSize = raf.length() - pos
        }

        if (boxSize < 8) break

        when (type) {
            "moov", "trak", "mdia" -> {
                patchBoxes(raf, pos + headerSize, pos + boxSize, seconds)
            }

            "mvhd", "tkhd", "mdhd" -> {
                patchTimestampBox(raf, pos + headerSize, seconds)
            }
        }
        pos += boxSize
    }
}

/**
 * Patches the creation and modification dates of an MP4 timestamp box.
 *
 * @param raf       The random access file to patch.
 * @param offset    The offset of the timestamp box.
 * @param seconds   The number of seconds since 1904 to set the creation and modification dates to.
 */
private fun patchTimestampBox(raf: RandomAccessFile, offset: Long, seconds: Long) {
    raf.seek(offset)
    val version = raf.read()
    raf.skipBytes(3) // flags
    if (version == 1) {
        raf.writeLong(seconds) // creation
        raf.writeLong(seconds) // modification
    }
    else {
        raf.writeInt(seconds.toInt()) // creation
        raf.writeInt(seconds.toInt()) // modification
    }
}