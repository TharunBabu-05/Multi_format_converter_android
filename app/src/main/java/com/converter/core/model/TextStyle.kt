package com.converter.core.model

import android.graphics.Typeface

/**
 * Captures visual styling of text.
 *
 * Limited to properties that can be:
 * 1. Extracted from both DOCX and PDF
 * 2. Rendered on Android Canvas
 * 3. Written back to both formats
 */
data class TextStyle(
    val fontFamily: FontFamily = FontFamily.SANS_SERIF,
    val fontSize: Float = 12f,              // Points
    val fontWeight: FontWeight = FontWeight.NORMAL,
    val isItalic: Boolean = false,
    val letterSpacing: Float = 0f,          // Points between characters
    val isUnderline: Boolean = false,
    val isStrikethrough: Boolean = false,
    val textColor: TextColor = TextColor.BLACK,
    val backgroundColor: TextColor? = null,
    val isSubscript: Boolean = false,
    val isSuperscript: Boolean = false
) {
    /**
     * Check if style has any emphasis (bold, italic, or both).
     */
    val hasEmphasis: Boolean
        get() = fontWeight == FontWeight.BOLD || isItalic

    /**
     * Check if style has any decoration (underline, strikethrough).
     */
    val hasDecoration: Boolean
        get() = isUnderline || isStrikethrough

    /**
     * Get Android Typeface style flags.
     */
    val typefaceStyle: Int
        get() = when {
            fontWeight == FontWeight.BOLD && isItalic -> Typeface.BOLD_ITALIC
            fontWeight == FontWeight.BOLD -> Typeface.BOLD
            isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }

    /**
     * Create a copy with bold enabled.
     */
    fun bold(): TextStyle = copy(fontWeight = FontWeight.BOLD)

    /**
     * Create a copy with italic enabled.
     */
    fun italic(): TextStyle = copy(isItalic = true)

    /**
     * Create a copy with underline enabled.
     */
    fun underline(): TextStyle = copy(isUnderline = true)

    /**
     * Create a copy with different font size.
     */
    fun withSize(points: Float): TextStyle = copy(fontSize = points)

    /**
     * Create a copy with different font family.
     */
    fun withFamily(family: FontFamily): TextStyle = copy(fontFamily = family)

    /**
     * Create a copy with different color.
     */
    fun withColor(color: TextColor): TextStyle = copy(textColor = color)

    /**
     * Create a copy with different letter spacing.
     */
    fun withLetterSpacing(points: Float): TextStyle = copy(letterSpacing = points)

    /**
     * Merge with another style (other style takes precedence for non-default values).
     */
    fun mergeWith(other: TextStyle): TextStyle = TextStyle(
        fontFamily = if (other.fontFamily != FontFamily.SANS_SERIF) other.fontFamily else fontFamily,
        fontSize = if (other.fontSize != 12f) other.fontSize else fontSize,
        fontWeight = if (other.fontWeight != FontWeight.NORMAL) other.fontWeight else fontWeight,
        isItalic = isItalic || other.isItalic,
        letterSpacing = if (other.letterSpacing != 0f) other.letterSpacing else letterSpacing,
        isUnderline = isUnderline || other.isUnderline,
        isStrikethrough = isStrikethrough || other.isStrikethrough,
        textColor = if (other.textColor != TextColor.BLACK) other.textColor else textColor,
        backgroundColor = other.backgroundColor ?: backgroundColor,
        isSubscript = isSubscript || other.isSubscript,
        isSuperscript = isSuperscript || other.isSuperscript
    )

    companion object {
        /**
         * Default text style (12pt sans-serif, black).
         */
        fun default(): TextStyle = TextStyle()

        /**
         * Heading 1 style.
         */
        fun heading1(): TextStyle = TextStyle(
            fontSize = 24f,
            fontWeight = FontWeight.BOLD
        )

        /**
         * Heading 2 style.
         */
        fun heading2(): TextStyle = TextStyle(
            fontSize = 18f,
            fontWeight = FontWeight.BOLD
        )

        /**
         * Heading 3 style.
         */
        fun heading3(): TextStyle = TextStyle(
            fontSize = 14f,
            fontWeight = FontWeight.BOLD
        )

        /**
         * Monospace/code style.
         */
        fun code(): TextStyle = TextStyle(
            fontFamily = FontFamily.MONOSPACE,
            fontSize = 10f
        )
    }
}

/**
 * Normalized font family representation.
 *
 * Maps source fonts to available Android system fonts.
 * This is intentionally limited to ensure cross-device compatibility.
 */
enum class FontFamily(val androidTypeface: String, val docxName: String) {
    SERIF("serif", "Times New Roman"),
    SANS_SERIF("sans-serif", "Arial"),
    MONOSPACE("monospace", "Courier New");

    /**
     * Get Android Typeface for this family.
     */
    fun toTypeface(): Typeface = when (this) {
        SERIF -> Typeface.SERIF
        SANS_SERIF -> Typeface.SANS_SERIF
        MONOSPACE -> Typeface.MONOSPACE
    }

