package com.hoker.anonymizer.data.export

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.hoker.anonymizer.domain.model.ExportOutcome
import com.hoker.anonymizer.domain.model.ExportStage
import com.hoker.anonymizer.domain.model.ExportStatus
import com.hoker.anonymizer.domain.model.VideoExportState
import com.hoker.anonymizer.domain.usecase.AnonymizeVideoUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Foreground service that runs one video export, so it keeps going after the user leaves the
 * app. Shows progress (with a Cancel button) in a notification, then a "saved" or "failed" one.
 */
@AndroidEntryPoint
class VideoExportService : Service() {

    @Inject lateinit var scheduler: ForegroundVideoExportScheduler
    @Inject lateinit var anonymizeVideo: AnonymizeVideoUseCase
    @Inject lateinit var notifications: ExportNotifications

    // Main thread: Media3's Transformer must be driven from a thread with a Looper.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> scheduler.cancel()
            else -> startExport()
        }
        return START_NOT_STICKY
    }

    private fun startExport() {
        // Android requires startForeground soon after startForegroundService, even if we then stop.
        // Not ServiceCompat.startForeground: on Android 14+ it masks the type down to the types that
        // existed in Android 14, which turns MEDIA_PROCESSING into "none" and crashes the app.
        startForeground(ExportNotifications.PROGRESS_ID, notifications.progress(null, ExportStage.RENDERING), foregroundType())
        if (scheduler.activeJob?.isActive == true) return
        val request = scheduler.takePending()
        if (request == null) {
            stopAndRemoveNotification()
            return
        }

        // A foreground service keeps the process alive but doesn't stop the CPU sleeping once the
        // screen locks; without this the export freezes until the phone is woken up.
        acquireWakeLock()

        scheduler.activeJob = scope.launch {
            var outcome: ExportOutcome = ExportOutcome.Failed
            var shownPercent: Int? = -1
            var shownStage: ExportStage? = null
            try {
                anonymizeVideo(request).collect { state ->
                    when (state) {
                        is VideoExportState.InProgress -> {
                            scheduler.update(ExportStatus.Running(state.fraction, state.stage))
                            val percent = state.fraction?.let { (it * 100).roundToInt() }
                            // Only touch the notification when what it shows actually changes.
                            if (percent != shownPercent || state.stage != shownStage) {
                                shownPercent = percent
                                shownStage = state.stage
                                notifications.updateProgress(percent, state.stage)
                            }
                        }
                        is VideoExportState.Completed -> outcome = ExportOutcome.Completed(state.uri)
                    }
                }
            } catch (e: CancellationException) {
                outcome = ExportOutcome.Cancelled
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                outcome = ExportOutcome.Failed
            } finally {
                releaseWakeLock()
                stopAndRemoveNotification()
                notifications.showOutcome(outcome)
                scheduler.finish(outcome)
            }
        }
    }

    /** Android 15+ limits media processing to 6 hours a day and calls this when time is up. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        scheduler.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private var wakeLock: PowerManager.WakeLock? = null

    /** Keeps the CPU (not the screen) running. The timeout is a safety net should release ever be missed. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun stopAndRemoveNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            // "Media processing" only exists from Android 15; data sync is the closest earlier type.
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

    companion object {
        private const val TAG = "VideoExport"
        private const val WAKE_LOCK_TAG = "Anonymizer:VideoExport"

        /** Matches Android 15's 6-hour daily cap on media processing. */
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val ACTION_START = "com.hoker.anonymizer.action.START_EXPORT"
        internal const val ACTION_CANCEL = "com.hoker.anonymizer.action.CANCEL_EXPORT"

        fun startIntent(context: Context) = Intent(context, VideoExportService::class.java).setAction(ACTION_START)
        fun cancelIntent(context: Context) = Intent(context, VideoExportService::class.java).setAction(ACTION_CANCEL)
    }
}
