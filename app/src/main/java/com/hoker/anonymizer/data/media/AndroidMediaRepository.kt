package com.hoker.anonymizer.data.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.hoker.anonymizer.core.dispatchers.IoDispatcher
import com.hoker.anonymizer.domain.model.MediaKind
import com.hoker.anonymizer.domain.model.MediaUri
import com.hoker.anonymizer.domain.model.PixelBuffer
import com.hoker.anonymizer.domain.repository.MediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

@Singleton
class AndroidMediaRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : MediaRepository {

    override suspend fun getKind(uri: MediaUri): MediaKind? = withContext(dispatcher) {
        val type = context.contentResolver.getType(uri.value.toUri()).orEmpty()
        when {
            type.startsWith("image/") -> MediaKind.PHOTO
            type.startsWith("video/") -> MediaKind.VIDEO
            else -> null
        }
    }

    override suspend fun loadImage(uri: MediaUri, maxDimension: Int): PixelBuffer = withContext(dispatcher) {
        val source = ImageDecoder.createSource(context.contentResolver, uri.value.toUri())
        // ImageDecoder applies the EXIF rotation, so the pixels come out upright.
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > maxDimension) {
                val scale = maxDimension.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
        // HDR or wide-gamut images may decode to other formats; the effect works on 8-bit ARGB.
        val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) decoded else {
            decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        PixelBuffer(bitmap.width, bitmap.height, pixels).also { bitmap.recycle() }
    }

    override suspend fun saveImage(image: PixelBuffer, copyMetadataFrom: MediaUri?): MediaUri = withContext(dispatcher) {
        // Encode to a temporary file first: ExifInterface needs a seekable file to write tags.
        val temp = File.createTempFile("anonymized_", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
            try {
                // Bitmap.compress writes no EXIF at all, so a scrubbed photo needs nothing further.
                val written = temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                if (!written) throw IOException("Could not encode image")
            } finally {
                bitmap.recycle()
            }
            copyMetadataFrom?.let { copyExif(from = it, to = temp, width = image.width, height = image.height) }
            insertIntoGallery(temp)
        } finally {
            temp.delete()
        }
    }

    /**
     * Copies the source's EXIF tags onto the new JPEG. Tags describing the file layout are skipped,
     * and orientation is reset because the pixels were already rotated upright when decoded.
     */
    private fun copyExif(from: MediaUri, to: File, width: Int, height: Int) {
        val source = context.contentResolver.openInputStream(from.value.toUri())?.use { ExifInterface(it) } ?: return
        val target = ExifInterface(to.absolutePath)
        for (tag in copyableExifTags) {
            val value = source.getAttribute(tag) ?: continue
            // A tag the JPEG writer rejects shouldn't stop the rest from being copied.
            runCatching { target.setAttribute(tag, value) }
        }
        target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        target.saveAttributes()
    }

    private fun insertIntoGallery(file: File): MediaUri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "anonymized_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = resolver.insert(collection, values) ?: throw IOException("Could not create gallery entry")
        try {
            resolver.openOutputStream(target)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw IOException("Could not write image")
            resolver.update(target, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            throw e
        }
        return MediaUri(target.toString())
    }

    private companion object {
        const val ALBUM = "Anonymizer"
        const val JPEG_QUALITY = 95

        /** Tags that describe the old file's layout or size rather than the photo; copying them would corrupt the new file. */
        val LAYOUT_TAGS = setOf(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_PIXEL_X_DIMENSION,
            ExifInterface.TAG_PIXEL_Y_DIMENSION,
            ExifInterface.TAG_COMPRESSION,
            ExifInterface.TAG_BITS_PER_SAMPLE,
            ExifInterface.TAG_SAMPLES_PER_PIXEL,
            ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION,
            ExifInterface.TAG_PLANAR_CONFIGURATION,
            ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING,
            ExifInterface.TAG_Y_CB_CR_POSITIONING,
            ExifInterface.TAG_STRIP_OFFSETS,
            ExifInterface.TAG_STRIP_BYTE_COUNTS,
            ExifInterface.TAG_ROWS_PER_STRIP,
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT,
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
            ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
            ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
            "ThumbnailOrientation", // ExifInterface.TAG_THUMBNAIL_ORIENTATION is library-private.
            ExifInterface.TAG_NEW_SUBFILE_TYPE,
            ExifInterface.TAG_SUBFILE_TYPE,
        )

        /** Every public ExifInterface tag name except the layout ones (kept from shrinking in rules.keep). */
        val copyableExifTags: List<String> by lazy {
            ExifInterface::class.java.fields
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java && it.name.startsWith("TAG_") }
                .mapNotNull { it.get(null) as? String }
                .filterNot { it in LAYOUT_TAGS }
                .distinct()
        }
    }
}
