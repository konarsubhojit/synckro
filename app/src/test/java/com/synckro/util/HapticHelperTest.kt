package com.synckro.util

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import org.junit.Assert.assertEquals
import org.junit.Test

class HapticHelperTest {
    @Test
    fun `light emits text-handle move haptic`() {
        val fake = FakeHapticFeedback()

        HapticHelper(fake).light()

        assertEquals(listOf(HapticFeedbackType.TextHandleMove), fake.events)
    }

    @Test
    fun `success emits long-press haptic`() {
        val fake = FakeHapticFeedback()

        HapticHelper(fake).success()

        assertEquals(listOf(HapticFeedbackType.LongPress), fake.events)
    }

    @Test
    fun `error emits long-press haptic`() {
        val fake = FakeHapticFeedback()

        HapticHelper(fake).error()

        assertEquals(listOf(HapticFeedbackType.LongPress), fake.events)
    }

    private class FakeHapticFeedback : HapticFeedback {
        val events = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            events += hapticFeedbackType
        }
    }
}
