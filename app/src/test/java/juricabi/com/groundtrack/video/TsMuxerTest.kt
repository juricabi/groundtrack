package juricabi.com.groundtrack.video

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recording is only as good as the file a player later opens, and no field
 * report can say which byte of a transport stream was wrong. These read the
 * muxer's output back the way a demuxer does — packet by packet, table by
 * table, PES by PES — against hand-built access units.
 */
class TsMuxerTest {

    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

    private fun idr(size: Int) = byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(size) { (it % 251 + 1).toByte() }
    private fun slice(size: Int) = byteArrayOf(0, 0, 0, 1, 0x41) + ByteArray(size) { (it % 7 + 1).toByte() }

    private fun mux(block: TsMuxer.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { TsMuxer(it).block() }.toByteArray()

    @Test
    fun keyframeCarriesTablesParameterSetsAndTheFrameIntact() {
        val frame = idr(3000)
        val ts = mux { frame(RtpCodec.H264, frame, 90_000, true, listOf(sps, pps), true) }
        val packets = packets(ts)
        assertEquals(TsMuxer.PAT_PID, packets[0].pid)
        assertEquals(TsMuxer.PMT_PID, packets[1].pid)
        val body = pes(packets).single()
        // PES start code, video stream, PTS-only header
        assertArrayEquals(byteArrayOf(0, 0, 1, 0xE0.toByte()), body.copyOfRange(0, 4))
        assertEquals(0x80, body[7].toInt() and 0xC0)
        val es = body.copyOfRange(14, body.size)
        val expected = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()) +
            annexB(sps) + annexB(pps) + frame
        assertArrayEquals(expected, es)
    }

    @Test
    fun tablesHaveValidCrcsAndNameTheCodec() {
        for ((codec, type) in listOf(RtpCodec.H264 to 0x1B, RtpCodec.H265 to 0x24)) {
            val ts = mux { frame(codec, idr(10), 0, true, emptyList(), false) }
            val packets = packets(ts)
            assertTablesValid(packets.take(2))
            // nothing but the picture: no sound is declared that never came
            assertEquals(listOf(type to TsMuxer.VIDEO_PID), streams(packets[1]))
        }
    }

    private fun assertTablesValid(tables: List<Packet>) {
        for (table in tables) {
            val section = section(table)
            val length = (section[1].toInt() and 0x0F shl 8) or (section[2].toInt() and 0xFF)
            // the MPEG CRC over a section including its own CRC is zero
            assertEquals(0, TsMuxer.crc32(section, 3 + length))
        }
    }

    // a 48 kHz mono AAC-LC frame
    private fun aacFrame(size: Int) = adts(byteArrayOf(0x11, 0x88.toByte()), ByteArray(size) { (it + 1).toByte() })
    private val aac = aacFrame(100)

    @Test
    fun soundRidesItsOwnPidOnThePicturesClock() {
        val ts = mux {
            frame(RtpCodec.H264, idr(100), 90_000, true, listOf(sps, pps), true)
            sound(aac, 90_000)
            sound(aac, 91_920)
        }
        val packets = packets(ts)
        val sounds = pes(packets, TsMuxer.SOUND_PID)
        assertEquals(2, sounds.size)
        for (body in sounds) {
            // an audio stream, its length bounded, the frame carried whole
            assertArrayEquals(byteArrayOf(0, 0, 1, 0xC0.toByte()), body.copyOfRange(0, 4))
            val length = (body[4].toInt() and 0xFF shl 8) or (body[5].toInt() and 0xFF)
            assertEquals(8 + aac.size, length)
            assertArrayEquals(aac, body.copyOfRange(14, 6 + length))
        }
        // the same lead as the picture's, so the two play together
        assertEquals(listOf(99_000L, 100_920L), sounds.map(::pts))
        assertEquals(99_000L, pts(pes(packets).single()))
        // the sound carries no clock of its own
        assertTrue(packets.filter { it.pid == TsMuxer.SOUND_PID }.none { it.flags and 0x10 != 0 })
    }

