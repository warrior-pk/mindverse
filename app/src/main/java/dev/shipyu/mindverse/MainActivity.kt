package dev.shipyu.mindverse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import dagger.hilt.android.AndroidEntryPoint
import dev.shipyu.mindverse.privacy.PrivacyController
import dev.shipyu.mindverse.privacy.PrivacyScreen
import javax.inject.Inject

/**
 * Composition root until the nav shell lands (ticket 06). For now the Privacy screen
 * (ticket 03's only UI) is shown directly.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var privacyController: PrivacyController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    PrivacyScreen(privacyController)
                }
            }
        }
    }
}
