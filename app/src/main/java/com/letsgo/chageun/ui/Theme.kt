package com.letsgo.chageun.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Same tokens as the Figma file ("차근차근/Color"). */
object C {
    val base = Color(0xFF0B0E14)
    val surface = Color(0xFF151A23)
    val surfaceHigh = Color(0xFF1F2633)
    val line = Color(0xFF2B3342)
    val text = Color(0xFFF5F2EA)
    val text2 = Color(0xFFAAB1BE)
    /** review-safety L8: at least 4.5:1 on every surface (was 0xFF6F7787, 3.4–4.3:1). */
    val text3 = Color(0xFF8A92A1)
    val onAccent = Color(0xFF1A1206)
    val warm = Color(0xFFFFB547)
    val warmSoft = Color(0xFF3A2B14)
    val mint = Color(0xFF5ED6A6)
    val cyan = Color(0xFF4FC3F7)
    val cyanSoft = Color(0xFF11303F)
    /** Episode panels: the "power cut" scene is darker than the chapter-0 surfaces. */
    val night = Color(0xFF0D1520)
    val success = Color(0xFF4CD08A)
    val danger = Color(0xFFFF6B5B)
    val dangerSoft = Color(0xFF3D1A17)
}

/** Text ramp from the Figma file. Large text multiplies every size. */
class Type(private val scale: Float) {
    private fun s(size: Int, weight: FontWeight, lh: Float): TextStyle =
        TextStyle(fontSize = (size * scale).sp, fontWeight = weight, lineHeight = (size * scale * lh).sp, color = C.text)

    val display = s(72, FontWeight.ExtraBold, 1.2f)
    val title = s(48, FontWeight.Bold, 1.3f)
    val headline = s(36, FontWeight.SemiBold, 1.35f)
    val body = s(30, FontWeight.Medium, 1.45f)
    val label = s(28, FontWeight.SemiBold, 1.3f)
    val caption = s(24, FontWeight.Normal, 1.4f)
}

val LocalType = staticCompositionLocalOf { Type(1f) }

@Composable
fun ChageunTheme(largeText: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = C.base, surface = C.surface, primary = C.warm)) {
        CompositionLocalProvider(LocalType provides Type(if (largeText) 1.2f else 1f), content = content)
    }
}
