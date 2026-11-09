package com.hoker.anonymizer.domain.usecase

import com.hoker.anonymizer.domain.model.MediaUri
import com.hoker.anonymizer.domain.model.PixelBuffer
import com.hoker.anonymizer.domain.repository.MediaRepository
import javax.inject.Inject

class LoadImageUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(uri: MediaUri): PixelBuffer =
        mediaRepository.loadImage(uri, MAX_DIMENSION)

    companion object {
        /** Editing and export share one resolution so what you see is exactly what gets saved. */
        const val MAX_DIMENSION = 2560
    }
}
