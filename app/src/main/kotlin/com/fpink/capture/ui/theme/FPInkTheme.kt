package com.fpink.capture.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import com.fpink.capture.data.ThemeMode

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun FPInkTheme(
    mode: ThemeMode,
    activity: ComponentActivity? = null,
    content: @Composable () -> Unit,
) {
    val dark = mode.isDark(isSystemInDarkTheme())
    SideEffect {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(0xE6FFFFFF.toInt(), 0x801B1B1B.toInt()) { dark },
        )
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
