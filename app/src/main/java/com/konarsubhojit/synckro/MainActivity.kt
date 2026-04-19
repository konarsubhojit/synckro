package com.konarsubhojit.synckro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.konarsubhojit.synckro.ui.navigation.SynckroNavHost
import com.konarsubhojit.synckro.ui.theme.SynckroTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /**
     * Initializes the activity: enables edge-to-edge rendering and sets the app's Compose UI content.
     *
     * @param savedInstanceState Bundle containing the activity's previously saved state, or `null` if none.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SynckroTheme {
                SynckroNavHost()
            }
        }
    }
}
