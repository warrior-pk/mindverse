package dev.shipyu.mindverse.core.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.shipyu.mindverse.core.data.db.MindverseDatabase
import dev.shipyu.mindverse.core.data.db.SchemaCallback
import dev.shipyu.mindverse.core.data.db.VecExtension
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Test harness for the storage core: in-memory Room database on the BundledSQLiteDriver
 * with the vendored sqlite-vec extension loaded from test resources (Linux x86_64 build).
 *
 * Robolectric context + native driver: the driver never touches the Android framework
 * SQLite, so the whole stack (Room + FTS5 + vec0) runs on the JVM exactly like on-device.
 */
object TestDatabaseFactory {
    // NOTE: AGP strips *.so files from unit-test Java resources, so the test copy of the
    // sqlite-vec extension is stored extension-less (vec0.bin); dlopen doesn't care about
    // the file extension. The device copy lives in jniLibs as libvec0.so.
    private const val TEST_VEC_RESOURCE = "native/linux-x86_64/vec0.bin"

    fun vecExtensionPath(): String {
        val url = checkNotNull(TestDatabaseFactory::class.java.classLoader.getResource(TEST_VEC_RESOURCE)) {
            "sqlite-vec test extension missing on the test classpath: $TEST_VEC_RESOURCE"
        }
        return File(url.toURI()).absolutePath
    }

    /** In-memory database with FTS5 + vec0 attached and the vec extension loaded. */
    fun inMemoryDatabase(context: Context): MindverseDatabase {
        val driver = BundledSQLiteDriver()
        driver.addExtension(vecExtensionPath(), VecExtension.ENTRY_POINT)
        return Room.inMemoryDatabaseBuilder(context, MindverseDatabase::class.java)
            .setDriver(driver)
            .addCallback(SchemaCallback())
            .build()
    }
}

/** Controllable clock for deterministic timestamp assertions. */
class FixedClock(var millis: Long = 1_000_000L) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(millis)

    fun advanceBy(millisDelta: Long) {
        millis += millisDelta
    }
}
