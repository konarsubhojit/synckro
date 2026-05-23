package com.synckro.data.worker

import androidx.work.Data
import androidx.work.workDataOf
import com.synckro.domain.sync.ActiveTransfer
import com.synckro.domain.sync.TransferProgress
import com.synckro.domain.sync.TransferDirection
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
                activeTransfers =
                    listOf(
                        ActiveTransfer(
                            relativePath = "docs/readme.md",
                            direction = TransferDirection.DOWNLOAD,
                            bytesTransferred = 2_048L,
                            totalBytes = 8_192L,
                        ),
                    ),
            )

        val parsed =
            SyncWorker.parseProgress(
                workDataOf(
                    SyncWorker.PROGRESS_FILES_COMPLETED to expected.filesCompleted,
                    SyncWorker.PROGRESS_TOTAL_FILES to expected.totalFiles,
                    SyncWorker.PROGRESS_BYTES_XFERRED to expected.bytesTransferred,
                    SyncWorker.PROGRESS_TOTAL_BYTES to expected.totalBytes,
                    SyncWorker.PROGRESS_CURRENT_FILE to expected.currentFileName,
                    SyncWorker.PROGRESS_ACTIVE_TRANSFERS to
                        "docs%2Freadme.md|DOWNLOAD|2048|8192",
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
                activeTransfers = emptyList(),
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
    fun `parseProgress decodes multiple active transfers`() {
        val parsed =
            SyncWorker.parseProgress(
                workDataOf(
                    SyncWorker.PROGRESS_FILES_COMPLETED to 1,
                    SyncWorker.PROGRESS_TOTAL_FILES to 3,
                    SyncWorker.PROGRESS_BYTES_XFERRED to 1024L,
                    SyncWorker.PROGRESS_TOTAL_BYTES to 4096L,
                    SyncWorker.PROGRESS_ACTIVE_TRANSFERS to
                        "camera%2FIMG_01.jpg|UPLOAD|512|2048\nnotes%2Ftodo.txt|DOWNLOAD|128|256",
                ),
            )

        assertEquals(2, parsed?.activeTransfers?.size)
        assertEquals("camera/IMG_01.jpg", parsed?.activeTransfers?.get(0)?.relativePath)
        assertEquals(TransferDirection.UPLOAD, parsed?.activeTransfers?.get(0)?.direction)
        assertEquals("notes/todo.txt", parsed?.activeTransfers?.get(1)?.relativePath)
        assertEquals(TransferDirection.DOWNLOAD, parsed?.activeTransfers?.get(1)?.direction)
    }
}
