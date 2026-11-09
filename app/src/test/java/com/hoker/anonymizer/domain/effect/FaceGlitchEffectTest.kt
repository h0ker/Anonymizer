package com.hoker.anonymizer.domain.effect

import com.hoker.anonymizer.domain.model.FaceRegion
import com.hoker.anonymizer.domain.model.GlitchParams
import com.hoker.anonymizer.domain.model.PixelBuffer
import com.hoker.anonymizer.domain.model.Point
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.random.Random

class FaceGlitchEffectTest {

    private val effect = FaceGlitchEffect()

    /** Smear and tint on, so every face pixel is guaranteed to change. */
    private val strong = GlitchParams(lowThreshold = 0.12f, highThreshold = 0.92f, smear = 0.55f, streakLength = 0.25f, tintStrength = 0.6f)

    @Test
    fun `no faces leaves the image unchanged`() {
        val image = noise(64, 64)
        val output = effect.apply(image, emptyList(), GlitchParams())
        assertArrayEquals(image.pixels, output.pixels)
    }

    @Test
    fun `input image is never modified`() {
        val image = noise(120, 120)
        val before = image.pixels.copyOf()
        effect.apply(image, listOf(square(40f, 40f, 40f)), GlitchParams())
        assertArrayEquals(before, image.pixels)
    }

