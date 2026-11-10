package juricabi.com.groundtrack.video

/**
 * A shape a picture is shown in, width to height — 16:9, 4:3 — for a camera
 * whose frames reach the phone in another: a 16:9 camera behind an analog
 * receiver arrives in 4:3 frames, squeezed thin. On screen the surface is
 * sized to it ([pictureBox]); in a recording it is written into the stream
 * itself ([shaped]), so the file plays back in the shape it was watched in.
 */
class Shape(val width: Int, val height: Int)

/**
 * [annexB] with every SPS in it saying [shape]. Nothing else in the run is
 * touched, and a run without one is handed back as it is. [unread] hears of
 * an SPS left as it came, as [shapedSps] leaves one.
 */
internal fun shaped(codec: RtpCodec, annexB: ByteArray, shape: Shape, unread: () -> Unit = {}): ByteArray {
    val units = nalUnits(codec, annexB)
    if (units.none { it.second == spsType(codec) }) return annexB
    val out = java.io.ByteArrayOutputStream(annexB.size + 8)
    for ((k, unit) in units.withIndex()) {
        val end = units.getOrNull(k + 1)?.first ?: annexB.size
        val nal = annexB.copyOfRange(unit.first, end)
        out.write(if (unit.second == spsType(codec)) shapedSps(codec, nal, shape, unread) else nal)
    }
    return out.toByteArray()
}

/**
 * One SPS, with or without its start code, saying [shape] — through its
 * sample aspect ratio, the one field every player reads for the shape of a
 * picture: VLC, mpv, ffmpeg, ExoPlayer and with it most gallery apps. The
 * pixels stay as they came; only their shape is said. The ratio is worked
 * out from the SPS's own picture size, so it holds whatever the source
 * sends, and it goes in as an extended ratio of its own, in a VUI added
 * where the SPS had none — everything else the SPS says is copied bit for
 * bit.
 *
 * Changed only when every bit of it is accounted for, read through to its
 * stop bit: an SPS misread would ruin the recording without a word, where
 * one left as it came only keeps its own shape. One this cannot read
 * through — an extension it does not know — is left so, and [unread] told.
 */
internal fun shapedSps(codec: RtpCodec, nal: ByteArray, shape: Shape, unread: () -> Unit = {}): ByteArray {
    val header = headerEnd(codec, nal)
    if (nal.size <= header) return nal.also { unread() }
    return try {
        val rbsp = unescape(nal, header)
        val stop = stopBit(rbsp)
        val read = Bits(rbsp)
        val sps = if (codec == RtpCodec.H264) readH264(read) else readH265(read)
        val vuiAt = read.at
        val hadVui = read.bit() == 1
        if (hadVui) {
            // the ratio it said, if any, gives way; the rest of it stands
            if (read.bit() == 1 && read.bits(8) == EXTENDED_SAR) read.bits(32)
        }
        val restAt = read.at
        if (hadVui) {
            if (codec == RtpCodec.H264) restOfH264Vui(read) else restOfH265Vui(read, sps.subLayers)
        }
        if (codec == RtpCodec.H265 && !h265Extensions(read) || read.at != stop) return nal.also { unread() }

        // the ratio of the frame's own pixels that shows it in the shape
        var sarWidth = shape.width.toLong() * sps.height
        var sarHeight = shape.height.toLong() * sps.width
        val common = gcd(sarWidth, sarHeight)
        sarWidth /= common
        sarHeight /= common
        while (sarWidth > 0xFFFF || sarHeight > 0xFFFF) {
            sarWidth = (sarWidth + 1) / 2
            sarHeight = (sarHeight + 1) / 2
        }
        val write = BitWriter()
        write.copy(rbsp, 0, vuiAt)
        write.bits(1, 1)
        write.bits(1, 1)
        write.bits(EXTENDED_SAR, 8)
        write.bits(sarWidth.toInt(), 16)
        write.bits(sarHeight.toInt(), 16)
        // a VUI that says nothing else: every other presence flag clear
        if (!hadVui) write.bits(0, if (codec == RtpCodec.H264) 8 else 9)
        write.copy(rbsp, restAt, stop)
        write.bits(1, 1)
        write.align()
        nal.copyOfRange(0, header) + escape(write.bytes())
    } catch (e: IndexOutOfBoundsException) {
        nal.also { unread() }
    }
}

/**
 * The width of a pixel to its height, as [nal], an SPS, says it — 1 where
 * it says nothing, or cannot be read. Most streams' pixels are square; an
 * IP camera's 1440x1080 that is really 16:9, or a digitised analog picture
 * at 720x576, says otherwise, and a player shows its recording so — the
 * screen, showing a picture in its own shape, does too.
 */
