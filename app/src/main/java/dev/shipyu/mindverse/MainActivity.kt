package dev.shipyu.mindverse

import android.content.Intent
import android.os.Bundle
import android.app.Activity.ScreenCaptureCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.shipyu.mindverse.privacy.PrivacyController
import dev.shipyu.mindverse.triggers.screenshot.ScreenshotIngestHandler
import dev.shipyu.mindverse.triggers.screenshot.WatcherSettings
import dev.shipyu.mindverse.ui.captures.CaptureReviewController
import dev.shipyu.mindverse.ui.detail.MemoryDetailController
import dev.shipyu.mindverse.ui.gallery.GalleryController
import dev.shipyu.mindverse.ui.ask.AskController
import dev.shipyu.mindverse.ui.nav.MindverseNav
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Composition root (ticket 06): the nav shell (Gallery / Ask / Privacy / Support) plus
 * deep-link routing (`mindverse://memory/<id>`, `mindverse://capture/<id>`), and host of
 * the screenshot watcher's `ScreenCaptureCallback` (ticket 05) — that API only reports
 * screenshots while the app is visible, so registration follows the activity lifecycle.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var privacyController: PrivacyController
    @Inject lateinit var galleryController: GalleryController
    @Inject lateinit var askController: AskController
    @Inject lateinit var memoryDetailController: MemoryDetailController
    @Inject lateinit var captureReviewController: CaptureReviewController
    @Inject lateinit var screenshotHandler: ScreenshotIngestHandler
    @Inject lateinit var watcherSettings: WatcherSettings

    private val screenCaptureCallback = ScreenCaptureCallback {
        lifecycleScope.launch { screenshotHandler.onScreenshotDetected() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (memoryId, captureId) = deepLinkRoute(intent)
        setContent {
            MaterialTheme {
                MindverseNav(
                    galleryController = galleryController,
                    askController = askController,
                    detailController = memoryDetailController,
                    reviewController = captureReviewController,
                    privacyController = privacyController,
                    watcherSettings = watcherSettings,
                    initialMemoryId = memoryId,
                    initialCaptureId = captureId,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerScreenCaptureCallback(mainExecutor, screenCaptureCallback)
    }

    override fun onStop() {
        unregisterScreenCaptureCallback(screenCaptureCallback)
        super.onStop()
    }

    companion object {
        /**
         * Deep links (spec R4-7): `mindverse://memory/<id>` → Memory detail,
         * `mindverse://capture/<id>` → Capture review sheet; anything else → Gallery.
         */
        fun deepLinkRoute(intent: Intent?): Pair<String?, String?> {
            val data = intent?.data ?: return null to null
            if (data.scheme != "mindverse") return null to null
            val id = data.lastPathSegment?.takeIf { it.isNotBlank() } ?: return null to null
            return when (data.host) {
                "memory" -> id to null
                "capture" -> null to id
                else -> null to null
            }
        }
    }
}
