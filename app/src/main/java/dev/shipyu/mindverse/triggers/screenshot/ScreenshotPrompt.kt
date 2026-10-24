package dev.shipyu.mindverse.triggers.screenshot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.shipyu.mindverse.MainActivity
import dev.shipyu.mindverse.core.pipeline.MindverseLinks
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "Add to Mindverse?" prompt (ticket 05): local notification, one-tap accept, no data
 * leaves the device to decide. Auto-cancels once actioned.
 */
class NotificationScreenshotPrompt(
    private val context: Context,
) : ScreenshotPrompt {

    override fun prompt(screenshot: Screenshot) {
        ensureChannel()
        val accept = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ScreenshotPromptReceiver::class.java).apply {
                action = ScreenshotPromptReceiver.ACTION_ACCEPT
                putExtra(ScreenshotPromptReceiver.EXTRA_URI, screenshot.uri.toString())
                putExtra(ScreenshotPromptReceiver.EXTRA_MIME, screenshot.mimeType)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = MindverseLinks.pipeline()
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Add to Mindverse?")
            .setContentText("Save this screenshot as a Memory")
            .setContentIntent(open)
            .addAction(0, "Add", accept)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return
        runCatching { compat.notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screenshot prompts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Asks before adding a screenshot to Mindverse"
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "screenshot-prompt"
        const val NOTIFICATION_ID = 4200
    }
}

/** One-tap accept handler for the watcher prompt. */
@AndroidEntryPoint
class ScreenshotPromptReceiver : BroadcastReceiver() {

    @Inject
    lateinit var handler: ScreenshotIngestHandler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ACCEPT) return
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: return
        val mime = intent.getStringExtra(EXTRA_MIME)
        NotificationManagerCompat.from(context).cancel(NotificationScreenshotPrompt.NOTIFICATION_ID)

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handler.accept(uri, mime)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "dev.shipyu.mindverse.action.ACCEPT_SCREENSHOT"
        const val EXTRA_URI = "uri"
        const val EXTRA_MIME = "mime"
    }
}
