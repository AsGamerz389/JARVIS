package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val JarvisColorScheme = darkColorScheme(
  primary = JarvisCyan,
  onPrimary = JarvisDarkBackground,
  primaryContainer = JarvisSurfaceElevated,
  onPrimaryContainer = JarvisCyanBright,
  secondary = JarvisBlueGlow,
  onSecondary = JarvisDarkBackground,
  secondaryContainer = JarvisSurfaceElevated,
  onSecondaryContainer = JarvisTextPrimary,
  tertiary = JarvisSpeakingGreen,
  background = JarvisDarkBackground,
  onBackground = JarvisTextPrimary,
  surface = JarvisSurfaceDark,
  onSurface = JarvisTextPrimary,
  surfaceVariant = JarvisSurfaceElevated,
  onSurfaceVariant = JarvisTextSecondary,
  outline = JarvisBorder,
  error = JarvisErrorRed,
  onError = JarvisDarkBackground,
)

@Composable
fun MyApplicationTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = JarvisColorScheme,
    typography = Typography,
    content = content
  )
}
