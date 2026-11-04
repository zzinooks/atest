package com.fgc.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.fgc.core.Level

@Composable
fun fgcColors(): ColorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

/** 혈당 수준 색 — 색만으로 의미를 전달하지 않도록 화면에는 항상 문구를 함께 표시한다 */
fun levelColor(level: Level): Color = when (level) {
    Level.URGENT_LOW -> Color(0xFFB3261E)
    Level.LOW -> Color(0xFFE8710A)
    Level.IN_RANGE -> Color(0xFF1E8E3E)
    Level.HIGH -> Color(0xFFC5221F)
    Level.UNKNOWN -> Color(0xFF5F6368)
}

fun levelText(level: Level): String = when (level) {
    Level.URGENT_LOW -> "매우 낮음"
    Level.LOW -> "낮음"
    Level.IN_RANGE -> "목표 범위"
    Level.HIGH -> "높음"
    Level.UNKNOWN -> "알 수 없음"
}
