package com.synckro.util

import android.content.Context
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** [StringProvider] implementation backed by an Android [Context]. */
@Singleton
class ContextStringProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : StringProvider {
    override fun getString(@StringRes id: Int): String = context.getString(id)
    override fun getString(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)
}
