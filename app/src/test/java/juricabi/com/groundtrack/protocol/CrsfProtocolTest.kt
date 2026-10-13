package juricabi.com.groundtrack.protocol

import juricabi.com.groundtrack.protocol.crc.CRC8
import juricabi.com.groundtrack.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class CrsfProtocolTest {

    @Test
    fun completeGpsFrameIsDecodedWithoutFollowingBytes() {
        var position: Pair<Double, Double>? = null
        val protocol = CrsfProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onGPSData(latitude: Double, longitude: Double) {
                position = latitude to longitude
            }
        })
        val payload = ByteBuffer.allocate(15)
            .putInt(451234567)
            .putInt(161234567)
            .putShort(100)
            .putShort(9000)
            .putShort(1100)
            .put(12)
            .array()

        feed(protocol, frame(0x02, payload))

        assertEquals(45.1234567, position?.first ?: Double.NaN, 0.0000001)
        assertEquals(16.1234567, position?.second ?: Double.NaN, 0.0000001)
    }

    @Test
    fun shortVarioFrameIsAccepted() {
        var verticalSpeed = Float.NaN
        val protocol = CrsfProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onVSpeedData(vspeed: Float) {
                verticalSpeed = vspeed
            }
        })

        feed(protocol, frame(0x07, byteArrayOf(0x00, 0xc8.toByte())))

        assertEquals(2f, verticalSpeed, 0.001f)
    }

    @Test
    fun airspeedUsesTenthsOfAKilometrePerHour() {
        var airspeed = Float.NaN
        val protocol = CrsfProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onAirSpeedData(speed: Float) {
                airspeed = speed
            }
        })

        val payload = ByteBuffer.allocate(2).putShort(360).array()
        feed(protocol, frame(0x0a, payload))

        assertEquals(36f, airspeed, 0.001f)
    }

    @Test
    fun extendedBarometerFrameIncludesElrsVario() {
        var reportedAltitude = Float.NaN
        var verticalSpeed = Float.NaN
        val protocol = CrsfProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onAltitudeData(altitude: Float) {
                reportedAltitude = altitude
            }

            override fun onVSpeedData(vspeed: Float) {
                verticalSpeed = vspeed
            }
        })

        val payload = ByteBuffer.allocate(4)
            .putShort(11000)
            .putShort(250)
            .array()
        feed(protocol, frame(0x09, payload))

        assertEquals(100f, reportedAltitude, 0.001f)
        assertEquals(2.5f, verticalSpeed, 0.001f)
    }

    @Test
    fun extendedBarometerFrameIncludesSignedTbsVario() {
        var verticalSpeed = Float.NaN
        val protocol = CrsfProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onVSpeedData(vspeed: Float) {
                verticalSpeed = vspeed
            }
        })

        val payload = ByteBuffer.allocate(3)
            .putShort(11000)
            .put((-10).toByte())
            .array()
        feed(protocol, frame(0x09, payload))

        assertEquals(-0.29f, verticalSpeed, 0.001f)
    }

    @Test
    fun channelsFrameCarryingEdgeTxStatusByteIsDecoded() {
        // EdgeTX 2.11.8 appends a status byte to every channels frame; the
        // spec has a receiver ignore what it does not know.
        var channels: IntArray? = null
        val protocol = CrsfProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onRCChannels(rcChannels: IntArray) {
                channels = rcChannels.copyOf()
            }
        })

        val values = IntArray(16) { 992 }.also { it[0] = 172 }
        feed(protocol, frame(0x16, packChannels(values) + 0x02.toByte()))

        assertEquals(988, channels?.get(0))
        assertEquals(1500, channels?.get(15))
    }

    @Test
    fun linkStatisticsNameTheAntennaFromOneAndThePowerInMilliwatts() {
        val antennas = ArrayList<Int>()
        val powers = ArrayList<Int>()
        val protocol = CrsfProtocol(object : DataDecoder.Companion.DefaultDecodeListener() {
            override fun onAntData(activeAntena: Int) {
                antennas.add(activeAntena)
            }

            override fun onPowerData(power: Int) {
                powers.add(power)
            }
        })

        // rssi 1 and 2, lq, snr, active antenna, rf mode, power level, then downlink
        feed(protocol, frame(0x14, byteArrayOf(-60, -70, 100, 8, 1, 5, 3, -50, 100, 7)))
        feed(protocol, frame(0x14, byteArrayOf(-60, -70, 100, 8, 0, 5, 9, -50, 100, 7)))

        assertEquals(listOf(2, 1), antennas)
        // level 3 is 100 mW; level 9 is past the table and says nothing
        assertEquals(listOf(100), powers)
    }

    /** Sixteen 11-bit channels, least significant bit first, into 22 bytes. */
    private fun packChannels(values: IntArray): ByteArray {
        val out = ByteArray(22)
        var bits = 0L
        var count = 0
        var at = 0
        for (value in values) {
            bits = bits or (value.toLong() shl count)
            count += 11
            while (count >= 8) {
                out[at++] = bits.toByte()
                bits = bits ushr 8
                count -= 8
            }
        }
        return out
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf(type.toByte()) + payload
        val crc = CRC8()
        body.forEach { crc.update(it) }
        return byteArrayOf(0xc8.toByte(), (body.size + 1).toByte()) +
            body + crc.value.toByte()
    }

    private fun feed(protocol: CrsfProtocol, bytes: ByteArray) {
        bytes.forEach { protocol.process(it.toInt() and 0xff) }
    }
}
