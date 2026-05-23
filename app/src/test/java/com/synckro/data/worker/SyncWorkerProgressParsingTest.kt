package com.synckro.data.worker

import androidx.work.Data
import androidx.work.workDataOf
import com.synckro.domain.sync.TransferProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncWorkerProgressParsingTest {
    @Test
    fun `parseProgress returns null for empty data`() {
        assertNull(SyncWorker.parseProgress(Data.EMPTY))
    }

    @Test
    fun `parseProgress round-trips byte-based progress`() {
        val expected =
            TransferProgress(
                filesCompleted = 2,
                totalFiles = 4,
                bytesTransferred = 2_048L,
                totalBytes = 8_192L,
                currentFileName = "docs/readme.md",
            )

        val parsed =
            SyncWorker.parseProgress(
                workDataOf(
                    SyncWorker.PROGRESS_FILES_COMPLETED to expected.filesCompleted,
                    SyncWorker.PROGRESS_TOTAL_FILES to expected.totalFiles,
                    SyncWorker.PROGRESS_BYTES_XFERRED to expected.bytesTransferred,
                    SyncWorker.PROGRESS_TOTAL_BYTES to expected.totalBytes,
                    SyncWorker.PROGRESS_CURRENT_FILE to expected.currentFileName,
                ),
            )

        assertEquals(expected, parsed)
    }

    @Test
    fun `parseProgress round-trips file-count progress without byte totals`() {
        val expected =
            TransferProgress(
                filesCompleted = 3,
                totalFiles = 5,
                bytesTransferred = 0L,
                totalBytes = 0L,
                currentFileName = null,
            )

        val parsed =
            SyncWorker.parseProgress(
                workDataOf(
                    SyncWorker.PROGRESS_FILES_COMPLETED to expected.filesCompleted,
                    SyncWorker.PROGRESS_TOTAL_FILES to expected.totalFiles,
                    SyncWorker.PROGRESS_BYTES_XFERRED to expected.bytesTransferred,
                    SyncWorker.PROGRESS_TOTAL_BYTES to expected.totalBytes,
                    SyncWorker.PROGRESS_CURRENT_FILE to expected.currentFileName,
                ),
            )

        assertEquals(expected, parsed)
    }
}
