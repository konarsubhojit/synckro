package com.synckro.util

import androidx.annotation.StringRes

/**
 * Platform-agnostic abstraction for resolving localised strings in ViewModels.
 * Keeping this interface out of Android framework types (i.e. no [android.content.Context])
 * makes ViewModels easier to test without Robolectric when only string resolution is needed.
 */
interface StringProvider {
    fun getString(@StringRes id: Int): String
    fun getString(@StringRes id: Int, vararg args: Any): String
}