    @Test
    fun soundJoiningAfterThePictureSendsTheTablesAgainAtOnce() {
        val packets = packets(mux {
            frame(RtpCodec.H264, idr(100), 0, true, listOf(sps, pps), true)
            frame(RtpCodec.H264, slice(100), 3000, false, emptyList(), false)
            sound(aac, 3000)
        })
        val pmts = packets.filter { it.pid == TsMuxer.PMT_PID }
        assertEquals(2, pmts.size)
        assertTablesValid(packets.filter { it.pid == TsMuxer.PAT_PID || it.pid == TsMuxer.PMT_PID })
        assertEquals(listOf(0x1B to TsMuxer.VIDEO_PID, 0x0F to TsMuxer.SOUND_PID), streams(pmts[1]))
        // a new version, so a player reading the tables again takes it up
        val version = { p: Packet -> section(p)[5].toInt() shr 1 and 0x1F }
        assertTrue(version(pmts[0]) != version(pmts[1]))
        // and the tables go out before the sound they announce
        val firstSound = packets.indexOfFirst { it.pid == TsMuxer.SOUND_PID }
        assertTrue(packets.indexOf(pmts[1]) < firstSound)
    }

    @Test
    fun soundSeenBeforeThePictureIsInItsFirstTables() {
        val packets = packets(mux {
            withSound()
            frame(RtpCodec.H264, idr(100), 0, true, listOf(sps, pps), true)
        })
        // one set of tables, the picture's, and they already say sound
        val pmts = packets.filter { it.pid == TsMuxer.PMT_PID }
        assertEquals(1, pmts.size)
        assertEquals(listOf(0x1B to TsMuxer.VIDEO_PID, 0x0F to TsMuxer.SOUND_PID), streams(pmts[0]))
    }

    @Test
    fun soundOfEverySizeIsWholeAndOnlyItsFirstIsARestart() {
        val sizes = listOf(1, 160, 161, 162, 163, 168, 169, 170, 171, 400)
        val packets = packets(mux {
            frame(RtpCodec.H264, idr(10), 0, true, emptyList(), false)
            for (size in sizes) sound(aacFrame(size), 0)
        })
        // around the first packet's 182 bytes of room (its restart flag takes
        // two) and the 184 after; the frame back whole, the stuffing all outside it
        assertEquals(sizes.map { aacFrame(it).toList() }, pes(packets, TsMuxer.SOUND_PID).map {
            it.copyOfRange(14, it.size).toList()
        })
        val starts = packets.filter { it.pid == TsMuxer.SOUND_PID && it.start }
        assertEquals(0x80, starts.first().flags and 0x80)
        assertTrue(starts.drop(1).all { it.flags and 0x80 == 0 })
    }

    @Test
    fun ptsLeadsPcrAndBothFollowTheClock() {
        val ts = mux {
            frame(RtpCodec.H264, idr(100), 90_000, true, listOf(sps, pps), false)
            frame(RtpCodec.H264, slice(100), 93_000, false, emptyList(), false)
        }
        val packets = packets(ts)
        val starts = packets.filter { it.pid == TsMuxer.VIDEO_PID && it.start }
        assertEquals(listOf(90_000L, 93_000L), starts.map { it.pcr })
        assertEquals(listOf(99_000L, 102_000L), pes(packets).map(::pts))
    }

