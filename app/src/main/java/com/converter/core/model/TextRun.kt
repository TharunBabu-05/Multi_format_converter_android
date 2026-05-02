package com.converter.core.model

/**
 * A TextRun is a contiguous sequence of characters with uniform styling.
 *
 * This is the atomic unit of styled text. A paragraph like:
 *   "Hello WORLD everyone"
 * where "WORLD" is bold would be represented as three TextRuns:
 *   - TextRun("Hello ", normal)
 *   - TextRun("WORLD", bold)
 *   - TextRun(" everyone", normal)
 */
data class TextRun(
    val text: String,
    val style: TextStyle = TextStyle.default(),
    val position: TextPosition? = null     // Non-null only for PDF-sourced content
) {
    /**
     * Check if run is empty.
     */
    val isEmpty: Boolean
        get() = text.isEmpty()

    /**
     * Check if run contains only whitespace.
     */
    val isBlank: Boolean
        get() = text.isBlank()

    /**
     * Character count.
     */
    val length: Int
        get() = text.length

    /**
     * Check if this run has positional data (from PDF source).
     */
    val hasPosition: Boolean
        get() = position != null

    /**
     * Create a copy with different text.
     */
    fun withText(newText: String): TextRun = copy(text = newText)

    /**
     * Create a copy with different style.
     */
    fun withStyle(newStyle: TextStyle): TextRun = copy(style = newStyle)

    /**
     * Split run at character index.
     * Returns pair of (before, after) runs.
     */
    fun splitAt(index: Int): Pair<TextRun, TextRun> {
        require(index in 0..text.length) { "Split index out of bounds" }
        return Pair(
            copy(text = text.substring(0, index)),
            copy(text = text.substring(index))
        )
    }

    companion object {
        /**
         * Create empty run.
         */
        fun empty(style: TextStyle = TextStyle.default()): TextRun =
            TextRun(text = "", style = style)

        /**
         * Create run from text with default style.
         */
        fun of(text: String): TextRun = TextRun(text = text)

        /**
         * Create space character run.
         */
        fun space(style: TextStyle = TextStyle.default()): TextRun =
            TextRun(text = " ", style = style)

        /**
         * Create line break run.
         */
        fun lineBreak(style: TextStyle = TextStyle.default()): TextRun =
            TextRun(text = "\n", style = style)
    }
}

/**
 * Position information for PDF-sourced text.
 *
 * PDF is a visual format where text is positioned absolutely.
 * We preserve coordinates to enable structure reconstruction.
 */
data class TextPosition(
    val x: Float,                   // Points from left edge
    val y: Float,                   // Points from top edge
    val width: Float,               // Measured width of this run
    val height: Float,              // Line height at this position
    val pageIndex: Int              // 0-indexed page reference
) {
    /**
     * Calculate the right edge X position.
     */
    val rightEdge: Float
        get() = x + width

    /**
     * Calculate the bottom edge Y position.
     */
    val bottomEdge: Float
        get() = y + height

    /**
     * Check if this position is on the same line as another.
     * Uses Y-coordinate tolerance of 2 points.
     */
    fun isSameLineAs(other: TextPosition, tolerance: Float = 2f): Boolean =
        kotlin.math.abs(y - other.y) <= tolerance && pageIndex == other.pageIndex

    /**
     * Check if this position is adjacent to another (horizontally).
     * Uses gap threshold based on font size.
     */
    fun isAdjacentTo(other: TextPosition, maxGap: Float = 10f): Boolean =
        isSameLineAs(other) && kotlin.math.abs(rightEdge - other.x) <= maxGap

    /**
     * Calculate vertical gap to another position (for paragraph detection).
     */
    fun verticalGapTo(other: TextPosition): Float =
        kotlin.math.abs((y + height) - other.y)

    companion object {
        /**
         * Create position at origin.
         */
        fun origin(pageIndex: Int = 0): TextPosition =
            TextPosition(0f, 0f, 0f, 0f, pageIndex)
    }
}
