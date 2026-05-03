package com.webgallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.webgallery.ui.navigation.AppNavHost
import com.webgallery.ui.theme.WebGalleryTheme
import com.webgallery.ui.theme.parseThemeMode

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as WebGalleryApp
        setContent {
            val themeMode by app.container.settingsRepository.themeMode.collectAsState(initial = "system")
            WebGalleryTheme(themeMode = parseThemeMode(themeMode)) {
                AppNavHost(app = app)
            }
        }
    }
}