internal fun pixelRatioOf(codec: RtpCodec, nal: ByteArray): Float {
    val header = headerEnd(codec, nal)
    if (nal.size <= header) return 1f
    return try {
        val read = Bits(unescape(nal, header))
        if (codec == RtpCodec.H264) readH264(read) else readH265(read)
        // no VUI, or no ratio in it
        if (read.bit() == 0 || read.bit() == 0) return 1f
        val idc = read.bits(8)
        val (width, height) = if (idc == EXTENDED_SAR) read.bits(16) to read.bits(16)
            else SAR_TABLE.getOrNull(idc - 1) ?: return 1f
        if (width == 0 || height == 0) 1f else width.toFloat() / height
    } catch (e: IndexOutOfBoundsException) {
        1f
    }
}

/** Where a NAL unit's payload begins: past its start code, if it has one, and its header. */
private fun headerEnd(codec: RtpCodec, nal: ByteArray): Int {
    val prefix = when {
        nal.size > 3 && nal[0].toInt() == 0 && nal[1].toInt() == 0 && nal[2].toInt() == 1 -> 3
        nal.size > 4 && nal[0].toInt() == 0 && nal[1].toInt() == 0 && nal[2].toInt() == 0 && nal[3].toInt() == 1 -> 4
        else -> 0
    }
    return prefix + if (codec == RtpCodec.H264) 1 else 2
}

/** What an SPS says that the shape needs: its picture's size, and its sub-layers past the first. */
private class Sps(val width: Int, val height: Int, val subLayers: Int = 0)

/** The VUI of an H.264 SPS after its aspect ratio, read through. */
private fun restOfH264Vui(r: Bits) {
    if (r.bit() == 1) r.bit() // overscan
    if (r.bit() == 1) {
        // video signal type, and its colour description
        r.bits(4)
        if (r.bit() == 1) r.bits(24)
    }
    if (r.bit() == 1) {
        r.ue()
        r.ue()
    }
    if (r.bit() == 1) {
        // timing
        r.bits(32)
        r.bits(32)
        r.bit()
    }
    val nal = r.bit() == 1
    if (nal) h264Hrd(r)
    val vcl = r.bit() == 1
    if (vcl) h264Hrd(r)
    if (nal || vcl) r.bit() // low_delay_hrd_flag
    r.bit() // pic_struct_present_flag
    if (r.bit() == 1) {
        // bitstream restriction
        r.bit()
        repeat(6) { r.ue() }
    }
}

private fun h264Hrd(r: Bits) {
    val count = r.ue() + 1
    r.bits(8) // bit rate and CPB size scales
    repeat(count) {
        r.ue()
        r.ue()
        r.bit()
    }
    r.bits(20) // the four delay and length fields
}

/** The VUI of an H.265 SPS after its aspect ratio, read through. */
private fun restOfH265Vui(r: Bits, subLayers: Int) {
    if (r.bit() == 1) r.bit() // overscan
    if (r.bit() == 1) {
        r.bits(4)
        if (r.bit() == 1) r.bits(24)
    }
    if (r.bit() == 1) {
        r.ue()
        r.ue()
    }
    r.bits(3) // neutral chroma, field sequence, frame field info
    if (r.bit() == 1) repeat(4) { r.ue() } // default display window
    if (r.bit() == 1) {
        // timing, and the HRD it may carry
        r.bits(32)
        r.bits(32)
        if (r.bit() == 1) r.ue()
        if (r.bit() == 1) h265Hrd(r, subLayers)
    }
    if (r.bit() == 1) {
        // bitstream restriction
        r.bits(3)
        repeat(5) { r.ue() }
    }
}

private fun h265Hrd(r: Bits, subLayers: Int) {
    val nal = r.bit() == 1
    val vcl = r.bit() == 1
    var subPicture = false
    if (nal || vcl) {
        subPicture = r.bit() == 1
        if (subPicture) r.bits(19)
        r.bits(8) // bit rate and CPB size scales
        if (subPicture) r.bits(4)
        r.bits(15)
    }
    for (i in 0..subLayers) {
        val fixedGeneral = r.bit() == 1
        val fixedWithin = fixedGeneral || r.bit() == 1
        var lowDelay = false
        if (fixedWithin) r.ue() else lowDelay = r.bit() == 1
        val count = if (lowDelay) 1 else r.ue() + 1
        repeat((if (nal) 1 else 0) + (if (vcl) 1 else 0)) {
            repeat(count) {
                r.ue()
                r.ue()
                if (subPicture) {
                    r.ue()
                    r.ue()
                }
                r.bit()
            }
        }
    }
}

/**
 * The extensions that may follow an H.265 SPS's VUI: none, or the range
 * extension's nine flags alone. Any other is not read, and the SPS is left
 * as it came.
 */
