package com.synckro.util.logging

import android.content.Context
import androidx.core.content.FileProvider
import com.synckro.data.repository.SyncEventRepository
import com.synckro.domain.model.SyncEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundles the structured [SyncEvent] log and all rolled Timber log files into a
 * single shareable zip archive placed in the app's cache directory.
 *
 * The export zip contains:
 * - `synckro-events-<timestamp>.csv` — all recent structured events from Room.
 * - `synckro-debug.log` (if present) — the current Timber rolling log.
 * - `synckro-debug.log.N` (N = 1…[FileLoggingTree.DEFAULT_MAX_BACKUPS]) — rotated backups.
 *
 * After creation, the caller receives a `content://` URI from [FileProvider] that can be
 * passed to [android.content.Intent.ACTION_SEND] or written to MediaStore Downloads.
 *
 * **Security note**: this class never reads or writes OAuth tokens, refresh tokens,
 * or other credential material.  The only personally-identifiable information that
 * may appear is the account email address, which is already present in the Timber log
 * and structured events written by other components.
 */
@Singleton
class LogExporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val syncEventRepository: SyncEventRepository,
    ) {
        /**
         * Creates the export zip and returns a [FileProvider] `content://` URI pointing to it.
         *
         * Must be called from a coroutine; disk I/O is dispatched to [Dispatchers.IO].
         *
         * @return A `content://` URI that can be passed to [android.content.Intent.ACTION_SEND]
         *         or written to [android.provider.MediaStore] Downloads.
         */
        suspend fun export(): android.net.Uri =
            withContext(Dispatchers.IO) {
                // Apply the build-variant visibility gate so DEBUG entries are excluded
                // from release-build exports (parity with the on-screen Logs tab).
                val events = filterVisibleForExport(syncEventRepository.getAll())
                val logFiles = collectLogFiles(context)
                val timestamp = timestampFormat.format(Date())
                val outDir = File(context.cacheDir, CACHE_SUBDIR).also { it.mkdirs() }
                val zipFile = buildExportZip(events, logFiles, outDir, timestamp)
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    zipFile,
                )
            }

        companion object {
            /** Sub-directory of [android.content.Context.getCacheDir] used for export zips. */
            private const val CACHE_SUBDIR = "log_export"

            private val timestampFormat: SimpleDateFormat
                get() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

            /**
             * Collects all Timber log files that exist on disk.
             *
             * Returns the primary [FileLoggingTree.LOG_RELATIVE_PATH] file first, followed
             * by any numbered backups (`synckro-debug.log.1` … `.N`).
             */
            internal fun collectLogFiles(context: Context): List<File> {
                val logFile = File(context.filesDir, FileLoggingTree.LOG_RELATIVE_PATH)
                val logDir = logFile.parentFile ?: return emptyList()
                return buildList {
                    if (logFile.exists()) add(logFile)
                    for (i in 1..FileLoggingTree.DEFAULT_MAX_BACKUPS) {
                        val backup = File(logDir, "synckro-debug.log.$i")
                        if (backup.exists()) add(backup)
                    }
                }
            }

            /**
             * Creates the zip archive at `<outputDir>/synckro-logs-<timestamp>.zip`.
             *
             * Exposed as `internal` so it can be called directly from unit tests without
             * needing an Android [Context] or [FileProvider].
             *
             * @param events     Structured events to serialize as a CSV entry.
             * @param logFiles   Timber log files to copy verbatim into the zip.
             * @param outputDir  Directory where the zip will be written (must exist).
             * @param timestamp  Timestamp string used in file names (e.g. `"20240101-120000"`).
             * @return The created zip [File].
             */
            internal fun buildExportZip(
                events: List<SyncEvent>,
                logFiles: List<File>,
                outputDir: File,
                timestamp: String,
            ): File {
                val zipFile = File(outputDir, "synckro-logs-$timestamp.zip")
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
                    // Structured events as CSV.
                    zip.putNextEntry(ZipEntry("synckro-events-$timestamp.csv"))
                    zip.write(buildCsvBytes(events))
                    zip.closeEntry()

                    // Timber log files — copied verbatim.
                    for (logFile in logFiles) {
                        zip.putNextEntry(ZipEntry(logFile.name))
                        logFile.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                return zipFile
            }

            /**
             * Serializes [events] to a CSV byte array with a header row.
             *
             * Line endings are LF (not the CRLF required by strict RFC-4180);
             * this is intentional for readability in most text editors and log
             * viewers.
             *
             * Fields: `id`, `pairId`, `timestampMs`, `level`, `tag`, `message`.
             * String fields (`tag`, `message`) are always double-quoted and internal
             * double-quotes are escaped by doubling them.
             */
            internal fun buildCsvBytes(events: List<SyncEvent>): ByteArray =
                buildString {
                    appendLine("id,pairId,timestampMs,level,tag,message")
                    for (e in events) {
                        append(e.id).append(',')
                        append(e.pairId ?: "").append(',')
                        append(e.timestampMs).append(',')
                        append(e.level.name).append(',')
                        append('"').append(e.tag.csvEscape()).append('"').append(',')
                        append('"').append(e.message.csvEscape()).append('"')
                        appendLine()
                    }
                }.toByteArray(Charsets.UTF_8)

            /** Escapes a CSV field value by doubling any embedded double-quote characters. */
            private fun String.csvEscape() = replace("\"", "\"\"")

            /**
             * Drops any events whose level falls below [LogVisibilityConfig.minVisibleLevel].
             *
             * Exposed as `internal` so the build-variant filtering can be unit-tested
             * without an Android [Context] or [FileProvider].
             */
            internal fun filterVisibleForExport(events: List<SyncEvent>): List<SyncEvent> =
                events.filter { LogVisibilityConfig.isVisible(it.level) }
        }
    }
