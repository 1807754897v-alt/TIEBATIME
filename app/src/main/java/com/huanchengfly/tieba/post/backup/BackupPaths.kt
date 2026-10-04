package com.huanchengfly.tieba.post.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-private storage layout for local backups.
 *
 * Compatible enough with Shelf export packages for the desktop EPUB converter:
 * markdowns/ + images/{exportKey}/ + posts/
 */
@Singleton
class BackupPaths @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val root: File
        get() = File(context.filesDir, "local_backup").apply { mkdirs() }

    val markdownsDir: File
        get() = File(root, "markdowns").apply { mkdirs() }

    val imagesDir: File
        get() = File(root, "images").apply { mkdirs() }

    val postsDir: File
        get() = File(root, "posts").apply { mkdirs() }

    val avatarsDir: File
        get() = File(root, "avatars").apply { mkdirs() }

    val voicesDir: File
        get() = File(root, "voices").apply { mkdirs() }

    fun imageDir(exportKey: String): File =
        File(imagesDir, exportKey).apply { mkdirs() }

    fun voiceDir(exportKey: String): File =
        File(voicesDir, exportKey).apply { mkdirs() }

    fun markdownFile(exportKey: String): File =
        File(markdownsDir, "$exportKey.md")

    fun jsonFile(exportKey: String): File =
        File(postsDir, "$exportKey.json")

    fun deleteBackupFiles(exportKey: String) {
        markdownFile(exportKey).delete()
        jsonFile(exportKey).delete()
        imageDir(exportKey).deleteRecursively()
        voiceDir(exportKey).deleteRecursively()
    }

    fun dirSizeBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    companion object {
        fun exportKey(threadId: Long, seeLz: Boolean): String =
            "${threadId}_${if (seeLz) "see_lz" else "full"}"

        fun postKey(threadId: Long, seeLz: Boolean): String =
            exportKey(threadId, seeLz)
    }
}
