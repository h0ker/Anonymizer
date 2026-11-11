package com.hoker.anonymizer.domain.effect

import com.hoker.anonymizer.domain.model.FaceRegion
import com.hoker.anonymizer.domain.model.GlitchParams
import com.hoker.anonymizer.domain.model.PixelBuffer
import com.hoker.anonymizer.domain.model.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The Face lighting option: the face's own blurred light and shadow over the sorted streaks. */
class FaceLightingTest {

    private val effect = FaceGlitchEffect()
    private val sortAll = GlitchParams(lowThreshold = 0f, highThreshold = 1f, bleed = 0.05f, raggedness = 0f)

    @Test
    fun `face lighting restores the light the sort flattened`() {
        // A face lit from above: bright forehead, dark chin. A downward sort drags the dark pixels
        // to the top and the bright ones to the bottom, turning the lighting upside down.
        val image = image { _, y -> gray((230 - (y - 60) * 0.9f).toInt().coerceIn(40, 230)) }
        val plain = effect.apply(image, listOf(face), sortAll)
        val lit = effect.apply(image, listOf(face), sortAll.copy(faceLighting = true))
        assertTrue("top should be brighter with lighting", meanLuma(lit, 150..190, 90..120) > meanLuma(plain, 150..190, 90..120) + 15)
        assertTrue("bottom should be darker with lighting", meanLuma(lit, 150..190, 230..260) < meanLuma(plain, 150..190, 230..260) - 15)
    }

    @Test
    fun `face lighting leaves an evenly lit face alone`() {
        val image = image { _, _ -> gray(140) }
        val plain = effect.apply(image, listOf(face), sortAll)
        val lit = effect.apply(image, listOf(face), sortAll.copy(faceLighting = true))
        for (i in plain.pixels.indices) assertTrue(abs(luminance(plain.pixels[i]) - luminance(lit.pixels[i])) <= 2)
    }

    @Test
    fun `brightening keeps the hue instead of clipping one channel`() {
        val skin = 0xFFE0A080.toInt()
        val brighter = scaleColor(skin, 1.6f)
        // Red is already near full: everything scales by the same amount until red hits 255.
        assertEquals(255, (brighter shr 16) and 0xFF)
        val ratio = ((brighter shr 8) and 0xFF) / 255f
        assertEquals(0xA0 / 224f, ratio, 0.01f)
    }

    private fun image(color: (x: Int, y: Int) -> Int) = PixelBuffer(SIZE, SIZE, IntArray(SIZE * SIZE) { color(it % SIZE, it / SIZE) })
    private fun gray(v: Int) = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
    private fun meanLuma(image: PixelBuffer, xs: IntRange, ys: IntRange) =
        ys.sumOf { y -> xs.sumOf { x -> luminance(image.pixels[y * SIZE + x]) } } / (xs.count() * ys.count())

    private companion object {
        const val SIZE = 340

        val face = FaceRegion(
            (0 until 36).map { i ->
                val a = i / 36.0 * 2 * Math.PI
                Point((170 + 80 * cos(a)).toFloat(), (175 + 110 * sin(a)).toFloat())
            },
        )
    }
}
