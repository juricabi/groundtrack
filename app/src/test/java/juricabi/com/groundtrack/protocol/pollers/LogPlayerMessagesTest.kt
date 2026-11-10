package juricabi.com.groundtrack.protocol.pollers

import android.os.Looper
import juricabi.com.groundtrack.protocol.crc.CRC8
import juricabi.com.groundtrack.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.ByteBuffer

/**
 * A replay's messages are asked of the recording. Heard on the bus they are
 * not all there: a seek hands on only the last of each kind of reading in the
 * stretch it walks, so a scrub, a rewind or a fast replay said a fraction.
 */
@RunWith(RobolectricTestRunner::class)
class LogPlayerMessagesTest {

    private class Screen : DataDecoder.Companion.DefaultDecodeListener() {
        val heard = ArrayList<String>()
        override fun onStatusText(message: String, severity: Int) { heard.add(message) }
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf(type.toByte()) + payload
        val crc = CRC8()
        crc.reset()
        body.forEach { crc.update(it) }
        return byteArrayOf(0xC8.toByte(), (body.size + 1).toByte()) + body + byteArrayOf(crc.value.toByte())
    }

    private fun gps(step: Int): ByteArray = frame(0x02, ByteBuffer.allocate(15)
        .putInt(450000000 + step * 100).putInt(150000000).putShort(0).putShort(0)
        .putShort((1000 + 120).toShort()).put(5).array())

    private fun text(severity: Int, words: String): ByteArray =
        frame(0x80, byteArrayOf(0xF1.toByte(), severity.toByte()) + words.toByteArray() + byteArrayOf(0))

    private fun load(log: ByteArray, screen: Screen, askEarly: Boolean = false): Pair<LogPlayer, Int> {
        val file = File.createTempFile("messages", ".tlm").apply { writeBytes(log); deleteOnExit() }
        val player = LogPlayer(screen)
        var size = -1
        player.load(file, object : LogPlayer.DataReadyListener {
            override fun onUpdate(percent: Int) {}
            override fun onDataReady(ready: Int) { size = ready }
            override fun onPlaybackPositionChange(prevPosition: Int, nextPosition: Int) {}
            override fun onPlaybackStateChange(isPlaying: Boolean) {}
            override fun getTotalPlaybackDurationSec() = 30
            override fun getPlaybackAutostart() = false
            override fun onProtocolDetected(protocolName: String) {}
        })
        // as the screen does when the CSV's clock is read before the log is
        if (askEarly) player.messages()
        val until = System.currentTimeMillis() + 10_000
        while (size < 0 && System.currentTimeMillis() < until) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return player to size
    }

    @Test
    fun everyMessageIsTheRecordingsEvenWhereASeekSaysOnlyTheLast() {
        var step = 0
        val log = List(10) { gps(step++) }.reduce(ByteArray::plus) +
            text(2, "PreArm: Throttle below failsafe") + text(2, "PreArm: GPS not healthy") +
            text(4, "Battery 1 low") + List(10) { gps(step++) }.reduce(ByteArray::plus)
        val screen = Screen()
        val (player, size) = load(log, screen)

        player.seek(size)
        assertEquals(listOf("Battery 1 low"), screen.heard)

        val all = player.messages()
        assertEquals(listOf("PreArm: Throttle below failsafe", "PreArm: GPS not healthy", "Battery 1 low"), all.map { it.text })
        assertEquals(listOf(2, 2, 4), all.map { it.severity })
        // in the order recorded, each where it came
        assertEquals(all.map { it.index }.sorted(), all.map { it.index })
        // and as far as a replay standing anywhere has got
        assertEquals(0, player.messagesBefore(all[0].index).size)
        assertEquals(1, player.messagesBefore(all[0].index + 1).size)
        assertEquals(3, player.messagesBefore(size).size)
    }

    /** An S.Port data frame: marker, sensor id, then the stuffed body and its sum. */
    private fun sport(appId: Int, value: Int): ByteArray {
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

    private fun sportGps(step: Int): ByteArray =
        sport(0x0800, (450000 + step) * 60) + sport(0x0800, (150000 * 60) or 0x80000000.toInt())

    @Test
    fun aFrskyLinksTextIsGatheredForTheReplay() {
        // "Hi" at ERROR and "ABCD" at WARNING, each word sent over and over
        val words = listOf(0x48698080, 0x41424344, 0x00800000)
        var step = 0
        val log = List(20) { sportGps(step++) }.reduce(ByteArray::plus) +
            words.flatMap { word -> List(4) { sport(0x5000, word) + sportGps(step++) } }.reduce(ByteArray::plus) +
            List(20) { sportGps(step++) }.reduce(ByteArray::plus)
        val (player, _) = load(log, Screen())
        val all = player.messages()
        assertEquals(listOf("Hi", "ABCD"), all.map { it.text })
        assertEquals(listOf(3, 4), all.map { it.severity })
    }

    @Test
    fun askedWhileTheLogWasStillLoadingTheMessagesAreThereOnceItHas() {
        val log = List(5) { gps(it) }.reduce(ByteArray::plus) + text(4, "Battery 1 low") + gps(5)
        val (player, _) = load(log, Screen(), askEarly = true)
        assertEquals(listOf("Battery 1 low"), player.messages().map { it.text })
    }
}
