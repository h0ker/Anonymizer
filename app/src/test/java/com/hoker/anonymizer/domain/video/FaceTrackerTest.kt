package com.hoker.anonymizer.domain.video

import com.hoker.anonymizer.domain.model.FaceRegion
import com.hoker.anonymizer.domain.model.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class FaceTrackerTest {

    private val frameUs = 33_333L

    @Test
    fun `a face moving slowly keeps the same id`() {
        val tracker = FaceTracker()
        val ids = (0 until 30).map { frame ->
            tracker.update(listOf(square(100f + frame * 3f, 100f, 80f)), frame * frameUs).single().id
        }
        assertEquals(1, ids.toSet().size)
    }

    @Test
    fun `a briefly missed face is held, then dropped after the hold time`() {
        val tracker = FaceTracker(holdUs = 200_000L)
        tracker.update(listOf(square(100f, 100f, 80f)), 0L)
        assertEquals(1, tracker.update(emptyList(), 100_000L).size)
        assertEquals(1, tracker.update(emptyList(), 200_000L).size)
        assertEquals(0, tracker.update(emptyList(), 250_000L).size)
    }

    @Test
    fun `two faces get different ids that stay stable`() {
        val tracker = FaceTracker()
        val first = tracker.update(listOf(square(50f, 50f, 60f), square(300f, 50f, 60f)), 0L)
        // Same faces, reported in the opposite order.
        val second = tracker.update(listOf(square(302f, 50f, 60f), square(52f, 50f, 60f)), frameUs)
        assertNotEquals(first[0].id, first[1].id)
        val leftId = first.minBy { it.bounds.left }.id
        assertEquals(leftId, second.minBy { it.bounds.left }.id)
    }

    @Test
    fun `fast motion with no overlap still matches the same face`() {
        val tracker = FaceTracker()
        val a = tracker.update(listOf(square(100f, 100f, 80f)), 0L).single()
        val b = tracker.update(listOf(square(140f, 100f, 80f)), frameUs).single()
        assertEquals(a.id, b.id)
    }

    @Test
    fun `jitter on a still face is reduced`() {
        val tracker = FaceTracker()
        val random = Random(3)
        var rawError = 0f
        var smoothedError = 0f
        for (frame in 0 until 60) {
            val jitter = random.nextFloat() * 6f - 3f
            val out = tracker.update(listOf(square(100f + jitter, 100f, 80f)), frame * frameUs).single()
            if (frame >= 10) {
                rawError += abs(jitter)
                smoothedError += abs(out.bounds.left - 100f)
            }
        }
        assertTrue("smoothed $smoothedError should be well below raw $rawError", smoothedError < rawError * 0.6f)
    }

    @Test
    fun `smoothing does not lag far behind a fast-moving face`() {
        val tracker = FaceTracker()
        var last: FaceRegion? = null
        for (frame in 0 until 20) {
            last = tracker.update(listOf(square(100f + frame * 20f, 100f, 80f)), frame * frameUs).single()
        }
        val trueLeft = 100f + 19 * 20f
        // Moving 20 px per frame; stay within about one face-quarter of the real position.
        assertTrue("lag ${trueLeft - last!!.bounds.left} px", trueLeft - last.bounds.left < 20f)
    }

    private fun square(left: Float, top: Float, size: Float) = FaceRegion(
        listOf(Point(left, top), Point(left + size, top), Point(left + size, top + size), Point(left, top + size)),
    )
}
