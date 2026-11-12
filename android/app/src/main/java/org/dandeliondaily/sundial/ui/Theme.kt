package org.dandeliondaily.sundial.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SunOrange = Color(0xFFE07A00)
val OfflineRed = Color(0xFFB3261E)

private val Light = lightColorScheme(
    primary = SunOrange,
    onPrimary = Color.White,
    secondary = Color(0xFF7A5A00),
    error = OfflineRed,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB85C),
    onPrimary = Color(0xFF462A00),
    secondary = Color(0xFFE7C26B),
)

@Composable
fun SundialTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
