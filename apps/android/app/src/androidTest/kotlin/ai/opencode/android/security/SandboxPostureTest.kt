package ai.opencode.android.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Filesystem invariants that only a real device can show.
 *
 * These are the M10 threat review's positive findings, pinned so they cannot
 * regress quietly. Each is something a plausible refactor would break: moving a
 * binary into `filesDir` to "make packaging simpler", or widening a directory's
 * permissions to work around a copy failure.
 *
 * A JVM unit test cannot cover any of it - Robolectric has no W^X and no real
 * uid separation, so the assertions would pass without meaning anything.
 */
@RunWith(AndroidJUnit4::class)
class SandboxPostureTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val nativeDir: File get() = File(context.applicationInfo.nativeLibraryDir)

    @Test
    fun runtimeBinariesAreNotWritable() {
        // W^X: from API 29 the app may not exec a file it can write, which is the
        // whole reason the runtime ships as lib*.so in nativeLibraryDir rather
        // than being unpacked into filesDir (ADR-0023). If this ever becomes
        // writable, either the packaging moved or the directory is not what we
        // think it is - and exec would start failing on real devices.
        val binary = File(nativeDir, "libnode.so")
        if (!binary.exists()) return // a build without the runtime staged is legitimate

        assertTrue("the runtime binary must exist to be executed", binary.canRead())
        assertFalse("an executable the app can write cannot be exec'd on API 29+", binary.canWrite())
    }

    @Test
    fun theNativeLibraryDirectoryIsNotWritable() {
        val staged = File(nativeDir, "libnode.so")
        if (!staged.exists()) return

        // Writing *into* the directory would allow replacing a binary wholesale,
        // which is the same defect one level up.
        assertFalse("nativeLibraryDir must not accept new files", nativeDir.canWrite())
    }

    @Test
    fun appPrivateStorageIsNotReadableByOtherApps() {
        // The single control standing between another installed app and the
        // provider credentials, the session database and every project file.
        //
        // The property is "others cannot read or write", NOT "others have no
        // bits at all": Android creates these directories 0751, so `others` gets
        // execute. That permits traversing to a known path and nothing more -
        // the files inside are 0600 - and it is the platform's own default, so
        // asserting against it would be asserting the wrong thing. Measured
        // `rwxrwx--x` on the device, which is exactly that.
        for (directory in listOf(context.filesDir, context.cacheDir, context.noBackupFilesDir)) {
            val permissions = java.nio.file.Files.getPosixFilePermissions(directory.toPath())
            val mode = directory.toModeString()

            assertFalse(
                "$directory is readable by other apps (mode $mode)",
                permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ),
            )
            assertFalse(
                "$directory is writable by other apps (mode $mode)",
                permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE),
            )
        }
    }

    @Test
    fun writtenFilesAreNotWorldReadable() {
        // MODE_PRIVATE is the default, but a stray MODE_WORLD_READABLE or an
        // explicit chmod would not fail anything else.
        val probe = File(context.filesDir, "posture-probe.txt")
        try {
            probe.writeText("x")
            assertFalse("a file the app writes must not be readable by others", probe.isWorldAccessible())
        } finally {
            probe.delete()
        }
    }

    /** POSIX mode as `rwxrwxrwx`, via the same stat the shell would use. */
    private fun File.toModeString(): String {
        val permissions = java.nio.file.Files.getPosixFilePermissions(toPath())
        return buildString {
            for (permission in java.nio.file.attribute.PosixFilePermission.entries) {
                append(
                    if (permission in permissions) {
                        when (permission.name.substringAfterLast('_')) {
                            "READ" -> 'r'
                            "WRITE" -> 'w'
                            else -> 'x'
                        }
                    } else {
                        '-'
                    },
                )
            }
        }
    }

    private fun File.isWorldAccessible(): Boolean =
        java.nio.file.Files.getPosixFilePermissions(toPath()).any { it.name.startsWith("OTHERS_") }
}
