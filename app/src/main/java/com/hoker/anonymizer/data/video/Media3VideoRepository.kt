package com.hoker.anonymizer.data.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ByteBufferGlEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.hoker.anonymizer.core.dispatchers.IoDispatcher
import com.hoker.anonymizer.data.audio.VoiceModulatorAudioProcessor
import com.hoker.anonymizer.domain.model.MediaUri
import com.hoker.anonymizer.domain.model.PixelBuffer
import com.hoker.anonymizer.domain.model.VideoExportState
import com.hoker.anonymizer.domain.model.VideoInfo
import com.hoker.anonymizer.domain.model.VoiceStyle
import com.hoker.anonymizer.domain.repository.VideoRepository
import com.hoker.anonymizer.domain.video.FrameTransform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

@Singleton
class Media3VideoRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : VideoRepository {

    override suspend fun getInfo(uri: MediaUri): VideoInfo = withContext(ioDispatcher) {
        withRetriever(uri) { retriever ->
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            VideoInfo(durationMs = duration ?: 0L)
        }
    }

    override suspend fun loadFrame(uri: MediaUri, timeMs: Long, maxDimension: Int): PixelBuffer = withContext(ioDispatcher) {
        withRetriever(uri) { retriever ->
            // The retriever applies the video's rotation, so the frame comes out upright.
            val frame = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IOException("Could not decode a frame at $timeMs ms")
            val longest = max(frame.width, frame.height)
            val scaled = if (longest <= maxDimension) frame else {
                val ratio = maxDimension.toFloat() / longest
                frame.scale((frame.width * ratio).roundToInt(), (frame.height * ratio).roundToInt())
            }
            val bitmap = if (scaled.config == Bitmap.Config.ARGB_8888) scaled else scaled.copy(Bitmap.Config.ARGB_8888, false)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            PixelBuffer(bitmap.width, bitmap.height, pixels)
        }
    }

    @OptIn(UnstableApi::class)
    override fun export(
        uri: MediaUri,
        removeAudio: Boolean,
        scrubMetadata: Boolean,
        voice: VoiceStyle?,
        createTransform: () -> FrameTransform,
    ): Flow<VideoExportState> = callbackFlow {
        val exportDir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val outputFile = File(exportDir, "anonymized_${System.currentTimeMillis()}.mp4")
        val processor = FrameTransformProcessor(createTransform)

        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri.value.toUri()))
            .setRemoveAudio(removeAudio)
            .setEffects(
                Effects(
                    // Adding an audio processor makes Transformer decode and re-encode the audio.
                    listOfNotNull(voice?.takeUnless { removeAudio }?.let { VoiceModulatorAudioProcessor(it) }),
                    listOf(ByteBufferGlEffect(processor)),
                ),
            )
            .build()
        // The effect works on 8-bit colour, so HDR footage is tone-mapped to SDR first.
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setMuxerFactory(if (scrubMetadata) MetadataScrubbingMuxerFactory(DefaultMuxer.Factory()) else DefaultMuxer.Factory())
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    launch {
                        try {
                            val saved = withContext(ioDispatcher) { saveToGallery(outputFile) }
                            send(VideoExportState.Completed(saved))
                            channel.close()
                        } catch (e: Exception) {
                            channel.close(e)
                        }
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    channel.close(exportException)
                }
            })
            .build()

        send(VideoExportState.InProgress(null))
        transformer.start(composition, outputFile.absolutePath)

        val progressJob = launch {
            val holder = ProgressHolder()
            while (isActive) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    trySend(VideoExportState.InProgress(holder.progress / 100f))
                }
                delay(PROGRESS_POLL_MS)
            }
        }

        awaitClose {
            progressJob.cancel()
            transformer.cancel()
            processor.shutdown()
            outputFile.delete()
        }
    }.flowOn(Dispatchers.Main.immediate) // Transformer must be driven from a Looper thread.

    private fun saveToGallery(file: File): MediaUri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, MimeTypes.VIDEO_MP4)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = resolver.insert(collection, values) ?: throw IOException("Could not create gallery entry")
        try {
            resolver.openOutputStream(target)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw IOException("Could not write video")
            resolver.update(target, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            throw e
        }
        return MediaUri(target.toString())
    }

    private inline fun <T> withRetriever(uri: MediaUri, block: (MediaMetadataRetriever) -> T): T {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri.value.toUri())
            block(retriever)
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val EXPORT_DIR = "exports"
        const val ALBUM = "Anonymizer"
        const val PROGRESS_POLL_MS = 250L
    }
}
