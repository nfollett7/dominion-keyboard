package com.follett.keyboard.ime

/**
 * KeyboardTheme — Centralized theme system for Dominion Keyboard.
 *
 * Provides color palettes for all keyboard elements. Supports:
 *  - Dark (default) — deep navy/purple tones
 *  - Light — clean white/gray
 *  - AMOLED — pure black for OLED power savings
 *  - Custom accent colors
 *
 * All colors are defined here — no hardcoded hex values elsewhere.
 */
data class KeyboardTheme(
    val name: String,
    // Background
    val bgColor: Int,
    val suggestionBarBg: Int,
    val statusBarBg: Int,
    // Keys
    val keyNormal: Int,
    val keySpecial: Int,
    val keyAction: Int,
    val keyMic: Int,
    val keyPressed: Int,
    // Borders
    val keyBorder: Int,
    val keyBorderAction: Int,
    // Text
    val textNormal: Int,
    val textAction: Int,
    val textSpecial: Int,
    val textSuggestion: Int,
    val textStatus: Int,
    // Accent
    val accent: Int,
    // Divider
    val divider: Int
) {
    companion object {
        val DARK = KeyboardTheme(
            name = "Dark",
            bgColor = 0xFF0D0D1A.toInt(),
            suggestionBarBg = 0xFF121228.toInt(),
            statusBarBg = 0xFF1A1A3A.toInt(),
            keyNormal = 0xFF1E1E38.toInt(),
            keySpecial = 0xFF2A2A4E.toInt(),
            keyAction = 0xFF0D3055.toInt(),
            keyMic = 0xFF2D1B3D.toInt(),
            keyPressed = 0xFF4A4A7A.toInt(),
            keyBorder = 0xFF3A3A5C.toInt(),
            keyBorderAction = 0xFF00B4D8.toInt(),
            textNormal = 0xFFE8E8FF.toInt(),
            textAction = 0xFF00D4FF.toInt(),
            textSpecial = 0xFFB0B0D0.toInt(),
            textSuggestion = 0xFFE8E8FF.toInt(),
            textStatus = 0xFFB388FF.toInt(),
            accent = 0xFF00B4D8.toInt(),
            divider = 0xFF2A2A4A.toInt()
        )

        val LIGHT = KeyboardTheme(
            name = "Light",
            bgColor = 0xFFF5F5F5.toInt(),
            suggestionBarBg = 0xFFFFFFFF.toInt(),
            statusBarBg = 0xFFE8E8F0.toInt(),
            keyNormal = 0xFFFFFFFF.toInt(),
            keySpecial = 0xFFE0E0E8.toInt(),
            keyAction = 0xFF1976D2.toInt(),
            keyMic = 0xFFE8D5F0.toInt(),
            keyPressed = 0xFFD0D0E0.toInt(),
            keyBorder = 0xFFCCCCDD.toInt(),
            keyBorderAction = 0xFF1976D2.toInt(),
            textNormal = 0xFF1A1A2E.toInt(),
            textAction = 0xFFFFFFFF.toInt(),
            textSpecial = 0xFF555577.toInt(),
            textSuggestion = 0xFF1A1A2E.toInt(),
            textStatus = 0xFF6200EA.toInt(),
            accent = 0xFF1976D2.toInt(),
            divider = 0xFFDDDDEE.toInt()
        )

        val AMOLED = KeyboardTheme(
            name = "AMOLED",
            bgColor = 0xFF000000.toInt(),
            suggestionBarBg = 0xFF0A0A0A.toInt(),
            statusBarBg = 0xFF0A0A0A.toInt(),
            keyNormal = 0xFF111111.toInt(),
            keySpecial = 0xFF1A1A1A.toInt(),
            keyAction = 0xFF0A2840.toInt(),
            keyMic = 0xFF1A0A2A.toInt(),
            keyPressed = 0xFF333333.toInt(),
            keyBorder = 0xFF2A2A2A.toInt(),
            keyBorderAction = 0xFF00B4D8.toInt(),
            textNormal = 0xFFE0E0E0.toInt(),
            textAction = 0xFF00D4FF.toInt(),
            textSpecial = 0xFF888888.toInt(),
            textSuggestion = 0xFFE0E0E0.toInt(),
            textStatus = 0xFFB388FF.toInt(),
            accent = 0xFF00B4D8.toInt(),
            divider = 0xFF222222.toInt()
        )

        val MIDNIGHT_BLUE = KeyboardTheme(
            name = "Midnight Blue",
            bgColor = 0xFF0A1628.toInt(),
            suggestionBarBg = 0xFF0F1D33.toInt(),
            statusBarBg = 0xFF0F1D33.toInt(),
            keyNormal = 0xFF152540.toInt(),
            keySpecial = 0xFF1C3055.toInt(),
            keyAction = 0xFF0D4080.toInt(),
            keyMic = 0xFF1A2550.toInt(),
            keyPressed = 0xFF2A4570.toInt(),
            keyBorder = 0xFF2A3A5C.toInt(),
            keyBorderAction = 0xFF4FC3F7.toInt(),
            textNormal = 0xFFE0E8FF.toInt(),
            textAction = 0xFF4FC3F7.toInt(),
            textSpecial = 0xFF8899BB.toInt(),
            textSuggestion = 0xFFE0E8FF.toInt(),
            textStatus = 0xFF80DEEA.toInt(),
            accent = 0xFF4FC3F7.toInt(),
            divider = 0xFF1C3055.toInt()
        )

        fun getThemeByName(name: String): KeyboardTheme {
            return when (name.lowercase()) {
                "light" -> LIGHT
                "amoled" -> AMOLED
                "midnight blue", "midnight_blue" -> MIDNIGHT_BLUE
                else -> DARK
            }
        }

        fun getAllThemes(): List<KeyboardTheme> = listOf(DARK, LIGHT, AMOLED, MIDNIGHT_BLUE)
    }
}
