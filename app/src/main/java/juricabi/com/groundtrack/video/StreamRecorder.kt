package juricabi.com.groundtrack.video

import android.os.SystemClock
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import juricabi.com.groundtrack.utils.DebugLog

/**
 * Where a source hands its compressed picture, and its sound, while a
 * recording runs. Called from whatever thread the source lives on; it must
 * never block it.
 */
interface RecordSink {
    /**
     * One access unit, Annex B, stamped on the source's own clock in µs — any
     * clock, as long as it runs forward within one run of the stream.
     * [parameterSets] matter only for a keyframe.
     */
    fun frame(codec: RtpCodec, annexB: ByteArray, streamUs: Long, key: Boolean, parameterSets: List<ByteArray>)

    /**
     * One AAC frame of the picture's sound, ADTS-framed, stamped on the same
     * clock as the picture's frames — the two are kept together by that alone.
     */
    fun sound(adts: ByteArray, streamUs: Long)

    /** The source cannot feed the recording after all; the recording ends, saying why. */
    fun trouble(why: String)
}

/**
 * Places each frame on the recording's own timeline. Within one run of a
 * stream the source's clock keeps the frames' exact spacing; a run begins
 * wherever that clock jumps — a rebuilt player starts again from zero, an RTP
 * clock wraps, a stream came back after a stall, the screen turned — and the
 * new run is pinned to the wall clock, so the gap in the file is as long as
 * the gap really was. Each run starts at a keyframe: what came before one is
 * built on pictures the file never saw.
 *
 * The sound rides the picture's runs: it is stamped on the same source clock,
 * so the picture's offset places it too, and it waits for the picture to pin
 * each run before it is written. Once a file has sound, the sound has no hole
 * wherever the picture runs: [silent] is handed the time of each frame of
 * silence that fills one. ExoPlayer holds a file at the last frame of any
 * stream it was told of, and where the sound jumps more than a fifth of a
 * second it jumps its clock with it, skipping the picture the jump spans.
 * A hole where the picture has one too skips nothing.
 */
internal class RecordingClock(private val startedAtUs: Long, private val silent: (Long) -> Unit = {}) {
    private var lastStreamUs: Long? = null
    private var offsetUs = 0L
    private var lastUs = -FRAME_US // nothing recorded: the first pin is free
    private var needKey = true
    private var rebase = true

    private var discontinuity = true

    // the sound: where its last frame began, and its rate — 0 while the
    // file has none. Silence is laid on a grid from the last frame written,
    // its slots counted in frames from there, not added up a rounded frame at
    // a time, so it keeps to its samples. A run's grid starts at its first
    // picture, slot 0 the picture's own ([leadsIn] until sound is placed).
    private var lastSoundUs = Long.MIN_VALUE
    private var soundRate = 0
    private var silenceFromUs = 0L
    private var silentFrames = 0L // slots filled after silenceFromUs's frame; -1 from a picture
    private var leadsIn = false
    // how far the sound now on its way sits from its own stamps: led in by
    // silence, it picks up on the silence's grid, under a frame from them
    private var soundShiftUs = 0L

    /** A file being appended to already runs to [us]; no picture goes before it. */
    fun notBefore(us: Long) {
        lastUs = maxOf(lastUs, us)
    }

    /**
     * The file has sound from here on, in frames of 1024 samples at [rate],
     * continuing from [fromUs] — where the sound of a file appended to left
     * off — or else from the picture's present, so a sound that joins late
     * owes no silence for the time before it.
     */
    fun soundFrom(rate: Int, fromUs: Long? = null) {
        soundRate = rate
        soundAt(fromUs ?: lastUs)
    }

    /**
     * A sound frame's time from the recording's start in µs, or null to skip
     * it: before the picture has pinned the run it belongs to, from a stretch
     * of the clock the picture is not on (an old run's last frames, queued
     * behind the new one's keyframe), or not after the sound already written
     * — which, at the start of a run, is its first picture. Coming back after
     * a stretch without it, the sound is led in by silence and picks up where
     * the silence ends, under a frame from its own time; the rest of its
     * stretch keeps to that. ExoPlayer counts samples: a hole too short for
     * it to jump its clock is one it carries, return after return, until they
     * add up to one it does jump, skipping the picture under it.
     */
    fun placeSound(streamUs: Long): Long? {
        val last = lastStreamUs ?: return null
        if (rebase || kotlin.math.abs(streamUs - last) > MAX_STEP_US) return null
        var t = streamUs + offsetUs + soundShiftUs
        if (t <= lastSoundUs) return null
        fillTo(t)
        if (silentFrames > 0 || leadsIn) {
            val end = silenceFromUs + (silentFrames + 1) * 1024 * 1_000_000L / soundRate
            soundShiftUs += end - t
            t = end
        }
        soundAt(t)
        return t
    }

