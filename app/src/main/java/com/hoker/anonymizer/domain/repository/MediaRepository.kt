package com.hoker.anonymizer.domain.repository

import com.hoker.anonymizer.domain.model.MediaKind
import com.hoker.anonymizer.domain.model.MediaUri
import com.hoker.anonymizer.domain.model.PixelBuffer

interface MediaRepository {
    /** Whether the item is a photo or a video, or null if it's neither. */
    suspend fun getKind(uri: MediaUri): MediaKind?

    /** Decodes an image, upright, scaled down so its longest side is at most [maxDimension]. */
    suspend fun loadImage(uri: MediaUri, maxDimension: Int): PixelBuffer

    /**
     * Saves the image to the user's gallery as a JPEG. With [copyMetadataFrom] null the file
     * has no metadata at all; otherwise the EXIF data (location, camera, time…) of that item
     * is carried over.
     */
    suspend fun saveImage(image: PixelBuffer, copyMetadataFrom: MediaUri?): MediaUri
}
