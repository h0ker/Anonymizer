package com.hoker.anonymizer.domain.effect

import com.hoker.anonymizer.domain.model.Point
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * For each pixel near a face, the distance (in pixels) to the face outline: 0 inside the face,
 * growing outward. Only a bounding box around the face (plus [padding]) is stored; anything
 * outside it reports infinity.
 */
internal class FaceMask private constructor(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    private val distance: FloatArray,
) {
    val right: Int get() = left + width - 1
    val bottom: Int get() = top + height - 1

    fun distanceAt(x: Int, y: Int): Float {
        val lx = x - left
        val ly = y - top
        if (lx < 0 || ly < 0 || lx >= width || ly >= height) return Float.POSITIVE_INFINITY
        return distance[ly * width + lx]
    }

    companion object {
        private val DIAGONAL = sqrt(2f)

        /**
         * Returns null when the padded face lies entirely outside the image. The box is also
         * stretched by ([extendX], [extendY]) so a one-directional trail has room to run.
         */
        fun build(
            contour: List<Point>,
            padding: Float,
            imageWidth: Int,
            imageHeight: Int,
            extendX: Float = 0f,
            extendY: Float = 0f,
        ): FaceMask? {
            val left = floor(contour.minOf { it.x } - padding + min(0f, extendX)).toInt().coerceAtLeast(0)
            val top = floor(contour.minOf { it.y } - padding + min(0f, extendY)).toInt().coerceAtLeast(0)
            val right = ceil(contour.maxOf { it.x } + padding + max(0f, extendX)).toInt().coerceAtMost(imageWidth - 1)
            val bottom = ceil(contour.maxOf { it.y } + padding + max(0f, extendY)).toInt().coerceAtMost(imageHeight - 1)
            if (right < left || bottom < top) return null

            val width = right - left + 1
            val height = bottom - top + 1
            val distance = FloatArray(width * height) { Float.POSITIVE_INFINITY }
            fillPolygon(contour, left, top, width, height, distance)
            chamferDistance(distance, width, height)
            return FaceMask(left, top, width, height, distance)
        }

        /** Even-odd scanline fill, sampling at pixel centres. Inside pixels get distance 0. */
        private fun fillPolygon(
            contour: List<Point>,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
            distance: FloatArray,
        ) {
            val crossings = FloatArray(contour.size)
            for (row in 0 until height) {
                val sy = top + row + 0.5f
                var count = 0
                for (i in contour.indices) {
                    val a = contour[i]
                    val b = contour[(i + 1) % contour.size]
                    if ((a.y <= sy && b.y > sy) || (b.y <= sy && a.y > sy)) {
                        crossings[count++] = a.x + (sy - a.y) / (b.y - a.y) * (b.x - a.x)
                    }
                }
                crossings.sort(0, count)
                var i = 0
                while (i + 1 < count) {
                    val start = ceil(crossings[i] - 0.5f - left).toInt().coerceAtLeast(0)
                    val end = (ceil(crossings[i + 1] - 0.5f - left).toInt() - 1).coerceAtMost(width - 1)
                    for (col in start..end) distance[row * width + col] = 0f
                    i += 2
                }
            }
        }

        /** Two-pass 8-neighbour chamfer transform: a fast approximation of Euclidean distance. */
        private fun chamferDistance(d: FloatArray, w: Int, h: Int) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    var v = d[i]
                    if (v == 0f) continue
                    if (x > 0) v = min(v, d[i - 1] + 1f)
                    if (y > 0) {
                        v = min(v, d[i - w] + 1f)
                        if (x > 0) v = min(v, d[i - w - 1] + DIAGONAL)
                        if (x < w - 1) v = min(v, d[i - w + 1] + DIAGONAL)
                    }
                    d[i] = v
                }
            }
            for (y in h - 1 downTo 0) {
                for (x in w - 1 downTo 0) {
                    val i = y * w + x
                    var v = d[i]
                    if (v == 0f) continue
                    if (x < w - 1) v = min(v, d[i + 1] + 1f)
                    if (y < h - 1) {
                        v = min(v, d[i + w] + 1f)
                        if (x < w - 1) v = min(v, d[i + w + 1] + DIAGONAL)
                        if (x > 0) v = min(v, d[i + w - 1] + DIAGONAL)
                    }
                    d[i] = v
                }
            }
        }
    }
}
