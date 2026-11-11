package com.hoker.anonymizer.domain.effect

import com.hoker.anonymizer.domain.model.FaceRegion
import com.hoker.anonymizer.domain.model.PixelBuffer
import com.hoker.anonymizer.domain.model.Point
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A coarse grid laid over a face (about [CELLS_ACROSS] cells across it), used to hold a smooth,
 * low-detail lighting map. Working at this resolution keeps the maps
 * cheap enough to rebuild for every video frame, and they are read back with bilinear filtering.
 */
internal class FieldGrid private constructor(
    val left: Float,
    val top: Float,
    val cell: Float,
    val cols: Int,
    val rows: Int,
) {
    fun newField() = FloatArray(cols * rows)
    fun centerX(col: Int) = left + (col + 0.5f) * cell
    fun centerY(row: Int) = top + (row + 0.5f) * cell

    /** Bilinear read of [field] at image position (x, y); positions outside the grid use the nearest edge. */
    fun sample(field: FloatArray, x: Float, y: Float): Float {
        val gx = ((x - left) / cell - 0.5f).coerceIn(0f, cols - 1f)
        val gy = ((y - top) / cell - 0.5f).coerceIn(0f, rows - 1f)
        val x0 = gx.toInt().coerceAtMost(cols - 2).coerceAtLeast(0)
        val y0 = gy.toInt().coerceAtMost(rows - 2).coerceAtLeast(0)
        val x1 = min(x0 + 1, cols - 1)
        val y1 = min(y0 + 1, rows - 1)
        val tx = gx - x0
        val ty = gy - y0
        val a = field[y0 * cols + x0] + (field[y0 * cols + x1] - field[y0 * cols + x0]) * tx
        val b = field[y1 * cols + x0] + (field[y1 * cols + x1] - field[y1 * cols + x0]) * tx
        return a + (b - a) * ty
    }

    /** 1 for cells whose centre is inside the face outline, 0 elsewhere. */
    fun insideField(contour: List<Point>): FloatArray {
        val field = newField()
        for (row in 0 until rows) {
            val y = centerY(row)
            for (col in 0 until cols) {
                val x = centerX(col)
                var inside = false
                for (i in contour.indices) {
                    val a = contour[i]
                    val b = contour[(i + 1) % contour.size]
                    if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
                }
                if (inside) field[row * cols + col] = 1f
            }
        }
        return field
    }

    /** Box blur in place, [passes] times (two passes is close to a Gaussian). Edges average what exists. */
    fun blur(field: FloatArray, radius: Int, passes: Int = 2) {
        if (radius < 1) return
        val scratch = FloatArray(max(cols, rows))
        repeat(passes) {
            for (row in 0 until rows) blurLine(field, row * cols, 1, cols, radius, scratch)
            for (col in 0 until cols) blurLine(field, col, cols, rows, radius, scratch)
        }
    }

    private fun blurLine(field: FloatArray, offset: Int, stride: Int, length: Int, radius: Int, scratch: FloatArray) {
        for (i in 0 until length) scratch[i] = field[offset + i * stride]
        var sum = 0f
        var from = 0
        var to = -1
        for (i in 0 until length) {
            val wantFrom = max(0, i - radius)
            val wantTo = min(length - 1, i + radius)
            while (to < wantTo) sum += scratch[++to]
            while (from < wantFrom) sum -= scratch[from++]
            field[offset + i * stride] = sum / (to - from + 1)
        }
    }

    companion object {
        private const val CELLS_ACROSS = 56f

        /** A grid covering the face plus [padding] pixels on every side. */
        fun around(face: FaceRegion, padding: Float): FieldGrid {
            val cell = max(1f, face.size / CELLS_ACROSS)
            val left = face.bounds.left - padding
            val top = face.bounds.top - padding
            val cols = ceil((face.bounds.width + 2 * padding) / cell).toInt().coerceAtLeast(2)
            val rows = ceil((face.bounds.height + 2 * padding) / cell).toInt().coerceAtLeast(2)
            return FieldGrid(left, top, cell, cols, rows)
        }
    }
}

/**
 * The face's own light and shadow, with all detail blurred away: a brightness multiplier for each
 * pixel, above 1 where the real face was brighter than its average and below 1 where it was darker.
 *
 * Sorting moves pixels along the streak direction and so flattens the face's lighting, which is a
 * large part of why the glitch can look pasted on. Multiplying this back over the sorted pixels
 * lights the streaks like the head underneath them. The blur is wide enough (about a seventh of
 * the face) that features such as eyes are not brought back, only broad shading.
 */
internal class FaceShading private constructor(private val grid: FieldGrid, private val gain: FloatArray) {

    fun gainAt(x: Int, y: Int): Float = grid.sample(gain, x + 0.5f, y + 0.5f)

    companion object {
        private const val BLUR_FRACTION = 0.14f
        private const val STRENGTH = 0.85f
        private const val MIN_RATIO = 0.4f
        private const val MAX_RATIO = 1.9f

        /** [source] must be the untouched image, not one that already has glitches drawn on it. */
        fun build(source: PixelBuffer, face: FaceRegion, padding: Float): FaceShading {
            val grid = FieldGrid.around(face, padding)
            val inside = grid.insideField(face.contour)
            val luma = grid.newField()
            var total = 0f
            var count = 0f
            for (row in 0 until grid.rows) {
                for (col in 0 until grid.cols) {
                    val i = row * grid.cols + col
                    if (inside[i] == 0f) continue
                    // Four taps per cell are plenty: the map is blurred heavily afterwards.
                    var sum = 0
                    for (tap in 0 until 4) {
                        val x = (grid.centerX(col) + (if (tap % 2 == 0) -0.25f else 0.25f) * grid.cell).toInt().coerceIn(0, source.width - 1)
                        val y = (grid.centerY(row) + (if (tap < 2) -0.25f else 0.25f) * grid.cell).toInt().coerceIn(0, source.height - 1)
                        sum += luminance(source.pixels[y * source.width + x])
                    }
                    luma[i] = sum / (4f * 255f)
                    total += luma[i]
                    count++
                }
            }
            val gain = grid.newField()
            if (count == 0f || total == 0f) {
                gain.fill(1f)
                return FaceShading(grid, gain)
            }
            val mean = total / count

            // Blur only face pixels (the background must not leak in), by blurring the values and
            // the coverage separately and dividing.
            val radius = (face.size * BLUR_FRACTION / grid.cell).toInt().coerceAtLeast(1)
            grid.blur(luma, radius)
            grid.blur(inside, radius)
            for (i in gain.indices) {
                val coverage = inside[i]
                if (coverage < 0.01f) {
                    gain[i] = 1f
                    continue
                }
                val ratio = (luma[i] / coverage / mean).coerceIn(MIN_RATIO, MAX_RATIO)
                // Far outside the face there is little to go on, so ease back to no change.
                val confidence = (coverage / 0.15f).coerceAtMost(1f)
                gain[i] = 1f + (ratio - 1f) * STRENGTH * confidence
            }
            return FaceShading(grid, gain)
        }
    }
}

