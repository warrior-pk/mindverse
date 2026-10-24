package dev.shipyu.mindverse.core.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.shipyu.mindverse.MainActivity

/**
 * Notification-chip lifecycle updates (ticket 04: "notification chip while queued
 * processing (issue 06 owns launch, pipeline owns lifecycle updates)"). Best-effort: a
 * missing POST_NOTIFICATIONS grant never fails processing.
 *
 * One chip for the whole pipeline (the spec calls it a processing pill, not one per
 * capture): updates replace the same notification slot, including the expedited workers'
 * foreground-service notification.
 */
interface PipelineChip {
    /** Extract started for a queued Capture. */
    fun processing(captureId: String)

    /** Memory exists; on-device embedding/indexing is running. */
    fun indexing(captureId: String, memoryId: String)

    /** Pipeline finished; chip is dismissed (surfaces own the result from here). */
    fun done(captureId: String, memoryId: String)

    /** Dead-letter: the chip stays, explaining the failure; tap opens the app. */
    fun failed(captureId: String, reason: String)
}

/** Deep-link scheme ratified in spec R4-7. */
object MindverseLinks {
    const val SCHEME = "mindverse"

    fun memory(memoryId: String): Uri = Uri.parse("$SCHEME://memory/$memoryId")

    fun pipeline(): Uri = Uri.parse("$SCHEME://pipeline")

    fun deadLetter(): Uri = Uri.parse("$SCHEME://dead-letter")
}

/**
 * Notification plumbing shared by the chip and the expedited workers' ForegroundInfo.
 * One channel, one notification slot (id [NOTIFICATION_ID], untagged) so chip updates and
 * the foreground-service notification replace each other instead of stacking.
 */
internal object PipelineNotifications {
    const val CHANNEL_ID = "processing"
    const val NOTIFICATION_ID = 4100

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Processing captures",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows while Mindverse turns a Capture into a Memory"
                setShowBadge(false)
            },
        )
    }

    fun contentIntent(context: Context, uri: Uri): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = uri
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun build(
        context: Context,
        title: String,
        text: String,
        uri: Uri,
        ongoing: Boolean,
    ): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(context, uri))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    fun notify(context: Context, notification: Notification) {
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return
        runCatching { compat.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }
}

/** Production chip over NotificationManagerCompat. */
internal class NotificationPipelineChip(
    private val context: Context,
) : PipelineChip {
    override fun processing(captureId: String) {
        PipelineNotifications.notify(
            context,
            PipelineNotifications.build(
                context = context,
                title = "Processing capture",
                text = "Extracting a Memory…",
                uri = MindverseLinks.pipeline(),
                ongoing = true,
            ),
        )
    }

    override fun indexing(captureId: String, memoryId: String) {
        PipelineNotifications.notify(
            context,
            PipelineNotifications.build(
                context = context,
                title = "Indexing Memory",
                text = "Making it searchable on this device…",
                uri = MindverseLinks.memory(memoryId),
                ongoing = true,
            ),
        )
    }

    override fun done(captureId: String, memoryId: String) {
        PipelineNotifications.cancel(context)
    }

    override fun failed(captureId: String, reason: String) {
        PipelineNotifications.notify(
            context,
            PipelineNotifications.build(
                context = context,
                title = "Couldn't process capture",
                text = reason,
                uri = MindverseLinks.deadLetter(),
                ongoing = false,
            ),
        )
    }
}