    /** The recording is closing: its sound is carried through its last picture. */
    fun finish() = fillThrough(lastUs)

    /** Frames went missing on the way here; carry on from the next keyframe. */
    fun lost() {
        needKey = true
    }

    /** The frame's time from the recording's start in µs, or null to skip it. */
    fun place(streamUs: Long, arrivalUs: Long, key: Boolean): Long? {
        val last = lastStreamUs
        lastStreamUs = streamUs
        if (last == null || streamUs < last || streamUs - last > MAX_STEP_US) {
            rebase = true
            needKey = true
        }
        if (needKey) {
            if (!key) return null
            needKey = false
        }
        val pinned = rebase
        if (rebase) {
            rebase = false
            // the run that ended keeps its sound through its last picture
            fillThrough(lastUs)
            // Pinned to the wall clock, but never at or before what is
            // already recorded — a stream clock that ran ahead of the wall's,
            // or a file appended to that ends later than the wall says. The
            // whole run moves, so its own spacing stays exact, and starts a
            // frame's length on, not crowded into the last frame's slot.
            offsetUs = maxOf(arrivalUs - startedAtUs, lastUs + FRAME_US) - streamUs
            discontinuity = true
        }
        // never backwards within a run either
        val t = maxOf(streamUs + offsetUs, lastUs + 1_000)
        lastUs = t
        // A run's sound begins with its picture: the gap before it had none,
        // and sound from before it is refused. Its silence, and the first
        // sound it leads in, start at the picture itself.
        if (pinned && t - 1 > lastSoundUs) {
            soundAt(t - 1)
            soundShiftUs = 0
            if (soundRate > 0) {
                silenceFromUs = t
                silentFrames = -1
                leadsIn = true
            }
        }
        // and where the sound has fallen a second behind the picture — turned
        // off, lost, not yet back — silence carries it, the second left free
        // for sound still on its way
        fillTo(t - SILENCE_LAG_US)
        return t
    }

    /** True once, for the first frame placed after a jump in time. */
    fun tookDiscontinuity(): Boolean = discontinuity.also { discontinuity = false }

    private fun soundAt(us: Long) {
        lastSoundUs = us
        silenceFromUs = us
        silentFrames = 0
        leadsIn = false
    }

    private fun frameUs() = 1024 * 1_000_000L / soundRate

    /** Silence for every frame that would end at or before [untilUs]. */
    private fun fillTo(untilUs: Long) {
        if (soundRate == 0) return
        while (silenceFromUs + (silentFrames + 2) * 1024 * 1_000_000L / soundRate <= untilUs) {
            silentFrames++
            lastSoundUs = silenceFromUs + silentFrames * 1024 * 1_000_000L / soundRate
            silent(lastSoundUs)
        }
    }

    /** Silence for every frame that would start at or before [us]: through the picture there. */
    private fun fillThrough(us: Long) {
        if (soundRate > 0) fillTo(us + frameUs())
    }

    private companion object {
        const val MAX_STEP_US = 2_000_000L
        const val FRAME_US = 40_000L
        const val SILENCE_LAG_US = 1_000_000L
    }
}

/**
 * One recording, written to [file] as MPEG-TS on a thread of its own, so a
 * slow card never reaches back into the network or the decoder: the frames
 * queue up to a bound, and past it they are dropped until the next keyframe.
 *
 * It lives as long as the screen it is on; the screen being rebuilt closes
 * it and a new one appends to the same file ([append]), [startedAtUs] — the
 * boot clock at the recording's start — carrying the timeline across.
 * [onClosed] runs on the writer thread once the file is shut, with the reason
 * when the recording ended by itself rather than being closed.
 */
