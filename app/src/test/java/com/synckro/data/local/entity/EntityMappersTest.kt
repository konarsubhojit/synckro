package com.synckro.data.local.entity

import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SyncPairEntity.toDomain] verifying that ALL persistable
 * fields — including [com.synckro.domain.model.SyncPair.deltaToken],
 * [com.synckro.domain.model.SyncPair.retentionDays],
 * [com.synckro.domain.model.SyncPair.excludeSubfolders],
 * [com.synckro.domain.model.SyncPair.excludeEmptyFolders],
 * [com.synckro.domain.model.SyncPair.lastFullScanAtMs], and
 * [com.synckro.domain.model.SyncPair.autoSyncEnabled] — are correctly mapped
 * from the Room entity to the domain model.
 */
class EntityMappersTest {
    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun buildEntity(
        id: Long = 1L,
        displayName: String = "Test Pair",
        localTreeUri: String = "content://test/tree",
        provider: CloudProviderType = CloudProviderType.FAKE,
        remoteFolderId: String = "remote-root",
        direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
        conflictPolicy: ConflictPolicy = ConflictPolicy.NEWEST_WINS,
        includeGlobs: String = "",
        excludeGlobs: String = "",
        wifiOnly: Boolean = true,
        requiresCharging: Boolean = false,
        autoSyncEnabled: Boolean = true,
        lastDeltaToken: String? = null,
        lastSyncAtMs: Long? = null,
        lastSyncResult: String? = null,
        scheduleIntervalMinutes: Long = 60L,
        lastFullScanAtMs: Long? = null,
        retentionDays: Int? = null,
        excludeSubfolders: Boolean = false,
        excludeEmptyFolders: Boolean = false,
        accountId: String? = null,
    ) = SyncPairEntity(
        id = id,
        displayName = displayName,
        localTreeUri = localTreeUri,
        provider = provider,
        accountId = accountId,
        remoteFolderId = remoteFolderId,
        direction = direction,
        conflictPolicy = conflictPolicy,
        includeGlobs = includeGlobs,
        excludeGlobs = excludeGlobs,
        wifiOnly = wifiOnly,
        requiresCharging = requiresCharging,
        autoSyncEnabled = autoSyncEnabled,
        lastDeltaToken = lastDeltaToken,
        lastSyncAtMs = lastSyncAtMs,
        lastSyncResult = lastSyncResult,
        scheduleIntervalMinutes = scheduleIntervalMinutes,
        lastFullScanAtMs = lastFullScanAtMs,
        retentionDays = retentionDays,
        excludeSubfolders = excludeSubfolders,
        excludeEmptyFolders = excludeEmptyFolders,
    )

    // ---------------------------------------------------------------------------
    // Core identity fields
    // ---------------------------------------------------------------------------

    @Test
    fun `toDomain maps id`() {
        val entity = buildEntity(id = 42L)
        assertEquals(42L, entity.toDomain().id)
    }

    @Test
    fun `toDomain maps displayName`() {
        val entity = buildEntity(displayName = "My Pair")
        assertEquals("My Pair", entity.toDomain().displayName)
    }

    @Test
    fun `toDomain maps localTreeUri`() {
        val entity = buildEntity(localTreeUri = "content://com.example/tree/ext")
        assertEquals("content://com.example/tree/ext", entity.toDomain().localTreeUri)
    }

    @Test
    fun `toDomain maps provider`() {
        val entity = buildEntity(provider = CloudProviderType.ONEDRIVE)
        assertEquals(CloudProviderType.ONEDRIVE, entity.toDomain().provider)
    }

    @Test
    fun `toDomain maps remoteFolderId`() {
        val entity = buildEntity(remoteFolderId = "folder-xyz")
        assertEquals("folder-xyz", entity.toDomain().remoteFolderId)
    }

    @Test
    fun `toDomain maps direction`() {
        val entity = buildEntity(direction = SyncDirection.LOCAL_TO_REMOTE)
        assertEquals(SyncDirection.LOCAL_TO_REMOTE, entity.toDomain().direction)
    }

