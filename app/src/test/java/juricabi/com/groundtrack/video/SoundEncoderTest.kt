package juricabi.com.groundtrack.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The ADTS header a player finds each sound frame by, read off the encoder's config. */
class AdtsTest {

    @Test
    fun adtsHeadersMatchTheConfigTheyAreReadOff() {
        // AAC-LC at 48 kHz mono and 44.1 kHz stereo: the headers ffmpeg
        // writes for the same two, byte for byte
        val mono = adts(byteArrayOf(0x11, 0x88.toByte()), ByteArray(100))
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x4C, 0x40, 0x0D, 0x7F, 0xFC.toByte()),
            mono.copyOf(7)
        )
        val stereo = adts(byteArrayOf(0x12, 0x10), ByteArray(1000))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte()), stereo.copyOf(4))
        // the 13-bit length counts the header too
        val length = (stereo[3].toInt() and 0x03 shl 11) or ((stereo[4].toInt() and 0xFF) shl 3) or
            ((stereo[5].toInt() and 0xFF) shr 5)
        assertEquals(1007, length)
        assertEquals(1007, stereo.size)
    }

    @Test
    fun silenceTakesTheFormOfTheSoundItStandsIn() {
        val mono = byteArrayOf(0x11, 0x88.toByte())
        val stereo = byteArrayOf(0x12, 0x10)
        // the config read back off a header is the one it was built from
        assertArrayEquals(mono, configOf(adts(mono, ByteArray(9))))
        assertArrayEquals(stereo, configOf(adts(stereo, ByteArray(9))))
        assertArrayEquals(adts(mono, ByteArray(0)).copyOf(3), silence(mono).copyOf(3))
        assertArrayEquals(adts(stereo, ByteArray(0)).copyOf(3), silence(stereo).copyOf(3))
        assertEquals(7 + 6, silence(mono).size)
        assertEquals(7 + 9, silence(stereo).size)
        assertEquals(48_000, sampleRate(mono))
        assertEquals(44_100, sampleRate(stereo))
        assertEquals(8_000, sampleRate(byteArrayOf(0x15, 0x88.toByte())))
    }
}

/** The clock the USB receiver's sound is stamped on, which reads arrive with none of. */
class SampleClockTest {

    @Test
    fun steadyReadsAreStampedByTheirCountNotTheirArrival() {
        val clock = SampleClock(48_000)
        // 20 ms chunks, arriving up to 15 ms late
        val jitter = listOf(0L, 15_000, 2_000, 9_000, 0, 14_000, 1_000)
        val stamps = jitter.mapIndexed { i, late -> clock.stamp(960, 1_020_000 + i * 20_000L + late) }
        // the first heard its own length before it arrived, and the rest
        // exactly a chunk apart
        assertEquals(List(jitter.size) { 1_000_000 + it * 20_000L }, stamps)
    }

    @Test
    fun aStallIsPinnedToWhenTheSoundResumed() {
        val clock = SampleClock(48_000)
        clock.stamp(960, 20_000)
        clock.stamp(960, 40_000)
        // half a second lost to a stall: counted on, it would say 40 ms
        assertEquals(520_000L, clock.stamp(960, 540_000))
        assertEquals(540_000L, clock.stamp(960, 560_000))
    }
}