    companion object {
        /**
         * Map font name from source document to normalized family.
         */
        fun fromName(name: String): FontFamily = when {
            // Serif fonts
            name.contains("times", ignoreCase = true) -> SERIF
            name.contains("georgia", ignoreCase = true) -> SERIF
            name.contains("garamond", ignoreCase = true) -> SERIF
            name.contains("palatino", ignoreCase = true) -> SERIF
            name.contains("serif", ignoreCase = true) && 
                !name.contains("sans", ignoreCase = true) -> SERIF

            // Monospace fonts
            name.contains("courier", ignoreCase = true) -> MONOSPACE
            name.contains("consolas", ignoreCase = true) -> MONOSPACE
            name.contains("monaco", ignoreCase = true) -> MONOSPACE
            name.contains("mono", ignoreCase = true) -> MONOSPACE
            name.contains("code", ignoreCase = true) -> MONOSPACE

            // Sans-serif (default for most modern fonts)
            name.contains("arial", ignoreCase = true) -> SANS_SERIF
            name.contains("helvetica", ignoreCase = true) -> SANS_SERIF
            name.contains("verdana", ignoreCase = true) -> SANS_SERIF
            name.contains("tahoma", ignoreCase = true) -> SANS_SERIF
            name.contains("calibri", ignoreCase = true) -> SANS_SERIF
            name.contains("segoe", ignoreCase = true) -> SANS_SERIF
            name.contains("roboto", ignoreCase = true) -> SANS_SERIF
            name.contains("sans", ignoreCase = true) -> SANS_SERIF

            // Default fallback
            else -> SANS_SERIF
        }
    }
}

/**
 * Font weight enumeration.
 *
 * Simplified to NORMAL and BOLD for Phase 1.
 * Full weight scale (100-900) can be added later.
 */
enum class FontWeight(val numericWeight: Int) {
    NORMAL(400),
    BOLD(700);

    companion object {
        /**
         * Convert numeric weight to enum.
         */
        fun fromNumeric(weight: Int): FontWeight =
            if (weight >= 600) BOLD else NORMAL

        /**
         * Parse from string value.
         */
        fun fromString(value: String?): FontWeight = when (value?.lowercase()) {
            "bold", "700", "800", "900" -> BOLD
            else -> NORMAL
        }
    }
}

/**
 * RGB color with alpha channel.
 */
data class TextColor(
    val red: Int,       // 0-255
    val green: Int,
    val blue: Int,
    val alpha: Int = 255
) {
    init {
        require(red in 0..255) { "Red must be 0-255" }
        require(green in 0..255) { "Green must be 0-255" }
        require(blue in 0..255) { "Blue must be 0-255" }
        require(alpha in 0..255) { "Alpha must be 0-255" }
    }

    /**
     * Convert to Android ARGB integer.
     */
    fun toArgb(): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    /**
     * Convert to DOCX hex color string (RRGGBB).
     */
    fun toHexString(): String = String.format("%02X%02X%02X", red, green, blue)

    /**
     * Check if color is effectively black.
     */
    val isBlack: Boolean
        get() = red == 0 && green == 0 && blue == 0

    /**
     * Check if color is effectively white.
     */
    val isWhite: Boolean
        get() = red == 255 && green == 255 && blue == 255

    companion object {
        val BLACK = TextColor(0, 0, 0)
        val WHITE = TextColor(255, 255, 255)
        val RED = TextColor(255, 0, 0)
        val GREEN = TextColor(0, 128, 0)
        val BLUE = TextColor(0, 0, 255)
        val GRAY = TextColor(128, 128, 128)

        /**
         * Parse from hex string (supports RGB, RRGGBB, AARRGGBB).
         */
        fun fromHex(hex: String): TextColor {
            val cleaned = hex.removePrefix("#").uppercase()
            return when (cleaned.length) {
                3 -> {
                    // RGB shorthand
                    val r = cleaned[0].toString().repeat(2).toInt(16)
                    val g = cleaned[1].toString().repeat(2).toInt(16)
                    val b = cleaned[2].toString().repeat(2).toInt(16)
                    TextColor(r, g, b)
                }
                6 -> {
                    // RRGGBB
                    val r = cleaned.substring(0, 2).toInt(16)
                    val g = cleaned.substring(2, 4).toInt(16)
                    val b = cleaned.substring(4, 6).toInt(16)
                    TextColor(r, g, b)
                }
                8 -> {
                    // AARRGGBB
                    val a = cleaned.substring(0, 2).toInt(16)
                    val r = cleaned.substring(2, 4).toInt(16)
                    val g = cleaned.substring(4, 6).toInt(16)
                    val b = cleaned.substring(6, 8).toInt(16)
                    TextColor(r, g, b, a)
                }
                else -> BLACK
            }
        }

        /**
         * Parse from Android ARGB integer.
         */
        fun fromArgb(argb: Int): TextColor = TextColor(
            red = (argb shr 16) and 0xFF,
            green = (argb shr 8) and 0xFF,
            blue = argb and 0xFF,
            alpha = (argb shr 24) and 0xFF
        )
    }
}
