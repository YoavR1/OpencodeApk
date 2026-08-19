package ai.opencode.android.project

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import ai.opencode.android.util.SafeLog
import java.io.File

/**
 * Copies a folder the user picked into a project the runtime can work in.
 *
 * The Storage Access Framework hands back `content://` URIs. Node cannot open
 * one, so the tree is copied into app-private storage where it has a real path
 * (ADR-0024). The user grants access to exactly one tree, for exactly as long as
 * the import takes - no broad storage permission is requested or needed.
 *
 * Copying is bounded on purpose. A phone is not a workstation: an accidental
 * import of a photo library or a `node_modules` tree would fill the device and
 * take minutes, so the limits below stop rather than grind, and report what they
 * skipped instead of pretending the import was complete.
 */
class ProjectImport(private val context: Context) {

    data class Result(
        val files: Int,
        val bytes: Long,
        val skippedLarge: List<String>,
        val skippedDirectories: List<String>,
        val stoppedEarly: Boolean,
    ) {
        val complete: Boolean get() = !stoppedEarly && skippedLarge.isEmpty()
    }

    /**
     * Copies [tree] into [destination].
     *
     * @param destination an existing project directory; contents are merged in.
     */
    fun copyInto(tree: Uri, destination: File): Result {
        val files = mutableListOf<String>()
        val skippedLarge = mutableListOf<String>()
        val skippedDirectories = mutableListOf<String>()
        var bytes = 0L
        var count = 0
        var stoppedEarly = false

        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return Result(0, 0, emptyList(), emptyList(), true)

        val queue = ArrayDeque(listOf(rootId to destination))
        while (queue.isNotEmpty()) {
            val (documentId, target) = queue.removeFirst()
            if (count >= MAX_FILES || bytes >= MAX_BYTES) {
                stoppedEarly = true
                break
            }

            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val cursor = runCatching {
                context.contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    null,
                    null,
                    null,
                )
            }.getOrNull() ?: continue

            cursor.use {
                while (it.moveToNext()) {
                    val childId = it.getString(0)
                    val name = it.getString(1) ?: continue
                    val mime = it.getString(2)
                    val size = if (it.isNull(3)) 0L else it.getLong(3)

                    if (!safeName(name)) {
                        SafeLog.d("skipping a document with an unusable name")
                        continue
                    }

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (name in EXCLUDED_DIRECTORIES) {
                            skippedDirectories += name
                            continue
                        }
                        val child = File(target, name)
                        child.mkdirs()
                        queue.addLast(childId to child)
                        continue
                    }

                    if (size > MAX_FILE_BYTES) {
                        // Big binaries are exactly what a model cannot read and
                        // what fills a phone. Named, not silently dropped.
                        skippedLarge += name
                        continue
                    }
                    if (count >= MAX_FILES || bytes + size > MAX_BYTES) {
                        stoppedEarly = true
                        return@use
                    }

                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                    val written = runCatching {
                        context.contentResolver.openInputStream(documentUri)?.use { input ->
                            File(target, name).outputStream().use { output -> input.copyTo(output) }
                        } ?: 0L
                    }.getOrElse {
                        SafeLog.w("could not copy a file during import", it)
                        0L
                    }
                    bytes += written
                    count++
                    files += name
                }
            }
        }

        SafeLog.d("imported $count file(s), ${bytes / 1024}KB")
        return Result(count, bytes, skippedLarge, skippedDirectories, stoppedEarly)
    }

    /** Document names come from another app; a separator or `..` must not escape. */
    private fun safeName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')

    companion object {
        /** Roughly a large source tree; well short of filling a phone. */
        const val MAX_FILES = 20_000
        const val MAX_BYTES = 512L * 1024 * 1024
        const val MAX_FILE_BYTES = 32L * 1024 * 1024

        /**
         * Never worth copying: reproducible from the manifest, and usually larger
         * than everything else combined.
         */
        val EXCLUDED_DIRECTORIES = setOf(
            "node_modules", ".git", ".gradle", "build", "dist", "out", "target",
            ".venv", "venv", "__pycache__", ".next", ".nuxt", ".cache", "Pods",
        )
    }
}
