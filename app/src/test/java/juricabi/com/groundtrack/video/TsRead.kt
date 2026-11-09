package juricabi.com.groundtrack.video

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals

/**
 * A transport stream read back the way a demuxer reads it — packet by
 * packet, table by table, PES by PES — for the tests of what writes one.
 */
internal class Packet(val bytes: ByteArray) {
    val start get() = bytes[1].toInt() and 0x40 != 0
    val pid get() = (bytes[1].toInt() and 0x1F shl 8) or (bytes[2].toInt() and 0xFF)
    val counter get() = bytes[3].toInt() and 0x0F
    val adaptation get() = bytes[3].toInt() and 0x20 != 0
    val adaptationLength get() = if (adaptation) bytes[4].toInt() and 0xFF else -1
    val flags get() = if (adaptationLength > 0) bytes[5].toInt() and 0xFF else 0
    val payload: ByteArray
        get() = bytes.copyOfRange(4 + if (adaptation) adaptationLength + 1 else 0, 188)
    val pcr: Long
        get() {
            val b = { i: Int -> bytes[6 + i].toLong() and 0xFF }
            return (b(0) shl 25) or (b(1) shl 17) or (b(2) shl 9) or (b(3) shl 1) or (b(4) shr 7)
        }
}

internal fun packets(ts: ByteArray): List<Packet> {
    assertEquals("whole packets only", 0, ts.size % 188)
    return (0 until ts.size / 188).map { Packet(ts.copyOfRange(it * 188, it * 188 + 188)) }
        .onEach { assertEquals(0x47, it.bytes[0].toInt()) }
}

/** The PES packets on [pid], each reassembled whole. */
internal fun pes(packets: List<Packet>, pid: Int = TsMuxer.VIDEO_PID): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var current: ByteArrayOutputStream? = null
    for (p in packets.filter { it.pid == pid }) {
        if (p.start) {
            current?.let { out += it.toByteArray() }
            current = ByteArrayOutputStream()
        }
        current!!.write(p.payload)
    }
    current?.let { out += it.toByteArray() }
    return out
}

internal fun pts(pes: ByteArray): Long {
    val b = { i: Int -> pes[9 + i].toLong() and 0xFF }
    return ((b(0) shr 1 and 0x07) shl 30) or (b(1) shl 22) or ((b(2) shr 1) shl 15) or
        (b(3) shl 7) or (b(4) shr 1)
}

/** A table packet's section, its pointer field stepped over. */
internal fun section(table: Packet): ByteArray = table.payload.copyOfRange(1, table.payload.size)

/** The (stream type, PID) pairs a PMT packet lists. */
internal fun streams(pmt: Packet): List<Pair<Int, Int>> {
    val s = section(pmt)
    val end = 3 + ((s[1].toInt() and 0x0F shl 8) or (s[2].toInt() and 0xFF)) - 4
    var at = 12 + ((s[10].toInt() and 0x0F shl 8) or (s[11].toInt() and 0xFF))
    val out = ArrayList<Pair<Int, Int>>()
    while (at < end) {
        out += (s[at].toInt() and 0xFF) to ((s[at + 1].toInt() and 0x1F shl 8) or (s[at + 2].toInt() and 0xFF))
        at += 5 + ((s[at + 3].toInt() and 0x0F shl 8) or (s[at + 4].toInt() and 0xFF))
    }
    return out
}
