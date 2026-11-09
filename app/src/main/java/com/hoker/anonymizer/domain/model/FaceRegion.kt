package com.hoker.anonymizer.domain.model

import kotlin.math.max

data class Point(val x: Float, val y: Float)

data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)

    fun intersectionOverUnion(other: Bounds): Float {
        val overlap = Bounds(
            left = max(left, other.left),
            top = max(top, other.top),
            right = minOf(right, other.right),
            bottom = minOf(bottom, other.bottom),
        ).let { if (it.width <= 0f || it.height <= 0f) 0f else it.area }
        val union = area + other.area - overlap
        return if (union <= 0f) 0f else overlap / union
    }
}

/**
 * A detected face, described by the closed polygon of its outline (forehead, cheeks, jaw)
 * in pixel coordinates of the image it was detected in.
 *
 * [id] identifies the face: its index in a photo, or its track across video frames. It seeds
 * the face's random pattern, so the same face keeps the same look from frame to frame.
 */
data class FaceRegion(val contour: List<Point>, val id: Int = 0) {

    init {
        require(contour.size >= 3) { "A face outline needs at least 3 points" }
    }

    val bounds: Bounds = Bounds(
        left = contour.minOf { it.x },
        top = contour.minOf { it.y },
        right = contour.maxOf { it.x },
        bottom = contour.maxOf { it.y },
    )

    /** Characteristic size used to scale effect parameters, in pixels. */
    val size: Float get() = max(bounds.width, bounds.height)
}
