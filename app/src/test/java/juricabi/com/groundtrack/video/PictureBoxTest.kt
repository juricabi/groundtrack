package juricabi.com.groundtrack.video

import juricabi.com.groundtrack.ui.VideoPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The box the picture is laid into. A 16:9 camera through an analog
 * receiver arrives as 4:3 frames, squeezed thin; said to be 16:9, it is
 * stretched back — and the shape stays the camera's through a turn.
 */
class PictureBoxTest {

    @Test
    fun withNoShapeTheFramesOwnSidesStand() {
        assertEquals(640 to 480, pictureBox(640, 480, 0, null))
        assertEquals(480 to 640, pictureBox(640, 480, 90, null))
    }

    @Test
    fun aShapeStretchesTheFrameToIt() {
        // a receiver's 4:3, said to be 16:9: as tall, wider
        assertEquals(853 to 480, pictureBox(640, 480, 0, Shape(16, 9)))
        // a 16:9 stream from a 4:3 camera: as tall, narrower
        assertEquals(960 to 720, pictureBox(1280, 720, 180, Shape(4, 3)))
    }

    @Test
    fun withNoShapeThePixelsOwnShapeStands() {
        // a digitised NTSC picture, its pixels 8:9: 4:3, as its recording plays
        assertEquals(640 to 480, pictureBox(720, 480, 0, null, 8f / 9f))
        // an IP camera's 1440x1080 with 4:3 pixels: 16:9
        assertEquals(1080 to 1920, pictureBox(1440, 1080, 90, null, 4f / 3f))
        // a shape chosen stands over the stream's own
        assertEquals(853 to 480, pictureBox(720, 480, 0, Shape(16, 9), 8f / 9f))
    }

    @Test
    fun aQuarterTurnTurnsTheShapeWithThePicture() {
        assertEquals(480 to 853, pictureBox(640, 480, 90, Shape(16, 9)))
        assertEquals(480 to 853, pictureBox(640, 480, 270, Shape(16, 9)))
    }

    @Test
    fun theShapesByName() {
        assertEquals(16 to 9, VideoPane.shapeOf("16:9")!!.let { it.width to it.height })
        assertEquals(4 to 3, VideoPane.shapeOf("4:3")!!.let { it.width to it.height })
        assertNull(VideoPane.shapeOf("auto"))
    }
}
