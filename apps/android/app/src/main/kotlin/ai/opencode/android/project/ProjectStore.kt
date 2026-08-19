package ai.opencode.android.project

import ai.opencode.android.util.SafeLog
import java.io.File

/**
 * Where code lives on the device.
 *
 * **Projects are real directories with real POSIX paths.** That is not a
 * preference, it is a requirement: the OpenCode server is a Node process, and
 * Node cannot open a `content://` URI. Anything the Storage Access Framework
 * hands back is unusable as a working directory, so a project the runtime can
 * work in has to be a genuine path - and the only directories an app owns
 * outright are the ones under its own data directory.
 *
 * So the store is app-private (`filesDir/projects/<slug>`) and the Storage Access
 * Framework is used for *movement* - importing an existing folder in, exporting
 * changes back out - rather than for the working copy itself. See ADR-0024.
 *
 * This also settles the permissions question: no `MANAGE_EXTERNAL_STORAGE`, no
 * `READ_EXTERNAL_STORAGE`, nothing broad at all. The user grants access to one
 * tree at a time, when they import, and it is revoked when they are done.
 */
class ProjectStore(private val root: File) {

    data class Project(val slug: String, val name: String, val directory: File) {
        /** What the runtime is given as a working directory. */
        val path: String get() = directory.absolutePath
    }

    fun list(): List<Project> {
        val directories = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        return directories.sortedBy { it.name }.map { Project(it.name, readName(it), it) }
    }

    fun find(slug: String): Project? =
        list().firstOrNull { it.slug == slug }

    /**
     * Creates a project directory for a display name.
     *
     * The name the user typed is kept in a metadata file rather than encoded in
     * the path, because a display name can contain anything and a path cannot.
     */
    fun create(name: String): Project {
        val slug = uniqueSlug(name)
        val directory = File(root, slug)
        directory.mkdirs()
        File(directory, NAME_FILE).writeText(name.trim().ifEmpty { slug })
        SafeLog.d("created project $slug")
        return Project(slug, readName(directory), directory)
    }

    fun delete(slug: String): Boolean {
        val project = find(slug) ?: return false
        return project.directory.deleteRecursively()
    }

    private fun readName(directory: File): String =
        runCatching { File(directory, NAME_FILE).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: directory.name

    private fun uniqueSlug(name: String): String {
        val base = slug(name)
        if (!File(root, base).exists()) return base
        // Two projects can legitimately be called the same thing; the directory
        // cannot. Suffix rather than reject, so importing twice does not fail.
        var index = 2
        while (File(root, "$base-$index").exists()) index++
        return "$base-$index"
    }

    companion object {
        const val NAME_FILE = ".opencode-name"

        /** Reserved on some filesystems, and confusing as a directory name. */
        private val RESERVED = setOf(".", "..", "")

        /**
         * Turns a display name into something safe to use as a directory name.
         *
         * Names arrive from the user and from imported folder names, so this is a
         * boundary: a name containing separators or traversal must not be able to
         * place a project outside the store.
         */
        fun slug(name: String): String {
            val cleaned = name.trim()
                .lowercase()
                .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
                .joinToString("")
                .trim('-')
                .replace(Regex("-{2,}"), "-")
                .take(60)
            return if (cleaned in RESERVED) "project" else cleaned.ifEmpty { "project" }
        }
    }
}
