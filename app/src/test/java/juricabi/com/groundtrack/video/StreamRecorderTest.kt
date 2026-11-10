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

    // at 16 kHz a frame of sound is exactly 64 ms
    private val frame16k = 64_000L

    @Test
    fun silenceCarriesTheSoundASecondBehindThePictureAndLeadsItBackIn() {
        val silent = ArrayList<Long>()
        val clock = RecordingClock(0) { silent += it }
        clock.place(0, 0, true)
        clock.soundFrom(16_000)
        assertEquals(10_000L, clock.placeSound(10_000))
        // the sound stops; the picture runs on, the silence a second behind it
        clock.place(1_100_000, 1_100_000, false)
        assertEquals(emptyList<Long>(), silent)
        clock.place(1_138_000, 1_138_000, false)
        assertEquals(listOf(10_000 + frame16k), silent)
        clock.place(1_500_000, 1_500_000, false)
        assertEquals((1..6).map { 10_000 + it * frame16k }, silent)
        // the sound coming back is led in by silence, and picks up where the
        // silence ends — under a frame early, so not a sample's hole is left
        assertEquals(10_000 + 18 * frame16k, clock.placeSound(1_200_000))
        assertEquals((1..17).map { 10_000 + it * frame16k }, silent)
        // and the rest of its stretch keeps to that
        assertEquals(10_000 + 19 * frame16k, clock.placeSound(1_200_000 + frame16k))
    }

    @Test
    fun aRunKeepsItsSoundToItsLastPictureAndTheNextBeginsWithItsOwn() {
        val silent = ArrayList<Long>()
        val clock = RecordingClock(0) { silent += it }
        clock.place(0, 0, true)
        clock.soundFrom(16_000)
        clock.placeSound(10_000)
        for (k in 1..30) clock.place(k * 100_000L, k * 100_000L, false) // three seconds, silent from the start
        // a rebuilt player, five seconds on
        assertEquals(8_000_000L, clock.place(0, 8_000_000, true))
        // the run that ended has its sound through its last picture, at 3 s...
        assertTrue(silent.last() <= 3_000_000 && silent.last() + frame16k > 3_000_000)
        // ...and the gap the picture has too goes without
        assertTrue(silent.none { it > 3_000_000 })
        assertNull(clock.placeSound(-5_000)) // from before the new run's picture
        // the new run's sound starts with its picture, under a frame early
        assertEquals(8_000_000L, clock.placeSound(5_000))
    }

    @Test
    fun aSoundThatJoinsLateOwesNoSilenceForTheTimeBefore() {
        val silent = ArrayList<Long>()
        val clock = RecordingClock(0) { silent += it }
        for (k in 0..100) clock.place(k * 100_000L, k * 100_000L, k == 0)
        clock.soundFrom(16_000)
        clock.place(10_900_000, 10_900_000, false)
        assertEquals(emptyList<Long>(), silent)
        clock.place(11_200_000, 11_200_000, false)
        assertEquals(listOf(10_000_000 + frame16k, 10_000_000 + 2 * frame16k), silent)
    }

    @Test
    fun anAppendedFilesSoundCarriesOnFromWhereItStopped() {
        val silent = ArrayList<Long>()
        val clock = RecordingClock(0) { silent += it }
        // the file's picture ended at 10 s, its sound a second behind it
        clock.notBefore(10_000_000)
        clock.soundFrom(16_000, fromUs = 9_000_000)
        clock.place(0, 12_000_000, true) // the next screen's first frame
        assertEquals((1..15).map { 9_000_000 + it * frame16k }, silent)
    }

    @Test
    fun aClosingRecordingCarriesItsSoundToItsLastPicture() {
        val silent = ArrayList<Long>()
        val clock = RecordingClock(0) { silent += it }
        clock.place(0, 0, true)
        clock.soundFrom(16_000)
        clock.placeSound(10_000)
        clock.place(2_000_000, 2_000_000, false)
        assertEquals(10_000 + 14 * frame16k, silent.last())
        clock.finish()
        // through the last picture: the frame that holds it
        assertEquals(10_000 + 31 * frame16k, silent.last())
    }

    @Test
    fun silenceIsCountedNotAddedUpSoItKeepsToItsSamples() {
        val silent = ArrayList<Long>()
        val clock = RecordingClock(0) { silent += it }
        clock.place(0, 0, true)
        clock.soundFrom(44_100) // 23 219.95 µs a frame: no whole number of µs
        for (k in 1..26) clock.place(k * 1_000_000L, k * 1_000_000L, false)
        assertEquals(999 * 1024 * 1_000_000L / 44_100, silent[998])
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
        assertNull(clock.placeSound(10_000)) // from before the new run's picture
        assertEquals(5_040_000L, clock.placeSound(40_000))
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

    private fun record(
        file: File, append: Boolean, minFree: Long = 0, shape: () -> Shape? = { null },
        frames: StreamRecorder.() -> Unit
    ): String? {
        val done = CountDownLatch(1)
        var ended: String? = null
        val recorder = StreamRecorder(file, append, 0, { _, why -> ended = why; done.countDown() },
            shape = shape, clockUs = { now }, minFreeBytes = minFree)
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
        // begins with the first picture, led in by silence to the real sound,
        // and runs a frame apart throughout
        val sounds = pes(packets, TsMuxer.SOUND_PID).map(::pts)
        assertEquals(pts(pes(packets).first()), sounds.first())
        assertTrue(sounds.zipWithNext().all { (a, b) -> b - a in FRAME_TICKS })
    }

    private val aac = adts(byteArrayOf(0x11, 0x88.toByte()), ByteArray(100))

    /** A steady 30 fps stream from frame [from] to [to], a keyframe a second, its sound alongside while [sound]. */
    private fun StreamRecorder.run(
        from: Int, to: Int, sound: Boolean, clockUs: Long = 0, wallUs: Long = 0, form: ByteArray = aac
    ) {
        var j = (from * 33_333L + 21_332) / 21_333
        for (i in from until to) {
            now = wallUs + i * 33_333L
            frame(
                RtpCodec.H264, byteArrayOf(0, 0, 0, 1, if (i % 30 == 0) 0x65 else 0x41) + ByteArray(700),
                clockUs + i * 33_333L, i % 30 == 0, listOf(byteArrayOf(0x67, 1, 2), byteArrayOf(0x68, 3))
            )
            while (sound && j * 21_333L < (i + 1) * 33_333L) sound(form, clockUs + j++ * 21_333L)
        }
    }

    private fun soundTimes(file: File) = pes(packets(file.readBytes()), TsMuxer.SOUND_PID).map(::pts)

    // A 48 kHz frame is 1920 ticks of the 90 kHz clock, give or take the one
    // each time's own rounding down from µs makes — never adding up.
    private val FRAME_TICKS = 1919L..1921L

    @Test
    fun aKeyframeAheadOfTheSoundStillHasItInTheFirstTables() {
        val file = File(folder.root, "race.ts")
        record(file, append = false) {
            // the picture's keyframe reaches the recorder first, the sound
            // a third of a second behind it
            run(0, 10, sound = false, wallUs = 5_000_000)
            run(10, 60, sound = true, wallUs = 5_000_000)
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
        // never a step but one frame's, and on to the last picture as it closes
        assertTrue(times.zipWithNext().all { (a, b) -> b - a in FRAME_TICKS })
        val lastPicture = pes(packets(file.readBytes())).map(::pts).last()
        assertTrue(lastPicture - times.last() in 0..2 * 1920)
    }

    @Test
    fun soundThatComesBackIsLedInBySilenceWithoutAHole() {
        val file = File(folder.root, "unmuted.ts")
        record(file, append = false) {
            run(0, 30, sound = true)
            run(30, 90, sound = false)
            run(90, 150, sound = true)
        }
        // a frame apart throughout, where the sound returns too
        assertTrue(soundTimes(file).zipWithNext().all { (a, b) -> b - a in FRAME_TICKS })
    }

    @Test
    fun anAppendMadeInSilenceCarriesTheSoundOnWithoutAHole() {
        val file = File(folder.root, "silent-turn.ts")
        record(file, append = false) {
            run(0, 30, sound = true)
            run(30, 90, sound = false)
        }
        // the screen turned with the sound off; a second's pause, a new player clock
        record(file, append = true) { run(0, 60, sound = false, clockUs = 40_000_000, wallUs = 4_000_000) }
        val pictures = pes(packets(file.readBytes())).map(::pts)
        val joinGap = pictures.zipWithNext().maxOf { (a, b) -> b - a }
        val steps = soundTimes(file).zipWithNext().map { (a, b) -> b - a }
        // one frame apart, but across the pause the picture itself has: the
        // hole, from the old sound's end to the new's start, spans that pause
        // and at most a frame at either end of it
        assertEquals(1, steps.count { it !in FRAME_TICKS })
        assertTrue(steps.max() - 1920 <= joinGap + 2 * 1920)
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
    fun theLookAheadCountsFromTheKeyframeThatBringsTheTables() {
        val file = File(folder.root, "midgop.ts")
        record(file, append = false) {
            // begun mid-picture-group: the keyframe half a second in, the
            // sound two thirds of a second after it
            run(15, 50, sound = false, wallUs = 5_000_000)
            run(50, 90, sound = true, wallUs = 5_000_000)
        }
        val pmts = packets(file.readBytes()).filter { it.pid == TsMuxer.PMT_PID }
        assertEquals(listOf(0x1B to TsMuxer.VIDEO_PID, 0x0F to TsMuxer.SOUND_PID), streams(pmts.first()))
    }

    @Test
    fun soundTooLateForTheFirstTablesIsInLaterOnes() {
        val file = File(folder.root, "late.ts")
        record(file, append = false) {
            run(0, 45, sound = false, wallUs = 5_000_000)
            run(45, 90, sound = true, wallUs = 5_000_000)
        }
        val pmts = packets(file.readBytes()).filter { it.pid == TsMuxer.PMT_PID }
        assertEquals(listOf(0x1B to TsMuxer.VIDEO_PID), streams(pmts.first()))
        assertTrue(pmts.any { streams(it).contains(0x0F to TsMuxer.SOUND_PID) })
    }

    @Test
    fun soundOfAnotherFormIsLeftOutAndSilenceStandsIn() {
        val file = File(folder.root, "forms.ts")
        val stereo = adts(byteArrayOf(0x12, 0x10), ByteArray(100))
        record(file, append = false) {
            run(0, 30, sound = true)
            run(30, 90, sound = true, form = stereo)
        }
        val sounds = pes(packets(file.readBytes()), TsMuxer.SOUND_PID)
        // one form throughout: the file's first
        assertTrue(sounds.all { it.copyOfRange(14, 17).contentEquals(aac.copyOf(3)) })
        assertTrue(soundTimes(file).zipWithNext().all { (a, b) -> b - a in FRAME_TICKS })
    }

    @Test
    fun anAppendCountsOnFromTheFilesOwnPackets() {
        val file = File(folder.root, "counted.ts")
        record(file, append = false) { run(0, 40, sound = true) }
        record(file, append = true) { run(0, 40, sound = true, clockUs = 30_000_000, wallUs = 3_000_000) }
        val packets = packets(file.readBytes())
        for (pid in listOf(TsMuxer.VIDEO_PID, TsMuxer.SOUND_PID)) {
            packets.filter { it.pid == pid }.zipWithNext().forEach { (a, b) ->
                assertEquals("pid $pid", (a.counter + 1) and 0x0F, b.counter)
            }
        }
    }

    @Test
    fun aRecordingWithoutSoundHasNoSoundStream() {
        val file = File(folder.root, "quiet.ts")
        record(file, append = false) { run(0, 90, sound = false) }
        val packets = packets(file.readBytes())
        assertTrue(packets.none { it.pid == TsMuxer.SOUND_PID })
        assertEquals(listOf(0x1B to TsMuxer.VIDEO_PID), streams(packets.first { it.pid == TsMuxer.PMT_PID }))
    }

    // what a 640x480 receiver's frames come with: x264's baseline SPS
    private val receiverSps = "6742c01ed900a03db011000003000100000300320f162e48"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** The display shape every SPS in the file's picture says, in order. */
    private fun shapesIn(file: File): List<Float> = pes(packets(file.readBytes())).flatMap { unit ->
        val units = nalUnits(RtpCodec.H264, unit)
        units.withIndex().filter { it.value.second == 7 }.map { (k, u) ->
            val end = units.getOrNull(k + 1)?.first ?: unit.size
            val nal = unit.copyOfRange(u.first, end).dropWhile { it.toInt() != 1 }.drop(1).toByteArray()
            val sps = androidx.media3.container.NalUnitUtil.parseSpsNalUnit(nal, 0, nal.size)
            sps.width * sps.pixelWidthHeightRatio / sps.height
        }
    }

    @Test
    fun theShapeOnScreenGoesIntoEverySpsFromTheNextKeyframe() {
        val file = File(folder.root, "shaped.ts")
        var shape: Shape? = Shape(16, 9)
        record(file, append = false, shape = { shape }) {
            for (i in 0 until 90) {
                now = i * 33_333L
                val key = i % 30 == 0
                // the encoder's way, the SPS beside the frames, and the
                // stream's own, in the keyframe itself
                val inline = if (key && i == 30) byteArrayOf(0, 0, 0, 1) + receiverSps else ByteArray(0)
                frame(
                    RtpCodec.H264, inline + byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41) + ByteArray(700),
                    i * 33_333L, key, listOf(receiverSps, byteArrayOf(0x68, 3))
                )
                // turned to 4:3 between the second keyframe and the third
                if (i == 50) shape = Shape(4, 3)
            }
        }
        val said = shapesIn(file)
        assertEquals(3, said.size)
        assertEquals(16 / 9f, said[0], 0.002f)
        assertEquals(16 / 9f, said[1], 0.002f)
        assertEquals(4 / 3f, said[2], 0.002f)
    }

    @Test
    fun withNoShapeChosenTheSpsGoesInAsItCame() {
        val file = File(folder.root, "own.ts")
        record(file, append = false) {
            now = 0
            frame(RtpCodec.H264, byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(700), 0, true, listOf(receiverSps))
        }
        val video = pes(packets(file.readBytes())).first()
        assertTrue(video.toList().windowed(receiverSps.size).any { it == receiverSps.toList() })
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
