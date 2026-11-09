package juricabi.com.groundtrack.video

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The recording's timeline has to survive everything a flight does to the
 * stream — rebuilt players, wrapped clocks, stalls, a turned screen — and
 * the file has to survive the recorder being closed and reopened under it.
 */
class RecordingClockTest {

    @Test
    fun waitsForAKeyframeAndPinsItToTheWallClock() {
        val clock = RecordingClock(startedAtUs = 1_000_000)
        assertNull(clock.place(streamUs = 500, arrivalUs = 1_100_000, key = false))
        assertEquals(200_000L, clock.place(streamUs = 33_833, arrivalUs = 1_200_000, key = true))
        assertTrue(clock.tookDiscontinuity())
        // then the stream's own spacing, whatever the arrival jitter
        assertEquals(233_333L, clock.place(streamUs = 67_166, arrivalUs = 1_260_000, key = false))
        assertEquals(false, clock.tookDiscontinuity())
    }

    @Test
    fun aClockThatRestartsIsANewRunFromTheNextKeyframe() {
        val clock = RecordingClock(0)
        clock.place(10_000_000, 1_000_000, true)
        clock.place(10_033_000, 1_033_000, false)
        // a rebuilt player counts from zero again, five seconds later
        assertNull(clock.place(0, 6_000_000, false))
        assertEquals(6_033_000L, clock.place(33_000, 6_033_000, true))
        assertTrue(clock.tookDiscontinuity())
    }

    @Test
    fun aStallLongerThanTwoSecondsIsAGapOfItsRealLength() {
        val clock = RecordingClock(0)
        clock.place(0, 0, true)
        assertNull(clock.place(9_000_000, 3_000_000, false))
        assertEquals(3_100_000L, clock.place(9_100_000, 3_100_000, true))
    }

    @Test
    fun lostFramesResumeAtAKeyframeOnTheSameTimeline() {
        val clock = RecordingClock(0)
        clock.place(0, 0, true)
        clock.tookDiscontinuity()
        clock.lost()
        assertNull(clock.place(33_000, 40_000, false))
        assertEquals(66_000L, clock.place(66_000, 90_000, true))
        assertEquals(false, clock.tookDiscontinuity())
    }

    @Test
    fun aRunPinnedBehindWhatIsRecordedMovesWholeAndKeepsItsSpacing() {
        val clock = RecordingClock(0)
        clock.notBefore(5_000_000) // the file being appended to ends here
        // the wall clock says one second — behind the file's end
        val first = clock.place(0, 1_000_000, true)!!
        assertTrue(first > 5_000_000)
        assertEquals(first + 33_333, clock.place(33_333, 1_033_333, false))
        assertEquals(first + 66_666, clock.place(66_666, 1_066_666, false))
    }

    @Test
    fun soundWaitsForThePictureToPinItsRunThenRidesItsOffset() {
        val clock = RecordingClock(startedAtUs = 1_000_000)
        assertNull(clock.placeSound(10_000))
        clock.place(streamUs = 0, arrivalUs = 1_200_000, key = false) // not yet a keyframe
        assertNull(clock.placeSound(20_000))
        assertEquals(200_000L, clock.place(streamUs = 33_000, arrivalUs = 1_200_000, key = true))
        // the file begins with its picture: sound from before it is left out
        assertNull(clock.placeSound(21_000))
        // then the picture's offset, exactly, whatever the sound's own arrival
        assertEquals(209_333L, clock.placeSound(42_333))
    }

    @Test
    fun silenceFillsWhatTheSoundLeavesEmptyASecondBehindThePicture() {
        val clock = RecordingClock(0)
        clock.place(0, 0, true)
        assertEquals(10_000L, clock.placeSound(10_000))
        // the sound stops; the picture runs on
        assertNull(clock.silence(pictureUs = 1_000_000, frameUs = 21_000))
        assertEquals(31_000L, clock.silence(1_031_000, 21_000))
        assertNull(clock.silence(1_031_000, 21_000))
        // a picture far ahead brings every frame due, back to back
        val due = generateSequence { clock.silence(1_200_000, 21_000) }.toList()
        assertEquals((1..8).map { 31_000L + it * 21_000 }, due)
        // the sound coming back lands after the silence, never over it
        assertNull(clock.placeSound(190_000))
        assertEquals(1_150_000L, clock.placeSound(1_150_000))
    }

    @Test
    fun aFileWithSoundButNoneYetPlacedIsFilledFromItsFirstPicture() {
        val clock = RecordingClock(0)
        assertNull(clock.silence(5_000_000, 21_000)) // no picture, no file
        val first = clock.place(0, 500_000, true)!!
        assertEquals(first - 1 + 21_000, clock.silence(first + 1_500_000, 21_000))
    }

