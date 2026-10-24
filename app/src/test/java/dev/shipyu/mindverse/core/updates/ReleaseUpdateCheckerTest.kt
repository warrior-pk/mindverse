package dev.shipyu.mindverse.core.updates

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Update-check seam (ticket 08): GitHub Releases payload → versionCode comparison →
 * APK-asset deep link. Fixtures are worked release payloads per the repo's release recipe.
 */
class ReleaseUpdateCheckerTest {

    private lateinit var server: mockwebserver3.MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = mockwebserver3.MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun checker() = ReleaseUpdateChecker(
        client = client,
        apiUrl = server.url("/releases/latest").toString(),
        currentVersionCode = 7,
        currentVersionName = "0.4.0",
    )

    private fun release(
        body: String,
        tag: String = "v0.5.0",
        prerelease: Boolean = false,
        assets: String = """[{"name":"mindverse-9-play.apk","browser_download_url":"https://dl/mindverse-9-play.apk"}]""",
    ) = """
        {
          "tag_name": "$tag",
          "name": "Release $tag",
          "html_url": "https://github.com/warrior-pk/mindverse/releases/tag/$tag",
          "body": ${body.let { "\"${it.replace("\"", "\\\"")}\"" }},
          "prerelease": $prerelease,
          "assets": $assets
        }
    """.trimIndent()

    @Test
    fun `newer release with versionCode marker surfaces the apk asset`() = runBlocking {
        server.enqueue(mockwebserver3.MockResponse.Builder().code(200).body(release(body = "Shiny new stuff\\nversionCode: 9")).build())

        val outcome = checker().check() as UpdateOutcome.Available
        assertEquals(9, outcome.versionCode)
        assertEquals("0.5.0", outcome.versionName)
        assertEquals("https://dl/mindverse-9-play.apk", outcome.apkUrl)
        assertEquals("https://github.com/warrior-pk/mindverse/releases/tag/v0.5.0", outcome.releaseUrl)
    }

    @Test
    fun `equal or older releaseCode is up to date`() = runBlocking {
        server.enqueue(mockwebserver3.MockResponse.Builder().code(200).body(release(body = "versionCode: 7")).build())
        assertTrue(checker().check() is UpdateOutcome.UpToDate)

        server.enqueue(mockwebserver3.MockResponse.Builder().code(200).body(release(body = "versionCode: 3")).build())
        assertTrue(checker().check() is UpdateOutcome.UpToDate)
    }

    @Test
    fun `prereleases are ignored`() = runBlocking {
        server.enqueue(mockwebserver3.MockResponse.Builder().code(200).body(release(body = "versionCode: 99", prerelease = true)).build())
        assertTrue(checker().check() is UpdateOutcome.UpToDate)
    }

    @Test
    fun `asset name carries the versionCode when the body does not`() = runBlocking {
        server.enqueue(
            mockwebserver3.MockResponse.Builder().code(200).body(
                release(body = "no marker here", assets = """[{"name":"mindverse-12-fdroid.apk","browser_download_url":"https://dl/12.apk"}]"""),
            ).build(),
        )
        val outcome = checker().check() as UpdateOutcome.Available
        assertEquals(12, outcome.versionCode)
        assertEquals("https://dl/12.apk", outcome.apkUrl)
    }

    @Test
    fun `tag fallback compares versionName segments`() = runBlocking {
        server.enqueue(mockwebserver3.MockResponse.Builder().code(200).body(release(body = "", tag = "v0.10.0", assets = "[]")).build())
        val newer = checker().check() as UpdateOutcome.Available
        assertEquals(8, newer.versionCode) // currentCode + 1 via tag comparison
        assertEquals(null, newer.apkUrl)

        server.enqueue(mockwebserver3.MockResponse.Builder().code(200).body(release(body = "", tag = "v0.3.0", assets = "[]")).build())
        assertTrue(checker().check() is UpdateOutcome.UpToDate)
    }

    @Test
    fun `http and payload failures degrade to a typed failure`() = runBlocking {
        server.enqueue(mockwebserver3.MockResponse.Builder().code(404).body("{}").build())
        val http = checker().check() as UpdateOutcome.Failed
        assertTrue(http.reason.contains("404"))

        server.enqueue(mockwebserver3.MockResponse.Builder().code(200).body("not json at all").build())
        val malformed = checker().check() as UpdateOutcome.Failed
        assertTrue(malformed.reason.contains("malformed"))

        // No parseable version anywhere → typed failure, not a bogus update prompt.
        server.enqueue(mockwebserver3.MockResponse.Builder().code(200).body("""{"tag_name":"","assets":[]}""").build())
        val noVersion = checker().check() as UpdateOutcome.Failed
        assertNotNull(noVersion.reason)
    }
}
