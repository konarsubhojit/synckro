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
