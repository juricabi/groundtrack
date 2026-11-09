package juricabi.com.groundtrack.protocol

import juricabi.com.groundtrack.protocol.crc.CRCMAVLink
import juricabi.com.groundtrack.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the autopilot says reaches the screen as it said it: its words, cut
 * where they end, and how much they matter. MAVLink pads a short text to fifty
 * bytes, and the padding used to come along — two copies of one message then
 * read as different.
 */
class StatusTextTest {

    private class Ear : DataDecoder.Companion.DefaultDecodeListener() {
        val said = ArrayList<Pair<String, Int>>()
        override fun onStatusText(message: String, severity: Int) { said.add(message to severity) }
    }

    private fun text(severity: Int, words: String, padTo: Int = 51): ByteArray {
        val payload = ByteArray(maxOf(padTo, 1 + words.length))
        payload[0] = severity.toByte()
        words.toByteArray().copyInto(payload, 1)
        return payload
    }

    @Test
    fun mavLink1TextIsCutWhereItEndsAndKeepsItsSeverity() {
        val ear = Ear()
        val protocol = MAVLinkProtocol(ear)
        feed(protocol, mav1Frame(STATUSTEXT, text(4, "PreArm: Throttle below failsafe")))
        assertEquals(listOf("PreArm: Throttle below failsafe" to 4), ear.said)
    }

    @Test
    fun mavLink2TextArrivesTrimmedAndIsReadTheSame() {
        val ear = Ear()
        val protocol = MAVLink2Protocol(ear)
        // MAVLink 2 drops the trailing zeros off the wire
        feed(protocol, mav2Frame(STATUSTEXT, text(2, "EKF3 lane switch 1", padTo = 0)))
        assertEquals(listOf("EKF3 lane switch 1" to 2), ear.said)
    }

    @Test
    fun aFullLengthMavLink2TextIsWholeWithoutANul() {
        val ear = Ear()
        val protocol = MAVLink2Protocol(ear)
        val words = "x".repeat(50)
        // id 0: the only chunk there is, its fifty characters with no NUL
        feed(protocol, mav2Frame(STATUSTEXT, text(6, words) + byteArrayOf(0, 0, 0)))
        assertEquals(listOf(words to 6), ear.said)
    }

    private fun chunk(severity: Int, words: String, id: Int, seq: Int) =
        mav2Frame(STATUSTEXT, text(severity, words) + byteArrayOf(id.toByte(), (id shr 8).toByte(), seq.toByte()))

    @Test
    fun aLongMessageInChunksArrivesWhole() {
        val ear = Ear()
        val protocol = MAVLink2Protocol(ear)
        val long = "Preflight Fail: Accel Sensor 0 missing - check the IMU wiring and power, then reboot the vehicle"
        long.chunked(50).forEachIndexed { k, part -> feed(protocol, chunk(3, part, id = 0x1234, seq = k)) }
        assertEquals(listOf(long to 3), ear.said)
    }

    @Test
    fun aMessageOfWholeChunksEndsWithAnEmptyOne() {
        val ear = Ear()
        val protocol = MAVLink2Protocol(ear)
        val long = "y".repeat(100)
        feed(protocol, chunk(4, long.take(50), id = 9, seq = 0))
        feed(protocol, chunk(4, long.drop(50), id = 9, seq = 1))
        assertEquals(emptyList<Pair<String, Int>>(), ear.said)
        feed(protocol, chunk(4, "", id = 9, seq = 2))
        assertEquals(listOf(long to 4), ear.said)
    }

    @Test
    fun aChunkLostOnTheLinkSendsOnWhatCame() {
        val ear = Ear()
        val protocol = MAVLink2Protocol(ear)
        feed(protocol, chunk(2, "a".repeat(50), id = 5, seq = 0))
        // chunk 1 never came; the next message begins
        feed(protocol, chunk(2, "Battery failsafe", id = 6, seq = 0))
        assertEquals(listOf("a".repeat(50) to 2, "Battery failsafe" to 2), ear.said)
    }

    @Test
    fun aMessageInOnePieceGoesAfterOneBeingGathered() {
        val ear = Ear()
        val protocol = MAVLink2Protocol(ear)
        feed(protocol, chunk(6, "b".repeat(50), id = 7, seq = 0))
        feed(protocol, mav2Frame(STATUSTEXT, text(6, "Armed")))
        assertEquals(listOf("b".repeat(50) to 6, "Armed" to 6), ear.said)
    }

    @Test
    fun anEmptyTextIsNoMessage() {
        val ear = Ear()
        val protocol = MAVLinkProtocol(ear)
        feed(protocol, mav1Frame(STATUSTEXT, text(6, "")))
        assertEquals(emptyList<Pair<String, Int>>(), ear.said)
    }

    private fun feed(protocol: Protocol, frame: ByteArray) {
        frame.forEach { protocol.process(it.toUByte().toInt()) }
    }

    private fun mav1Frame(messageId: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(payload.size.toByte(), 0, 1, 1, messageId.toByte())
        return byteArrayOf(0xFE.toByte()) + header + payload + checksum(header, payload, messageId)
    }

    private fun mav2Frame(messageId: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(
            payload.size.toByte(), 0, 0, 0, 1, 1,
            messageId.toByte(), (messageId shr 8).toByte(), (messageId shr 16).toByte()
        )
        return byteArrayOf(0xFD.toByte()) + header + payload + checksum(header, payload, messageId)
    }

    private fun checksum(header: ByteArray, payload: ByteArray, messageId: Int): ByteArray {
        val crc = CRCMAVLink()
        header.forEach { crc.update_checksum(it.toUByte().toInt()) }
        payload.forEach { crc.update_checksum(it.toUByte().toInt()) }
        crc.finish_checksum(messageId)
        return byteArrayOf(crc.lsb.toByte(), crc.msb.toByte())
    }

    private companion object {
        const val STATUSTEXT = 253
    }
}
