package com.hoker.anonymizer.domain.model

/**
 * Platform-independent image: packed ARGB_8888 pixels in row-major order.
 * Keeping this free of android.graphics lets the effect run (and be tested) on the plain JVM.
 */
class PixelBuffer(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "Image must not be empty" }
        require(pixels.size == width * height) { "Pixel count does not match ${width}x$height" }
    }

    fun copy(): PixelBuffer = PixelBuffer(width, height, pixels.copyOf())
}
