package com.konarsubhojit.synckro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.konarsubhojit.synckro.R

/**
 * Displays the onboarding screen containing a title, explanatory body text, and a continue button.
 *
 * The content is centered both vertically and horizontally and uses string resources for the texts.
 *
 * @param onContinue Callback invoked when the user taps the continue button.
 */
@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.onboarding_title))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.onboarding_body))
        Spacer(Modifier.height(24.dp))
        Button(onClick = onContinue) {
            Text(stringResource(R.string.onboarding_cta))
        }
    }
}
