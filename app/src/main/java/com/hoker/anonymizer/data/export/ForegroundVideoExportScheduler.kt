package com.hoker.anonymizer.data.export

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoker.anonymizer.domain.model.ExportOutcome
import com.hoker.anonymizer.domain.model.ExportStage
import com.hoker.anonymizer.domain.model.ExportStatus
import com.hoker.anonymizer.domain.model.VideoExportRequest
import com.hoker.anonymizer.domain.repository.VideoExportScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands export requests to [VideoExportService] and publishes its progress. The request is passed
 * in memory rather than through the Intent: the service always runs in this same process.
 */
@Singleton
class ForegroundVideoExportScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : VideoExportScheduler {

    private val _status = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
    override val status: StateFlow<ExportStatus> = _status.asStateFlow()

    private val _outcomes = MutableSharedFlow<ExportOutcome>(extraBufferCapacity = 4)
    override val outcomes: Flow<ExportOutcome> = _outcomes.asSharedFlow()

    private val pending = AtomicReference<VideoExportRequest?>()

    /** Set by the service while an export runs. */
    @Volatile
    internal var activeJob: Job? = null

    override fun start(request: VideoExportRequest): Boolean {
        if (!_status.compareAndSet(ExportStatus.Idle, ExportStatus.Running(null, ExportStage.RENDERING))) return false
        pending.set(request)
        return try {
            ContextCompat.startForegroundService(context, VideoExportService.startIntent(context))
            true
        } catch (e: IllegalStateException) {
            // Android refuses to start foreground services from the background; the user tapped
            // Export, so this shouldn't happen, but don't leave the status stuck on "running".
            Log.e(TAG, "Could not start export service", e)
            pending.set(null)
            finish(ExportOutcome.Failed)
            false
        }
    }

    override fun cancel() {
        val job = activeJob
        if (job != null) {
            job.cancel()
        } else if (pending.getAndSet(null) != null) {
            // Cancelled before the service picked the request up.
            finish(ExportOutcome.Cancelled)
        }
    }

    internal fun takePending(): VideoExportRequest? = pending.getAndSet(null)

    internal fun update(status: ExportStatus.Running) {
        _status.value = status
    }

    internal fun finish(outcome: ExportOutcome) {
        activeJob = null
        _status.value = ExportStatus.Idle
        _outcomes.tryEmit(outcome)
    }

    private companion object {
        const val TAG = "VideoExport"
    }
}
