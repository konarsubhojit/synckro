package com.synckro.domain.sync

import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstantSyncEligibilityPolicyTest {
    private val policy = InstantSyncEligibilityPolicy()

    @Test
    fun `truth table covers every eligibility input`() {
        val rows =
            buildList {
                booleans.forEach { globalAutoSyncEnabled ->
                    booleans.forEach { globalInstantSyncEnabled ->
                        booleans.forEach { pairInstantSyncEnabled ->
                            booleans.forEach { uploadCapableDirection ->
                                InstantSyncAccountState.values().forEach { accountState ->
                                    booleans.forEach { pathInScope ->
                                        add(
                                            TruthTableRow(
                                                globalAutoSyncEnabled = globalAutoSyncEnabled,
                                                globalInstantSyncEnabled = globalInstantSyncEnabled,
                                                pairInstantSyncEnabled = pairInstantSyncEnabled,
                                                uploadCapableDirection = uploadCapableDirection,
                                                accountState = accountState,
                                                pathInScope = pathInScope,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

        assertEquals("truth table should cover every input combination", 128, rows.size)

        rows.forEach { row ->
            val pair = row.toPair()
            val decision =
                policy.evaluate(
                    pair = pair,
                    globalAutoSyncEnabled = row.globalAutoSyncEnabled,
                    globalInstantSyncEnabled = row.globalInstantSyncEnabled,
                    accountState = row.accountState,
                    relativePath = "notes.txt",
                )

            assertEquals("wrong reasons for $row", row.expectedReasons, decision.reasons)
            assertEquals("wrong eligibility for $row", row.expectedReasons.isEmpty(), decision.isEligible)
        }
    }

    @Test
    fun `all satisfied controls make upload-scoped pair eligible`() {
        val decision =
            policy.evaluate(
                pair = eligiblePair(),
                globalAutoSyncEnabled = true,
                globalInstantSyncEnabled = true,
                accountState = InstantSyncAccountState.READY,
                relativePath = "notes.txt",
            )

        assertTrue(decision.isEligible)
        assertTrue(decision.reasons.isEmpty())
    }

    @Test
    fun `unknown path hint does not block otherwise eligible pair`() {
        val decision =
            policy.evaluate(
                pair = eligiblePair(includeGlobs = listOf("*.jpg")),
                globalAutoSyncEnabled = true,
                globalInstantSyncEnabled = true,
                accountState = InstantSyncAccountState.READY,
                relativePath = null,
            )

        assertTrue(decision.isEligible)
    }

    @Test
    fun `shared path scope rejects excluded subfolder paths`() {
        val decision =
            policy.evaluate(
                pair =
                    eligiblePair(
                        includeGlobs = listOf("**/*.txt"),
                        excludeGlobs = listOf("private/**"),
                        excludeSubfolders = true,
                    ),
                globalAutoSyncEnabled = true,
                globalInstantSyncEnabled = true,
                accountState = InstantSyncAccountState.READY,
                relativePath = "docs/notes.txt",
            )

        assertFalse(decision.isEligible)
        assertEquals(setOf(InstantSyncIneligibilityReason.PATH_OUT_OF_SCOPE), decision.reasons)
    }

    private fun TruthTableRow.toPair(): SyncPair =
        eligiblePair(
            direction =
                if (uploadCapableDirection) {
                    SyncDirection.BIDIRECTIONAL
                } else {
                    SyncDirection.REMOTE_TO_LOCAL
                },
            instantSyncEnabled = pairInstantSyncEnabled,
            includeGlobs = if (pathInScope) listOf("*.txt") else listOf("*.jpg"),
        )

    private val TruthTableRow.expectedReasons: Set<InstantSyncIneligibilityReason>
        get() =
            buildSet {
                if (!globalAutoSyncEnabled) {
                    add(InstantSyncIneligibilityReason.GLOBAL_AUTO_SYNC_DISABLED)
                }
                if (!globalInstantSyncEnabled) {
                    add(InstantSyncIneligibilityReason.GLOBAL_INSTANT_SYNC_DISABLED)
                }
                if (!pairInstantSyncEnabled) {
                    add(InstantSyncIneligibilityReason.PAIR_INSTANT_SYNC_DISABLED)
                }
                if (!uploadCapableDirection) {
                    add(InstantSyncIneligibilityReason.DIRECTION_NOT_UPLOAD_CAPABLE)
                }
                when (accountState) {
                    InstantSyncAccountState.READY -> Unit
                    InstantSyncAccountState.ACCOUNT_NOT_LINKED ->
                        add(InstantSyncIneligibilityReason.ACCOUNT_NOT_LINKED)
                    InstantSyncAccountState.NEEDS_REAUTH -> add(InstantSyncIneligibilityReason.NEEDS_REAUTH)
                    InstantSyncAccountState.NEEDS_RELINK -> add(InstantSyncIneligibilityReason.NEEDS_RELINK)
                }
                if (!pathInScope) {
                    add(InstantSyncIneligibilityReason.PATH_OUT_OF_SCOPE)
                }
            }

    private fun eligiblePair(
        direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
        instantSyncEnabled: Boolean = true,
        accountId: String? = "account",
        includeGlobs: List<String> = emptyList(),
        excludeGlobs: List<String> = emptyList(),
        excludeSubfolders: Boolean = false,
    ): SyncPair =
        SyncPair(
            id = 1,
            displayName = "Pair",
            localTreeUri = "content://local/root",
            provider = CloudProviderType.ONEDRIVE,
            accountId = accountId,
            remoteFolderId = "remote",
            direction = direction,
            instantSyncEnabled = instantSyncEnabled,
            includeGlobs = includeGlobs,
            excludeGlobs = excludeGlobs,
            excludeSubfolders = excludeSubfolders,
        )

    private data class TruthTableRow(
        val globalAutoSyncEnabled: Boolean,
        val globalInstantSyncEnabled: Boolean,
        val pairInstantSyncEnabled: Boolean,
        val uploadCapableDirection: Boolean,
        val accountState: InstantSyncAccountState,
        val pathInScope: Boolean,
    )

    private companion object {
        val booleans = listOf(false, true)
    }
}
