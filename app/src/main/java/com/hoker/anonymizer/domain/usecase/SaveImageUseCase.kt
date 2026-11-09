package com.hoker.anonymizer.domain.usecase

import com.hoker.anonymizer.domain.model.MediaUri
import com.hoker.anonymizer.domain.model.PixelBuffer
import com.hoker.anonymizer.domain.repository.MediaRepository
import javax.inject.Inject

class SaveImageUseCase @Inject constructor(
    private val mediaRepository: MediaRepository,
) {
    /** With [scrubMetadata] the saved photo carries no EXIF; otherwise it keeps [source]'s. */
    suspend operator fun invoke(image: PixelBuffer, source: MediaUri, scrubMetadata: Boolean): MediaUri =
        mediaRepository.saveImage(image, copyMetadataFrom = source.takeUnless { scrubMetadata })
}
