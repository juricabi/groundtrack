package juricabi.com.groundtrack.protocol

import juricabi.com.groundtrack.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ArduPilot's status text over S.Port passthrough, appid 0x5000: four 7-bit
 * characters to a word, each word sent several times over, the last one with
 * an empty slot and the severity in the tops of its low bytes. The words here
 * are built the way ArduPilot's get_next_msg_chunk builds them, and the
 * vectors are the ones a model of it gives.
 */
class FrskyStatusTextTest {

    private class Ear : DataDecoder.Companion.DefaultDecodeListener() {
        val said = ArrayList<Pair<String, Int>>()
        override fun onStatusText(message: String, severity: Int) { said.add(message to severity) }
    }

    @Test
    fun theFirmwaresOwnWordsReadBack() {
        val ear = Ear()
        val protocol = FrSkySportProtocol(ear)
        // "ABC" at INFO: the severity rides the empty slots' tops
        repeat(4) { feed(protocol, frame(TEXT, 0x41C2C300)) }
        // "ABCD" at WARNING: four characters, then a word of severity alone
        repeat(4) { feed(protocol, frame(TEXT, 0x41424344)) }
        repeat(4) { feed(protocol, frame(TEXT, 0x00800000)) }
        // "Hi" at ERROR
        repeat(4) { feed(protocol, frame(TEXT, 0x48698080)) }
        assertEquals(listOf("ABC" to 6, "ABCD" to 4, "Hi" to 3), ear.said)
    }

    @Test
    fun anEmergencyEndsOnAWordOfNothing() {
        val ear = Ear()
        val protocol = FrSkySportProtocol(ear)
        // the end word of a four-character EMERGENCY is 0: a real word, not silence
        feed(protocol, frame(TEXT, 0x41424344))
        feed(protocol, frame(TEXT, 0x00000000))
        assertEquals(listOf("ABCD" to 0), ear.said)
    }

    @Test
    fun copiesAmongTheOtherSensorsAreReadOnce() {
        val ear = Ear()
        val protocol = FrSkySportProtocol(ear)
        val said = "PreArm: Compass not calibrated"
        for (word in words(said, 4)) repeat(3) {
            feed(protocol, frame(TEXT, word))
            // the passthrough's other words come between the copies
            feed(protocol, frame(ATTITUDE, 0x12345))
            feed(protocol, frame(AP_STATUS, 0x40))
        }
        assertEquals(listOf(said to 4), ear.said)
    }

    @Test
    fun fiftyCharactersIsAWholeMessage() {
        val ear = Ear()
        val protocol = FrSkySportProtocol(ear)
        val said = "EKF3 IMU0 is using GPS; origin set, yaw aligned ok"
        assertEquals(50, said.length)
        words(said, 6).forEach { feed(protocol, frame(TEXT, it)) }
        // and what follows starts afresh
        words("Armed", 5).forEach { feed(protocol, frame(TEXT, it)) }
        assertEquals(listOf(said to 6, "Armed" to 5), ear.said)
    }

    @Test
    fun aMessageWhoseEndWasLostGivesWayToTheNext() {
        val ear = Ear()
        val protocol = FrSkySportProtocol(ear)
        // forty-eight characters whose end word never came: the next
        // message's first word runs past fifty, and starts afresh
        val lost = ('A'..'Z').joinToString("") + ('a'..'v').joinToString("")
        words(lost, 6).dropLast(1).forEach { feed(protocol, frame(TEXT, it)) }
        words("Disarmed", 5).forEach { feed(protocol, frame(TEXT, it)) }
        assertEquals(listOf("Disarmed" to 5), ear.said)
    }

    @Test
    fun charactersTheLinkStuffsComeThrough() {
        val ear = Ear()
        val protocol = FrSkySportProtocol(ear)
        // '~' and '}' are the frame's own marker and escape on the wire
        words("a~}b", 6).forEach { feed(protocol, frame(TEXT, it)) }
        assertEquals(listOf("a~}b" to 6), ear.said)
    }

    /** ArduPilot's words for [text]: at most fifty characters, the last word marked. */
    private fun words(text: String, severity: Int): List<Int> {
        val out = ArrayList<Int>()
        var at = 0
        while (true) {
            var word = 0
            var end = false
            for (k in 0 until 4) {
                if (at == 50) { end = true; break }
                val c = text.getOrNull(at++)?.code ?: 0
                if (c == 0) { end = true; break }
                word = word or (c shl ((3 - k) * 8))
            }
            if (end) {
                word = word or ((severity and 4) shl 21) or ((severity and 2) shl 14) or ((severity and 1) shl 7)
                out.add(word)
                return out
            }
            out.add(word)
        }
    }

    private fun feed(protocol: Protocol, bytes: ByteArray) {
        bytes.forEach { protocol.process(it.toInt() and 0xFF) }
    }

    /** One S.Port data frame as the receiver hands it on: marker, sensor id, then the stuffed body. */
    private fun frame(appId: Int, value: Int): ByteArray {
        val body = intArrayOf(
            0x10, appId and 0xFF, appId shr 8,
            value and 0xFF, (value ushr 8) and 0xFF, (value ushr 16) and 0xFF, value ushr 24
        )
        var sum = 0
        for (b in body) { sum += b; sum += sum shr 8; sum = sum and 0xFF }
        val out = arrayListOf(0x7E, 0x1B)
        for (b in body + (0xFF - sum)) {
            if (b == 0x7E || b == 0x7D) { out.add(0x7D); out.add(b xor 0x20) } else out.add(b)
        }
        return ByteArray(out.size) { out[it].toByte() }
    }

    private companion object {
        const val TEXT = 0x5000
        const val AP_STATUS = 0x5001
        const val ATTITUDE = 0x5006
    }
}
