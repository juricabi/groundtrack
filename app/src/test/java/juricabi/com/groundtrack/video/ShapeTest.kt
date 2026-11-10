package juricabi.com.groundtrack.video

import androidx.media3.container.NalUnitUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A recording says the shape it was watched in: every SPS that goes into it
 * carries the sample aspect ratio that shows its frames so. Read back here
 * by media3's own parser — the one ExoPlayer and the gallery apps built on it
 * read the shape with — and the SPSs are what real encoders write: x264 and
 * x265 across their profiles, cropped, interlaced, with scaling matrices,
 * HRD, and a ratio of their own already.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeTest {

    private val h264 = mapOf(
        "baseline 640x480" to "6742c01ed900a03db011000003000100000300320f162e48",
        "main 720x480" to "674d401eeca05a1ed808800000030080000019078b16cb",
        "high 1080p, cropped" to "67640028acd940780227e5c044000003000400000300c83c60c658",
        "interlaced 720x576" to "6764001eacd940b424d8088000000300800000190f8a14cb",
        "scaling matrices" to "6764001eacd940a03db011000003000100000300320f162d96",
        "4:4:4" to "67f4001e919b281405ff1380880000030008000003019078b16cb0",
        "4:2:2, cropped" to "677a001fbcd9405005bfbc0440000003004000000c83c60c6580",
        "nal hrd" to "6764001eacd940a03db01100000300010000030032e06000f424001e84e6c30078b16cb0",
        "monochrome" to "6764001ef3650280f6c05b20000003002000000641e2c5b2c0",
        "a ratio of its own" to "6764001eacd940b43dbff000800091000003000100000300320f162d96",
    )

    private val h265 = mapOf(
        "main 640x480" to "42010101600000030090000003000003005aa0050201e165959a4932bc05a02000000300200000030321",
        "1080p, cropped" to "420101016000000300900000030000030078a003c08010e596566924caf0168080000003008000000c84",
        "4:4:4" to "4201010408000003009e0800000300003f9000a0402d2cb2b349265780b4040000030004000003006420",
        "4:2:2 10-bit, cropped" to "4201010408000003009d0800000300005db00280802d1f6d96566924caf0168080000003008000000c84",
        "monochrome" to "4201010408000003009fc800000300005ac01408078596566924caf016c080000003008000000c84",
        "scaling lists" to "42010101600000030090000003000003005aa0050201e165959a49395e02d0100000030010000003019080",
        "hrd" to "42010101600000030090000003000003005aa0050201e165959a4932bc05a0200000030020000003032c039acbc001e848003d0910",
        "a ratio of its own" to "42010101600000030090000003000003005aa005a201e165959a4932bffc00200025a02000000300200000030321",
    )

    private val shapes = listOf(Shape(16, 9), Shape(4, 3))

    @Test
    fun everyH264SpsSaysTheShapeAndNothingElseChanges() {
        for ((name, hex) in h264) for (shape in shapes) {
            val before = hex(hex)
            val after = shapedSps(RtpCodec.H264, before, shape)
            val was = NalUnitUtil.parseSpsNalUnit(before, 0, before.size)
            val now = NalUnitUtil.parseSpsNalUnit(after, 0, after.size)
            assertEquals(name, shape.width / shape.height.toFloat(), now.width * now.pixelWidthHeightRatio / now.height, 0.002f)
            assertEquals(name, listOf(was.profileIdc, was.levelIdc, was.width, was.height, was.maxNumRefFrames,
                was.frameNumLength, was.picOrderCountType, was.picOrderCntLsbLength, was.frameMbsOnlyFlag,
                was.colorSpace, was.colorRange),
                listOf(now.profileIdc, now.levelIdc, now.width, now.height, now.maxNumRefFrames,
                now.frameNumLength, now.picOrderCountType, now.picOrderCntLsbLength, now.frameMbsOnlyFlag,
                now.colorSpace, now.colorRange))
        }
    }

    @Test
    fun everyH265SpsSaysTheShapeAndNothingElseChanges() {
        for ((name, hex) in h265) for (shape in shapes) {
            val before = hex(hex)
            val after = shapedSps(RtpCodec.H265, before, shape)
            val was = NalUnitUtil.parseH265SpsNalUnit(before, 0, before.size, null)
            val now = NalUnitUtil.parseH265SpsNalUnit(after, 0, after.size, null)
            assertEquals(name, shape.width / shape.height.toFloat(), now.width * now.pixelWidthHeightRatio / now.height, 0.002f)
            assertEquals(name, listOf(was.width, was.height, was.chromaFormatIdc, was.bitDepthLumaMinus8,
                was.maxSubLayersMinus1, was.maxNumReorderPics, was.colorSpace, was.colorRange, was.colorTransfer),
                listOf(now.width, now.height, now.chromaFormatIdc, now.bitDepthLumaMinus8,
                now.maxSubLayersMinus1, now.maxNumReorderPics, now.colorSpace, now.colorRange, now.colorTransfer))
        }
    }

    @Test
    fun aShapeAlreadySaidIsWrittenTheSameAgain() {
        // 720x480 at 8:9 is already 4:3
        val sps = hex(h264.getValue("a ratio of its own"))
        assertArrayEquals(sps, shapedSps(RtpCodec.H264, sps, Shape(4, 3)))
        val once = shapedSps(RtpCodec.H265, hex(h265.getValue("main 640x480")), Shape(16, 9))
        assertArrayEquals(once, shapedSps(RtpCodec.H265, once, Shape(16, 9)))
    }

    @Test
    fun aPixelsOwnShapeIsReadAsAPlayerReadsIt() {
        // 720x480 said to be 4:3: its pixels are 8:9
        assertEquals(8 / 9f, pixelRatioOf(RtpCodec.H264, hex(h264.getValue("a ratio of its own"))), 0.0001f)
        assertEquals(8 / 9f, pixelRatioOf(RtpCodec.H265, hex(h265.getValue("a ratio of its own"))), 0.0001f)
        // square, from the table's first entry
        assertEquals(1f, pixelRatioOf(RtpCodec.H264, hex(h264.getValue("baseline 640x480"))), 0f)
        // and what a shape writes is read back the same way, start code or not
        val wide = shapedSps(RtpCodec.H265, hex(h265.getValue("main 640x480")), Shape(16, 9))
        assertEquals(4 / 3f, pixelRatioOf(RtpCodec.H265, START4 + wide), 0.0001f)
        for ((name, hex) in h264) {
            val sps = hex(hex)
            val media3 = NalUnitUtil.parseSpsNalUnit(sps, 0, sps.size).pixelWidthHeightRatio
            assertEquals(name, media3, pixelRatioOf(RtpCodec.H264, sps), 0.0001f)
        }
    }

    @Test
    fun theStartCodeStaysAndTheRestOfTheRunIsUntouched() {
        val sps = hex(h264.getValue("baseline 640x480"))
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
        val slice = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0, 0, 3, 1, 0x21)
        val run = START4 + sps + START3 + pps + START4 + slice
        val shaped = shaped(RtpCodec.H264, run, Shape(16, 9))
        val expected = START4 + shapedSps(RtpCodec.H264, sps, Shape(16, 9)) + START3 + pps + START4 + slice
        assertArrayEquals(expected, shaped)
        assertArrayEquals(START4 + shapedSps(RtpCodec.H264, sps, Shape(16, 9)),
            shapedSps(RtpCodec.H264, START4 + sps, Shape(16, 9)))
        // a run without an SPS is handed back as it is
        val frame = START4 + slice
        assertSame(frame, shaped(RtpCodec.H264, frame, Shape(16, 9)))
    }

    @Test
    fun anSpsThatCannotBeReadThroughIsLeftAsItCame() {
        val sps = hex(h264.getValue("high 1080p, cropped"))
        // cut short on the link
        val cut = sps.copyOf(sps.size - 6)
        assertArrayEquals(cut, shapedSps(RtpCodec.H264, cut, Shape(16, 9)))
        // a byte more than its syntax accounts for
        val more = sps.copyOf(sps.size - 1) + byteArrayOf(0x5A, sps.last())
        assertArrayEquals(more, shapedSps(RtpCodec.H264, more, Shape(16, 9)))
        val h = hex(h265.getValue("hrd"))
        val hCut = h.copyOf(h.size - 4)
        assertArrayEquals(hCut, shapedSps(RtpCodec.H265, hCut, Shape(4, 3)))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        val START4 = byteArrayOf(0, 0, 0, 1)
        val START3 = byteArrayOf(0, 0, 1)
    }
}
