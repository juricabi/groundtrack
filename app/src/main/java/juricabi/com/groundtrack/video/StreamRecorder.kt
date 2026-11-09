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
 * each run before it is written. Once a file has sound it keeps it to the
 * end: a stretch the sound leaves empty is filled with silence ([silence]).
 */
internal class RecordingClock(private val startedAtUs: Long) {
    private var lastStreamUs: Long? = null
    private var offsetUs = 0L
    private var lastUs = -FRAME_US // nothing recorded: the first pin is free
    private var lastSoundUs = Long.MIN_VALUE
    private var needKey = true
    private var rebase = true

    private var discontinuity = true

    /** A file being appended to already runs to [us]; nothing goes before it. */
    fun notBefore(us: Long) {
        lastUs = maxOf(lastUs, us)
        lastSoundUs = maxOf(lastSoundUs, us)
    }

    /**
     * A sound frame's time from the recording's start in µs, or null to skip
     * it: before the picture has pinned the run it belongs to, from a stretch
     * of the clock the picture is not on (an old run's last frames, queued
     * behind the new one's keyframe), or not after the sound already written
     * — which, in a file just begun, is its first picture.
     */
    fun placeSound(streamUs: Long): Long? {
        val last = lastStreamUs ?: return null
        if (rebase || kotlin.math.abs(streamUs - last) > MAX_STEP_US) return null
        val t = streamUs + offsetUs
        if (t <= lastSoundUs) return null
        lastSoundUs = t
        return t
    }

    /**
     * The time for a frame of silence [frameUs] long, or null when none is
     * due: the sound has fallen more than a second behind the picture at
     * [pictureUs] — turned off, lost, or not yet back after a rebuilt screen.
     * ExoPlayer holds a file at the last frame of any stream it was told of,
     * so a sound that stopped would freeze the picture there too.
     */
    fun silence(pictureUs: Long, frameUs: Long): Long? {
        val next = lastSoundUs + frameUs
        if (lastSoundUs == Long.MIN_VALUE || next > pictureUs - SILENCE_LAG_US) return null
        lastSoundUs = next
        return next
    }

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
        if (rebase) {
            rebase = false
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
        // a new file's sound begins with its picture, not before it
        if (lastSoundUs == Long.MIN_VALUE) lastSoundUs = t - 1
        return t
    }

    /** True once, for the first frame placed after a jump in time. */
    fun tookDiscontinuity(): Boolean = discontinuity.also { discontinuity = false }

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
    startedAtUs: Long,
    private val onClosed: (StreamRecorder, endedBy: String?) -> Unit,
    private val clockUs: () -> Long = { SystemClock.elapsedRealtimeNanos() / 1000 },
    private val minFreeBytes: Long = 200L shl 20
) : RecordSink {

    private open class Item(val data: ByteArray)

    private class Frame(
        val codec: RtpCodec, data: ByteArray, val streamUs: Long, val key: Boolean,
        val parameterSets: List<ByteArray>, val arrivalUs: Long,
        /** Frames were dropped just before this one; the timeline resumes at a keyframe. */
        val afterGap: Boolean
    ) : Item(data)

    private class Sound(data: ByteArray, val streamUs: Long) : Item(data)

    private val queue = LinkedBlockingQueue<Any>()
    private val queuedBytes = AtomicLong()
    @Volatile private var open = true
    @Volatile private var dropped = false
    @Volatile private var endedBy: String? = null
    private val clock = RecordingClock(startedAtUs)
    // the writer's own: the form of the file's sound, once it has any
    private var soundConfig: ByteArray? = null

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
        queue.offer(Frame(codec, annexB, streamUs, key, parameterSets, clockUs(), afterGap))
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
                if (append) carryOn()
                BufferedOutputStream(FileOutputStream(file, append), 1 shl 18).use { out ->
                    val muxer = TsMuxer(out)
                    // a file appended to that has sound keeps it from the start
                    if (soundConfig != null) muxer.withSound()
                    fun declare(sound: Sound) {
                        if (soundConfig != null) return
                        soundConfig = configOf(sound.data)
                        muxer.withSound()
                    }
                    fun take(item: Item) {
                        when (item) {
                            is Frame -> {
                                if (item.afterGap) {
                                    clock.lost()
                                    DebugLog.note("Video", "record fell behind, frames dropped")
                                }
                                val t = clock.place(item.streamUs, item.arrivalUs, item.key) ?: return
                                muxer.frame(
                                    item.codec, item.data, t * 9 / 100, item.key,
                                    item.parameterSets, clock.tookDiscontinuity()
                                )
                                val config = soundConfig ?: return
                                while (true) {
                                    val at = clock.silence(t, frameUs(config)) ?: break
                                    muxer.sound(silence(config), at * 9 / 100)
                                }
                            }
                            is Sound -> {
                                declare(item)
                                clock.placeSound(item.streamUs)?.let { muxer.sound(item.data, it * 9 / 100) }
                            }
                        }
                    }
                    // The picture's first keyframe brings the file's first
                    // tables, and those are the only ones some players read:
                    // ExoPlayer takes a file's first stream list as final. So
                    // until the first sound or a second has come, the writer
                    // looks ahead before writing, and sound seen by then is in
                    // them — the keyframe often reaches here first.
                    var held: ArrayList<Item>? = if (soundConfig == null) ArrayList() else null
                    var heldSince = 0L
                    var lastFlush = clockUs()
                    // due at once: a recording must not start onto a full card
                    var lastSpaceCheck = lastFlush - 10_000_000
                    while (true) {
                        val next = queue.poll(1, TimeUnit.SECONDS)
                        if (next is Item) queuedBytes.addAndGet(-next.data.size.toLong())
                        val looking = held
                        if (looking == null) {
                            if (next is Item) take(next)
                        } else {
                            if (next is Item) {
                                if (looking.isEmpty()) heldSince = clockUs()
                                looking += next
                            }
                            if (next is Sound || next === CLOSE ||
                                looking.isNotEmpty() && clockUs() - heldSince >= LOOK_AHEAD_US
                            ) {
                                held = null
                                // declared ahead of the keyframe it may follow
                                (next as? Sound)?.let(::declare)
                                looking.forEach(::take)
                            }
                        }
                        if (next === CLOSE) break
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

    /**
     * Before appending: a kill can leave half a packet at the end, and every
     * packet written after it would be misaligned — so the file is cut back
     * to whole packets, the clock told where the file's time stands, and the
     * recorder whether the file has sound, which it then keeps.
     */
    private fun carryOn() {
        if (!file.isFile) return
        java.io.RandomAccessFile(file, "rw").use { f ->
            val whole = f.length() - f.length() % TsMuxer.PACKET
            if (whole != f.length()) f.setLength(whole)
            // enough tail to hold the start of a picture even at a goggle's
            // 20 Mbit/s, where one frame alone can outrun a short read
            val tail = ByteArray(minOf(whole, 8192L * TsMuxer.PACKET).toInt())
            f.seek(whole - tail.size)
            f.readFully(tail)
            lastTime90k(tail)?.let { clock.notBefore(it * 100 / 9) }
            soundConfig = lastSoundHeader(tail)?.let(::configOf)
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
