package dev.shipyu.mindverse

import android.app.Activity.ScreenCaptureCallback
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.shipyu.mindverse.privacy.PrivacyController
import dev.shipyu.mindverse.privacy.PrivacyScreen
import dev.shipyu.mindverse.triggers.screenshot.ScreenshotIngestHandler
import dev.shipyu.mindverse.triggers.screenshot.WatcherSettings
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Composition root until the nav shell lands (ticket 06). Also hosts the screenshot
 * watcher's `ScreenCaptureCallback` (ticket 05): the API only reports screenshots taken
 * while the app is visible, so registration follows the activity lifecycle.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var privacyController: PrivacyController
    @Inject lateinit var screenshotHandler: ScreenshotIngestHandler
    @Inject lateinit var watcherSettings: WatcherSettings

    private val screenCaptureCallback = ScreenCaptureCallback {
        lifecycleScope.launch { screenshotHandler.onScreenshotDetected() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    PrivacyScreen(
                        controller = privacyController,
                        watcherSettings = watcherSettings,
                    )
                }
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
}
