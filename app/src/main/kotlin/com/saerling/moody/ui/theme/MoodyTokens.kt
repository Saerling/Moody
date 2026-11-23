// MoodyTokens.kt
// moody's own palette ("vinilo nocturno"): a black and white base with blue, red and green accents and their deep
// tones for tinted grounds. blue is the one main action per screen, red is like and alerts, green is state
// (downloaded, live, bit-perfect). every ink here passes 4.5:1 on night and surface.

package com.saerling.moody.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Moody {
    // grounds
    val Night = Color(0xFF0A0A0A)
    val Surface = Color(0xFF151515)
    val Raised = Color(0xFF1F1F1F)
    val Line = Color(0xFF2A2A2A)

    // ink
    val Ink = Color(0xFFFAFAFA)
    val Ink2 = Color(0xFFBDBDBD)
    val Ink3 = Color(0xFF8C8C8C)

    // accents
    val Blue = Color(0xFF7FA6FF)
    val OnBlue = Color(0xFF06122E)
    val BlueShadow = Color(0xFF3E63C9)
    val Red = Color(0xFFFF6B6B)
    val Green = Color(0xFF4CD38A)
    val OnGreen = Color(0xFF04210F)

    // deep tones, for tinted panels and backgrounds
    val DeepBlue = Color(0xFF0B1530)
    val DeepRed = Color(0xFF8A1F2C)
    val DeepGreen = Color(0xFF0F2E20)

    // shape
    val RadiusS = 8.dp
    val RadiusM = 14.dp
    val RadiusL = 22.dp

    // the tinted frosted glass: panel alpha over the cover-tinted ground, blur radius and the 1px top light
    const val GlassTintAlpha = 0.62f
    val GlassBlur = 22.dp
    const val GlassEdgeAlpha = 0.14f
    const val GlassHighlightAlpha = 0.18f
}
