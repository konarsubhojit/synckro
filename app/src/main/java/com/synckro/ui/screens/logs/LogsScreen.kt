package com.synckro.ui.screens.logs

import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.domain.model.SyncEvent
import com.synckro.domain.model.SyncEventLevel
import com.synckro.ui.components.EmptyState
import androidx.compose.material.icons.filled.History
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: () -> Unit,
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMsg = stringResource(R.string.logs_copied)
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }

    val exportSavedMsg = stringResource(R.string.logs_export_saved)
    val exportFailedMsg = stringResource(R.string.logs_export_failed)
    val exportSubject = stringResource(R.string.logs_export_subject)
    val exportChooser = stringResource(R.string.logs_export_chooser)
    val exportMediaStoreError = stringResource(R.string.logs_export_mediastore_error)
    val exportIoError = stringResource(R.string.logs_export_io_error)

    // Observe one-shot export results from the ViewModel.
    LaunchedEffect(Unit) {
        viewModel.exportResult.collect { result ->
            result.fold(
                onSuccess = { uri ->
                    handleExportUri(
                        context = context,
                        uri = uri,
                        savedMsg = exportSavedMsg,
                        failedMsg = exportFailedMsg,
                        mediaStoreError = exportMediaStoreError,
                        ioError = exportIoError,
                        subject = exportSubject,
                        chooser = exportChooser,
                    )
                },
                onFailure = { e ->
                    snackbarHostState.showSnackbar(
                        exportFailedMsg.format(e.message ?: e.javaClass.simpleName),
                    )
                },
            )
        }
    }

    val buildLogText: () -> String = {
        buildLogExportText(state.events, dateFormat)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val text = buildLogText()
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(context.getString(R.string.logs_title), text),
                        )
                        scope.launch {
                            snackbarHostState.showSnackbar(copiedMsg)
                        }
                    }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.logs_copy),
                        )
                    }
                    IconButton(onClick = {
                        val text = buildLogText()
                        val intent =
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                                putExtra(
                                    Intent.EXTRA_SUBJECT,
                                    context.getString(R.string.logs_share_subject),
                                )
                            }
                        context.startActivity(
                            Intent.createChooser(intent, context.getString(R.string.logs_share_chooser)),
                        )
                    }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.logs_share),
                        )
                    }
                    // Export action: bundles structured events + Timber logs into a zip.
                    // Always available in both debug and release builds.
                    IconButton(onClick = { viewModel.exportLogs() }) {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = stringResource(R.string.logs_export),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (!state.isLoading && state.events.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.logs_empty_title),
                body = stringResource(R.string.logs_empty_body),
                icon = Icons.Filled.History,
                primaryActionLabel = stringResource(R.string.logs_empty_cta),
                onPrimaryAction = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.events, key = { it.id }) { event ->
                    LogEntryRow(event = event, dateFormat = dateFormat)
                }
            }
        }
    }
}

/**
 * Handles a successfully created export zip URI.
 *
 * On API 29+, copies the zip into MediaStore Downloads (no permission required).
 * On API 26–28, fires an [Intent.ACTION_SEND] share-chooser via [FileProvider].
 */
private fun handleExportUri(
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
            resolver.openOutputStream(itemUri)?.use { out ->
                resolver.openInputStream(uri)?.use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            Toast.makeText(context, savedMsg.format(fileName), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            Toast.makeText(context, failedMsg.format(e.localizedMessage ?: ioError), Toast.LENGTH_LONG).show()
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

@Composable
private fun LogEntryRow(
    event: SyncEvent,
    dateFormat: SimpleDateFormat,
) {
    val levelColor =
        when (event.level) {
            SyncEventLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
            SyncEventLevel.INFO -> MaterialTheme.colorScheme.onSurface
            SyncEventLevel.WARN -> Color(0xFFF59E0B) // Amber-500
            SyncEventLevel.ERROR -> MaterialTheme.colorScheme.error
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dateFormat.format(Date(event.timestampMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = event.level.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = levelColor,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = event.tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = event.message,
                style = MaterialTheme.typography.bodySmall,
                color = levelColor,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun SyncEvent.toLogLine(dateFormat: SimpleDateFormat): String {
    val ts = dateFormat.format(Date(timestampMs))
    return "$ts ${level.name.padEnd(5)} [$tag] $message"
}

/**
 * Maximum number of log entries to include in copy / share output.
 *
 * Building one giant string for many thousands of entries can cause UI jank,
 * out-of-memory errors, or ANRs (issue #93). When the event count exceeds
 * this cap, only the most recent entries are exported and the truncation is
 * indicated with a leading `… N earlier entries omitted …` line.
 */
internal const val MAX_COPY_SHARE_ENTRIES: Int = 1_000

/**
 * Builds the plain-text log payload used by the copy and share actions.
 *
 * Limits the output to the most recent [MAX_COPY_SHARE_ENTRIES] events. When
 * truncation occurs, the first line of the result describes how many earlier
 * entries were omitted.
 */
internal fun buildLogExportText(
    events: List<SyncEvent>,
    dateFormat: SimpleDateFormat,
): String {
    val total = events.size
    if (total <= MAX_COPY_SHARE_ENTRIES) {
        return events.joinToString("\n") { it.toLogLine(dateFormat) }
    }
    val omitted = total - MAX_COPY_SHARE_ENTRIES
    val recent = events.subList(total - MAX_COPY_SHARE_ENTRIES, total)
    return buildString {
        append("… $omitted earlier entries omitted (showing most recent $MAX_COPY_SHARE_ENTRIES)\n")
        recent.joinTo(this, separator = "\n") { it.toLogLine(dateFormat) }
    }
}
