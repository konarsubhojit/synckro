package com.synckro.ui.components

import com.synckro.domain.sync.ActiveTransfer
import com.synckro.domain.sync.TransferDirection
import com.synckro.domain.sync.TransferProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncProgressRowsTest {
    @Test
    fun primaryProgressFraction_prefersByteBasedFraction() {
        val progress =
            TransferProgress(
                filesCompleted = 1,
                totalFiles = 4,
                bytesTransferred = 80L,
                totalBytes = 100L,
            )

        assertEquals(0.8f, primaryProgressFraction(progress))
    }

    @Test
    fun primaryProgressFraction_fallsBackToFileBasedFraction() {
        val progress =
            TransferProgress(
                filesCompleted = 2,
                totalFiles = 5,
                bytesTransferred = 0L,
                totalBytes = 0L,
            )

        assertEquals(0.4f, primaryProgressFraction(progress))
    }

    @Test
    fun primaryProgressFraction_returnsNullForIndeterminateProgress() {
        val progress =
            TransferProgress(
                filesCompleted = 0,
                totalFiles = 0,
                bytesTransferred = 0L,
                totalBytes = 0L,
            )

        assertNull(primaryProgressFraction(progress))
    }

    @Test
    fun transferProgressFraction_returnsNullWhenTotalBytesUnknown() {
        val transfer =
            ActiveTransfer(
                relativePath = "foo.txt",
                direction = TransferDirection.UPLOAD,
                bytesTransferred = 12L,
                totalBytes = 0L,
            )

        assertNull(transferProgressFraction(transfer))
    }
}