class StreamRecorder(
    val file: File,
    private val append: Boolean,
    private val startedAtUs: Long,
    private val onClosed: (StreamRecorder, endedBy: String?) -> Unit,
    /** The shape the picture is watched in, asked as each keyframe arrives; null for its own. */
    private val shape: () -> Shape? = { null },
    private val clockUs: () -> Long = { SystemClock.elapsedRealtimeNanos() / 1000 },
    private val minFreeBytes: Long = 200L shl 20
) : RecordSink {

    private open class Item(val data: ByteArray)

    private class Frame(
        val codec: RtpCodec, data: ByteArray, val streamUs: Long, val key: Boolean,
        val parameterSets: List<ByteArray>, val arrivalUs: Long,
        /** Frames were dropped just before this one; the timeline resumes at a keyframe. */
        val afterGap: Boolean,
        /** A keyframe's shape, which every SPS up to the next keyframe takes; null on the frames between. */
        val shape: Shape?
    ) : Item(data)

    private class Sound(data: ByteArray, val streamUs: Long) : Item(data)

    private val queue = LinkedBlockingQueue<Any>()
    private val queuedBytes = AtomicLong()
    @Volatile private var open = true
    @Volatile private var dropped = false
    @Volatile private var endedBy: String? = null

    init {
        Thread({ write() }, "stream-record").start()
    }

    override fun frame(codec: RtpCodec, annexB: ByteArray, streamUs: Long, key: Boolean, parameterSets: List<ByteArray>) {
        if (!open) return
        if (queuedBytes.get() + annexB.size > MAX_QUEUED) {
            dropped = true
            return
        }
        // the gap is marked on the first frame after it, not on whatever the
        // writer happens to take next — that is from before the gap, and a
        // wait for a keyframe there threw good frames away and let the real
        // gap through unmended
        val afterGap = dropped
        dropped = false
        queuedBytes.addAndGet(annexB.size.toLong())
        queue.offer(Frame(codec, annexB, streamUs, key, parameterSets, clockUs(), afterGap, if (key) shape() else null))
    }

    // past the bound a sound frame is simply lost: the picture's gap is what
    // the timeline mends, and the sound carries on from wherever it resumes
    override fun sound(adts: ByteArray, streamUs: Long) {
        if (!open || queuedBytes.get() + adts.size > MAX_QUEUED) return
        queuedBytes.addAndGet(adts.size.toLong())
        queue.offer(Sound(adts, streamUs))
    }

    override fun trouble(why: String) = end(why)

    /** Stop taking frames; what is queued is still written, then the file closes. */
    fun close() {
        open = false
        queue.offer(CLOSE)
    }

    private fun end(why: String) {
        if (endedBy == null) endedBy = why
        close()
    }

    private fun write() {
        // One writer at a time, process-wide: the recorder a rebuilt screen
        // opens appends to the file its predecessor may still be draining
        // into, and two streams interleaving in one file would ruin both.
        synchronized(ONE_WRITER) {
            try {
                file.parentFile?.mkdirs()
                val tail = if (append) carryOn() else null
                BufferedOutputStream(FileOutputStream(file, append), 1 shl 18).use { out ->
                    // counting on where the file's own packets left off: a
                    // demuxer reading across the join sees one stream, not a
                    // restart — media3 takes a counter that repeats for a copy
                    // and drops it, and the next picture's first packet went
                    val muxer = TsMuxer(out, tail?.videoCounter ?: 0, tail?.soundCounter ?: 0)
                    // the form of the file's sound once it has any, and its
                    // silence in that form
                    var config: ByteArray? = null
                    var silent = ByteArray(0)
                    val clock = RecordingClock(startedAtUs) { at -> muxer.sound(silent, at * 9 / 100) }
                    fun declare(form: ByteArray, fromUs: Long?) {
                        config = form
                        silent = silence(form)
                        muxer.withSound()
                        clock.soundFrom(sampleRate(form), fromUs)
                    }
                    tail?.endUs?.let(clock::notBefore)
                    // a file appended to that has sound keeps it, in its form
                    tail?.soundHeader?.let { declare(configOf(it), tail.soundEndUs) }
                    var mixed = false
                    // said once: the encoder of some phone writes an SPS
                    // past what the shape can read, and the file keeps the
                    // stream's own shape — the log is where that shows
                    var keptOwnShape = false
                    // the shape the run took at its keyframe: an SPS said
                    // again mid-run must say the same, or a player decoding
                    // it drops the picture until the next keyframe
                    var runShape: Shape? = null
                    val unread = {
                        if (!keptOwnShape) DebugLog.note("Video", "record keeps the stream's own shape: its SPS could not be read through")
                        keptOwnShape = true
                    }
                    fun take(item: Item) {
                        when (item) {
                            is Frame -> {
                                if (item.afterGap) {
                                    clock.lost()
                                    DebugLog.note("Video", "record fell behind, frames dropped")
                                }
                                val t = clock.place(item.streamUs, item.arrivalUs, item.key) ?: return
                                // the shape it was watched in goes in with its
                                // SPS, in the frame or beside a keyframe — the
                                // sets read as runs, for the encoder hands its
                                // SPS and PPS over as one
                                if (item.key) runShape = item.shape
                                val s = runShape
                                val data = if (s == null) item.data else shaped(item.codec, item.data, s, unread)
                                val sets = if (s == null || !item.key) item.parameterSets
                                    else item.parameterSets.map { shaped(item.codec, annexB(it), s, unread) }
                                muxer.frame(item.codec, data, t * 9 / 100, item.key, sets, clock.tookDiscontinuity())
                            }
                            is Sound -> {
                                val form = configOf(item.data)
                                val declared = config ?: form.also { declare(it, null) }
                                // A file keeps one form of sound: players set
                                // themselves up from its first frame. Another
                                // source's, carried in by Settings changing it
                                // mid-recording, is left out; silence stands in.
                                if (!form.contentEquals(declared)) {
                                    if (!mixed) DebugLog.note("Video", "record left out sound of another form")
                                    mixed = true
                                    return
                                }
                                clock.placeSound(item.streamUs)?.let { muxer.sound(item.data, it * 9 / 100) }
                            }
                        }
                    }
                    // The picture's first keyframe brings a new file's first
                    // tables, and those are the only ones some players read:
                    // ExoPlayer takes a file's first stream list as final. So
                    // from that keyframe until the first sound, or a second
                    // of picture, the writer looks ahead before writing, and
                    // sound seen by then is in them — the keyframe often
                    // reaches here first. Anything before the keyframe writes
                    // nothing and goes straight through; a file appended to
                    // that has a picture already has its tables.
                    var lookAhead = tail?.endUs == null
                    var held: ArrayList<Item>? = null
                    var heldSince = 0L
                    var lastFlush = clockUs()
                    // due at once: a recording must not start onto a full card
                    var lastSpaceCheck = lastFlush - 10_000_000
                    while (true) {
                        val next = queue.poll(1, TimeUnit.SECONDS)
                        if (next is Item) queuedBytes.addAndGet(-next.data.size.toLong())
                        val looking = held
                        if (looking == null) {
                            if (lookAhead && config == null && next is Frame && next.key) {
                                lookAhead = false
                                held = arrayListOf(next)
                                heldSince = next.arrivalUs
                            } else if (next is Item) {
                                take(next)
                            }
                        } else {
                            if (next is Item) looking += next
                            // timed by the picture's own arrivals, by the
                            // writer's clock only while the stream is silent
                            val seen = (next as? Frame)?.arrivalUs ?: clockUs()
                            if (next is Sound || next === CLOSE || seen - heldSince >= LOOK_AHEAD_US) {
                                held = null
                                // declared ahead of the keyframe it follows
                                if (next is Sound) declare(configOf(next.data), null)
                                looking.forEach(::take)
                            }
                        }
                        if (next === CLOSE) {
                            clock.finish()
                            break
                        }
                        val now = clockUs()
                        // written through each second, so a kill loses at most that
                        if (now - lastFlush >= 1_000_000) {
                            out.flush()
                            lastFlush = now
                        }
                        if (now - lastSpaceCheck >= 10_000_000) {
                            lastSpaceCheck = now
                            if ((file.parentFile?.usableSpace ?: Long.MAX_VALUE) < minFreeBytes) {
                                end("the storage is nearly full")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                end("it could not be written (${e.message})")
            }
            // A recording that never saw a keyframe is nothing to keep, nor to
            // show in the gallery as a video that will not play. Judged while
            // the file is still this writer's: a successor waiting to append
            // could otherwise open it in between, and write on into a file
            // already unlinked.
            if (file.isFile && file.length() == 0L) file.delete()
        }
        open = false
        queue.clear()
        DebugLog.note("Video", "record closed ${file.name}, ${file.length() shr 10} KiB" +
            (endedBy?.let { ", ended: $it" } ?: ""))
        onClosed(this, endedBy)
    }

    /** Where a file appended to left off: its last time, its sound's if it has any, and its packets' counts. */
    private class Tail(
        val endUs: Long?, val soundEndUs: Long?, val soundHeader: ByteArray?,
        val videoCounter: Int, val soundCounter: Int
    )

    /**
     * Before appending: a kill can leave half a packet at the end, and every
     * packet written after it would be misaligned — so the file is cut back
     * to whole packets, and read for where its time stands and whether it has
     * sound, which it then keeps.
     */
    private fun carryOn(): Tail? {
        if (!file.isFile) return null
        return java.io.RandomAccessFile(file, "rw").use { f ->
            val whole = f.length() - f.length() % TsMuxer.PACKET
            if (whole != f.length()) f.setLength(whole)
            // Enough tail to hold the start of a picture even at a goggle's
            // 20 Mbit/s, where one frame alone can outrun a short read — and
            // the sound, whose silence trails the picture by a second: a file
            // cut short by a kill has not had it carried to its end.
            val tail = ByteArray(minOf(whole, 16384L * TsMuxer.PACKET).toInt())
            f.seek(whole - tail.size)
            f.readFully(tail)
            Tail(
                lastTime90k(tail)?.let { it * 100 / 9 },
                lastTime90k(tail, TsMuxer.SOUND_PID)?.let { it * 100 / 9 },
                lastSoundHeader(tail),
                nextCounter(tail, TsMuxer.VIDEO_PID),
                nextCounter(tail, TsMuxer.SOUND_PID)
            )
        }
    }

    private companion object {
        val CLOSE = Any()
        val ONE_WRITER = Any()
        // about six seconds of a 20 Mbit/s goggle stream
        const val MAX_QUEUED = 16L shl 20
        const val LOOK_AHEAD_US = 1_000_000L
    }
}
