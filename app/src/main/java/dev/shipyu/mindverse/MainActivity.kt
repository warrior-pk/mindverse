package dev.shipyu.mindverse

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.shipyu.mindverse.core.updates.UpdateController
import dev.shipyu.mindverse.firstrun.FirstRunController
import dev.shipyu.mindverse.firstrun.FirstRunScreen
import dev.shipyu.mindverse.privacy.PrivacyController
import dev.shipyu.mindverse.triggers.clipboard.ClipboardSettings
import dev.shipyu.mindverse.ui.captures.CaptureReviewController
import dev.shipyu.mindverse.ui.detail.MemoryDetailController
import dev.shipyu.mindverse.ui.gallery.GalleryController
import dev.shipyu.mindverse.ui.ask.AskController
import dev.shipyu.mindverse.ui.nav.MindverseNav
import javax.inject.Inject

/**
 * Composition root: first-run onboarding gate, the nav shell
 * (Brain / Ask / Privacy / Support), deep-link routing (`mindverse://memory/<id>`,
 * `mindverse://capture/<id>`), and the cold-start update check (debounced 24h).
 * Ingest is explicit-share only (privacy-first): Share sheet, clipboard auto-add
 * (opt-in, foreground-only), QS tile opens the dashboard.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var privacyController: PrivacyController
    @Inject lateinit var galleryController: GalleryController
    @Inject lateinit var askController: AskController
    @Inject lateinit var memoryDetailController: MemoryDetailController
    @Inject lateinit var captureReviewController: CaptureReviewController
    @Inject lateinit var clipboardSettings: ClipboardSettings
    @Inject lateinit var firstRunController: FirstRunController
    @Inject lateinit var updateController: UpdateController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateController.checkIfNeeded()
        val (memoryId, captureId) = deepLinkRoute(intent)
        setContent {
            MaterialTheme {
                val firstRunComplete by firstRunController.firstRunComplete
                    .collectAsStateWithLifecycle(initialValue = null)
                var openSupportTab by remember { mutableStateOf(false) }
                when (firstRunComplete) {
                    // Still reading the preference: keep the splash surface minimal.
                    null -> Unit
                    false -> FirstRunScreen(
                        controller = firstRunController,
                        onOpenPrivacy = { /* privacy preface is step 3; screen is one tap away in nav */ },
                        onOpenSupport = { openSupportTab = true },
                        onDone = { /* state flips via the collected flow */ },
                    )
                    else -> MindverseNav(
                        galleryController = galleryController,
                        askController = askController,
                        detailController = memoryDetailController,
                        reviewController = captureReviewController,
                        privacyController = privacyController,
                        clipboardSettings = clipboardSettings,
                        updateController = updateController,
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                        initialMemoryId = memoryId,
                        initialCaptureId = captureId,
                        initialTab = if (openSupportTab) {
                            dev.shipyu.mindverse.ui.nav.NavTab.SUPPORT
                        } else {
                            dev.shipyu.mindverse.ui.nav.NavTab.BRAIN
                        },
                    )
                }
            }
        }
    }

    companion object {
        /**
         * Deep links (spec R4-7): `mindverse://memory/<id>` → Memory detail,
         * `mindverse://capture/<id>` → Capture review sheet; anything else → Brain.
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
