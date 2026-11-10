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
        assertEquals(853 to 480, pictureBox(640, 480, 0, 16f / 9f))
        // a 16:9 stream from a 4:3 camera: as tall, narrower
        assertEquals(960 to 720, pictureBox(1280, 720, 180, 4f / 3f))
    }

    @Test
    fun aQuarterTurnTurnsTheShapeWithThePicture() {
        assertEquals(480 to 853, pictureBox(640, 480, 90, 16f / 9f))
        assertEquals(480 to 853, pictureBox(640, 480, 270, 16f / 9f))
    }

    @Test
    fun theShapesByName() {
        assertEquals(16f / 9f, VideoPane.shapeRatio("16:9")!!, 0f)
        assertEquals(4f / 3f, VideoPane.shapeRatio("4:3")!!, 0f)
        assertNull(VideoPane.shapeRatio("auto"))
    }
}
