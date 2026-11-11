package com.hoker.anonymizer.domain.model

/**
 * Tunable settings for the face glitch. Lengths are fractions of the face size so the
 * look stays consistent regardless of how large the face is in the frame.
 */
data class GlitchParams(
    /** Direction the streaks run, in degrees. 0 = left to right, 90 = downward. */
    val angleDegrees: Float = 90f,
    /** Only pixels whose brightness falls in [lowThreshold, highThreshold] get sorted. */
    val lowThreshold: Float = 0f,
    val highThreshold: Float = 0.63f,
    /** How far the glitch reaches past the face outline in every direction, as a fraction of face size. */
    val bleed: Float = 0.10f,
    /**
     * How far the glitch drags past the face in the streak direction only, as a fraction of face
     * size. 0 = off; 2 = streaks run two face-heights beyond the face.
     */
    val trail: Float = 0f,
    /** 0 = smooth edge, 1 = very spiky edge where individual streaks poke out unevenly. */
    val raggedness: Float = 0.98f,
    /** 0..1: amount of pixel smearing, which wipes out features like eyes. */
    val smear: Float = 0f,
    /** Maximum smear length, as a fraction of face size. */
    val streakLength: Float = 0.05f,
    /** 0..1: strength of the duotone colour grade applied inside the glitch. */
    val tintStrength: Float = 0f,
    val tintShadow: Int = 0xFF2B1A7A.toInt(),
    val tintHighlight: Int = 0xFFF7DCFF.toInt(),
    /** Videos only. 0 = off; up to 1 = how strongly the glitch flushes red while someone speaks. */
    val speechPulse: Float = 0f,
    /**
     * Videos only. How many times per second the glitch pattern reshuffles, so it moves like a
     * glitch rather than sitting on the face like a sticker. 0 = static; at or above the video's
     * frame rate it changes every frame.
     */
    val shufflesPerSecond: Float = 30f,
    /** Light the streaks with the face's own (heavily blurred) light and shadow, so they don't look flat. */
    val faceLighting: Boolean = false,
    /** Seed for the random parts of the effect, so a given result is reproducible. */
    val seed: Int = 1,
)
