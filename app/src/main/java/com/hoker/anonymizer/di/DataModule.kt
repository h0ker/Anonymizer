package com.hoker.anonymizer.di

import com.hoker.anonymizer.data.audio.MediaCodecAudioRepository
import com.hoker.anonymizer.data.export.ForegroundVideoExportScheduler
import com.hoker.anonymizer.domain.repository.VideoExportScheduler
import com.hoker.anonymizer.data.face.MediaPipeFaceDetector
import com.hoker.anonymizer.domain.repository.AudioRepository
import com.hoker.anonymizer.data.media.AndroidMediaRepository
import com.hoker.anonymizer.data.video.Media3VideoRepository
import com.hoker.anonymizer.domain.repository.VideoRepository
import com.hoker.anonymizer.domain.repository.FaceDetector
import com.hoker.anonymizer.domain.repository.MediaRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun bindFaceDetector(impl: MediaPipeFaceDetector): FaceDetector

    @Binds
    abstract fun bindMediaRepository(impl: AndroidMediaRepository): MediaRepository

    @Binds
    abstract fun bindVideoRepository(impl: Media3VideoRepository): VideoRepository

    @Binds
    abstract fun bindAudioRepository(impl: MediaCodecAudioRepository): AudioRepository

    @Binds
    abstract fun bindVideoExportScheduler(impl: ForegroundVideoExportScheduler): VideoExportScheduler
}
