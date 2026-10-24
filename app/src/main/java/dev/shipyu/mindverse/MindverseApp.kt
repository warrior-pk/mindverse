package dev.shipyu.mindverse

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Hilt + WorkManager wiring (ticket 04): WorkManager's default initializer is removed from
 * the manifest so Hilt workers receive their dependencies through [HiltWorkerFactory].
 */
@HiltAndroidApp
class MindverseApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
