package com.konarsubhojit.synckro.domain.scan

/**
 * Progress events emitted by [LocalFolderScanner.scan].
 *
 * The typical sequence for a successful scan is one or more [Scanning] emissions
 * (roughly every [LocalFolderScanner.PROGRESS_INTERVAL] files) followed by a
 * single terminal [Done].  If the scan cannot complete, [Failed] is emitted instead.
 */
sealed interface ScanProgress {
    /**
     * Emitted periodically while the SAF document tree is being walked.
     *
     * @param filesScanned Running count of files discovered so far (directories excluded).
     */
    data class Scanning(val filesScanned: Int) : ScanProgress

    /**
     * Terminal success event.  The Room index has been fully reconciled with the
     * on-disk state at the time of the scan.
     *
     * @param added   Files inserted into the index (not previously known).
     * @param updated Files whose size or last-modified timestamp changed.
     * @param deleted Files removed from the index because they no longer exist on disk.
     */
    data class Done(val added: Int, val updated: Int, val deleted: Int) : ScanProgress

    /**
     * Terminal failure event.  The scan aborted before completing; the index
     * may be incomplete or stale.
     *
     * @param reason Human-readable description of the error.
     */
    data class Failed(val reason: String) : ScanProgress
}
