package com.signalzero.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SzRed = Color(0xFFE53935); val SzGreen = Color(0xFF2E7D32); val SzAmber = Color(0xFFF9A825)
private val Dark = darkColorScheme(primary = Color(0xFF4FC3F7), secondary = SzRed, background = Color(0xFF0B1220),
    surface = Color(0xFF131C2E), surfaceVariant = Color(0xFF1C2842))
private val Light = lightColorScheme(primary = Color(0xFF0277BD), secondary = SzRed, background = Color(0xFFF4F7FB),
    surface = Color.White, surfaceVariant = Color(0xFFE3ECF7))

@Composable fun SignalZeroTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
