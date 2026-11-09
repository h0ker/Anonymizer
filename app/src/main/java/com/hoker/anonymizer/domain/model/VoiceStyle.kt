package com.hoker.anonymizer.domain.model

/** Voice disguises for exported video audio. All of them move the speaker's real pitch. */
enum class VoiceStyle {
    /** Layered pitch-shifted voices, metallic ring and syllable stutters: a menacing station AI. */
    ROGUE_AI,

    /** Classic ring-modulated, bit-reduced robot. */
    SYNTHETIC,

    /** Deep, heavily driven industrial voice. */
    LOW_MACHINE,

    /** Bit-crushed and frequently glitching, like a damaged transmission. */
    CORRUPTED,
}
