package dev.quinntyx.charon.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageOrientationTest {
    @Test
    fun exifOrientationsPreserveRotationAndMirroring() {
        assertEquals(ImageOrientation(0, false), imageOrientationForExif(1))
        assertEquals(ImageOrientation(0, true), imageOrientationForExif(2))
        assertEquals(ImageOrientation(180, false), imageOrientationForExif(3))
        assertEquals(ImageOrientation(180, true), imageOrientationForExif(4))
        assertEquals(ImageOrientation(90, true), imageOrientationForExif(5))
        assertEquals(ImageOrientation(90, false), imageOrientationForExif(6))
        assertEquals(ImageOrientation(270, true), imageOrientationForExif(7))
        assertEquals(ImageOrientation(270, false), imageOrientationForExif(8))
        assertEquals(ImageOrientation(0, false), imageOrientationForExif(99))
    }

    @Test
    fun cameraRotationUsesStableQuadrants() {
        assertEquals(0, cameraTargetRotationDegrees(0, 180))
        assertEquals(0, cameraTargetRotationDegrees(44, 180))
        assertEquals(270, cameraTargetRotationDegrees(45, 0))
        assertEquals(270, cameraTargetRotationDegrees(134, 0))
        assertEquals(180, cameraTargetRotationDegrees(135, 0))
        assertEquals(180, cameraTargetRotationDegrees(224, 0))
        assertEquals(90, cameraTargetRotationDegrees(225, 0))
        assertEquals(90, cameraTargetRotationDegrees(314, 0))
        assertEquals(0, cameraTargetRotationDegrees(315, 180))
        assertEquals(0, cameraTargetRotationDegrees(359, 180))
    }

    @Test
    fun unknownPhysicalOrientationKeepsPreviousTarget() {
        assertEquals(270, cameraTargetRotationDegrees(-1, 270))
        assertEquals(90, cameraTargetRotationDegrees(OrientationUnknown, 90))
    }

    private companion object {
        const val OrientationUnknown = 400
    }
}
