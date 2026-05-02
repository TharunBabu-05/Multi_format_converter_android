package com.converter.core.model

import java.util.UUID

/**
 * Represents a single page in the document.
 *
 * Pages contain an ordered list of block-level elements.
 * For Phase 1, blocks are limited to Paragraphs.
 */
data class Page(
    val pageNumber: Int,            // 1-indexed
    val blocks: List<Block>,
    val pageHeight: Float,          // Actual content height used
    val explicitBreak: Boolean      // True if page break was explicit in source
) {
    /**
     * Get all paragraphs on this page.
     */
    val paragraphs: List<Paragraph>
        get() = blocks.filterIsInstance<Paragraph>()

    /**
     * Check if page is empty.
     */
    val isEmpty: Boolean
        get() = blocks.isEmpty()

    /**
     * Total character count on this page.
     */
    val characterCount: Int
        get() = paragraphs.sumOf { p -> p.runs.sumOf { it.text.length } }

    companion object {
        /**
         * Create an empty page.
         */
        fun empty(pageNumber: Int): Page = Page(
            pageNumber = pageNumber,
            blocks = emptyList(),
            pageHeight = 0f,
            explicitBreak = false
        )
    }
}

/**
 * Sealed interface for block-level elements.
 *
 * Blocks are the primary structural units within a page.
 * Extensible for future support (tables, images, etc.)
 */
sealed interface Block {
    /**
     * Unique identifier for debugging and tracking.
     */
    val id: String

    /**
     * Spacing before this block (in points).
     */
    val spacingBefore: Float

    /**
     * Spacing after this block (in points).
     */
    val spacingAfter: Float
}

/**
 * A paragraph is a block containing inline content (TextRuns).
 *
 * Paragraphs are the primary unit of text flow. Line breaking
 * within a paragraph is handled by the Layout Engine.
 */
data class Paragraph(
    override val id: String = UUID.randomUUID().toString(),
    override val spacingBefore: Float = 0f,
    override val spacingAfter: Float = 12f,     // Default ~1 line spacing after
    val runs: List<TextRun>,
    val alignment: ParagraphAlignment = ParagraphAlignment.LEFT,
    val lineSpacing: LineSpacing = LineSpacing.single(),
    val indentation: Indentation = Indentation.none()
) : Block {

    /**
     * Get full text content of paragraph (runs concatenated).
     */
    val text: String
        get() = runs.joinToString("") { it.text }

    /**
     * Check if paragraph is empty.
     */
    val isEmpty: Boolean
        get() = runs.isEmpty() || runs.all { it.text.isEmpty() }

    /**
     * Check if paragraph contains only whitespace.
     */
    val isBlank: Boolean
        get() = text.isBlank()

    /**
     * Character count in this paragraph.
     */
    val characterCount: Int
        get() = runs.sumOf { it.text.length }

    companion object {
        /**
         * Create an empty paragraph.
         */
        fun empty(): Paragraph = Paragraph(runs = emptyList())

        /**
         * Create a simple paragraph with single run and default style.
         */
        fun simple(text: String, style: TextStyle = TextStyle.default()): Paragraph =
            Paragraph(runs = listOf(TextRun(text = text, style = style)))
    }
}

/**
 * Paragraph horizontal alignment.
 */
enum class ParagraphAlignment {
    LEFT,
    CENTER,
    RIGHT,
    JUSTIFY;

    /**
     * Convert to DOCX XML value.
     */
    fun toDocxValue(): String = when (this) {
        LEFT -> "left"
        CENTER -> "center"
        RIGHT -> "right"
        JUSTIFY -> "both"
    }

    companion object {
        /**
         * Parse from DOCX XML value.
         */
        fun fromDocxValue(value: String?): ParagraphAlignment = when (value?.lowercase()) {
            "left", "start" -> LEFT
            "center" -> CENTER
            "right", "end" -> RIGHT
            "both", "justify" -> JUSTIFY
            else -> LEFT
        }
    }
}

/**
 * Line spacing configuration.
 */
data class LineSpacing(
    val type: LineSpacingType,
    val value: Float               // Multiplier or absolute points
) {
    /**
     * Get effective multiplier for line height calculation.
     */
    val multiplier: Float
        get() = when (type) {
            LineSpacingType.SINGLE -> 1.0f
            LineSpacingType.ONE_POINT_FIVE -> 1.5f
            LineSpacingType.DOUBLE -> 2.0f
            LineSpacingType.MULTIPLE -> value
            LineSpacingType.EXACT -> 1.0f       // Handled separately
            LineSpacingType.AT_LEAST -> 1.0f    // Handled separately
        }

    companion object {
        fun single() = LineSpacing(LineSpacingType.SINGLE, 1.0f)
        fun onePointFive() = LineSpacing(LineSpacingType.ONE_POINT_FIVE, 1.5f)
        fun double() = LineSpacing(LineSpacingType.DOUBLE, 2.0f)
        fun multiple(factor: Float) = LineSpacing(LineSpacingType.MULTIPLE, factor)
        fun exact(points: Float) = LineSpacing(LineSpacingType.EXACT, points)
        fun atLeast(points: Float) = LineSpacing(LineSpacingType.AT_LEAST, points)
    }
}

/**
 * Types of line spacing.
 */
enum class LineSpacingType {
    SINGLE,         // 1.0x line height
    ONE_POINT_FIVE, // 1.5x
    DOUBLE,         // 2.0x
    MULTIPLE,       // Custom multiplier
    EXACT,          // Absolute points (may clip)
    AT_LEAST        // Minimum points
}

/**
 * Paragraph indentation settings.
 */
data class Indentation(
    val firstLine: Float,           // Points (can be negative for hanging)
    val left: Float,                // Left margin offset
    val right: Float                // Right margin offset
) {
    companion object {
        /**
         * No indentation.
         */
        fun none() = Indentation(0f, 0f, 0f)

        /**
         * Standard first-line indent (0.5 inch).
         */
        fun firstLine(points: Float = 36f) = Indentation(points, 0f, 0f)

        /**
         * Hanging indent (negative first line).
         */
        fun hanging(points: Float = 36f) = Indentation(-points, points, 0f)

        /**
         * Block indent (left margin offset).
         */
        fun block(left: Float, right: Float = 0f) = Indentation(0f, left, right)
    }
}
