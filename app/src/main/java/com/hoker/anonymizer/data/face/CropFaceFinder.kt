package com.hoker.anonymizer.data.face

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.hoker.anonymizer.domain.model.Bounds
import com.hoker.anonymizer.domain.model.FaceRegion
import com.hoker.anonymizer.domain.model.Point
import kotlin.math.hypot

/**
 * Runs Face Landmarker on crops of a frame instead of the whole frame, so small faces are found.
 *
 * - [search] scans tiles at several zoom levels, merges the duplicates that overlapping tiles
 *   produce, then re-detects each face in a crop centred on it for a clean outline.
 * - [refine] re-detects one known face close up; video uses it to follow faces between frames.
 *
 * The landmarker must be in IMAGE mode and is only used from one thread at a time.
 */
internal class CropFaceFinder(private val landmarker: FaceLandmarker) {

    private class Candidate(val region: FaceRegion, val atTileEdge: Boolean)

    fun search(frame: Bitmap, levels: List<Float>): List<FaceRegion> {
        val candidates = buildList {
            for (level in levels) {
                for (crop in FaceSearch.tiles(frame.width, frame.height, level)) {
                    detectIn(frame, crop).forEach { region ->
                        add(Candidate(region, FaceSearch.touchesInnerEdge(region.bounds, crop, frame.width, frame.height)))
                    }
                }
            }
        }
        // Whole faces before ones cut by a tile edge; among those, the larger (more complete) first.
        val merged = FaceSearch.dedupe(
            candidates.sortedWith(compareBy<Candidate> { it.atTileEdge }.thenByDescending { it.region.bounds.area }),
        ) { it.region.bounds }
        val refined = merged.map { refine(frame, it.region.bounds) ?: it.region }
        return FaceSearch.dedupe(refined) { it.bounds }
    }

    /** Looks for the face near [previous] in a crop around it. Null if it's no longer there. */
    fun refine(frame: Bitmap, previous: Bounds): FaceRegion? {
        val crop = FaceSearch.around(previous, frame.width, frame.height)
        val size = maxOf(previous.width, previous.height)
        return detectIn(frame, crop)
            .minByOrNull { distance(it.bounds, previous) }
            ?.takeIf { distance(it.bounds, previous) < size * MAX_REFINE_SHIFT }
    }

    private fun detectIn(frame: Bitmap, crop: Crop): List<FaceRegion> {
        // Always a separate bitmap: closing an MPImage recycles the bitmap inside it, and
        // createBitmap may hand back the frame itself for a full-size crop.
        val bitmap = if (crop.width == frame.width && crop.height == frame.height) {
            frame.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            Bitmap.createBitmap(frame, crop.left, crop.top, crop.width, crop.height)
        }
        val result = BitmapImageBuilder(bitmap).build().use { landmarker.detect(it) }
        return result.faceLandmarks().map { landmarks ->
            FaceRegion(
                FACE_OVAL.map { index ->
                    val landmark = landmarks[index]
                    Point(crop.left + landmark.x() * crop.width, crop.top + landmark.y() * crop.height)
                },
            )
        }
    }

    private fun distance(a: Bounds, b: Bounds) =
        hypot((a.left + a.right - b.left - b.right) / 2f, (a.top + a.bottom - b.top - b.bottom) / 2f)

    companion object {
        /** A face that moved further than this (in face sizes) between frames is treated as a different face. */
        private const val MAX_REFINE_SHIFT = 0.8f

        /** Face-mesh landmark indices tracing the face outline, in order around the face. */
        val FACE_OVAL = intArrayOf(
            10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377,
            152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109,
        ).toList()
    }
}