    @Test
    fun `toDomain maps conflictPolicy`() {
        val entity = buildEntity(conflictPolicy = ConflictPolicy.KEEP_BOTH)
        assertEquals(ConflictPolicy.KEEP_BOTH, entity.toDomain().conflictPolicy)
    }

    // ---------------------------------------------------------------------------
    // Glob lists
    // ---------------------------------------------------------------------------

    @Test
    fun `toDomain splits includeGlobs on newline`() {
        val entity = buildEntity(includeGlobs = "*.jpg\n*.png\n*.gif")
        assertEquals(listOf("*.jpg", "*.png", "*.gif"), entity.toDomain().includeGlobs)
    }

    @Test
    fun `toDomain filters blank includeGlobs`() {
        val entity = buildEntity(includeGlobs = "*.txt\n\n")
        assertEquals(listOf("*.txt"), entity.toDomain().includeGlobs)
    }

    @Test
    fun `toDomain splits excludeGlobs on newline`() {
        val entity = buildEntity(excludeGlobs = "*.tmp\n*.bak")
        assertEquals(listOf("*.tmp", "*.bak"), entity.toDomain().excludeGlobs)
    }

    @Test
    fun `toDomain returns empty list for blank globs`() {
        val entity = buildEntity(includeGlobs = "", excludeGlobs = "")
        assertTrue(entity.toDomain().includeGlobs.isEmpty())
        assertTrue(entity.toDomain().excludeGlobs.isEmpty())
    }

    // ---------------------------------------------------------------------------
    // Network / charging constraints
    // ---------------------------------------------------------------------------

    @Test
    fun `toDomain maps wifiOnly true`() {
        assertTrue(buildEntity(wifiOnly = true).toDomain().wifiOnly)
    }

    @Test
    fun `toDomain maps wifiOnly false`() {
        assertFalse(buildEntity(wifiOnly = false).toDomain().wifiOnly)
    }

    @Test
    fun `toDomain maps requiresCharging`() {
        assertTrue(buildEntity(requiresCharging = true).toDomain().requiresCharging)
    }

    // ---------------------------------------------------------------------------
    // Schedule fields
    // ---------------------------------------------------------------------------

    @Test
    fun `toDomain maps autoSyncEnabled`() {
        assertFalse(buildEntity(autoSyncEnabled = false).toDomain().autoSyncEnabled)
    }

    @Test
    fun `toDomain maps scheduleIntervalMinutes`() {
        val entity = buildEntity(scheduleIntervalMinutes = 120L)
        assertEquals(120L, entity.toDomain().scheduleIntervalMinutes)
    }

    // ---------------------------------------------------------------------------
    // Incremental-sync token (critical for delta/incremental sync)
    // ---------------------------------------------------------------------------

    @Test
    fun `toDomain maps deltaToken from lastDeltaToken`() {
        val entity = buildEntity(lastDeltaToken = "tok-abc123")
        assertEquals("tok-abc123", entity.toDomain().deltaToken)
    }

    @Test
    fun `toDomain maps null deltaToken`() {
        val entity = buildEntity(lastDeltaToken = null)
        assertNull(entity.toDomain().deltaToken)
    }

    // ---------------------------------------------------------------------------
    // Last-sync metadata
    // ---------------------------------------------------------------------------

    @Test
    fun `toDomain maps lastSyncAtMs`() {
        val entity = buildEntity(lastSyncAtMs = 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, entity.toDomain().lastSyncAtMs)
    }

    @Test
    fun `toDomain maps lastSyncResult`() {
        val entity = buildEntity(lastSyncResult = "SUCCESS")
        assertEquals("SUCCESS", entity.toDomain().lastSyncResult)
    }

    @Test
    fun `toDomain maps lastFullScanAtMs`() {
        val entity = buildEntity(lastFullScanAtMs = 1_600_000_000_000L)
        assertEquals(1_600_000_000_000L, entity.toDomain().lastFullScanAtMs)
    }

    // ---------------------------------------------------------------------------
    // Retention / filtering fields
    // ---------------------------------------------------------------------------

