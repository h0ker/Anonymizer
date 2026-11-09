package com.hoker.anonymizer.domain.repository

import com.hoker.anonymizer.domain.model.ExportOutcome
import com.hoker.anonymizer.domain.model.ExportStatus
import com.hoker.anonymizer.domain.model.VideoExportRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Runs video exports in the background so they keep going when the user leaves the app.
 * One export at a time.
 */
interface VideoExportScheduler {
    val status: StateFlow<ExportStatus>

    /** One event per finished export. Only delivered to collectors active at the time. */
    val outcomes: Flow<ExportOutcome>

    /** Returns false (and does nothing) if an export is already running. */
    fun start(request: VideoExportRequest): Boolean

    fun cancel()
}
