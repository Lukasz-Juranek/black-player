package com.lj.blackplayer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Track(val uri: Uri, val name: String, val path: String)

fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(uri.toString())
    .setUri(uri)
    .setMediaMetadata(MediaMetadata.Builder().setDisplayTitle(name).build())
    .build()

/** Recursively lists audio files in a folder picked through the Storage Access Framework. */
object Library {
    private val audioExtensions = setOf(
        "mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "wma", "mka", "alac", "aiff"
    )

    fun scan(context: Context, tree: Uri): List<Track> {
        val out = mutableListOf<Track>()
        walk(context.contentResolver, tree, DocumentsContract.getTreeDocumentId(tree), "", out)
        return out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path })
    }

    fun folderLabel(tree: Uri): String =
        DocumentsContract.getTreeDocumentId(tree).substringAfter(':').substringAfterLast('/')
            .ifEmpty { "Storage" }

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
