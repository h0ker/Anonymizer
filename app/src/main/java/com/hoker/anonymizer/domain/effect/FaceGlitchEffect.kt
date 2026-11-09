package com.hoker.anonymizer.domain.effect

import com.hoker.anonymizer.domain.model.FaceRegion
import com.hoker.anonymizer.domain.model.GlitchParams
import com.hoker.anonymizer.domain.model.PixelBuffer
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pixel-sort glitch limited to faces.
 *
 * Each face area is cut into parallel lines running at [GlitchParams.angleDegrees]. Along every line:
 *  1. **Mask:** each pixel gets an opacity from its distance to the face outline. The allowed bleed
 *     is varied per line, so the edge ends in uneven spikes along the streak direction instead
 *     of a smooth outline.
 *  2. **Smear:** random runs are stretched out from a few source pixels. This is what destroys
 *     features (eyes, mouth) that sorting alone would leave readable.
 *  3. **Sort:** consecutive pixels whose brightness is within the threshold range are sorted by
 *     brightness, which produces the classic pixel-sort gradients.
 *  4. **Tint:** an optional duotone colour grade.
 *  5. **Composite:** the result is blended over the original using the mask opacity.
 *
 * Pixels outside every face's reach are never written.
 */
class FaceGlitchEffect @Inject constructor() {

    /**
     * Returns a new image with every face glitched. [speechLevel] (0..1) is how loudly someone is
     * speaking right now; with [GlitchParams.speechPulse] on, it flushes the glitch red.
     * [checkCancelled] is called once per line so callers can abort long renders (it should throw).
     */
    fun apply(
        image: PixelBuffer,
        faces: List<FaceRegion>,
        params: GlitchParams,
        speechLevel: Float = 0f,
        checkCancelled: () -> Unit = {},
    ): PixelBuffer {
        val output = image.copy()
        for (face in faces) {
            // Seed per face so one face's look doesn't change when another face is added.
            renderFace(output, face, params, speechLevel, mix(params.seed, face.id), checkCancelled)
        }
        return output
    }

    private fun renderFace(
        image: PixelBuffer,
        face: FaceRegion,
        params: GlitchParams,
        speechLevel: Float,
        faceSeed: Int,
        checkCancelled: () -> Unit,
    ) {
        val faceSize = face.size
        if (faceSize < MIN_FACE_SIZE) return

        val bleedPx = params.bleed.coerceAtLeast(0f) * faceSize
        val raggedness = params.raggedness.coerceIn(0f, 1f)
        val feather = max(MIN_FEATHER, faceSize * FEATHER_FRACTION)
        val maxReach = bleedPx * (1f + raggedness) + feather + 1f
        val trailPx = params.trail.coerceAtLeast(0f) * faceSize
        val radians = Math.toRadians(params.angleDegrees.toDouble())
        val mask = FaceMask.build(
            contour = face.contour,
            padding = maxReach,
            imageWidth = image.width,
            imageHeight = image.height,
            extendX = (cos(radians) * trailPx).toFloat(),
            extendY = (sin(radians) * trailPx).toFloat(),
        ) ?: return

        val maxLine = max(mask.width, mask.height) + 1
        val scratch = Scratch(maxLine)
        val settings = SegmentSettings.from(params, faceSize, trailPx, speechLevel)

        forEachLine(mask, params.angleDegrees, image.width, scratch.line) { lineIndex, count ->
            checkCancelled()
            // Each line gets its own sequence, numbered from the face's edge, so the pattern
            // stays attached to the face as it moves between video frames.
            val random = Random(mix(faceSeed, lineIndex))
            // Vary the edge per line: some streaks stop short, others shoot further out.
            val limit = bleedPx * (1f + raggedness * (random.nextFloat() * 2f - 1f))
            for (i in 0 until count) {
                val pixel = scratch.line[i]
                val d = mask.distanceAt(pixel % image.width, pixel / image.width)
                scratch.alpha[i] = if (d <= limit) 1f else (1f - (d - limit) / feather).coerceIn(0f, 1f)
            }
            var start = 0
            while (start < count) {
                if (scratch.alpha[start] <= 0f) {
                    start++
                    continue
                }
                var end = start
                while (end < count && scratch.alpha[end] > 0f) end++
                // The trail runs on past the face, but never into the next face segment on this line.
                var trailEnd = end
                if (settings.trailPx >= 1f) {
                    val wanted = (settings.trailPx * (1f - raggedness * random.nextFloat())).toInt()
                    while (trailEnd < count && trailEnd - end < wanted && scratch.alpha[trailEnd] <= 0f) trailEnd++
                }
                processSegment(image.pixels, scratch, start, end, trailEnd, settings, random)
                start = trailEnd
            }
        }
    }

