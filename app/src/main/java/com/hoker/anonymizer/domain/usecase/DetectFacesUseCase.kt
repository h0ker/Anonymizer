package com.hoker.anonymizer.domain.usecase

import com.hoker.anonymizer.domain.model.FaceRegion
import com.hoker.anonymizer.domain.model.PixelBuffer
import com.hoker.anonymizer.domain.repository.FaceDetector
import javax.inject.Inject

class DetectFacesUseCase @Inject constructor(
    private val faceDetector: FaceDetector,
) {
    suspend operator fun invoke(image: PixelBuffer): List<FaceRegion> =
        faceDetector.detect(image)
}
