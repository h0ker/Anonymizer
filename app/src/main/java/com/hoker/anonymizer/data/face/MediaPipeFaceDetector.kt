package com.hoker.anonymizer.data.face

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.hoker.anonymizer.core.dispatchers.DefaultDispatcher
import com.hoker.anonymizer.domain.model.FaceRegion
import com.hoker.anonymizer.domain.model.PixelBuffer
import com.hoker.anonymizer.domain.repository.FaceDetector
import com.hoker.anonymizer.domain.repository.VideoFaceDetectionSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds faces with MediaPipe Face Landmarker (478 points per face) and keeps only the
 * outline points, so the glitch follows the real shape of the head instead of a box.
 * Searching in zoomed-in crops ([CropFaceFinder]) finds faces that are small in the frame.
 */
@Singleton
class MediaPipeFaceDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : FaceDetector {

    // FaceLandmarker isn't thread-safe, so calls are serialized.
    private val mutex = Mutex()
    private var finder: CropFaceFinder? = null

    override suspend fun detect(image: PixelBuffer): List<FaceRegion> = withContext(dispatcher) {
        mutex.withLock {
            val search = finder ?: CropFaceFinder(createLandmarker()).also { finder = it }
            val bitmap = image.toBitmap()
            try {
                search.search(bitmap, FaceSearch.PHOTO_LEVELS).withIndexIds()
            } finally {
                bitmap.recycle()
            }
        }
    }

    override fun openVideoSession(): VideoFaceDetectionSession = CropTrackingSession()

    /**
     * Follows faces through a video. Each frame, every known face is re-detected in a crop
     * around where it was, which is cheap and works for small faces. A full tiled search for
     * new faces runs every few frames (the deepest zoom less often, since it's the costliest).
     * Between searches, [com.hoker.anonymizer.domain.video.FaceTracker] smooths and holds faces.
     */
    private inner class CropTrackingSession : VideoFaceDetectionSession {
        private val landmarker = createLandmarker()
        private val finder = CropFaceFinder(landmarker)
        private var known: List<FaceRegion> = emptyList()
        private var frameIndex = 0

        override fun detect(frame: PixelBuffer, timestampMs: Long): List<FaceRegion> {
            val bitmap = frame.toBitmap()
            try {
                val followed = known.mapNotNull { finder.refine(bitmap, it.bounds) }
                val levels = when {
                    frameIndex % DEEP_SEARCH_INTERVAL == 0 -> FaceSearch.PHOTO_LEVELS
                    frameIndex % SEARCH_INTERVAL == 0 -> SHALLOW_LEVELS
                    else -> emptyList()
                }
                val found = if (levels.isEmpty()) emptyList() else finder.search(bitmap, levels)
                known = FaceSearch.dedupe(followed + found) { it.bounds }
                frameIndex++
                return known.withIndexIds()
            } finally {
                bitmap.recycle()
            }
        }

        override fun close() = landmarker.close()
    }

    private fun createLandmarker(): FaceLandmarker {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(MAX_FACES)
            .setMinFaceDetectionConfidence(MIN_CONFIDENCE)
            .setMinFacePresenceConfidence(MIN_CONFIDENCE)
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    private fun PixelBuffer.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

    private fun List<FaceRegion>.withIndexIds() = mapIndexed { index, face -> face.copy(id = index) }

    private companion object {
        const val MODEL_ASSET = "face_landmarker.task"
        const val MAX_FACES = 20

        /** Searching many tiles means more chances of a false hit, so the bar is a little higher. */
        const val MIN_CONFIDENCE = 0.5f

        /** Whole frame plus medium tiles, every few frames, to pick up faces entering the shot. */
        val SHALLOW_LEVELS = listOf(1f, 0.55f)
        const val SEARCH_INTERVAL = 5
        const val DEEP_SEARCH_INTERVAL = 15
    }
}
