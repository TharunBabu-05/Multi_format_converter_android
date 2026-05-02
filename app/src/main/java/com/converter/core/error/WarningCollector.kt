package com.converter.core.error

/**
 * Collects warnings during conversion operations.
 *
 * Warnings represent non-fatal issues - conversion continues but
 * the user should be informed of potential quality loss.
 *
 * Thread-safe for use across coroutines.
 */
class WarningCollector {

    private val _warnings = mutableListOf<ConversionWarning>()
    private val lock = Any()

    /**
     * Get all collected warnings.
     */
    val warnings: List<ConversionWarning>
        get() = synchronized(lock) { _warnings.toList() }

    /**
     * Get warning count.
     */
    val count: Int
        get() = synchronized(lock) { _warnings.size }

    /**
     * Check if any warnings were collected.
     */
    val hasWarnings: Boolean
        get() = synchronized(lock) { _warnings.isNotEmpty() }

    /**
     * Add a warning.
     */
    fun warn(type: WarningType, message: String, location: WarningLocation? = null) {
        synchronized(lock) {
            _warnings.add(ConversionWarning(type, message, location))
        }
    }

    /**
     * Add a warning for unsupported font.
     */
    fun warnUnsupportedFont(fontName: String, fallback: String, location: WarningLocation? = null) {
        warn(
            WarningType.UNSUPPORTED_FONT,
            "Font '$fontName' not available, using '$fallback'",
            location
        )
    }

    /**
     * Add a warning for unsupported feature.
     */
    fun warnUnsupportedFeature(feature: String, location: WarningLocation? = null) {
        warn(
            WarningType.UNSUPPORTED_FEATURE,
            "Feature not supported: $feature",
            location
        )
    }

    /**
     * Add a warning for approximate layout.
     */
    fun warnApproximateLayout(details: String, location: WarningLocation? = null) {
        warn(
            WarningType.APPROXIMATE_LAYOUT,
            "Layout is approximate: $details",
            location
        )
    }

    /**
     * Add a warning for lost style.
     */
    fun warnStyleLost(style: String, location: WarningLocation? = null) {
        warn(
            WarningType.STYLE_LOST,
            "Style could not be preserved: $style",
            location
        )
    }

    /**
     * Add a warning for truncated content.
     */
    fun warnContentTruncated(details: String, location: WarningLocation? = null) {
        warn(
            WarningType.CONTENT_TRUNCATED,
            "Content was truncated: $details",
            location
        )
    }

    /**
     * Add a warning for encoding issue.
     */
    fun warnEncodingIssue(details: String, location: WarningLocation? = null) {
        warn(
            WarningType.ENCODING_ISSUE,
            "Character encoding issue: $details",
            location
        )
    }

    /**
     * Clear all warnings.
     */
    fun clear() {
        synchronized(lock) {
            _warnings.clear()
        }
    }

    /**
     * Get warnings grouped by type.
     */
    fun groupedByType(): Map<WarningType, List<ConversionWarning>> {
        return synchronized(lock) {
            _warnings.groupBy { it.type }
        }
    }

    /**
     * Get warnings for a specific page.
     */
    fun forPage(pageNumber: Int): List<ConversionWarning> {
        return synchronized(lock) {
            _warnings.filter { it.location?.pageNumber == pageNumber }
        }
    }

    /**
     * Get a summary string of all warnings.
     */
    fun summary(): String {
        val grouped = groupedByType()
        if (grouped.isEmpty()) return "No warnings"

        return grouped.entries.joinToString("\n") { (type, list) ->
            "• ${type.displayName}: ${list.size} issue(s)"
        }
    }
}

/**
 * A single warning from the conversion process.
 */
data class ConversionWarning(
    val type: WarningType,
    val message: String,
    val location: WarningLocation?
) {
    /**
     * Get formatted string representation.
     */
    override fun toString(): String {
        val loc = location?.let { " at $it" } ?: ""
        return "[${type.displayName}]$loc: $message"
    }
}

/**
 * Types of warnings that can occur during conversion.
 */
enum class WarningType(val displayName: String) {
    /**
     * Font not available, using fallback.
     */
    UNSUPPORTED_FONT("Unsupported Font"),

    /**
     * Feature was ignored (e.g., text box, table).
     */
    UNSUPPORTED_FEATURE("Unsupported Feature"),

    /**
     * Layout is best-effort (PDF→DOCX reconstruction).
     */
    APPROXIMATE_LAYOUT("Approximate Layout"),

    /**
     * Style couldn't be preserved exactly.
     */
    STYLE_LOST("Style Lost"),

    /**
     * Content was truncated (page limit, etc.).
     */
    CONTENT_TRUNCATED("Content Truncated"),

    /**
     * Character encoding problem.
     */
    ENCODING_ISSUE("Encoding Issue")
}

/**
 * Location context for a warning.
 */
data class WarningLocation(
    val pageNumber: Int? = null,
    val paragraphIndex: Int? = null,
    val description: String? = null
) {
    override fun toString(): String {
        val parts = mutableListOf<String>()
        pageNumber?.let { parts.add("page $it") }
        paragraphIndex?.let { parts.add("paragraph $it") }
        description?.let { parts.add(it) }
        return parts.joinToString(", ")
    }

    companion object {
        fun page(number: Int) = WarningLocation(pageNumber = number)
        fun paragraph(page: Int, index: Int) = WarningLocation(page, index)
    }
}
