package juricabi.com.groundtrack.video

import java.io.OutputStream

/**
 * Just enough MPEG transport stream to carry one H.264 or H.265 picture — the
 * stream as it arrived, access unit by access unit, never re-encoded — and
 * the sound beside it, AAC in ADTS frames, when there is any.
 *
 * TS, not MP4, because a recording here ends however the flight ends. An MP4
 * is unreadable until its index is written at the very end, so a kill, a flat
 * battery or an install mid-flight lost the whole file; a transport stream is
 * playable up to its last whole packet. It also takes an append: the screen
 * rebuilds on every rotation and the stream restarts with it, and the file
 * just carries on, the gap marked as a discontinuity, rather than splitting.
 *
 * Every keyframe is preceded by the tables and by the parameter sets, so the
 * file can be opened, cut or resumed at any keyframe — and a goggle changing
 * resolution mid-flight lands its new SPS in-band where a player finds it.
 */
internal class TsMuxer(private val out: OutputStream) {

    private val packet = ByteArray(PACKET)
    private var codec: RtpCodec? = null
    private var pmtVersion = -1
    private var patCounter = 0
    private var pmtCounter = 0
    private var videoCounter = 0
    private var soundCounter = 0
    private var soundDeclared = false
    private var soundSent = false

    /**
     * One access unit, Annex B. [time90k] is its place on the recording's
     * 90 kHz clock; [parameterSets] are consulted only for a keyframe, and
     * written ahead of it when it does not carry its own. [discontinuity]
     * marks a jump in time — a resumed file, a stream that came back.
     */
    fun frame(
        codec: RtpCodec,
        annexB: ByteArray,
        time90k: Long,
        key: Boolean,
        parameterSets: List<ByteArray>,
        discontinuity: Boolean
    ) {
        if (codec != this.codec) {
            this.codec = codec
            pmtVersion = (pmtVersion + 1) and 0x1F
        }
        if (key) writeTables(codec)
        // The delimiter opens the access unit, the parameter sets follow it:
        // a unit already led by its own delimiter keeps it first, and a set
        // the source gave only out of band is slotted in right after.
        val units = nalUnits(codec, annexB)
        val ownAud = units.firstOrNull()?.second == audType(codec)
        val split = if (ownAud) units.getOrNull(1)?.first ?: annexB.size else 0
        val lead = if (ownAud) annexB.copyOfRange(0, split) else aud(codec)
        val sets = if (key && units.none { it.second == spsType(codec) })
            parameterSets.map(::annexB) else emptyList()
        val header = pesHeader(VIDEO_STREAM, (time90k + PTS_LEAD) and MASK_33, 0)
        val payload = ByteArray(
            header.size + lead.size + sets.sumOf { it.size } + annexB.size - split
        )
        var at = 0
        for (part in listOf(header, lead) + sets) {
            System.arraycopy(part, 0, payload, at, part.size)
            at += part.size
        }
        System.arraycopy(annexB, split, payload, at, annexB.size - split)
        writePes(VIDEO_PID, payload, time90k and MASK_33, key, discontinuity)
    }

    /**
     * The recording has sound from here on. The tables say so from now, and
     * go out again at once if a picture was already sent under tables that
     * did not; nothing is declared before it is seen, since a player stalls
     * waiting on a declared stream that never comes.
     */
    fun withSound() {
        if (soundDeclared) return
        soundDeclared = true
        pmtVersion = (pmtVersion + 1) and 0x1F
        codec?.let(::writeTables)
    }

    /** One ADTS frame of the sound, at [time90k] on the picture's clock. */
    fun sound(adts: ByteArray, time90k: Long) {
        withSound()
        val header = pesHeader(SOUND_STREAM, (time90k + PTS_LEAD) and MASK_33, 8 + adts.size)
        // the muxer's first, like its first tables: an appended file's sound
        // ran on counters this one starts over
        writePes(SOUND_PID, header + adts, null, false, discontinuity = !soundSent)
        soundSent = true
    }

    private fun writeTables(codec: RtpCodec) {
        writeSection(PAT_PID, pat())
        writeSection(PMT_PID, pmt(codec))
    }