private fun h265Extensions(r: Bits): Boolean {
    if (r.bit() == 0) return true
    val range = r.bit() == 1
    if (r.bits(7) != 0) return false // multilayer, 3D, screen content, and later ones
    if (range) r.bits(9)
    return true
}

/** What an H.264 SPS says, read up to where its VUI flag stands. */
private fun readH264(r: Bits): Sps {
    val profile = r.bits(8)
    r.bits(16) // constraint flags, level
    r.ue() // seq_parameter_set_id
    var chroma = 1
    var separate = false
    if (profile in HIGH_PROFILES) {
        chroma = r.ue()
        if (chroma == 3) separate = r.bit() == 1
        r.ue() // bit_depth_luma_minus8
        r.ue() // bit_depth_chroma_minus8
        r.bit() // qpprime_y_zero_transform_bypass_flag
        if (r.bit() == 1) {
            for (i in 0 until if (chroma != 3) 8 else 12) {
                if (r.bit() == 1) scalingList(r, if (i < 6) 16 else 64)
            }
        }
    }
    r.ue() // log2_max_frame_num_minus4
    when (r.ue()) {
        0 -> r.ue()
        1 -> {
            r.bit()
            r.se()
            r.se()
            repeat(r.ue()) { r.se() }
        }
    }
    r.ue() // max_num_ref_frames
    r.bit() // gaps_in_frame_num_value_allowed_flag
    val widthMbs = r.ue() + 1
    val heightUnits = r.ue() + 1
    val frameMbsOnly = r.bit()
    if (frameMbsOnly == 0) r.bit()
    r.bit() // direct_8x8_inference_flag
    var width = widthMbs * 16
    var height = (2 - frameMbsOnly) * heightUnits * 16
    if (r.bit() == 1) {
        val (left, right, top, bottom) = List(4) { r.ue() }
        val mono = chroma == 0 || separate
        val unitX = if (mono || chroma == 3) 1 else 2
        val unitY = (if (mono || chroma != 1) 1 else 2) * (2 - frameMbsOnly)
        width -= unitX * (left + right)
        height -= unitY * (top + bottom)
    }
    return Sps(width, height)
}

private fun scalingList(r: Bits, size: Int) {
    var last = 8
    var next = 8
    repeat(size) {
        if (next != 0) next = (last + r.se() + 256) % 256
        if (next != 0) last = next
    }
}

/** What an H.265 SPS says, read up to where its VUI flag stands. */
private fun readH265(r: Bits): Sps {
    r.bits(4) // sps_video_parameter_set_id
    val subLayers = r.bits(3)
    r.bit() // sps_temporal_id_nesting_flag
    // profile_tier_level: the general profile and level, then each sub-layer's
    r.bits(32)
    r.bits(32)
    r.bits(32)
    val profilePresent = BooleanArray(subLayers)
    val levelPresent = BooleanArray(subLayers)
    for (i in 0 until subLayers) {
        profilePresent[i] = r.bit() == 1
        levelPresent[i] = r.bit() == 1
    }
    if (subLayers > 0) for (i in subLayers until 8) r.bits(2)
    for (i in 0 until subLayers) {
        if (profilePresent[i]) {
            r.bits(32)
            r.bits(32)
            r.bits(24)
        }
        if (levelPresent[i]) r.bits(8)
    }
    r.ue() // sps_seq_parameter_set_id
    val chroma = r.ue()
    val separate = chroma == 3 && r.bit() == 1
    var width = r.ue()
    var height = r.ue()
    if (r.bit() == 1) {
        val (left, right, top, bottom) = List(4) { r.ue() }
        val mono = chroma == 0 || separate
        width -= (if (mono || chroma == 3) 1 else 2) * (left + right)
        height -= (if (mono || chroma != 1) 1 else 2) * (top + bottom)
    }
    r.ue() // bit_depth_luma_minus8
    r.ue() // bit_depth_chroma_minus8
    val pocLsbBits = r.ue() + 4
    val orderingForAll = r.bit() == 1
    for (i in (if (orderingForAll) 0 else subLayers)..subLayers) repeat(3) { r.ue() }
    repeat(6) { r.ue() } // coding and transform block sizes, hierarchy depths
    if (r.bit() == 1 && r.bit() == 1) {
        // scaling_list_data
        for (sizeId in 0..3) {
            for (matrixId in 0 until 6 step if (sizeId == 3) 3 else 1) {
                if (r.bit() == 0) {
                    r.ue()
                } else {
                    if (sizeId > 1) r.se()
                    repeat(minOf(64, 1 shl (4 + (sizeId shl 1)))) { r.se() }
                }
            }
        }
    }
    r.bit() // amp_enabled_flag
    r.bit() // sample_adaptive_offset_enabled_flag
    if (r.bit() == 1) {
        r.bits(8) // pcm sample bit depths
        r.ue()
        r.ue()
        r.bit()
    }
    // st_ref_pic_set(i) for each; in an SPS a predicted set leans on the one
    // before, reading a flag or two for each of its pictures. Counted as HM,
    // libavcodec and Android's own decoder count them — every entry used or
    // kept — which the spec's text differs from only for an entry no encoder
    // writes, one landing on the current picture itself.
    val sets = r.ue()
    val deltaPocs = IntArray(sets)
    for (i in 0 until sets) {
        if (i != 0 && r.bit() == 1) {
            r.bit() // delta_rps_sign
            r.ue() // abs_delta_rps_minus1
            var count = 0
            for (j in 0..deltaPocs[i - 1]) {
                val used = r.bit() == 1
                if (used || r.bit() == 1) count++
            }
            deltaPocs[i] = count
        } else {
            val negative = r.ue()
            val positive = r.ue()
            repeat(negative + positive) {
                r.ue()
                r.bit()
            }
            deltaPocs[i] = negative + positive
        }
    }
    if (r.bit() == 1) {
        repeat(r.ue()) {
            r.bits(pocLsbBits)
            r.bit()
        }
    }
    r.bit() // sps_temporal_mvp_enabled_flag
    r.bit() // strong_intra_smoothing_enabled_flag
    return Sps(width, height, subLayers)
}

