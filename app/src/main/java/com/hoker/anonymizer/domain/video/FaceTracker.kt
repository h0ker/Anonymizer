package com.hoker.anonymizer.domain.video

import com.hoker.anonymizer.domain.model.Bounds
import com.hoker.anonymizer.domain.model.FaceRegion
import com.hoker.anonymizer.domain.model.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns per-frame face detections into steady tracks:
 *  - **Identity:** each face keeps the same id from frame to frame, so its glitch pattern stays the same.
 *  - **Smoothing:** outline points go through a One-Euro filter. It smooths heavily when a face is
 *    still (no jitter) and barely at all when it moves fast (no lag that would uncover the face).
 *  - **Hold:** if the detector misses a face for a moment (motion blur, a hand in front), the last
 *    known outline stays glitched for [holdUs] so the face isn't exposed for a few frames.
 *
 * Not thread-safe: feed frames in time order from one thread.
 */
class FaceTracker(
    private val holdUs: Long = 600_000L,
    private val minCutoffHz: Float = 1.2f,
    /** How much the smoothing loosens with speed, measured in face sizes per second. */
    private val speedResponse: Float = 3f,
) {
    private class Track(val id: Int, var region: FaceRegion, var lastSeenUs: Long, val filters: Array<OneEuroFilter>)

    private val tracks = mutableListOf<Track>()
    private var nextId = 0

    fun update(detections: List<FaceRegion>, timeUs: Long): List<FaceRegion> {
        val matchedTracks = HashSet<Track>()
        val matchedDetections = HashSet<Int>()

        // Greedy matching: the best-overlapping pairs first.
        val candidates = buildList {
            for ((d, detection) in detections.withIndex()) {
                for (track in tracks) {
                    val score = matchScore(track.region, detection)
                    if (score > 0f) add(Triple(score, track, d))
                }
            }
        }.sortedByDescending { it.first }

        for ((_, track, d) in candidates) {
            if (track in matchedTracks || d in matchedDetections) continue
            matchedTracks += track
            matchedDetections += d
            track.region = smooth(track, detections[d], timeUs)
            track.lastSeenUs = timeUs
        }

        detections.forEachIndexed { d, detection ->
            if (d !in matchedDetections) {
                val track = Track(nextId++, detection, timeUs, newFilters(detection))
                track.region = smooth(track, detection, timeUs)
                tracks += track
            }
        }

        tracks.removeAll { it !in matchedTracks && timeUs - it.lastSeenUs > holdUs }
        return tracks.map { it.region.copy(id = it.id) }
    }

    private fun smooth(track: Track, detection: FaceRegion, timeUs: Long): FaceRegion {
        if (detection.contour.size * 2 != track.filters.size) return detection
        val scale = detection.size.coerceAtLeast(1f)
        val points = detection.contour.mapIndexed { i, p ->
            Point(
                track.filters[i * 2].filter(p.x, timeUs, scale),
                track.filters[i * 2 + 1].filter(p.y, timeUs, scale),
            )
        }
        return FaceRegion(points, track.id)
    }

    private fun newFilters(region: FaceRegion) =
        Array(region.contour.size * 2) { OneEuroFilter(minCutoffHz, speedResponse) }

    /** > 0 when the detection plausibly belongs to the track; higher is a better match. */
    private fun matchScore(previous: FaceRegion, detection: FaceRegion): Float {
        val iou = previous.bounds.intersectionOverUnion(detection.bounds)
        if (iou >= MIN_IOU) return iou
        // Fast motion can leave no overlap between frames; fall back to centre distance.
        val distance = hypot(previous.bounds.centerX - detection.bounds.centerX, previous.bounds.centerY - detection.bounds.centerY)
        return if (distance < previous.size * MAX_CENTER_JUMP) FALLBACK_SCORE else 0f
    }

    private val Bounds.centerX get() = (left + right) / 2f
    private val Bounds.centerY get() = (top + bottom) / 2f

    private companion object {
        const val MIN_IOU = 0.2f
        const val MAX_CENTER_JUMP = 0.6f
        const val FALLBACK_SCORE = 0.01f
    }
}

/**
 * One-Euro filter (Casiez et al. 2012): a low-pass filter whose cutoff rises with speed.
 * Speed is divided by [scale] so the same settings work for small and large faces.
 */
internal class OneEuroFilter(
    private val minCutoffHz: Float,
    private val speedResponse: Float,
    private val derivativeCutoffHz: Float = 1f,
) {
    private var initialized = false
    private var previousValue = 0f
    private var previousDerivative = 0f
    private var previousTimeUs = 0L

    fun filter(value: Float, timeUs: Long, scale: Float): Float {
        if (!initialized) {
            initialized = true
            previousValue = value
            previousTimeUs = timeUs
            return value
        }
        val dt = ((timeUs - previousTimeUs) / 1_000_000f).takeIf { it > 0f } ?: DEFAULT_DT
        previousTimeUs = timeUs

        val derivative = (value - previousValue) / dt
        val smoothedDerivative = lerp(previousDerivative, derivative, alpha(derivativeCutoffHz, dt))
        previousDerivative = smoothedDerivative

        val cutoff = minCutoffHz + speedResponse * abs(smoothedDerivative) / scale
        val smoothed = lerp(previousValue, value, alpha(cutoff, dt))
        previousValue = smoothed
        return smoothed
    }

    private fun alpha(cutoffHz: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dt)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        const val DEFAULT_DT = 1f / 30f
    }
}