    /**
     * One PES over as many packets as it takes, the last stuffed to a whole
     * packet. The first carries the flags, and the PCR when there is one —
     * only the picture's are, the sound keeping to its clock.
     */
    private fun writePes(pid: Int, payload: ByteArray, pcr: Long?, key: Boolean, discontinuity: Boolean) {
        var pos = 0
        var first = true
        while (pos < payload.size) {
            val flags = if (!first) 0 else
                (if (discontinuity) 0x80 else 0) or (if (key) 0x40 else 0) or (if (pcr != null) 0x10 else 0)
            // -1: no adaptation field; otherwise its length byte's value
            var adaptation = when {
                flags and 0x10 != 0 -> 7 // flags + PCR
                flags != 0 -> 1
                else -> -1
            }
            val room = PAYLOAD - (if (adaptation >= 0) adaptation + 1 else 0)
            val chunk = minOf(room, payload.size - pos)
            val stuffing = room - chunk
            if (stuffing > 0) adaptation = if (adaptation < 0) stuffing - 1 else adaptation + stuffing
            header(pid, first, adaptation >= 0, nextCounter(pid))
            var i = 4
            if (adaptation >= 0) {
                packet[i++] = adaptation.toByte()
                if (adaptation > 0) {
                    val end = i + adaptation
                    packet[i++] = flags.toByte()
                    if (pcr != null && first) {
                        packet[i++] = (pcr shr 25).toByte()
                        packet[i++] = (pcr shr 17).toByte()
                        packet[i++] = (pcr shr 9).toByte()
                        packet[i++] = (pcr shr 1).toByte()
                        packet[i++] = (((pcr and 1) shl 7) or 0x7E).toByte()
                        packet[i++] = 0
                    }
                    while (i < end) packet[i++] = 0xFF.toByte()
                }
            }
            System.arraycopy(payload, pos, packet, i, chunk)
            out.write(packet)
            pos += chunk
            first = false
        }
    }

    private fun nextCounter(pid: Int): Int =
        if (pid == VIDEO_PID) videoCounter.also { videoCounter = (it + 1) and 0x0F }
        else soundCounter.also { soundCounter = (it + 1) and 0x0F }

    private fun writeSection(pid: Int, section: ByteArray) {
        val counter = if (pid == PAT_PID) patCounter++ else pmtCounter++
        // a muxer's first tables follow whatever an appended file already
        // holds, their counters starting over — flagged, so that is no error
        val first = counter == 0
        header(pid, true, first, counter and 0x0F)
        var i = 4
        if (first) {
            packet[i++] = 1
            packet[i++] = 0x80.toByte()
        }
        packet[i++] = 0 // pointer field
        System.arraycopy(section, 0, packet, i, section.size)
        java.util.Arrays.fill(packet, i + section.size, PACKET, 0xFF.toByte())
        out.write(packet)
    }

    private fun header(pid: Int, start: Boolean, adaptation: Boolean, counter: Int) {
        packet[0] = 0x47
        packet[1] = ((if (start) 0x40 else 0) or (pid shr 8 and 0x1F)).toByte()
        packet[2] = pid.toByte()
        packet[3] = ((if (adaptation) 0x30 else 0x10) or counter).toByte()
    }

    private fun pat(): ByteArray = section(
        0x00, 0x0001, 0,
        byteArrayOf(0x00, 0x01, (0xE0 or (PMT_PID shr 8)).toByte(), PMT_PID.toByte())
    )

    private fun pmt(codec: RtpCodec): ByteArray = section(
        0x02, 0x0001, pmtVersion,
        byteArrayOf(
            (0xE0 or (VIDEO_PID shr 8)).toByte(), VIDEO_PID.toByte(), // PCR rides the video
            0xF0.toByte(), 0x00
        ) + stream(if (codec == RtpCodec.H264) 0x1B else 0x24, VIDEO_PID) +
            (if (soundDeclared) stream(0x0F, SOUND_PID) else ByteArray(0)) // 0x0F: AAC in ADTS
    )

    private fun stream(type: Int, pid: Int) = byteArrayOf(
        type.toByte(), (0xE0 or (pid shr 8)).toByte(), pid.toByte(), 0xF0.toByte(), 0x00
    )

    private fun section(table: Int, id: Int, version: Int, body: ByteArray): ByteArray {
        val length = 5 + body.size + 4
        val s = ByteArray(3 + length)
        s[0] = table.toByte()
        s[1] = (0xB0 or (length shr 8)).toByte()
        s[2] = length.toByte()
        s[3] = (id shr 8).toByte()
        s[4] = id.toByte()
        s[5] = (0xC1 or (version shl 1)).toByte()
        s[6] = 0
        s[7] = 0
        System.arraycopy(body, 0, s, 8, body.size)
        val crc = crc32(s, s.size - 4)
        for (k in 0 until 4) s[s.size - 4 + k] = (crc ushr (24 - 8 * k)).toByte()
        return s
    }

    /** [length] is what follows its own field: 0 says unbounded, which only video may be. */
    private fun pesHeader(stream: Int, pts: Long, length: Int) = byteArrayOf(
        0, 0, 1, stream.toByte(),
        (length shr 8).toByte(), length.toByte(),
        0x80.toByte(), 0x80.toByte(), 5, // PTS only: these streams carry no B-frames
        (0x21 or ((pts shr 29).toInt() and 0x0E)).toByte(),
        (pts shr 22).toByte(),
        ((pts shr 14).toInt() or 1).toByte(),
        (pts shr 7).toByte(),
        ((pts shl 1).toInt() or 1).toByte()
    )

