package com.example.servermobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkBackground = Color(0xFF121212)
val CardBackground = Color(0xFF1E1E1E)
val PrimaryGreen = Color(0xFF4CAF50)
val PrimaryRed = Color(0xFFF44336)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFAAAAAA)
val AccentColor = Color(0xFF2196F3)
val PrimaryBlue = Color(0xFF2196F3)
val ErrorRed = Color(0xFFF44336)
val SuccessGreen = Color(0xFF4CAF50)

@Composable
fun ServerAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = DarkBackground,
            surface = CardBackground,
            primary = AccentColor
        ),
        content = content
    )
}
