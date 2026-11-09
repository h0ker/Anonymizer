package com.hoker.anonymizer.data.video

import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.GlRect
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ByteBufferGlEffect
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.hoker.anonymizer.domain.model.PixelBuffer
import com.hoker.anonymizer.domain.video.FrameTransform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * Bridges Media3's effect pipeline to a [FrameTransform]:
 *  1. Media3 reads each frame off the GPU into a buffer and calls [processImage].
 *  2. On a single background thread (so frames stay in order for face tracking) the frame is
 *     converted to a [PixelBuffer], transformed, and converted back to bytes ready for upload.
 *  3. On the GL thread, [finishProcessingAndBlend] writes those bytes over the frame's texture.
 *
 * GL frames are stored bottom row first, so rows are flipped on the way in and out.
 */
@OptIn(UnstableApi::class)
internal class FrameTransformProcessor(
    private val createTransform: () -> FrameTransform,
) : ByteBufferGlEffect.Processor<FrameTransformProcessor.FrameResult> {

    /** The bytes to upload, or null when the frame had no faces and stays as it is. */
    class FrameResult(val upload: ByteBuffer?)

    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "anonymizer-frames")
    })
    private val bufferPool = ConcurrentLinkedQueue<ByteBuffer>()

    // Only touched on the executor thread.
    private var transform: FrameTransform? = null

    @Volatile private var width = 0
    @Volatile private var height = 0

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun getScaledRegion(presentationTimeUs: Long): GlRect = GlRect(width, height)

    override fun processImage(image: ByteBufferGlEffect.Image, presentationTimeUs: Long): ListenableFuture<FrameResult> =
        executor.submit<FrameResult> {
            val activeTransform = transform ?: createTransform().also { transform = it }
            val frame = readFrame(image)
            val output = activeTransform.apply(frame, presentationTimeUs)
            // Same instance means no faces: skip the upload and leave the frame as it is.
            FrameResult(if (output === frame) null else toUploadBuffer(output))
        }

    override fun finishProcessingAndBlend(outputFrame: GlTextureInfo, presentationTimeUs: Long, result: FrameResult) {
        val upload = result.upload ?: return
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputFrame.texId)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, outputFrame.width, outputFrame.height,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, upload,
            )
            GlUtil.checkGlError()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        } finally {
            bufferPool.offer(upload)
        }
    }

    override fun release() = shutdown()

    /** Closes the transform on its own thread after any in-flight frame finishes. Safe to call twice. */
    fun shutdown() {
        if (executor.isShutdown) return
        executor.execute {
            transform?.close()
            transform = null
        }
        executor.shutdown()
    }

    /** RGBA bytes, bottom row first → ARGB ints, top row first. */
    private fun readFrame(image: ByteBufferGlEffect.Image): PixelBuffer {
        val w = image.width
        val h = image.height
        val source = image.pixelBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { rewind() }.asIntBuffer()
        val pixels = IntArray(w * h)
        for (row in 0 until h) {
            source.position((h - 1 - row) * w)
            source.get(pixels, row * w, w)
        }
        for (i in pixels.indices) pixels[i] = swapRedBlue(pixels[i])
        return PixelBuffer(w, h, pixels)
    }

    /** ARGB ints, top row first → RGBA bytes, bottom row first. */
    private fun toUploadBuffer(frame: PixelBuffer): ByteBuffer {
        val size = frame.width * frame.height * 4
        val buffer = bufferPool.poll()?.takeIf { it.capacity() == size }
            ?: ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.clear()
        val ints = buffer.asIntBuffer()
        val row = IntArray(frame.width)
        for (y in frame.height - 1 downTo 0) {
            val offset = y * frame.width
            for (x in 0 until frame.width) row[x] = swapRedBlue(frame.pixels[offset + x])
            ints.put(row)
        }
        buffer.position(0).limit(size)
        return buffer
    }

    /** Little-endian RGBA bytes read as an int are 0xAABBGGRR; Android ARGB is 0xAARRGGBB. Same swap both ways. */
    private fun swapRedBlue(color: Int): Int =
        (color and 0xFF00FF00.toInt()) or ((color and 0xFF) shl 16) or ((color ushr 16) and 0xFF)
}
