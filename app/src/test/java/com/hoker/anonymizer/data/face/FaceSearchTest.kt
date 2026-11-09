package com.hoker.anonymizer.data.face

import com.hoker.anonymizer.domain.model.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceSearchTest {

    @Test
    fun `whole-frame level is a single crop of the full image`() {
        assertEquals(listOf(Crop(0, 0, 1920, 2560)), FaceSearch.tiles(1920, 2560, 1f))
    }

    @Test
    fun `tiles cover the whole frame with overlap and stay inside it`() {
        val (w, h) = 1920 to 2560
        for (level in listOf(0.55f, 0.3f)) {
            val tiles = FaceSearch.tiles(w, h, level)
            assertTrue(tiles.isNotEmpty())
            tiles.forEach { assertTrue("$it out of frame", it.left >= 0 && it.top >= 0 && it.right <= w && it.bottom <= h) }
            // Every pixel is covered.
            for (y in 0 until h step 37) for (x in 0 until w step 37) {
                assertTrue("($x,$y) at $level", tiles.any { x >= it.left && x < it.right && y >= it.top && y < it.bottom })
            }
            // Any face up to 30% of a tile fits wholly inside at least one tile.
            val side = tiles.first().width
            val face = (side * 0.3f).toInt()
            for (y in 0..(h - face) step 53) for (x in 0..(w - face) step 53) {
                assertTrue(tiles.any { x >= it.left && x + face <= it.right && y >= it.top && y + face <= it.bottom })
            }
        }
    }

    @Test
    fun `tiny images skip the zoomed levels`() {
        assertTrue(FaceSearch.tiles(300, 400, 0.3f).isEmpty())
    }

    @Test
    fun `crop around a face is centred, square and clamped to the frame`() {
        val crop = FaceSearch.around(Bounds(100f, 100f, 200f, 220f), 1000, 1000)
        assertEquals(crop.width, crop.height)
        assertEquals(288, crop.width)
        assertEquals(6, crop.left) // centre 150 minus 144
        val edge = FaceSearch.around(Bounds(0f, 0f, 50f, 50f), 1000, 1000)
        assertEquals(0, edge.left)
        assertEquals(0, edge.top)
    }

    @Test
    fun `duplicates from overlapping tiles collapse, keeping the first`() {
        val full = Bounds(100f, 100f, 200f, 220f)
        val shifted = Bounds(104f, 102f, 203f, 221f)
        val partial = Bounds(150f, 100f, 200f, 220f) // cut by a tile edge, centre inside full
        val other = Bounds(600f, 100f, 700f, 220f)
        val kept = FaceSearch.dedupe(listOf(full, shifted, partial, other)) { it }
        assertEquals(listOf(full, other), kept)
    }

    @Test
    fun `faces at an inner tile edge are flagged, frame edges are not`() {
        val crop = Crop(500, 0, 500, 500)
        assertTrue(FaceSearch.touchesInnerEdge(Bounds(501f, 100f, 600f, 200f), crop, 2000, 2000))
        assertFalse(FaceSearch.touchesInnerEdge(Bounds(600f, 0f, 700f, 100f), crop, 2000, 2000))
        assertFalse(FaceSearch.touchesInnerEdge(Bounds(600f, 100f, 700f, 200f), crop, 2000, 2000))
    }
}
