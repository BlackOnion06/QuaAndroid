package com.example.myapplication

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.navigation.AppNavigation
import com.example.myapplication.settings.AppSettingsViewModel
import com.example.myapplication.settings.ThemeMode
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val settingsViewModel: AppSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // Keep status/navigation bar icons legible when the app theme differs from the system.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LightScrim, DarkScrim) { darkTheme },
                )
                onDispose {}
            }

            MyApplicationTheme(darkTheme = darkTheme) {
                AppNavigation(settingsViewModel = settingsViewModel, modifier = Modifier.fillMaxSize())
            }
        }
    }

    private companion object {
        // Defaults used by enableEdgeToEdge() for 3-button navigation.
        val LightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