    /**
     * Glitches the face part of a line ([start] until [end]), then fills the trail ([end] until
     * [trailEnd]) with a stretched mirror image of it, so the colour carries on smoothly from
     * where the face ends and slowly works back through the face's colours.
     */
    private fun processSegment(
        pixels: IntArray,
        scratch: Scratch,
        start: Int,
        end: Int,
        trailEnd: Int,
        settings: SegmentSettings,
        random: Random,
    ) {
        val faceLength = end - start
        val total = trailEnd - start
        val original = scratch.original
        val colors = scratch.colors
        for (k in 0 until total) {
            original[k] = pixels[scratch.line[start + k]]
            colors[k] = original[k]
        }

        if (settings.smearChance > 0f) smear(original, colors, faceLength, settings, random)
        sortSpans(colors, scratch.keys, faceLength, settings)

        val trailLength = total - faceLength
        if (trailLength > 0) {
            for (t in 0 until trailLength) {
                val back = min(faceLength - 1, (t * faceLength / settings.trailPx).toInt())
                colors[faceLength + t] = colors[faceLength - 1 - back]
                // A short fade at the very end so streaks don't stop on a hard pixel edge.
                scratch.alpha[end + t] = ((trailLength - t) / TRAIL_FADE_PX).coerceIn(0f, 1f)
            }
        }
        if (settings.tintStrength > 0f) tint(colors, total, settings)
        if (settings.pulseAmount > 0f) pulse(colors, total, settings.pulseAmount)

        for (k in 0 until total) {
            val alpha = scratch.alpha[start + k]
            pixels[scratch.line[start + k]] =
                if (alpha >= 1f) colors[k] else lerpColor(original[k], colors[k], alpha)
        }
    }

    /** Stretches a short piece of the line across a long run, like a dragged video frame. */
    private fun smear(original: IntArray, colors: IntArray, length: Int, s: SegmentSettings, random: Random) {
        var k = 0
        while (k < length) {
            if (random.nextFloat() < s.smearChance) {
                val run = (s.maxStreak * (0.2f + 0.8f * random.nextFloat())).toInt().coerceAtLeast(2)
                val runEnd = min(length, k + run)
                for (m in k until runEnd) {
                    colors[m] = original[k + ((m - k) * SMEAR_STRETCH).toInt()]
                }
                k = runEnd
            } else {
                k++
            }
        }
    }

    /** Sorts every run of pixels whose brightness is inside the threshold range. */
    private fun sortSpans(colors: IntArray, keys: LongArray, length: Int, s: SegmentSettings) {
        var a = 0
        while (a < length) {
            if (luminance(colors[a]) !in s.lowLuma..s.highLuma) {
                a++
                continue
            }
            var b = a
            while (b < length) {
                val luma = luminance(colors[b])
                if (luma !in s.lowLuma..s.highLuma) break
                // Brightness in the high bits, the colour itself in the low bits.
                keys[b] = (luma.toLong() shl 32) or (colors[b].toLong() and 0xFFFFFFFFL)
                b++
            }
            if (b - a > 1) {
                keys.sort(a, b)
                for (m in a until b) colors[m] = keys[m].toInt()
            }
            a = b
        }
    }

    /** Blends toward a dark-to-bright red ramp, keeping each pixel's brightness so streak detail survives. */
    private fun pulse(colors: IntArray, length: Int, amount: Float) {
        for (k in 0 until length) {
            val red = lerpColor(PULSE_SHADOW, PULSE_HIGHLIGHT, luminance(colors[k]) / 255f)
            colors[k] = lerpColor(colors[k], red, amount)
        }
    }

    private fun tint(colors: IntArray, length: Int, s: SegmentSettings) {
        for (k in 0 until length) {
            val mapped = lerpColor(s.tintShadow, s.tintHighlight, luminance(colors[k]) / 255f)
            colors[k] = lerpColor(colors[k], mapped, s.tintStrength)
        }
    }

