package com.synckro.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode

class HapticHelper(
    private val hapticFeedback: HapticFeedback,
) {
    fun light() {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun success() {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun error() {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

@Composable
fun rememberHapticHelper(enabled: Boolean): HapticHelper? {
    if (!enabled || LocalInspectionMode.current) return null
    val hapticFeedback = LocalHapticFeedback.current
    return remember(hapticFeedback) { HapticHelper(hapticFeedback) }
}
