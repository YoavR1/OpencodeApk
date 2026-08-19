package ai.opencode.android.project

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Project names come from people and from imported folder names, and become
 * directory names. That makes the translation a boundary, not a formatting
 * detail: a name that escaped the store would put a working directory - and
 * therefore anything the agent writes - somewhere it does not belong.
 */
class ProjectStoreTest {

    @get:Rule val temporary = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: ProjectStore

    @Before
    fun setUp() {
        root = temporary.newFolder("projects")
        store = ProjectStore(root)
    }

    // ---- path translation ---------------------------------------------------

    @Test
    fun ordinaryNamesBecomeReadableSlugs() {
        assertEquals("my-app", ProjectStore.slug("My App"))
        assertEquals("opencode", ProjectStore.slug("opencode"))
        assertEquals("api-v2", ProjectStore.slug("API v2"))
        assertEquals("under_score", ProjectStore.slug("under_score"))
    }

    @Test
    fun separatorsCannotSurviveIntoAPath() {
        // The property that matters: no slug may contain anything that would
        // make it more than one path component.
        for (name in listOf("a/b", "a\\b", "../etc", "/absolute", "a/../b", "..")) {
            val slug = ProjectStore.slug(name)
            assertFalse("$name -> $slug still contains a separator", slug.contains('/') || slug.contains('\\'))
            assertFalse("$name -> $slug can still traverse", slug == ".." || slug.startsWith(".."))
        }
    }

    @Test
    fun traversalResolvesInsideTheStore() {
        // The end-to-end version of the property, stated in terms of the
        // filesystem rather than the string.
        val project = store.create("../../escape")
        assertTrue(
            "project at ${project.path} escaped ${root.absolutePath}",
            project.directory.canonicalPath.startsWith(root.canonicalPath + File.separator),
        )
    }

    @Test
    fun aNameThatSlugifiesToNothingStillGetsADirectory() {
        for (name in listOf("", "   ", "---", "///", "...")) {
            val project = store.create(name)
            assertTrue(project.slug.isNotEmpty())
            assertTrue(project.directory.isDirectory)
        }
    }

    @Test
    fun overlongNamesAreTruncated() {
        val slug = ProjectStore.slug("x".repeat(500))
        assertTrue("slug was ${slug.length} characters", slug.length <= 60)
    }

    @Test
    fun unicodeNamesProduceAUsableSlug() {
        // Not transliterated - just made safe. The display name keeps the original.
        val project = store.create("проект 日本語")
        assertTrue(project.slug.isNotEmpty())
        assertFalse(project.slug.contains('/'))
        assertEquals("проект 日本語", project.name)
    }

    // ---- workspace lifecycle ------------------------------------------------

    @Test
    fun aNewStoreIsEmpty() {
        assertEquals(emptyList<ProjectStore.Project>(), store.list())
    }

    @Test
    fun creatingAProjectMakesARealDirectoryWithARealPath() {
        val project = store.create("My App")

        assertTrue(project.directory.isDirectory)
        assertTrue("the runtime needs an absolute path", File(project.path).isAbsolute)
        // Not a content:// URI: the whole reason projects live here.
        assertFalse(project.path.contains("://"))
    }

    @Test
    fun theDisplayNameSurvivesSlugging() {
        val project = store.create("My App")
        assertEquals("my-app", project.slug)
        assertEquals("My App", project.name)
        assertEquals("My App", store.find("my-app")?.name)
    }

    @Test
    fun twoProjectsCanShareADisplayName() {
        // People name things the same; directories cannot be the same.
        val first = store.create("Notes")
        val second = store.create("Notes")

        assertNotEquals(first.slug, second.slug)
        assertEquals("Notes", first.name)
        assertEquals("Notes", second.name)
        assertEquals(2, store.list().size)
    }

    @Test
    fun projectsSurviveANewStoreOverTheSameDirectory() {
        // Stands in for process death and app restart.
        store.create("Kept")
        val reopened = ProjectStore(root)

        assertEquals(1, reopened.list().size)
        assertEquals("Kept", reopened.list().first().name)
    }

    @Test
    fun findReturnsNothingForAnUnknownSlug() {
        store.create("Real")
        assertNull(store.find("imaginary"))
    }

    @Test
    fun deletingRemovesTheProjectAndItsContents() {
        val project = store.create("Doomed")
        File(project.directory, "file.txt").writeText("content")

        assertTrue(store.delete(project.slug))
        assertFalse(project.directory.exists())
        assertEquals(emptyList<ProjectStore.Project>(), store.list())
    }

    @Test
    fun deletingSomethingThatIsNotThereIsNotAnError() {
        assertFalse(store.delete("never-existed"))
    }

    @Test
    fun theNameFileIsNotMistakenForAProject() {
        store.create("Real")
        // list() walks directories; a stray file at the root must not appear.
        File(root, "stray.txt").writeText("x")
        assertEquals(1, store.list().size)
    }
}