    @Test
    fun everySizeSplitsAndStuffsIntoWholePackets() {
        // around the first packet's 176 bytes of room, and the 184 after
        for (size in listOf(1, 150, 155, 156, 157, 158, 170, 171, 330, 339, 340, 341, 342, 5000)) {
            val frame = slice(size)
            val ts = mux { frame(RtpCodec.H264, frame, 0, false, emptyList(), false) }
            val body = pes(packets(ts)).single()
            assertArrayEquals(
                "size $size", byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()) + frame,
                body.copyOfRange(14, body.size)
            )
        }
    }

    @Test
    fun continuityCountsOnPerPid() {
        val ts = mux {
            repeat(40) {
                frame(RtpCodec.H264, if (it % 10 == 0) idr(900) else slice(400), it * 3000L, it % 10 == 0, listOf(sps, pps), false)
                if (it > 4) sound(aac, it * 3000L)
            }
        }
        packets(ts).groupBy { it.pid }.forEach { (pid, list) ->
            list.zipWithNext().forEach { (a, b) ->
                assertEquals("pid $pid", (a.counter + 1) and 0x0F, b.counter)
            }
        }
    }

    @Test
    fun discontinuityAndRandomAccessAreFlaggedOnlyWhereTheyAre() {
        val ts = mux {
            frame(RtpCodec.H264, idr(50), 0, true, listOf(sps, pps), true)
            frame(RtpCodec.H264, slice(50), 3000, false, emptyList(), false)
        }
        val (first, second) = packets(ts).filter { it.pid == TsMuxer.VIDEO_PID && it.start }
        assertEquals(0xC0, first.flags and 0xC0)
        assertEquals(0, second.flags and 0xC0)
    }

    @Test
    fun inBandParameterSetsAndDelimitersAreNotRepeated() {
        val frame = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()) + annexB(sps) + annexB(pps) + idr(80)
        val ts = mux { frame(RtpCodec.H264, frame, 0, true, listOf(sps, pps), false) }
        val body = pes(packets(ts)).single()
        assertArrayEquals(frame, body.copyOfRange(14, body.size))
    }

    @Test
    fun outOfBandParameterSetsGoAfterTheStreamsOwnDelimiter() {
        val aud = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
        val slice = idr(80)
        val ts = mux { frame(RtpCodec.H264, aud + slice, 0, true, listOf(sps, pps), false) }
        val body = pes(packets(ts)).single()
        assertArrayEquals(aud + annexB(sps) + annexB(pps) + slice, body.copyOfRange(14, body.size))
    }

    @Test
    fun theFirstTablesOfAMuxerAreFlaggedAsARestart() {
        val packets = packets(mux {
            frame(RtpCodec.H264, idr(10), 0, true, emptyList(), false)
            frame(RtpCodec.H264, idr(10), 3000, true, emptyList(), false)
        })
        val tables = packets.filter { it.pid == TsMuxer.PAT_PID }
        assertEquals(0x80, tables[0].flags and 0x80)
        assertFalse(tables[1].adaptation)
    }

    @Test
    fun theLastTimeInAFileIsReadBackFromItsTail() {
        val ts = mux {
            frame(RtpCodec.H264, idr(400), 90_000, true, listOf(sps, pps), false)
            frame(RtpCodec.H264, slice(900), 93_000, false, emptyList(), false)
            frame(RtpCodec.H264, slice(30), 96_000, false, emptyList(), false)
        }
        assertEquals(96_000L, lastTime90k(ts))
        assertEquals(null, lastTime90k(ByteArray(0)))
    }

    @Test
    fun theTailCountsTheSoundAndKeepsItsForm() {
        val stereo = adts(byteArrayOf(0x12, 0x10), ByteArray(50))
        val ts = mux {
            frame(RtpCodec.H264, idr(400), 90_000, true, listOf(sps, pps), false)
            sound(stereo, 96_500) // a little past the last picture
            frame(RtpCodec.H264, slice(900), 93_000, false, emptyList(), false)
        }
        assertEquals(96_500L, lastTime90k(ts))
        assertArrayEquals(stereo.copyOf(7), lastSoundHeader(ts))
        assertEquals(null, lastSoundHeader(mux { frame(RtpCodec.H264, idr(10), 0, true, emptyList(), false) }))
    }

    @Test
    fun annexBPrefixesOnlyWhatLacksAStartCode() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + sps, annexB(sps))
        val four = byteArrayOf(0, 0, 0, 1) + sps
        val three = byteArrayOf(0, 0, 1) + sps
        assertTrue(annexB(four) === four)
        assertTrue(annexB(three) === three)
        assertEquals(listOf(9, 7, 8, 5), nalTypes(RtpCodec.H264, byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()) + three + annexB(pps) + idr(4)))
        assertFalse(nalTypes(RtpCodec.H265, byteArrayOf(0, 0, 1, 0x40, 1)).isEmpty())
    }
}
