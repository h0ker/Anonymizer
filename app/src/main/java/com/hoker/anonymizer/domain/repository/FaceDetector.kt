package com.hoker.anonymizer.domain.repository

import com.hoker.anonymizer.domain.model.FaceRegion
import com.hoker.anonymizer.domain.model.PixelBuffer

interface FaceDetector {
    /** Returns the outline of every face found, in the image's pixel coordinates. */
    suspend fun detect(image: PixelBuffer): List<FaceRegion>

    /** Starts detection over consecutive video frames. Close the session when done. */
    fun openVideoSession(): VideoFaceDetectionSession
}

/**
 * Detects faces in a stream of frames that arrive in time order. Calls block and must come
 * from one thread at a time.
 */
interface VideoFaceDetectionSession : AutoCloseable {
    fun detect(frame: PixelBuffer, timestampMs: Long): List<FaceRegion>
}
