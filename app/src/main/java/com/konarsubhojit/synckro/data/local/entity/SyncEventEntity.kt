package com.konarsubhojit.synckro.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a single structured sync-event log entry.
 *
 * Rows are soft-capped at [MAX_EVENTS_PER_PAIR] per pair and [MAX_GLOBAL_EVENTS] globally.
 * Rolling deletion removes the oldest entries beyond those limits after each insert.
 *
 * The foreign key ON DELETE CASCADE ensures that all events for a pair are removed
 * when the pair is deleted, keeping orphaned rows out of the table.
 */
@Entity(
    tableName = "sync_event",
    foreignKeys = [
        ForeignKey(
            entity = SyncPairEntity::class,
            parentColumns = ["id"],
            childColumns = ["pairId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pairId"), Index("timestampMs")],
)
data class SyncEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Owning pair id, or null for global events not tied to a specific pair. */
    val pairId: Long?,
    val timestampMs: Long,
    /** Severity: "INFO", "WARN", or "ERROR". */
    val level: String,
    /** Short source label, e.g. "SyncWorker" or "Retry". */
    val tag: String,
    val message: String,
)
