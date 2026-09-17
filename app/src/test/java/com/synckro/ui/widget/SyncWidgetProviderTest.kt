package com.synckro.ui.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.model.ConflictPolicy
import com.synckro.domain.model.SyncDirection
import com.synckro.domain.model.SyncPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for [SyncWidgetProvider.refresh]. Only exercises the pure
 * RemoteViews-building path (no Hilt injection is required for [SyncWidgetProvider.refresh],
 * [onUpdate]/[onReceive] are the only members that need injected fields), using
 * Robolectric's [org.robolectric.shadows.ShadowAppWidgetManager] to bind a widget id
 * and inspect the resulting inflated view tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncWidgetProviderTest {
    private lateinit var context: Context
    private lateinit var appWidgetManager: AppWidgetManager
    private var widgetId: Int = 0

    private fun fakePair(
        id: Long,
        name: String,
        lastSyncAtMs: Long? = null,
    ) = SyncPair(
        id = id,
        displayName = name,
        localTreeUri = "content://local/path",
        provider = CloudProviderType.GOOGLE_DRIVE,
        accountId = "test-account-id",
        remoteFolderId = "remote-folder-id",
        direction = SyncDirection.BIDIRECTIONAL,
        conflictPolicy = ConflictPolicy.NEWEST_WINS,
        wifiOnly = false,
        autoSyncEnabled = true,
        scheduleIntervalMinutes = 60,
        lastSyncAtMs = lastSyncAtMs,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        appWidgetManager = AppWidgetManager.getInstance(context)
        widgetId = 42
        shadowOf(appWidgetManager).bindAppWidgetId(widgetId, ComponentName(context, SyncWidgetProvider::class.java))
    }

    @Test
    fun `refresh with no pairs shows the empty state`() {
        SyncWidgetProvider.refresh(context, emptyList())

        val root = shadowOf(appWidgetManager).getViewFor(widgetId)
        val emptyText = root.findViewById<TextView>(com.synckro.R.id.widget_empty_text)
        val list = root.findViewById<LinearLayout>(com.synckro.R.id.widget_pair_list)

        assertEquals(android.view.View.VISIBLE, emptyText.visibility)
        assertEquals(0, list.childCount)
    }

    @Test
    fun `refresh renders one row per pair with never-synced label`() {
        SyncWidgetProvider.refresh(context, listOf(fakePair(id = 1L, name = "Camera Roll")))

        val root = shadowOf(appWidgetManager).getViewFor(widgetId)
        val emptyText = root.findViewById<TextView>(com.synckro.R.id.widget_empty_text)
        val list = root.findViewById<LinearLayout>(com.synckro.R.id.widget_pair_list)

        assertEquals(android.view.View.GONE, emptyText.visibility)
        assertEquals(1, list.childCount)
        val row = list.getChildAt(0)
        val name = row.findViewById<TextView>(com.synckro.R.id.widget_row_name)
        val subtitle = row.findViewById<TextView>(com.synckro.R.id.widget_row_subtitle)
        assertEquals("Camera Roll", name.text.toString())
        assertEquals(context.getString(com.synckro.R.string.widget_never_synced), subtitle.text.toString())
    }

    @Test
    fun `refresh renders a relative last-sync label when the pair has synced`() {
        val fiveMinutesAgo = System.currentTimeMillis() - 5 * 60_000L
        SyncWidgetProvider.refresh(
            context,
            listOf(fakePair(id = 2L, name = "Docs", lastSyncAtMs = fiveMinutesAgo)),
        )

        val root = shadowOf(appWidgetManager).getViewFor(widgetId)
        val list = root.findViewById<LinearLayout>(com.synckro.R.id.widget_pair_list)
        val subtitle = list.getChildAt(0).findViewById<TextView>(com.synckro.R.id.widget_row_subtitle)

        assertTrue(subtitle.text.toString() != context.getString(com.synckro.R.string.widget_never_synced))
    }
}
