package ai.opencode.android.platform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Notification tags arriving from outside the app.
 *
 * `MainActivity` is exported because it is the launcher, so any app on the
 * device can start it carrying `EXTRA_TAG`. The tag reaches the renderer as a
 * `notification.clicked` event, which fires whatever callback the UI registered
 * for it - so the tag has to be something this process actually posted rather
 * than something it was handed.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun grantNotificationPermission() {
        // Not granted by default under Robolectric, and post() correctly refuses
        // without it - so without this the tests would assert on a code path that
        // never posts anything.
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext() as Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun aTagThisAppNeverPostedIsNotAccepted() {
        assertFalse(Notifications.claimPosted("tag-from-another-app"))
    }

    @Test
    fun aPostedTagIsAccepted() {
        val notifications = Notifications(context)
        assertTrue("post() should succeed once the permission is granted", notifications.post("Done", "body", "tag-a"))

        assertTrue(Notifications.claimPosted("tag-a"))
    }

    @Test
    fun aTagIsAcceptedOnlyOnce() {
        // A tap is one event. A replayed intent - which an exported Activity can
        // be handed at any time - is not a second tap.
        Notifications(context).post("Done", "body", "tag-b")

        assertTrue(Notifications.claimPosted("tag-b"))
        assertFalse("the same tag must not be replayable", Notifications.claimPosted("tag-b"))
    }

    @Test
    fun tagsAreNotConfusedWithOneAnother() {
        val notifications = Notifications(context)
        notifications.post("One", "body", "tag-c")
        notifications.post("Two", "body", "tag-d")

        assertTrue(Notifications.claimPosted("tag-d"))
        assertTrue("claiming one tag must not consume another", Notifications.claimPosted("tag-c"))
    }

    @Test
    fun theRememberedSetDoesNotGrowWithoutBound() {
        // A long-lived process posting many notifications must not accumulate
        // tags forever. Dropping the oldest at worst ignores a tap on a very old
        // notification.
        val notifications = Notifications(context)
        repeat(200) { notifications.post("N", "body", "bulk-$it") }

        assertFalse("the oldest tag should have been dropped", Notifications.claimPosted("bulk-0"))
        assertTrue("the most recent tag must still be honoured", Notifications.claimPosted("bulk-199"))
    }
}
