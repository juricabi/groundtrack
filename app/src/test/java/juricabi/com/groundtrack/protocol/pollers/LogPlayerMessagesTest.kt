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

    @Test
    fun askedWhileTheLogWasStillLoadingTheMessagesAreThereOnceItHas() {
        val log = List(5) { gps(it) }.reduce(ByteArray::plus) + text(4, "Battery 1 low") + gps(5)
        val (player, _) = load(log, Screen(), askEarly = true)
        assertEquals(listOf("Battery 1 low"), player.messages().map { it.text })
    }
}
