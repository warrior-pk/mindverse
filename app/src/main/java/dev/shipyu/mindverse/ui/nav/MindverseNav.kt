package dev.shipyu.mindverse.ui.nav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shipyu.mindverse.privacy.PrivacyController
import dev.shipyu.mindverse.privacy.PrivacyScreen
import dev.shipyu.mindverse.triggers.screenshot.WatcherSettings
import dev.shipyu.mindverse.core.updates.UpdateController
import dev.shipyu.mindverse.ui.ask.AskController
import dev.shipyu.mindverse.ui.ask.AskScreen
import dev.shipyu.mindverse.ui.captures.CaptureReviewController
import dev.shipyu.mindverse.ui.captures.CaptureReviewScreen
import dev.shipyu.mindverse.ui.detail.MemoryDetailController
import dev.shipyu.mindverse.ui.detail.MemoryDetailScreen
import dev.shipyu.mindverse.ui.gallery.GalleryController
import dev.shipyu.mindverse.ui.gallery.GalleryScreen
import dev.shipyu.mindverse.ui.support.SupportPlaceholderScreen

/** Bottom-level navigation (ticket 06): Gallery / Ask / Privacy / Support. */
enum class NavTab(val label: String) {
    GALLERY("Gallery"),
    ASK("Ask"),
    PRIVACY("Privacy"),
    SUPPORT("Support"),
}

/**
 * The M1 shell (ticket 06): bottom nav + Memory detail / Capture review overlays, with
 * deep-link entry points (`mindverse://memory/<id>`, `mindverse://capture/<id>`) routed
 * by [initialMemoryId]/[initialCaptureId].
 */
@Composable
fun MindverseNav(
    galleryController: GalleryController,
    askController: AskController,
    detailController: MemoryDetailController,
    reviewController: CaptureReviewController,
    privacyController: PrivacyController,
    watcherSettings: WatcherSettings,
    updateController: UpdateController,
    versionName: String,
    versionCode: Int,
    initialMemoryId: String? = null,
    initialCaptureId: String? = null,
) {
    var tab by rememberSaveable { mutableStateOf(NavTab.GALLERY) }
    var memoryId by rememberSaveable { mutableStateOf(initialMemoryId) }
    var captureId by rememberSaveable { mutableStateOf(initialCaptureId) }

    LaunchedEffect(memoryId) { memoryId?.let { detailController.load(it) } }
    LaunchedEffect(captureId) { captureId?.let { reviewController.load(it) } }
    val detailState by detailController.state.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry && memoryId == null && captureId == null,
                        onClick = {
                            memoryId = null
                            captureId = null
                            tab = entry
                        },
                        icon = { TabIcon(entry) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                memoryId != null -> MemoryDetailScreen(
                    state = detailState,
                    onBack = { memoryId = null },
                )
                captureId != null -> CaptureReviewScreen(
                    controller = reviewController,
                    onBack = { captureId = null },
                )
                else -> when (tab) {
                    NavTab.GALLERY -> GalleryScreen(
                        controller = galleryController,
                        onOpenMemory = { id -> memoryId = id },
                    )
                    NavTab.ASK -> AskScreen(
                        controller = askController,
                        onOpenMemory = { id -> memoryId = id },
                    )
                    NavTab.PRIVACY -> PrivacyScreen(
                        controller = privacyController,
                        watcherSettings = watcherSettings,
                    )
                    NavTab.SUPPORT -> SupportPlaceholderScreen(
                        updateController = updateController,
                        versionName = versionName,
                        versionCode = versionCode,
                    )
                }
            }
        }
    }
}

/**
 * Minimal vector icons drawn inline (no icon dependency): a dot grid, a speech bubble, a
 * shield, and a heart.
 */
@Composable
private fun TabIcon(tab: NavTab) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.padding(2.dp)) {
        val w = size.width
        val h = size.height
        when (tab) {
            NavTab.GALLERY -> {
                val cell = w / 4f
                for (row in 0..1) for (col in 0..1) {
                    drawCircle(color, radius = cell * 0.55f, center = androidx.compose.ui.geometry.Offset(cell * (0.75f + col * 1.5f), cell * (0.75f + row * 1.5f)))
                }
            }
            NavTab.ASK -> {
                drawRoundRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.18f),
                    size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.5f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.18f),
                )
            }
            NavTab.PRIVACY -> {
                drawRoundRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.12f),
                    size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.72f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.16f),
                )
            }
            NavTab.SUPPORT -> {
                drawCircle(color, radius = w * 0.16f, center = androidx.compose.ui.geometry.Offset(w * 0.35f, h * 0.38f))
                drawCircle(color, radius = w * 0.16f, center = androidx.compose.ui.geometry.Offset(w * 0.65f, h * 0.38f))
                drawRoundRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.19f, h * 0.38f),
                    size = androidx.compose.ui.geometry.Size(w * 0.62f, h * 0.34f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f),
                )
            }
        }
    }
}