    /**
     * Visits every pixel of the mask's bounding box exactly once, grouped into straight lines
     * at [angleDegrees]. Each line's packed pixel indices (y * imageWidth + x) are written into
     * [buffer] in streak order, then [action] receives the line's index and how many pixels it has.
     */
    private inline fun forEachLine(
        mask: FaceMask,
        angleDegrees: Float,
        imageWidth: Int,
        buffer: IntArray,
        action: (lineIndex: Int, count: Int) -> Unit,
    ) {
        val radians = Math.toRadians(angleDegrees.toDouble())
        val dx = cos(radians)
        val dy = sin(radians)
        if (abs(dx) >= abs(dy)) {
            // Mostly horizontal: one pixel per column, row offset follows the slope.
            val slope = dy / dx
            val offsets = IntArray(mask.width) { (it * slope).roundToInt() }
            val minOffset = offsets.min()
            val maxOffset = offsets.max()
            val first = mask.top - maxOffset
            for (k in first..(mask.bottom - minOffset)) {
                var n = 0
                for (step in 0 until mask.width) {
                    val col = if (dx >= 0) step else mask.width - 1 - step
                    val y = k + offsets[col]
                    if (y >= mask.top && y <= mask.bottom) buffer[n++] = y * imageWidth + mask.left + col
                }
                if (n > 0) action(k - first, n)
            }
        } else {
            // Mostly vertical: one pixel per row, column offset follows the slope.
            val slope = dx / dy
            val offsets = IntArray(mask.height) { (it * slope).roundToInt() }
            val minOffset = offsets.min()
            val maxOffset = offsets.max()
            val first = mask.left - maxOffset
            for (k in first..(mask.right - minOffset)) {
                var n = 0
                for (step in 0 until mask.height) {
                    val row = if (dy >= 0) step else mask.height - 1 - step
                    val x = k + offsets[row]
                    if (x >= mask.left && x <= mask.right) buffer[n++] = (mask.top + row) * imageWidth + x
                }
                if (n > 0) action(k - first, n)
            }
        }
    }

    /** Reused per-line working arrays, so rendering a face doesn't allocate per line. */
    private class Scratch(size: Int) {
        val line = IntArray(size)
        val alpha = FloatArray(size)
        val original = IntArray(size)
        val colors = IntArray(size)
        val keys = LongArray(size)
    }

    private class SegmentSettings(
        val lowLuma: Int,
        val highLuma: Int,
        val maxStreak: Float,
        val smearChance: Float,
        val tintStrength: Float,
        val tintShadow: Int,
        val tintHighlight: Int,
        val trailPx: Float,
        val pulseAmount: Float,
    ) {
        companion object {
            fun from(params: GlitchParams, faceSize: Float, trailPx: Float, speechLevel: Float): SegmentSettings {
                val low = params.lowThreshold.coerceIn(0f, 1f)
                val high = params.highThreshold.coerceIn(low, 1f)
                val maxStreak = max(2f, params.streakLength.coerceAtLeast(0f) * faceSize)
                val averageRun = maxStreak * 0.6f
                // Chosen so smear = 1 covers roughly three quarters of each line with smears.
                val smearChance = params.smear.coerceIn(0f, 1f) * SMEAR_COVERAGE / averageRun
                return SegmentSettings(
                    lowLuma = (low * 255f).roundToInt(),
                    highLuma = (high * 255f).roundToInt(),
                    maxStreak = maxStreak,
                    smearChance = smearChance,
                    tintStrength = params.tintStrength.coerceIn(0f, 1f),
                    tintShadow = params.tintShadow,
                    tintHighlight = params.tintHighlight,
                    trailPx = trailPx,
                    pulseAmount = params.speechPulse.coerceIn(0f, 1f) * speechLevel.coerceIn(0f, 1f) * MAX_PULSE,
                )
            }
        }
    }

    private companion object {
        /** Combines two ints into a well-spread seed (MurmurHash3 finaliser), so neighbouring lines aren't correlated. */
        fun mix(a: Int, b: Int): Int {
            var h = a * -0x61c88647 + b
            h = h xor (h ushr 16)
            h *= -0x7a143595
            h = h xor (h ushr 13)
            h *= -0x3d4d51cb
            return h xor (h ushr 16)
        }

        const val MIN_FACE_SIZE = 4f
        const val MIN_FEATHER = 1.5f
        const val FEATHER_FRACTION = 0.012f
        const val SMEAR_STRETCH = 0.2f
        const val SMEAR_COVERAGE = 3f
        const val TRAIL_FADE_PX = 6f

        /** Even at full strength the red stays a tint, so the glitch underneath still reads. */
        const val MAX_PULSE = 0.65f
        val PULSE_SHADOW = 0xFF2A0008.toInt()
        val PULSE_HIGHLIGHT = 0xFFFF5A5A.toInt()
    }
}