    @Test
    fun `pixels beyond the bleed are untouched and the face is changed`() {
        val image = noise(200, 200)
        val face = square(80f, 80f, 40f)
        val params = strong.copy(bleed = 0.08f, raggedness = 1f)
        val output = effect.apply(image, listOf(face), params)

        // Worst-case reach: bleed * (1 + raggedness) plus the feather and rounding.
        val maxReach = params.bleed * face.size * 2f + 1.5f + 2f
        var insideChanged = 0
        var insideTotal = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val i = y * image.width + x
                val d = distanceToSquare(x + 0.5f, y + 0.5f, 80f, 80f, 40f)
                if (d > maxReach) {
                    assertEquals("Pixel ($x, $y) is outside the face but changed", image.pixels[i], output.pixels[i])
                } else if (d == 0f) {
                    insideTotal++
                    if (image.pixels[i] != output.pixels[i]) insideChanged++
                }
            }
        }
        assertTrue("Expected most of the face to change, got $insideChanged/$insideTotal", insideChanged > insideTotal * 0.8)
    }

    @Test
    fun `same seed gives the same result and a new seed gives a different one`() {
        val image = noise(150, 150)
        val faces = listOf(square(30f, 30f, 90f))
        val a = effect.apply(image, faces, strong.copy(seed = 7))
        val b = effect.apply(image, faces, strong.copy(seed = 7))
        val c = effect.apply(image, faces, strong.copy(seed = 8))
        assertArrayEquals(a.pixels, b.pixels)
        assertFalse(a.pixels.contentEquals(c.pixels))
    }

    @Test
    fun `horizontal sort orders each row by brightness left to right`() {
        val output = sortOnly(angle = 0f)
        for (y in 60..140) {
            assertNonDecreasing((55..145).map { luminance(output.pixels[y * output.width + it]) }, "row $y")
        }
    }

    @Test
    fun `reversed angle sorts right to left`() {
        val output = sortOnly(angle = 180f)
        for (y in 60..140) {
            assertNonDecreasing((145 downTo 55).map { luminance(output.pixels[y * output.width + it]) }, "row $y")
        }
    }

    @Test
    fun `vertical sort orders each column by brightness top to bottom`() {
        val output = sortOnly(angle = 90f)
        for (x in 60..140) {
            assertNonDecreasing((55..145).map { luminance(output.pixels[it * output.width + x]) }, "column $x")
        }
    }

    @Test
    fun `trail runs only in the streak direction`() {
        val image = noise(200, 300)
        // Face spans x 80..120, y 40..80; streaks point down; trail of 2 face sizes = 80 px.
        val params = GlitchParams(angleDegrees = 90f, bleed = 0.05f, raggedness = 0f, trail = 2f)
        val output = effect.apply(image, listOf(square(80f, 40f, 40f)), params)
        val reach = 0.05f * 40f + 1.5f + 2f

        fun changed(x: Int, y: Int) = image.pixels[y * 200 + x] != output.pixels[y * 200 + x]

        // Above and beside the face (beyond the bleed), nothing changes.
        for (x in 0 until 200) for (y in 0 until (40 - reach).toInt()) assertFalse("($x, $y) above", changed(x, y))
        for (y in 0 until 300) {
            for (x in 0 until (80 - reach).toInt()) assertFalse("($x, $y) left", changed(x, y))
            for (x in (120 + reach).toInt() + 1 until 200) assertFalse("($x, $y) right", changed(x, y))
        }
        // Past the end of the trail, nothing changes.
        for (x in 0 until 200) for (y in (80 + reach + 80 + 2).toInt() until 300) assertFalse("($x, $y) below trail", changed(x, y))

        // Below the face, inside the trail, most pixels are replaced.
        var trailChanged = 0
        var trailTotal = 0
        for (x in 85..115) for (y in 90..150) {
            trailTotal++
            if (changed(x, y)) trailChanged++
        }
        assertTrue("trail changed $trailChanged/$trailTotal", trailChanged > trailTotal * 0.9)
    }

    @Test
    fun `trail follows the angle`() {
        val image = noise(300, 200)
        // Streaks pointing left: the trail should appear left of the face, not right.
        val params = GlitchParams(angleDegrees = 180f, bleed = 0f, raggedness = 0f, trail = 1.5f)
        val output = effect.apply(image, listOf(square(180f, 80f, 40f)), params)
        fun changed(x: Int, y: Int) = image.pixels[y * 300 + x] != output.pixels[y * 300 + x]
        val leftChanged = (140..170).count { x -> (85..115).any { y -> changed(x, y) } }
        val rightChanged = (230..290).count { x -> (85..115).any { y -> changed(x, y) } }
        assertTrue("left side changed in $leftChanged columns", leftChanged > 25)
        assertEquals(0, rightChanged)
    }

    @Test
    fun `speech pulse turns the face red only while someone speaks`() {
        val image = noise(120, 120)
        val faces = listOf(square(30f, 30f, 60f))
        val params = GlitchParams(speechPulse = 1f)
        val silent = effect.apply(image, faces, params, speechLevel = 0f)
        val speaking = effect.apply(image, faces, params, speechLevel = 1f)
        val pulseOff = effect.apply(image, faces, params.copy(speechPulse = 0f), speechLevel = 1f)

        fun redness(buffer: PixelBuffer): Double = (40 until 80).flatMap { y -> (40 until 80).map { x -> buffer.pixels[y * 120 + x] } }
            .sumOf { c -> ((c shr 16) and 0xFF) - (((c shr 8) and 0xFF) + (c and 0xFF)) / 2.0 }

        assertArrayEquals(silent.pixels, pulseOff.pixels)
        assertTrue("speaking ${redness(speaking)} vs silent ${redness(silent)}", redness(speaking) > redness(silent) + 40 * 40 * 40)
        // Outside the face nothing changes, pulse or not.
        assertEquals(image.pixels[5 * 120 + 5], speaking.pixels[5 * 120 + 5])
    }

    @Test
    fun `face partly outside the image does not crash`() {
        val image = noise(100, 100)
        val output = effect.apply(image, listOf(square(-30f, 70f, 60f)), GlitchParams(trail = 3f))
        assertEquals(image.pixels.size, output.pixels.size)
    }

    /** Sorting only (no smear, tint or bleed), across the full brightness range. */
    private fun sortOnly(angle: Float): PixelBuffer {
        val params = GlitchParams(
            angleDegrees = angle,
            lowThreshold = 0f,
            highThreshold = 1f,
            bleed = 0f,
            raggedness = 0f,
            smear = 0f,
            tintStrength = 0f,
        )
        return effect.apply(noise(200, 200), listOf(square(50f, 50f, 100f)), params)
    }

    private fun assertNonDecreasing(values: List<Int>, label: String) {
        for (i in 1 until values.size) {
            assertTrue("$label is not sorted at $i: ${values[i - 1]} > ${values[i]}", values[i - 1] <= values[i])
        }
    }

    private fun noise(width: Int, height: Int): PixelBuffer {
        val random = Random(42)
        return PixelBuffer(width, height, IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0x1000000) })
    }

    private fun square(left: Float, top: Float, size: Float) = FaceRegion(
        listOf(Point(left, top), Point(left + size, top), Point(left + size, top + size), Point(left, top + size)),
    )

    private fun distanceToSquare(x: Float, y: Float, left: Float, top: Float, size: Float): Float {
        val dx = max(max(left - x, 0f), x - (left + size))
        val dy = max(max(top - y, 0f), y - (top + size))
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
