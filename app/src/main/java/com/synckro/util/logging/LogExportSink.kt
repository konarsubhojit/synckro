package com.synckro.util.logging

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast

/**
 * Shared implementation of the "log export finished" side-effect used by both the
 * standalone Logs screen and any host (e.g. [com.synckro.ui.navigation.MainScaffold])
 * that exposes the export action.
 *
 * On API 29+ the produced zip is copied into MediaStore Downloads (no runtime
 * permission needed). On API 26–28 we fall back to an `ACTION_SEND` share-chooser
 * via FileProvider. This keeps the export flow free of
 * `MANAGE_EXTERNAL_STORAGE`.
 *
 * Extracted from the duplicated `handleExportUri` / `handleHomeExportUri` helpers
 * that previously lived in `LogsScreen.kt` and `HomeScreen.kt` (issue: UX Phase 1).
 */
object LogExportSink {
    /**
     * Handles a successfully created export zip [uri].
     *
     * All user-visible strings are passed in so this object can stay free of
     * Compose / resource dependencies and be reused from anywhere that already
     * has localized strings on hand.
     */
    fun handleExportUri(
        context: Context,
        uri: Uri,
        savedMsg: String,
        failedMsg: String,
        mediaStoreError: String,
        ioError: String,
        subject: String,
        chooser: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val fileName = uri.lastPathSegment ?: "synckro-logs.zip"
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri =
                resolver.insert(collection, values) ?: run {
                    Toast.makeText(context, mediaStoreError, Toast.LENGTH_LONG).show()
                    return
                }
            try {
                val out =
                    resolver.openOutputStream(itemUri)
                        ?: throw IllegalStateException(
                            "Failed to open output stream for MediaStore Downloads entry: $itemUri",
                        )
                val ins =
                    resolver.openInputStream(uri)
                        ?: throw IllegalStateException("Failed to open input stream for export zip: $uri")
                out.use { o -> ins.use { i -> i.copyTo(o) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
                Toast.makeText(context, savedMsg.format(fileName), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                runCatching { resolver.delete(itemUri, null, null) }
                Toast.makeText(
                    context,
                    failedMsg.format(e.localizedMessage ?: ioError),
                    Toast.LENGTH_LONG,
                ).show()
            }
        } else {
            val intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            context.startActivity(Intent.createChooser(intent, chooser))
        }
    }
}
