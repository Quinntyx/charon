package dev.quinntyx.charon.capture

import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Transform described by an image's EXIF orientation tag. */
data class ImageOrientation(
    val rotationDegrees: Int,
    val flipHorizontal: Boolean,
)

/** Reads the transform without rewriting or degrading the original imported image. */
fun readImageOrientation(file: File): ImageOrientation = imageOrientationForExif(
    ExifInterface(file).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    ),
)

/** Keeps imported EXIF orientation meaningful for OCR or image rendering consumers. */
fun imageOrientationForExif(exifOrientation: Int): ImageOrientation = when (exifOrientation) {
    2 -> ImageOrientation(0, true)
    3 -> ImageOrientation(180, false)
    4 -> ImageOrientation(180, true)
    5 -> ImageOrientation(90, true)
    6 -> ImageOrientation(90, false)
    7 -> ImageOrientation(270, true)
    8 -> ImageOrientation(270, false)
    else -> ImageOrientation(0, false)
}

/**
 * Maps physical device orientation to the target rotation CameraX needs for upright JPEG metadata.
 * Unknown/flat positions retain the previous target instead of suddenly rotating the capture.
 */
fun cameraTargetRotationDegrees(orientationDegrees: Int, previousDegrees: Int): Int =
    when (orientationDegrees) {
        in 45..134 -> 270
        in 135..224 -> 180
        in 225..314 -> 90
        in 0..44, in 315..359 -> 0
        else -> previousDegrees
    }
