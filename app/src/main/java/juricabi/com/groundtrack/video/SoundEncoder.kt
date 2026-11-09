package juricabi.com.groundtrack.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import juricabi.com.groundtrack.utils.DebugLog

/**
 * The picture's sound for a recording: 16-bit PCM in, AAC-LC out, each frame
 * ADTS-framed — the form a transport stream carries AAC in. A USB receiver's
 * sound is PCM off its audio input, and a network stream's is the PCM the
 * player decoded for the speaker, so both reach the file by this one road,
 * whatever codec the stream used.
 *
 * Used from the one thread that has the PCM, and never blocking it: PCM the
 * encoder has no room for is dropped.
 */
internal class SoundEncoder(val rate: Int, val channels: Int) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private val frameBytes = 2 * channels
    // the AudioSpecificConfig, which every ADTS header is read off
    private var config: ByteArray? = null

    init {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            // 64 kbit/s a channel at 48 kHz, less at a lower rate: AAC-LC
            // caps a frame at 6144 bits a channel, which at G.711's 8 kHz
            // is 48 kbit/s, and asked for more an encoder may refuse
            format.setInteger(MediaFormat.KEY_BIT_RATE, rate * 4 / 3 * channels)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    /** The PCM between [pcm]'s position and limit, its first sample at [timeUs]; the buffer is left as it was. */
    fun encode(pcm: ByteBuffer, timeUs: Long, sink: RecordSink) {
        val chunk = pcm.duplicate()
        var at = timeUs
        while (chunk.remaining() >= frameBytes) {
            var index = codec.dequeueInputBuffer(0)
            if (index < 0) {
                drain(sink)
                index = codec.dequeueInputBuffer(0)
                if (index < 0) return
            }
            val input = codec.getInputBuffer(index)!!
            input.clear()
            val n = minOf(input.remaining(), chunk.remaining()) / frameBytes * frameBytes
            val part = chunk.duplicate()
            part.limit(part.position() + n)
            input.put(part)
            chunk.position(chunk.position() + n)
            codec.queueInputBuffer(index, 0, n, at, 0)
            at += n / frameBytes * 1_000_000L / rate
        }
        drain(sink)
    }

    private fun drain(sink: RecordSink) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                codec.outputFormat.getByteBuffer("csd-0")?.let { config = it.bytes() }
                continue
            }
            if (index < 0) return
            val out = codec.getOutputBuffer(index)
            if (out != null && info.size > 0) {
                out.position(info.offset)
                out.limit(info.offset + info.size)
                val bytes = out.bytes()
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config = bytes
                } else {
                    config?.let { sink.sound(adts(it, bytes), info.presentationTimeUs) }
                }
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    fun release() {
        try {
            codec.stop()
        } catch (e: Exception) {
            // already failed; releasing is all that is left
        }
        codec.release()
    }
}

/**
 * The sound's half of a recording, for a source that has PCM: an encoder
 * built at the first chunk a recording wants, at that chunk's format, and let
 * go with the recording. A phone that cannot encode the sound records the
 * picture alone, and the log says why — once, not at every chunk.
 */
internal class SoundTap(private val what: String) {
    private var encoder: SoundEncoder? = null
    private var failedFor: RecordSink? = null

    fun pcm(pcm: ByteBuffer, rate: Int, channels: Int, timeUs: Long, sink: RecordSink?) {
        if (sink == null) return release()
        if (sink === failedFor) return
        try {
            val e = encoder?.takeIf { it.rate == rate && it.channels == channels }
                ?: SoundEncoder(rate, channels).also {
                    release()
                    encoder = it
                    DebugLog.note("Video", "$what recording sound, $rate Hz × $channels")
                }
            e.encode(pcm, timeUs, sink)
        } catch (x: Exception) {
            DebugLog.note("Video", "$what sound cannot be recorded: ${x.message}")
            release()
            failedFor = sink
        }
    }

