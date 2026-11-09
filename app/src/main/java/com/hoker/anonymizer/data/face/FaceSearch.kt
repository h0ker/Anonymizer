package com.hoker.anonymizer.data.face

import com.hoker.anonymizer.domain.model.Bounds
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A crop of the frame, in pixels. */
internal data class Crop(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/**
 * Where to look for faces. MediaPipe shrinks whatever it's given to a small square before
 * detecting, so a face that's small in the full frame becomes invisible. Searching overlapping
 * square tiles at a few zoom levels makes every face reasonably large in at least one tile.
 */
internal object FaceSearch {

    /** Tile sizes as a fraction of the frame's shorter side. 1 = the whole frame. */
    val PHOTO_LEVELS = listOf(1f, 0.55f, 0.3f)

    /** Tiles overlap by this much, so a face cut by one tile's edge is whole in its neighbour. */
    private const val OVERLAP = 0.3f

    /** Below this size a tile adds nothing: MediaPipe already sees that much detail in larger tiles. */
    private const val MIN_TILE_PX = 192

    fun tiles(width: Int, height: Int, level: Float): List<Crop> {
        if (level >= 1f) return listOf(Crop(0, 0, width, height))
        val side = (min(width, height) * level).roundToInt()
        if (side < MIN_TILE_PX) return emptyList()
        val xs = positions(width, side)
        val ys = positions(height, side)
        return ys.flatMap { y -> xs.map { x -> Crop(x, y, side, side) } }
    }

    /** Evenly spread start positions so tiles of [side] cover [length] with at least [OVERLAP]. */
    private fun positions(length: Int, side: Int): List<Int> {
        if (side >= length) return listOf(0)
        val stride = side * (1f - OVERLAP)
        val count = ceil((length - side) / stride).toInt() + 1
        val step = (length - side).toFloat() / (count - 1)
        return (0 until count).map { (it * step).roundToInt() }
    }

    /** A square crop centred on a face with room around it, for re-detecting it close up. */
    fun around(face: Bounds, width: Int, height: Int, scale: Float = 2.4f): Crop {
        val side = (max(face.width, face.height) * scale).roundToInt()
            .coerceIn(MIN_TILE_PX.coerceAtMost(min(width, height)), min(width, height))
        val cx = (face.left + face.right) / 2f
        val cy = (face.top + face.bottom) / 2f
        val left = (cx - side / 2f).roundToInt().coerceIn(0, width - side)
        val top = (cy - side / 2f).roundToInt().coerceIn(0, height - side)
        return Crop(left, top, side, side)
    }

    /** True when the face runs into an edge of the crop (and that edge isn't the frame's edge), so it may be cut off. */
    fun touchesInnerEdge(face: Bounds, crop: Crop, frameWidth: Int, frameHeight: Int): Boolean {
        val margin = crop.width * 0.02f
        return (crop.left > 0 && face.left < crop.left + margin) ||
            (crop.top > 0 && face.top < crop.top + margin) ||
            (crop.right < frameWidth && face.right > crop.right - margin) ||
            (crop.bottom < frameHeight && face.bottom > crop.bottom - margin)
    }

    /**
     * Collapses detections of the same face (found by several overlapping tiles) into one.
     * Earlier items win, so callers pass the most trustworthy detections first.
     */
    fun <T> dedupe(items: List<T>, bounds: (T) -> Bounds): List<T> {
        val kept = mutableListOf<T>()
        for (item in items) {
            val b = bounds(item)
            if (kept.none { isSameFace(bounds(it), b) }) kept += item
        }
        return kept
    }

    private fun isSameFace(a: Bounds, b: Bounds): Boolean {
        if (a.intersectionOverUnion(b) > 0.3f) return true
        // A partial detection from a tile edge sits inside the full one.
        return a.contains(b.centerX, b.centerY) || b.contains(a.centerX, a.centerY)
    }

    private val Bounds.centerX get() = (left + right) / 2f
    private val Bounds.centerY get() = (top + bottom) / 2f
    private fun Bounds.contains(x: Float, y: Float) = x in left..right && y in top..bottom
}
