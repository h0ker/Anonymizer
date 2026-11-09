package com.hoker.anonymizer.domain.usecase

import com.hoker.anonymizer.core.dispatchers.DefaultDispatcher
import com.hoker.anonymizer.domain.effect.FaceGlitchEffect
import com.hoker.anonymizer.domain.model.FaceRegion
import com.hoker.anonymizer.domain.model.GlitchParams
import com.hoker.anonymizer.domain.model.PixelBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ApplyFaceGlitchUseCase @Inject constructor(
    private val effect: FaceGlitchEffect,
    @param:DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    /**
     * Returns a new image; [image] is left untouched. Cancels promptly if the caller is cancelled.
     * [speechLevel] previews the speech pulse (1 = peak).
     */
    suspend operator fun invoke(
        image: PixelBuffer,
        faces: List<FaceRegion>,
        params: GlitchParams,
        speechLevel: Float = 0f,
    ): PixelBuffer = withContext(dispatcher) {
        val context = coroutineContext
        effect.apply(image, faces, params, speechLevel) { context.ensureActive() }
    }
}