    @Test
    fun `toDomain maps retentionDays`() {
        val entity = buildEntity(retentionDays = 30)
        assertEquals(30, entity.toDomain().retentionDays)
    }

    @Test
    fun `toDomain maps null retentionDays`() {
        assertNull(buildEntity(retentionDays = null).toDomain().retentionDays)
    }

    @Test
    fun `toDomain maps excludeSubfolders`() {
        assertTrue(buildEntity(excludeSubfolders = true).toDomain().excludeSubfolders)
    }

    @Test
    fun `toDomain maps excludeEmptyFolders`() {
        assertTrue(buildEntity(excludeEmptyFolders = true).toDomain().excludeEmptyFolders)
    }

    // ---------------------------------------------------------------------------
    // Multi-account fields
    // ---------------------------------------------------------------------------

    @Test
    fun `toDomain maps accountId`() {
        val entity = buildEntity(accountId = "acc-123")
        assertEquals("acc-123", entity.toDomain().accountId)
    }

    @Test
    fun `toDomain maps null accountId`() {
        assertNull(buildEntity(accountId = null).toDomain().accountId)
    }

    // ---------------------------------------------------------------------------
    // needsReLink parameter
    // ---------------------------------------------------------------------------

    @Test
    fun `toDomain needsReLink defaults to false`() {
        assertFalse(buildEntity().toDomain().needsReLink)
    }

    @Test
    fun `toDomain needsReLink true is propagated`() {
        assertTrue(buildEntity().toDomain(needsReLink = true).needsReLink)
    }

    // ---------------------------------------------------------------------------
    // Round-trip completeness check
    // ---------------------------------------------------------------------------

    @Test
    fun `toDomain maps all fields in a fully-populated entity`() {
        val entity =
            buildEntity(
                id = 99L,
                displayName = "Full Pair",
                localTreeUri = "content://full/tree",
                provider = CloudProviderType.GOOGLE_DRIVE,
                remoteFolderId = "gdrive-folder",
                direction = SyncDirection.REMOTE_TO_LOCAL,
                conflictPolicy = ConflictPolicy.PREFER_LOCAL,
                includeGlobs = "*.doc\n*.docx",
                excludeGlobs = "*.tmp",
                wifiOnly = false,
                requiresCharging = true,
                autoSyncEnabled = false,
                lastDeltaToken = "delta-token-xyz",
                lastSyncAtMs = 1_710_000_000_000L,
                lastSyncResult = "PARTIAL_FAILURE",
                scheduleIntervalMinutes = 30L,
                lastFullScanAtMs = 1_700_000_000_000L,
                retentionDays = 7,
                excludeSubfolders = true,
                excludeEmptyFolders = true,
            )
        val pair = entity.toDomain(needsReLink = true)

        assertEquals(99L, pair.id)
        assertEquals("Full Pair", pair.displayName)
        assertEquals("content://full/tree", pair.localTreeUri)
        assertEquals(CloudProviderType.GOOGLE_DRIVE, pair.provider)
        assertEquals("gdrive-folder", pair.remoteFolderId)
        assertEquals(SyncDirection.REMOTE_TO_LOCAL, pair.direction)
        assertEquals(ConflictPolicy.PREFER_LOCAL, pair.conflictPolicy)
        assertEquals(listOf("*.doc", "*.docx"), pair.includeGlobs)
        assertEquals(listOf("*.tmp"), pair.excludeGlobs)
        assertFalse(pair.wifiOnly)
        assertTrue(pair.requiresCharging)
        assertFalse(pair.autoSyncEnabled)
        assertEquals("delta-token-xyz", pair.deltaToken)
        assertEquals(1_710_000_000_000L, pair.lastSyncAtMs)
        assertEquals("PARTIAL_FAILURE", pair.lastSyncResult)
        assertEquals(30L, pair.scheduleIntervalMinutes)
        assertEquals(1_700_000_000_000L, pair.lastFullScanAtMs)
        assertEquals(7, pair.retentionDays)
        assertTrue(pair.excludeSubfolders)
        assertTrue(pair.excludeEmptyFolders)
        assertTrue(pair.needsReLink)
    }
}
