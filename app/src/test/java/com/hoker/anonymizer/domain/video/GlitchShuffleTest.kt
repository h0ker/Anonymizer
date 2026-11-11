package com.hoker.anonymizer.domain.video

import com.hoker.anonymizer.domain.effect.FaceGlitchEffect
import com.hoker.anonymizer.domain.model.FaceRegion
import com.hoker.anonymizer.domain.model.GlitchParams
import com.hoker.anonymizer.domain.model.PixelBuffer
import com.hoker.anonymizer.domain.model.Point
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GlitchShuffleTest {

    @Test
    fun `shuffle step advances at the requested rate regardless of frame rate`() {
        // 30 shuffles a second: every frame at 30 fps, every other frame at 60 fps.
        val at30fps = (0 until 30).map { FaceGlitchFrameTransform.shuffleStep(it * 33_333L, 30f) }
        val at60fps = (0 until 60).map { FaceGlitchFrameTransform.shuffleStep(it * 16_667L, 30f) }
        assertTrue("30 fps should change every frame: $at30fps", at30fps.zipWithNext().all { (a, b) -> a != b })
        val changes60 = at60fps.zipWithNext().count { (a, b) -> a != b }
        assertTrue("60 fps should change about every other frame, changed $changes60 times", changes60 in 29..31)
    }

    @Test
    fun `zero rate keeps one pattern for the whole video`() {
        val steps = (0 until 300).map { FaceGlitchFrameTransform.shuffleStep(it * 33_333L, 0f) }
        assertEquals(setOf(0), steps.toSet())
    }

    @Test
    fun `a new step reshuffles the glitch, the same step repeats it`() {
        val random = Random(9)
        val image = PixelBuffer(120, 120, IntArray(120 * 120) { 0xFF000000.toInt() or random.nextInt(0x1000000) })
        val face = FaceRegion(listOf(Point(30f, 30f), Point(90f, 30f), Point(90f, 90f), Point(30f, 90f)))
        val params = GlitchParams(trail = 0.5f, smear = 0.4f)
        val effect = FaceGlitchEffect()
        val step1 = effect.apply(image, listOf(face), params, variation = 1)
        val step1Again = effect.apply(image, listOf(face), params, variation = 1)
        val step2 = effect.apply(image, listOf(face), params, variation = 2)
        assertArrayEquals(step1.pixels, step1Again.pixels)
        assertFalse(step1.pixels.contentEquals(step2.pixels))
    }
}