    fun release() {
        encoder?.release()
        encoder = null
    }
}

/**
 * Times for PCM that carries none — a USB receiver's, read off AudioRecord.
 * Reads return in uneven bursts, so stamped on arrival the sound came out in
 * clicks; counted from the first sample instead, its spacing is exact. A
 * count that has fallen more than a fifth of a second off the arrivals —
 * samples the input lost to a stall, or the receiver's crystal drifting from
 * the phone's over a long flight — is pinned back to them.
 */
internal class SampleClock(private val rate: Int) {
    private var startUs = 0L
    private var samples = -1L

    /** The time of the first of [count] samples that arrived at [arrivalUs]. */
    fun stamp(count: Int, arrivalUs: Long): Long {
        // the chunk's first sample was heard its own length before it arrived
        val heardUs = arrivalUs - count * 1_000_000L / rate
        val countedUs = startUs + samples * 1_000_000L / rate
        if (samples < 0 || kotlin.math.abs(countedUs - heardUs) > 200_000) {
            startUs = heardUs
            samples = 0
        }
        val t = startUs + samples * 1_000_000L / rate
        samples += count
        return t
    }
}

/**
 * One raw AAC frame behind its 7-byte ADTS header, the header read off the
 * encoder's AudioSpecificConfig: its object type, rate index and channels.
 */
internal fun adts(config: ByteArray, frame: ByteArray): ByteArray {
    val objectType = (config[0].toInt() and 0xFF) shr 3
    val rateIndex = ((config[0].toInt() and 0x07) shl 1) or ((config[1].toInt() and 0xFF) shr 7)
    val channels = ((config[1].toInt() and 0xFF) shr 3) and 0x0F
    val length = 7 + frame.size
    return byteArrayOf(
        0xFF.toByte(), 0xF1.toByte(), // sync, MPEG-4, no CRC
        (((objectType - 1) shl 6) or (rateIndex shl 2) or (channels shr 2)).toByte(),
        (((channels and 3) shl 6) or (length shr 11)).toByte(),
        (length shr 3).toByte(),
        (((length and 7) shl 5) or 0x1F).toByte(),
        0xFC.toByte() // buffer fullness 0x7FF: variable rate; one raw block
    ) + frame
}

/** The AudioSpecificConfig an ADTS header was built from: its object type, rate index and channels. */
internal fun configOf(adts: ByteArray): ByteArray {
    val objectType = ((adts[2].toInt() and 0xFF) shr 6) + 1
    val rateIndex = (adts[2].toInt() shr 2) and 0x0F
    val channels = ((adts[2].toInt() and 0x01) shl 2) or ((adts[3].toInt() and 0xFF) shr 6)
    return byteArrayOf(
        ((objectType shl 3) or (rateIndex shr 1)).toByte(),
        (((rateIndex and 1) shl 7) or (channels shl 3)).toByte()
    )
}

/**
 * One frame of silence in [config]'s form, mono or stereo — the two the
 * recording takes. The frames are the ones hls.js pads gaps with, so they
 * are known to decode on the phones' own AAC decoders.
 */
internal fun silence(config: ByteArray): ByteArray =
    adts(config, if (((config[1].toInt() and 0xFF) shr 3) and 0x0F == 1) SILENT_MONO else SILENT_STEREO)

/** The sample rate [config] names; a frame is 1024 samples of it. */
internal fun sampleRate(config: ByteArray): Int =
    RATES[((config[0].toInt() and 0x07) shl 1) or ((config[1].toInt() and 0xFF) shr 7)]

private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
private val SILENT_MONO = byteArrayOf(0x00, 0xC8.toByte(), 0x00, 0x80.toByte(), 0x23, 0x80.toByte())
private val SILENT_STEREO = byteArrayOf(0x21, 0x00, 0x49, 0x90.toByte(), 0x02, 0x19, 0x00, 0x23, 0x80.toByte())