    @Test
    fun soundFromAnotherStretchOfTheClockIsLeftOut() {
        val clock = RecordingClock(0)
        clock.place(10_000_000, 0, true)
        // an old run's sound, queued behind a rebuilt player's first frames
        assertNull(clock.placeSound(1_000))
        // the picture jumped and has not pinned the new run yet
        assertNull(clock.place(0, 5_000_000, false))
        assertNull(clock.placeSound(10_000))
        clock.place(33_000, 5_033_000, true)
        assertEquals(5_010_000L, clock.placeSound(10_000))
    }

    @Test
    fun soundNeverStepsBackNorBeforeAnAppendedFilesEnd() {
        val clock = RecordingClock(0)
        clock.notBefore(5_000_000)
        // pinned a frame past the file's end: 5.04 s, so the run's offset is 4.04 s
        assertEquals(5_040_000L, clock.place(1_000_000, 1_000_000, true))
        // sound from 50 ms before that frame would land before the file's end
        assertNull(clock.placeSound(950_000))
        assertEquals(5_050_000L, clock.placeSound(1_010_000))
        assertNull(clock.placeSound(1_010_000))
        assertNull(clock.placeSound(1_005_000))
    }
}

@RunWith(RobolectricTestRunner::class)
class StreamRecorderTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Volatile private var now = 0L

    private fun record(file: File, append: Boolean, minFree: Long = 0, frames: StreamRecorder.() -> Unit): String? {
        val done = CountDownLatch(1)
        var ended: String? = null
        val recorder = StreamRecorder(file, append, 0, { _, why -> ended = why; done.countDown() },
            clockUs = { now }, minFreeBytes = minFree)
        recorder.frames()
        recorder.close()
        assertTrue("closed", done.await(5, TimeUnit.SECONDS))
        return ended
    }

    private fun StreamRecorder.gop(fromUs: Long) {
        for (i in 0 until 30) {
            now = fromUs + i * 33_333L
            val key = i == 0
            frame(
                RtpCodec.H264, byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41) + ByteArray(700),
                i * 33_333L, key, listOf(byteArrayOf(0x67, 1, 2), byteArrayOf(0x68, 3))
            )
        }
    }

    @Test
    fun aClosedRecordingIsWholePacketsAndAppendsCarryOn() {
        val file = File(folder.root, "flight.ts")
        assertNull(record(file, append = false) { gop(0) })
        val first = file.length()
        assertTrue(first > 0 && first % 188 == 0L)
        // the screen turned: a new recorder carries on in the same file
        assertNull(record(file, append = true) { gop(3_000_000) })
        assertTrue(file.length() > first && file.length() % 188 == 0L)
    }

    @Test
    fun anAppendNeverStepsBackInTimeAndHealsATornTail() {
        val file = File(folder.root, "turned.ts")
        record(file, append = false) { gop(10_000_000) }
        val before = lastTime90k(file.readBytes())!!
        // half a packet left by a kill
        file.appendBytes(ByteArray(100) { 0x47 })
        // the next screen's clock pins its first frame earlier than the file ends
        record(file, append = true) { gop(1_000_000) }
        val bytes = file.readBytes()
        assertEquals(0, bytes.size % 188)
        assertTrue(lastTime90k(bytes)!! > before)
    }

    @Test
    fun soundBeforeTheFirstKeyframeIsDeclaredInTheFilesFirstTables() {
        val file = File(folder.root, "sound.ts")
        assertNull(record(file, append = false) {
            // the sound is up before the picture's first keyframe, as it
            // usually is: these are left out, but say a sound is coming
            for (i in 0 until 5) sound(aac, i * 21_333L)
            gop(0)
            sound(aac, 100_000)
            sound(aac, 121_333)
        })
        val packets = packets(file.readBytes())
        val pmts = packets.filter { it.pid == TsMuxer.PMT_PID }
        // one stream list, the first, and it names the sound
        assertEquals(1, pmts.size)
        assertEquals(listOf(0x1B to TsMuxer.VIDEO_PID, 0x0F to TsMuxer.SOUND_PID), streams(pmts[0]))
        // on the picture's timeline, 90 kHz with the 0.1 s lead: the sound
        // from stream time 100 ms lands beside the picture's frame from 99.999
        assertEquals(listOf(18_000L, 19_919L), pes(packets, TsMuxer.SOUND_PID).map(::pts))
        assertEquals(17_999L, pts(pes(packets)[3]))
    }

    private val aac = adts(byteArrayOf(0x11, 0x88.toByte()), ByteArray(100))

    /** A steady 30 fps stream from frame [from] to [to], a keyframe a second, its sound alongside while [sound]. */
    private fun StreamRecorder.run(from: Int, to: Int, sound: Boolean, clockUs: Long = 0, wallUs: Long = 0) {
        var j = (from * 33_333L + 21_332) / 21_333
        for (i in from until to) {
            now = wallUs + i * 33_333L
            frame(
                RtpCodec.H264, byteArrayOf(0, 0, 0, 1, if (i % 30 == 0) 0x65 else 0x41) + ByteArray(700),
                clockUs + i * 33_333L, i % 30 == 0, listOf(byteArrayOf(0x67, 1, 2), byteArrayOf(0x68, 3))
            )
            while (sound && j * 21_333L < (i + 1) * 33_333L) sound(aac, clockUs + j++ * 21_333L)
        }
    }

    private fun soundTimes(file: File) = pes(packets(file.readBytes()), TsMuxer.SOUND_PID).map(::pts)

    @Test
    fun aKeyframeAheadOfTheSoundStillHasItInTheFirstTables() {
        val file = File(folder.root, "race.ts")
        record(file, append = false) {
            // the picture's keyframe reaches the recorder first, the sound
            // a third of a second behind it
            run(0, 10, sound = false)
            run(10, 60, sound = true)
        }
        val pmts = packets(file.readBytes()).filter { it.pid == TsMuxer.PMT_PID }
        assertEquals(listOf(0x1B to TsMuxer.VIDEO_PID, 0x0F to TsMuxer.SOUND_PID), streams(pmts.first()))
    }

    @Test
    fun soundThatStopsIsCarriedOnAsSilenceToTheEnd() {
        val file = File(folder.root, "muted.ts")
        record(file, append = false) {
            run(0, 30, sound = true)
            run(30, 150, sound = false) // turned off for four seconds
        }
        val times = soundTimes(file)
        // never a step but one frame's, and on until a second behind the picture
        assertTrue(times.zipWithNext().all { (a, b) -> b - a in 1919..1920 })
        val lastPicture = pes(packets(file.readBytes())).map(::pts).last()
        assertTrue(lastPicture - times.last() in 90_000..90_000 + 1920)
    }

    @Test
    fun anAppendKeepsTheFilesSoundFromItsFirstTables() {
        val file = File(folder.root, "turned.ts")
        record(file, append = false) { run(0, 60, sound = true) }
        val before = soundTimes(file)
        // the screen turned; the next screen's sound is not back yet
        record(file, append = true) { run(0, 90, sound = false, clockUs = 50_000_000, wallUs = 3_000_000) }
        val packets = packets(file.readBytes())
        val pmts = packets.filter { it.pid == TsMuxer.PMT_PID }
        assertTrue(pmts.all { streams(it).contains(0x0F to TsMuxer.SOUND_PID) })
        val times = soundTimes(file)
        assertTrue(times.size > before.size)
        // on from where the file's sound left off, never back over it
        assertTrue(times.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun aRecordingWithoutSoundHasNoSoundStream() {
        val file = File(folder.root, "quiet.ts")
        record(file, append = false) { run(0, 90, sound = false) }
        val packets = packets(file.readBytes())
        assertTrue(packets.none { it.pid == TsMuxer.SOUND_PID })
        assertEquals(listOf(0x1B to TsMuxer.VIDEO_PID), streams(packets.first { it.pid == TsMuxer.PMT_PID }))
    }

    @Test
    fun aRecordingThatNeverSawAKeyframeLeavesNoFile() {
        val file = File(folder.root, "empty.ts").also { it.createNewFile() }
        record(file, append = false) {
            frame(RtpCodec.H264, byteArrayOf(0, 0, 0, 1, 0x41, 1), 0, false, emptyList())
        }
        assertTrue(!file.exists())
    }

    @Test
    fun endsByItselfWhenTheStorageRunsLow() {
        val file = File(folder.root, "full.ts")
        val ended = record(file, append = false, minFree = Long.MAX_VALUE) { gop(0) }
        assertEquals("the storage is nearly full", ended)
    }

    @Test
    fun anUnwritableFileEndsTheRecordingSayingSo() {
        val dir = File(folder.root, "dir.ts").also { it.mkdirs() }
        val ended = record(dir, append = false) { gop(0) }
        assertTrue(ended!!.startsWith("it could not be written"))
    }

    @Test
    fun troubleFromTheSourceEndsItWithTheSourcesWords() {
        val file = File(folder.root, "t.ts")
        val ended = record(file, append = false) { trouble("the encoder failed") }
        assertEquals("the encoder failed", ended)
    }
}
