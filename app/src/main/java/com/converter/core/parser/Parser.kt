package com.converter.core.parser

import com.converter.core.model.Document
import com.converter.core.model.SourceFormat
import java.io.InputStream

/**
 * Contract for all format parsers.
 *
 * Parsers are responsible for:
 * 1. Reading source bytes (streaming)
 * 2. Extracting structural information
 * 3. Producing an IDM Document
 * 4. Reporting progress and errors
 *
 * Implementation Requirements:
 * - Must be thread-safe for concurrent parsing of different files
 * - Must support cancellation via coroutine cancellation
 * - Must not hold references to input after parsing completes
 * - Should use streaming where possible to minimize memory
 */
interface Parser {

    /**
     * Parse input stream into Internal Document Model.
     *
     * @param input Source byte stream (caller manages lifecycle)
     * @param config Parser-specific configuration
     * @param onProgress Callback for progress updates (0.0 to 1.0)
     * @return Parsed document or error
     */
    suspend fun parse(
        input: InputStream,
        config: ParserConfig = ParserConfig(),
        onProgress: (Float) -> Unit = {}
    ): Result<Document>

    /**
     * Validate that input can be parsed without full parsing.
     * Useful for quick format verification.
     *
     * @param input Source byte stream
     * @return True if format appears valid
     */
    suspend fun validate(input: InputStream): Boolean

    /**
     * Supported source format for this parser.
     */
    val supportedFormat: SourceFormat

    /**
     * Human-readable name of this parser.
     */
    val parserName: String

    /**
     * Estimated memory overhead per page (bytes).
     * Used by engine to decide streaming strategy.
     */
    val estimatedMemoryPerPage: Long
        get() = DEFAULT_MEMORY_PER_PAGE

    companion object {
        const val DEFAULT_MEMORY_PER_PAGE = 50_000L  // ~50KB per page
    }
}

/**
 * Configuration for parsing operations.
 */
data class ParserConfig(
    /**
     * Maximum number of pages to parse.
     * Use for preview or partial conversion.
     */
    val maxPages: Int = Int.MAX_VALUE,

    /**
     * Whether to extract document metadata.
     */
    val extractMetadata: Boolean = true,

    /**
     * If true, fail on warnings instead of continuing.
     */
    val strictMode: Boolean = false,

    /**
     * Maximum file size to accept (bytes).
     * Default: 100MB
     */
    val maxFileSizeBytes: Long = 100 * 1024 * 1024,

    /**
     * Timeout for parsing operation (milliseconds).
     * Default: 5 minutes
     */
    val timeoutMillis: Long = 5 * 60 * 1000,

    /**
     * Whether to preserve positional information (for PDF).
     */
    val preservePositions: Boolean = true
)

/**
 * Result of parsing with additional metadata.
 */
data class ParseResult(
    val document: Document,
    val warnings: List<ParseWarning>,
    val stats: ParseStats
)

/**
 * Warning generated during parsing.
 */
data class ParseWarning(
    val type: ParseWarningType,
    val message: String,
    val location: String? = null
)

/**
 * Types of parsing warnings.
 */
enum class ParseWarningType {
    UNSUPPORTED_FEATURE,    // Feature was ignored
    MALFORMED_CONTENT,      // Content was repaired
    MISSING_RESOURCE,       // Resource (font, image) not found
    ENCODING_ISSUE,         // Character encoding problem
    STYLE_FALLBACK          // Style couldn't be preserved
}

/**
 * Statistics from parsing operation.
 */
data class ParseStats(
    val pageCount: Int,
    val paragraphCount: Int,
    val characterCount: Int,
    val durationMillis: Long,
    val bytesProcessed: Long
)
