package com.hoker.anonymizer.data.export

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.hoker.anonymizer.R
import com.hoker.anonymizer.domain.model.ExportOutcome
import com.hoker.anonymizer.domain.model.ExportStage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.notification_channel_progress), NotificationManager.IMPORTANCE_LOW),
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, context.getString(R.string.notification_channel_result), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** [percent] null shows an indeterminate bar. */
    fun progress(percent: Int?, stage: ExportStage): Notification {
        val text = when {
            stage == ExportStage.ANALYZING_AUDIO -> context.getString(R.string.video_analyzing_audio)
            percent == null -> context.getString(R.string.notification_preparing)
            else -> context.getString(R.string.notification_percent, percent)
        }
        val cancel = PendingIntent.getService(
            context, 0, VideoExportService.cancelIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_anonymizer)
            .setContentTitle(context.getString(R.string.notification_exporting_title))
            .setContentText(text)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(0, context.getString(R.string.editor_cancel), cancel)
            .build()
    }

    fun updateProgress(percent: Int?, stage: ExportStage) = notify(PROGRESS_ID, progress(percent, stage))

    fun showOutcome(outcome: ExportOutcome) {
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_anonymizer)
            .setAutoCancel(true)
        when (outcome) {
            is ExportOutcome.Completed -> {
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(outcome.uri.value.toUri(), "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                builder
                    .setContentTitle(context.getString(R.string.notification_saved_title))
                    .setContentText(context.getString(R.string.message_video_saved))
                    .setContentIntent(PendingIntent.getActivity(context, 1, view, PendingIntent.FLAG_IMMUTABLE))
            }
            ExportOutcome.Failed -> builder
                .setContentTitle(context.getString(R.string.message_export_failed))
                .setContentIntent(openAppIntent())
            ExportOutcome.Cancelled -> return
        }
        notify(RESULT_ID, builder.build())
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Without notification permission the export still runs; it just isn't shown. */
    @SuppressLint("MissingPermission")
    private fun notify(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        manager.notify(id, notification)
    }

    companion object {
        const val PROGRESS_ID = 1001
        private const val RESULT_ID = 1002
        private const val CHANNEL_PROGRESS = "export_progress"
        private const val CHANNEL_RESULT = "export_result"
    }
}
