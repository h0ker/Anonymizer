package com.hoker.anonymizer.domain.effect

/** Perceived brightness of an ARGB colour, 0..255 (Rec. 709 weights, integer maths). */
internal fun luminance(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (54 * r + 183 * g + 19 * b) shr 8
}

/** Linear blend between two ARGB colours; t = 0 gives [from], t = 1 gives [to]. */
internal fun lerpColor(from: Int, to: Int, t: Float): Int {
    if (t <= 0f) return from
    if (t >= 1f) return to
    val a = channel(from, to, 24, t)
    val r = channel(from, to, 16, t)
    val g = channel(from, to, 8, t)
    val b = channel(from, to, 0, t)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun channel(from: Int, to: Int, shift: Int, t: Float): Int {
    val f = (from shr shift) and 0xFF
    val e = (to shr shift) and 0xFF
    return (f + (e - f) * t + 0.5f).toInt().coerceIn(0, 255)
}
