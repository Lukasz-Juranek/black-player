package com.lj.blackplayer

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.DocumentsContract.Document
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Track(val uri: Uri, val name: String, val path: String)

fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(uri.toString())
    .setUri(uri)
    .setMediaMetadata(MediaMetadata.Builder().setDisplayTitle(name).build())
    .build()

/**
 * Lists audio files either from a folder picked through the Storage Access Framework (recursively),
 * or from the Download folder via MediaStore — Android 11+ refuses to let the picker grant Download itself.
 */
object Library {
    const val DOWNLOADS = "downloads"

    val audioPermission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val audioExtensions = setOf(
        "mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "wma", "mka", "alac", "aiff"
    )

    fun scan(context: Context, source: String): List<Track> {
        val out = if (source == DOWNLOADS) {
            downloads(context.contentResolver)
        } else {
            val tree = Uri.parse(source)
            mutableListOf<Track>().also {
                walk(context.contentResolver, tree, DocumentsContract.getTreeDocumentId(tree), "", it)
            }
        }
        return out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path })
    }

    /** A single file opened from another app, named by its display name when the provider has one. */
    fun opened(context: Context, uri: Uri): Track {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Audio"
        return Track(uri, name.substringBeforeLast('.'), name)
    }

    fun label(source: String): String =
        if (source == DOWNLOADS) "Downloads"
        else DocumentsContract.getTreeDocumentId(Uri.parse(source)).substringAfter(':')
            .substringAfterLast('/').ifEmpty { "Storage" }

    private fun downloads(cr: ContentResolver): List<Track> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        // RELATIVE_PATH exists from Android 10; older versions only expose the absolute path.
        val pathColumn =
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH
            else @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        val selection = "$pathColumn LIKE ?"
        val arg =
            if (Build.VERSION.SDK_INT >= 29) "${Environment.DIRECTORY_DOWNLOADS}/%"
            else "%/${Environment.DIRECTORY_DOWNLOADS}/%"
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, pathColumn)
        val out = mutableListOf<Track>()
        cr.query(collection, columns, selection, arrayOf(arg), null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val path = c.getString(2) ?: ""
                val relative =
                    if (Build.VERSION.SDK_INT >= 29) path
                    else path.substringAfter("/${Environment.DIRECTORY_DOWNLOADS}/").substringBeforeLast('/', "")
                out += Track(
                    ContentUris.withAppendedId(collection, c.getLong(0)),
                    name.substringBeforeLast('.'),
                    relative.trimEnd('/') + "/" + name
                )
            }
        }
        return out
    }

    private fun walk(cr: ContentResolver, tree: Uri, docId: String, prefix: String, out: MutableList<Track>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        cr.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                if (name.startsWith(".")) continue
                if (mime == Document.MIME_TYPE_DIR) {
                    walk(cr, tree, id, "$prefix$name/", out)
                } else if (mime.startsWith("audio/") ||
                    name.substringAfterLast('.', "").lowercase() in audioExtensions
                ) {
                    out += Track(
                        DocumentsContract.buildDocumentUriUsingTree(tree, id),
                        name.substringBeforeLast('.'),
                        prefix + name
                    )
                }
            }
        }
    }
}
