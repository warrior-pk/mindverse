package dev.shipyu.mindverse.core.updates

import dev.shipyu.mindverse.core.data.FixedClock
import dev.shipyu.mindverse.core.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Update scheduling seam (ticket 08 / spec R6-3): manual checks + a 24h cold-start debounce. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateControllerTest {

    private lateinit var server: mockwebserver3.MockWebServer
    private lateinit var clock: FixedClock
    private lateinit var controller: UpdateController

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        clock = FixedClock(1_000_000_000L)
        controller = UpdateController(
            checker = ReleaseUpdateChecker(
                client = okhttp3.OkHttpClient(),
                apiUrl = server.url("/releases/latest").toString(),
                currentVersionCode = 7,
                currentVersionName = "0.4.0",
            ),
            appSettings = AppSettings(RuntimeEnvironment.getApplication()),
            clock = clock,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun enqueueUpToDate() {
        server.enqueue(
            MockResponse.Builder().code(200).body("""{"tag_name":"v0.4.0","body":"versionCode: 7","assets":[]}""").build(),
        )
    }

    @Test
    fun `cold start checks at most once per 24h`() = runBlocking {
        enqueueUpToDate()
        controller.checkIfDue()
        assertEquals(1, server.requestCount)

        // Within the debounce window: no second call.
        enqueueUpToDate()
        clock.advanceBy(23 * 60 * 60 * 1000L)
        controller.checkIfDue()
        assertEquals(1, server.requestCount)

        // Window elapsed: checked again.
        clock.advanceBy(2 * 60 * 60 * 1000L)
        controller.checkIfDue()
        assertEquals(2, server.requestCount)
        assertTrue(controller.state.value is UpdateUiState.UpToDate)
    }

    @Test
    fun `manual check ignores the debounce and surfaces availability`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"tag_name":"v0.5.0","html_url":"https://gh/r","body":"versionCode: 9","assets":[{"name":"a.apk","browser_download_url":"https://dl/a.apk"}]}""")
                .build(),
        )
        controller.checkIfDue()
        assertTrue(controller.state.value is UpdateUiState.Available)

        // A manual check right after an automatic one still hits the network.
        server.enqueue(
            MockResponse.Builder().code(200).body("""{"tag_name":"v0.5.0","body":"versionCode: 9","assets":[]}""").build(),
        )
        controller.checkNow()
        assertEquals(2, server.requestCount)
    }
}
