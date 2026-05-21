package com.synckro.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the onboarding destination. Resolves whether onboarding is
 * required (a single one-shot check at startup via [OnboardingGateway]) and
 * exposes an action to mark it as completed.
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val gateway: OnboardingGateway,
    ) : ViewModel() {
        /**
         * `null` while the [OnboardingGateway] is being queried on the first
         * composition, `true` when onboarding must be shown, `false` when it
         * has already been completed and the host should navigate to `main`.
         *
         * Only the **first** emission from [OnboardingGateway.isRequired] is
         * captured so that connecting an account on Page 2 of the pager does
         * not dismiss the flow mid-way.
         */
        private val _shouldShowOnboarding = MutableStateFlow<Boolean?>(null)
        val shouldShowOnboarding: StateFlow<Boolean?> = _shouldShowOnboarding.asStateFlow()

        init {
            viewModelScope.launch {
                _shouldShowOnboarding.value = gateway.isRequired().first()
            }
        }

        /** Marks onboarding as explicitly completed (called by Skip or the final CTA). */
        fun completeOnboarding() {
            viewModelScope.launch { gateway.complete() }
        }
    }