/** The RBSP of a NAL unit: its bytes after [from], emulation prevention taken out. */
private fun unescape(nal: ByteArray, from: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(nal.size)
    var zeros = 0
    for (i in from until nal.size) {
        val b = nal[i].toInt() and 0xFF
        if (zeros >= 2 && b == 3) {
            zeros = 0
            continue
        }
        zeros = if (b == 0) zeros + 1 else 0
        out.write(b)
    }
    return out.toByteArray()
}

/** An RBSP made safe to stand between start codes again. */
private fun escape(rbsp: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream(rbsp.size + 4)
    var zeros = 0
    for (byte in rbsp) {
        val b = byte.toInt() and 0xFF
        if (zeros >= 2 && b <= 3) {
            out.write(3)
            zeros = 0
        }
        zeros = if (b == 0) zeros + 1 else 0
        out.write(b)
    }
    return out.toByteArray()
}

/** Where the RBSP's stop bit stands: its last bit set. */
private fun stopBit(rbsp: ByteArray): Int {
    var i = rbsp.size - 1
    while (i >= 0 && rbsp[i].toInt() == 0) i--
    if (i < 0) throw IndexOutOfBoundsException()
    val b = rbsp[i].toInt() and 0xFF
    return i * 8 + 7 - Integer.numberOfTrailingZeros(b)
}

private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

/** Bits of an RBSP, read from the top of each byte down. */
private class Bits(private val data: ByteArray) {
    var at = 0
        private set

    fun bit(): Int {
        val b = (data[at shr 3].toInt() shr (7 - (at and 7))) and 1
        at++
        return b
    }

    fun bits(n: Int): Int {
        var v = 0
        repeat(n) { v = (v shl 1) or bit() }
        return v
    }

    fun ue(): Int {
        var zeros = 0
        while (bit() == 0) {
            zeros++
            if (zeros > 31) throw IndexOutOfBoundsException()
        }
        return ((1L shl zeros) - 1 + bits(zeros)).toInt()
    }

    fun se(): Int {
        val k = ue()
        return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
    }
}

private class BitWriter {
    private val out = java.io.ByteArrayOutputStream()
    private var current = 0
    private var count = 0

    fun bits(value: Int, n: Int) {
        for (i in n - 1 downTo 0) {
            current = (current shl 1) or ((value shr i) and 1)
            if (++count == 8) {
                out.write(current)
                current = 0
                count = 0
            }
        }
    }

    /** The bits of [data] from [from] up to [until]. */
    fun copy(data: ByteArray, from: Int, until: Int) {
        for (at in from until until) bits((data[at shr 3].toInt() shr (7 - (at and 7))) and 1, 1)
    }

    fun align() {
        if (count > 0) bits(0, 8 - count)
    }

    fun bytes(): ByteArray = out.toByteArray()
}

private const val EXTENDED_SAR = 255

// the sample aspect ratios aspect_ratio_idc 1 to 16 stand for, alike in both codecs
private val SAR_TABLE = listOf(
    1 to 1, 12 to 11, 10 to 11, 16 to 11, 40 to 33, 24 to 11, 20 to 11, 32 to 11,
    80 to 33, 18 to 11, 15 to 11, 64 to 33, 160 to 99, 4 to 3, 3 to 2, 2 to 1
)

// the profiles whose SPS carries chroma format, bit depths and scaling lists
private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
