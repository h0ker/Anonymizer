package com.hoker.anonymizer.domain.effect

import com.hoker.anonymizer.domain.model.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class FaceMaskTest {

    private val square = listOf(Point(10f, 10f), Point(30f, 10f), Point(30f, 30f), Point(10f, 30f))

    @Test
    fun `distance is zero inside and grows outside`() {
        val mask = FaceMask.build(square, padding = 10f, imageWidth = 50, imageHeight = 50)
        assertNotNull(mask)
        mask!!
        assertEquals(0f, mask.distanceAt(20, 20), 0f)
        assertEquals(0f, mask.distanceAt(10, 10), 0f)
        // Last inside column is 29, so column 35 is 6 pixels away.
        assertEquals(6f, mask.distanceAt(35, 20), 0.01f)
        assertEquals(6f * sqrt(2f), mask.distanceAt(35, 35), 0.01f)
    }

    @Test
    fun `pixels outside the padded box are infinitely far`() {
        val mask = FaceMask.build(square, padding = 5f, imageWidth = 50, imageHeight = 50)!!
        assertEquals(Float.POSITIVE_INFINITY, mask.distanceAt(45, 45), 0f)
    }

    @Test
    fun `mask is clipped to the image`() {
        val mask = FaceMask.build(square, padding = 20f, imageWidth = 50, imageHeight = 50)!!
        assertEquals(0, mask.left)
        assertEquals(0, mask.top)
        assertEquals(49, mask.right)
        assertEquals(49, mask.bottom)
    }

    @Test
    fun `face entirely off screen gives no mask`() {
        val offscreen = square.map { Point(it.x + 200f, it.y) }
        assertNull(FaceMask.build(offscreen, padding = 5f, imageWidth = 50, imageHeight = 50))
    }
}
