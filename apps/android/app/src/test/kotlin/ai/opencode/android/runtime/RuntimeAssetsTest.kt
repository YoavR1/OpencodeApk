package ai.opencode.android.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowPackageManager

/**
 * When the extracted runtime is replaced.
 *
 * This is a security property rather than a caching detail: the extracted copy
 * is the JavaScript the server executes, so a marker that fails to notice a new
 * APK leaves the device running old code while reporting the new version. M10
 * found exactly that on a device - the launcher on disk was two milestones
 * behind the one in the APK.
 */
@RunWith(RobolectricTestRunner::class)
class RuntimeAssetsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val shadowPackages: ShadowPackageManager
        get() = Shadows.shadowOf(context.packageManager)

    private fun installedAt(time: Long) {
        shadowPackages.getInternalMutablePackageInfo(context.packageName).lastUpdateTime = time
    }

    private val marker: File get() = File(context.filesDir, "runtime/.installed")

    @Before
    fun setUp() {
        File(context.filesDir, "runtime").deleteRecursively()
        installedAt(1_000L)
    }

    @Test
    fun theFirstInstallExtractsAndLeavesAMarker() {
        RuntimeAssets(context, "0.1.0").install()

        assertTrue("install() must record what it extracted", marker.exists())
        assertTrue(marker.readText().isNotBlank())
    }

    @Test
    fun reinstallingTheSameApkDoesNotReExtract() {
        // The reason the marker exists: a 37 MB copy on every launch would be a
        // visible delay for no reason.
        RuntimeAssets(context, "0.1.0").install()
        val first = marker.readText()

        RuntimeAssets(context, "0.1.0").install()

        assertEquals("nothing changed, so nothing should have been copied", first, marker.readText())
    }

    @Test
    fun aNewApkWithAnIdenticalVersionNameStillReExtracts() {
        // The defect this pins. versionName is a constant during development, so
        // keying on it alone means editing a bundled file, rebuilding and
        // reinstalling leaves the OLD copy running - and the app reports the new
        // version while executing the old code.
        RuntimeAssets(context, "0.1.0-m2").install()
        val before = marker.readText()

        installedAt(2_000L) // what the package manager does on every reinstall
        RuntimeAssets(context, "0.1.0-m2").install()

        assertNotEquals(
            "a reinstall must re-extract even when the version name has not moved",
            before,
            marker.readText(),
        )
    }

    @Test
    fun aNewVersionReExtracts() {
        RuntimeAssets(context, "0.1.0").install()
        val before = marker.readText()

        installedAt(2_000L)
        RuntimeAssets(context, "0.2.0").install()

        assertNotEquals(before, marker.readText())
    }

    @Test
    fun aMarkerFromAnotherBuildIsReplacedRatherThanTrusted() {
        // Stands in for an upgrade over an install whose marker is unrecognised:
        // the safe reading of "I do not know what is here" is to extract again.
        File(context.filesDir, "runtime").mkdirs()
        marker.writeText("a marker this build has never written")

        RuntimeAssets(context, "0.1.0").install()

        assertNotEquals("a marker this build has never written", marker.readText())
    }

    @Test
    fun theExtractedRootIsInsideAppPrivateStorage() {
        // The bundle is executable content in the sense that Node reads and runs
        // it. It must not be anywhere another app can write.
        val root = RuntimeAssets(context, "0.1.0").root
        assertTrue(root.canonicalPath.startsWith(context.filesDir.canonicalPath))
    }
}
