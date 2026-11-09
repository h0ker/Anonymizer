package com.hoker.anonymizer.data.video

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4AlternateGroupData
import androidx.media3.container.Mp4OrientationData
import androidx.media3.muxer.Muxer

/**
 * Media3 copies the source video's metadata into the output: location, capture time, XMP, and
 * device details such as make, model and capture frame rate. This keeps only what is needed to
 * play the file correctly (rotation, so portrait videos stay upright, and track grouping) and
 * drops everything else.
 */
@OptIn(UnstableApi::class)
internal class MetadataScrubbingMuxerFactory(private val delegate: Muxer.Factory) : Muxer.Factory by delegate {
    override fun create(path: String): Muxer = MetadataScrubbingMuxer(delegate.create(path))
}

@OptIn(UnstableApi::class)
private class MetadataScrubbingMuxer(private val delegate: Muxer) : Muxer by delegate {
    override fun addMetadataEntry(entry: Metadata.Entry) {
        if (entry is Mp4OrientationData || entry is Mp4AlternateGroupData) {
            delegate.addMetadataEntry(entry)
        }
    }
}
