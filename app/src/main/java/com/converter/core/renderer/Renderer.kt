package com.converter.core.renderer

import com.converter.core.model.Document
import com.converter.core.model.LayoutState
import com.converter.core.model.SourceFormat
import java.io.OutputStream

/**
 * Contract for all format renderers.
 *
 * Renderers are responsible for:
 * 1. Receiving a PAGINATED Document
 * 2. Generating output bytes (streaming)
 * 3. Mapping IDM concepts to target format
 * 4. Reporting progress
 *
 * Implementation Requirements:
 * - Document must have layoutState = PAGINATED before rendering
 * - Must be thread-safe for concurrent rendering of different documents
 * - Must support cancellation via coroutine cancellation
 * - Should stream output where possible
 */
interface Renderer {

    /**
     * Render document to output stream.
     *
     * @param document Must have layoutState = PAGINATED
     * @param output Target stream (caller manages lifecycle)
     * @param config Renderer-specific configuration
     * @param onProgress Callback for progress updates (0.0 to 1.0)
     * @return Success or error
     */
    suspend fun render(
        document: Document,
        output: OutputStream,
        config: RendererConfig = RendererConfig(),
        onProgress: (Float) -> Unit = {}
    ): Result<RenderResult>

    /**
     * Target format produced by this renderer.
     */
    val targetFormat: SourceFormat

    /**
     * Human-readable name of this renderer.
     */
    val rendererName: String

    /**
     * Check if document is ready for rendering.
     */
    fun canRender(document: Document): Boolean =
        document.layoutState == LayoutState.PAGINATED

    /**
     * Validate configuration before rendering.
     */
    fun validateConfig(config: RendererConfig): List<String> = emptyList()
}

/**
 * Configuration for rendering operations.
 */
data class RendererConfig(
    /**
     * Quality level for rendering.
     */
    val quality: RenderQuality = RenderQuality.STANDARD,

    /**
     * Whether to embed fonts (where supported).
     * Not supported in Phase 1.
     */
    val embedFonts: Boolean = false,

    /**
     * Whether to compress output where possible.
     */
    val compress: Boolean = true,

    /**
     * Timeout for rendering operation (milliseconds).
     * Default: 5 minutes
     */
    val timeoutMillis: Long = 5 * 60 * 1000,

    /**
     * PDF-specific: PDF version to produce.
     */
    val pdfVersion: String = "1.4",

    /**
     * DOCX-specific: Whether to include minimal styles.
     */
    val includeStyles: Boolean = true
)

/**
 * Quality levels for rendering.
 */
enum class RenderQuality {
    /**
     * Fast rendering, lower visual fidelity.
     * Good for previews.
     */
    DRAFT,

    /**
     * Balanced speed and quality.
     * Default for most use cases.
     */
    STANDARD,

    /**
     * Highest quality, slower rendering.
     * For final output.
     */
    HIGH
}

/**
 * Result of rendering operation.
 */
data class RenderResult(
    val bytesWritten: Long,
    val pageCount: Int,
    val warnings: List<RenderWarning>,
    val durationMillis: Long
)

/**
 * Warning generated during rendering.
 */
data class RenderWarning(
    val type: RenderWarningType,
    val message: String,
    val pageNumber: Int? = null
)

/**
 * Types of rendering warnings.
 */
enum class RenderWarningType {
    FONT_SUBSTITUTION,      // Font was substituted
    CONTENT_CLIPPED,        // Content was clipped to page
    STYLE_APPROXIMATED,     // Style was approximated
    FEATURE_UNSUPPORTED     // Feature couldn't be rendered
}