    companion object {
        const val PACKET = 188
        private const val PAYLOAD = PACKET - 4
        const val PAT_PID = 0x0000
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x0100
        const val SOUND_PID = 0x0101
        private const val VIDEO_STREAM = 0xE0
        private const val SOUND_STREAM = 0xC0
        private const val MASK_33 = (1L shl 33) - 1
        // the PTS runs a tenth of a second ahead of the PCR: the decoder's
        // buffer is given that long to hold a frame before it is due
        const val PTS_LEAD = 9000L

        fun crc32(data: ByteArray, length: Int): Int {
            var crc = -1
            for (i in 0 until length) {
                crc = crc xor ((data[i].toInt() and 0xFF) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }
    }
}

private fun audType(codec: RtpCodec) = if (codec == RtpCodec.H264) 9 else 35
private fun spsType(codec: RtpCodec) = if (codec == RtpCodec.H264) 7 else 33

private fun aud(codec: RtpCodec) = when (codec) {
    RtpCodec.H264 -> byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
    RtpCodec.H265 -> byteArrayOf(0, 0, 0, 1, 0x46, 0x01, 0x50)
}

/** The types of the NAL units in an Annex B run, in order. */
internal fun nalTypes(codec: RtpCodec, annexB: ByteArray): List<Int> =
    nalUnits(codec, annexB).map { it.second }

/** Each NAL unit of an Annex B run: where its start code begins, and its type. */
private fun nalUnits(codec: RtpCodec, annexB: ByteArray): List<Pair<Int, Int>> {
    val units = ArrayList<Pair<Int, Int>>(4)
    var i = 0
    while (i + 3 < annexB.size) {
        if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
            val header = annexB[i + 3].toInt()
            val start = if (i > 0 && annexB[i - 1].toInt() == 0) i - 1 else i
            units += start to if (codec == RtpCodec.H264) header and 0x1F else (header shr 1) and 0x3F
            i += 4
        } else {
            i++
        }
    }
    return units
}

/**
 * Where a transport stream already on disk left off, on its 90 kHz clock: the
 * latest picture or sound in its tail — or of [pid] alone — or null when it
 * holds none. A recording that appends carries on from here, so time never
 * steps back across the join, whatever clock placed the frames before; and
 * its sound carries on from where the file's own sound stopped.
 */
internal fun lastTime90k(tail: ByteArray, pid: Int? = null): Long? = pesStarts(tail)
    .filter { (pid ?: it.pid) == it.pid && (it.pid == TsMuxer.VIDEO_PID || it.pid == TsMuxer.SOUND_PID) }
    .filter { it.pes + 14 <= it.end }
    .filter { tail[it.pes + 7].toInt() and 0x80 != 0 }
    .maxOfOrNull {
        val b = { k: Int -> tail[it.pes + 9 + k].toLong() and 0xFF }
        val pts = ((b(0) shr 1 and 0x07) shl 30) or (b(1) shl 22) or ((b(2) shr 1) shl 15) or
            (b(3) shl 7) or (b(4) shr 1)
        pts - TsMuxer.PTS_LEAD
    }

/**
 * The ADTS header of the last sound frame in a stream's tail, or null when
 * it holds no sound: a file that has sound keeps it across an append, in the
 * same form.
 */
internal fun lastSoundHeader(tail: ByteArray): ByteArray? = pesStarts(tail)
    .filter { it.pid == TsMuxer.SOUND_PID && it.pes + 9 <= it.end }
    .firstNotNullOfOrNull {
        val adts = it.pes + 9 + (tail[it.pes + 8].toInt() and 0xFF)
        if (adts + 7 <= it.end && tail[adts].toInt() and 0xFF == 0xFF) tail.copyOfRange(adts, adts + 7) else null
    }

/** Where a PES begins in a stream's tail: its PID, its header, and the end of the packet holding it. */
private class PesStart(val pid: Int, val pes: Int, val end: Int)

/** Every PES start in a stream's tail, the last first. */
private fun pesStarts(tail: ByteArray): Sequence<PesStart> = sequence {
    var at = tail.size - tail.size % TsMuxer.PACKET
    while (at >= TsMuxer.PACKET) {
        at -= TsMuxer.PACKET
        val p = at
        if (tail[p].toInt() != 0x47 || tail[p + 1].toInt() and 0x40 == 0) continue
        var pes = p + 4
        if (tail[p + 3].toInt() and 0x20 != 0) pes += 1 + (tail[p + 4].toInt() and 0xFF)
        yield(PesStart((tail[p + 1].toInt() and 0x1F shl 8) or (tail[p + 2].toInt() and 0xFF), pes, p + TsMuxer.PACKET))
    }
}

/**
 * A NAL unit with a start code in front — the sources disagree on whether
 * their parameter sets carry one (media3's and the encoder's do, the RTP
 * depacketizer's do not), so the muxer takes either.
 */
internal fun annexB(nal: ByteArray): ByteArray {
    val prefixed = nal.size >= 3 && nal[0].toInt() == 0 && nal[1].toInt() == 0 &&
        (nal[2].toInt() == 1 || nal.size >= 4 && nal[2].toInt() == 0 && nal[3].toInt() == 1)
    return if (prefixed) nal else byteArrayOf(0, 0, 0, 1) + nal
}
