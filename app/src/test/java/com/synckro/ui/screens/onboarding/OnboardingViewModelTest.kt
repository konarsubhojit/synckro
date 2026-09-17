package com.synckro.ui.screens.onboarding

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [OnboardingViewModel]. Verifies the one-shot resolution of
 * [OnboardingViewModel.shouldShowOnboarding] from [OnboardingGateway.isRequired]
 * and that [OnboardingViewModel.completeOnboarding] (invoked by both Skip and
 * the final-page CTA) delegates to [OnboardingGateway.complete].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val gateway: OnboardingGateway = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `shouldShowOnboarding starts null and resolves to true when gateway requires it`() =
        runTest {
            every { gateway.isRequired() } returns MutableStateFlow(true)
            val viewModel = OnboardingViewModel(gateway)

            assertEquals(null, viewModel.shouldShowOnboarding.value)

            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(true, viewModel.shouldShowOnboarding.value)
        }

    @Test
    fun `shouldShowOnboarding resolves to false when gateway says onboarding is done`() =
        runTest {
            every { gateway.isRequired() } returns MutableStateFlow(false)
            val viewModel = OnboardingViewModel(gateway)

            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(false, viewModel.shouldShowOnboarding.value)
        }

    @Test
    fun `shouldShowOnboarding only captures the first emission (skip mid-flow doesn't dismiss)`() =
        runTest {
            val isRequiredFlow = MutableStateFlow(true)
            every { gateway.isRequired() } returns isRequiredFlow
            val viewModel = OnboardingViewModel(gateway)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(true, viewModel.shouldShowOnboarding.value)

            // Simulate an account being linked mid-onboarding (Page 2), which
            // would flip the gateway's live flow to false. The already-resolved
            // shouldShowOnboarding value must not change.
            isRequiredFlow.value = false
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(true, viewModel.shouldShowOnboarding.value)
        }

    @Test
    fun `completeOnboarding delegates to gateway complete (skip and final CTA share this path)`() =
        runTest {
            every { gateway.isRequired() } returns MutableStateFlow(true)
            coEvery { gateway.complete() } returns Unit
            val viewModel = OnboardingViewModel(gateway)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.completeOnboarding()
            dispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { gateway.complete() }
        }
}
