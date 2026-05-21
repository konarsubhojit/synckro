package com.synckro.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synckro.R
import com.synckro.ui.auth.ActivityAuthUiHost
import com.synckro.ui.screens.accounts.AccountsViewModel
import kotlinx.coroutines.launch

private const val ONBOARDING_PAGE_COUNT = 3

/**
 * Multi-step onboarding pager shown on the user's first launch. Guides them
 * through three pages:
 * 1. **Welcome** – what Synckro does (condensed).
 * 2. **Connect an account** – live provider-connect buttons via [AccountsViewModel].
 * 3. **Pick your first folders** – primary CTA navigates to the pair editor.
 *
 * A **Skip** action (top-right) and the final-page CTA both invoke
 * [onCreateFirstSyncPair] / [onSkip] so the host ([SynckroNavHost]) can mark
 * onboarding as complete and update the navigation back-stack.
 *
 * @param activity   Threaded in from the host so Page 2 can launch interactive
 *   OAuth sign-in flows without casting [LocalContext].
 * @param onSkip     Called when the user taps the **Skip** action.
 * @param onCreateFirstSyncPair Called when the user taps **Create my first sync pair**
 *   on the final page.
 * @param accountsViewModel ViewModel used on Page 2 to drive provider rows and
 *   the connect action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    activity: ComponentActivity,
    onSkip: () -> Unit,
    onCreateFirstSyncPair: () -> Unit,
    accountsViewModel: AccountsViewModel = hiltViewModel(),
) {
    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val accountsState by accountsViewModel.state.collectAsStateWithLifecycle()
    val host = remember(activity) { ActivityAuthUiHost(activity) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    TextButton(onClick = onSkip) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 ->
                        ConnectPage(
                            rows = accountsState.rows,
                            onConnect = { providerKey ->
                                accountsViewModel.connect(providerKey) { manager ->
                                    manager.signIn(host)
                                }
                            },
                        )
                    2 -> FirstPairPage()
                }
            }

            // Pager indicator
            OnboardingPagerIndicator(
                pagerState = pagerState,
                pageCount = ONBOARDING_PAGE_COUNT,
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 16.dp),
            )

            // Next / "Create my first sync pair" button
            Button(
                onClick = {
                    if (pagerState.currentPage < ONBOARDING_PAGE_COUNT - 1) {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    } else {
                        onCreateFirstSyncPair()
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
            ) {
                Text(
                    if (pagerState.currentPage < ONBOARDING_PAGE_COUNT - 1) {
                        stringResource(R.string.onboarding_next)
                    } else {
                        stringResource(R.string.onboarding_create_first_pair)
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pages
// ---------------------------------------------------------------------------

@Composable
private fun WelcomePage() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Sync,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.onboarding_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OnboardingStep(
                stepNumber = 1,
                icon = Icons.Default.AccountCircle,
                title = stringResource(R.string.onboarding_step1_title),
                body = stringResource(R.string.onboarding_step1_body),
            )
            OnboardingStep(
                stepNumber = 2,
                icon = Icons.Default.FolderOpen,
                title = stringResource(R.string.onboarding_step2_title),
                body = stringResource(R.string.onboarding_step2_body),
            )
            OnboardingStep(
                stepNumber = 3,
                icon = Icons.Default.Sync,
                title = stringResource(R.string.onboarding_step3_title),
                body = stringResource(R.string.onboarding_step3_body),
            )
        }
    }
}

@Composable
private fun ConnectPage(
    rows: List<AccountsViewModel.AccountRow>,
    onConnect: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Default.AccountCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = stringResource(R.string.onboarding_page2_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.onboarding_page2_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        rows.forEach { row ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = row.providerDisplayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    row.accounts.forEach { item ->
                        Text(
                            text = item.account.email ?: item.account.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Button(
                        onClick = { onConnect(row.providerKey) },
                        enabled = !row.isBusy && row.isConfigured,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (row.isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            val label =
                                if (row.accounts.isNotEmpty()) {
                                    stringResource(
                                        R.string.accounts_add_another_format,
                                        row.providerDisplayName,
                                    )
                                } else {
                                    stringResource(
                                        R.string.accounts_connect_format,
                                        row.providerDisplayName,
                                    )
                                }
                            Text(label, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FirstPairPage() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Text(
            text = stringResource(R.string.onboarding_page3_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.onboarding_page3_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// Shared sub-composables
// ---------------------------------------------------------------------------

@Composable
private fun OnboardingPagerIndicator(
    pagerState: PagerState,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val isSelected = pagerState.currentPage == index
            val color by animateColorAsState(
                targetValue =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    },
                label = "onboarding_indicator_color",
            )
            Box(
                modifier =
                    Modifier
                        .size(if (isSelected) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(color),
            )
        }
    }
}

@Composable
private fun OnboardingStep(
    stepNumber: Int,
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .wrapContentHeight()
                        .sizeIn(minWidth = 36.dp, minHeight = 36.dp),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stepNumber.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
