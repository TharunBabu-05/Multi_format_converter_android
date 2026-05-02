package com.converter.core.model

/**
 * Root container representing an entire document.
 *
 * A Document is a sequence of Pages. In flow-based formats like DOCX,
 * page boundaries are computed during layout. In fixed formats like PDF,
 * page boundaries are explicit.
 *
 * This is the canonical representation used for all conversions.
 * Source format → Document → Target format
 */
data class Document(
    val metadata: DocumentMetadata,
    val pages: List<Page>,
    val sourceFormat: SourceFormat,
    val layoutState: LayoutState
) {
    /**
     * Get all blocks across all pages (flattened).
     */
    val allBlocks: List<Block>
        get() = pages.flatMap { it.blocks }

    /**
     * Get all paragraphs across all pages.
     */
    val allParagraphs: List<Paragraph>
        get() = allBlocks.filterIsInstance<Paragraph>()

    /**
     * Total character count in the document.
     */
    val totalCharacters: Int
        get() = allParagraphs.sumOf { p ->
            p.runs.sumOf { it.text.length }
        }

    /**
     * Create a copy with updated layout state.
     */
    fun withLayoutState(state: LayoutState): Document =
        copy(layoutState = state)

    /**
     * Create a copy with new pages (after pagination).
     */
    fun withPages(newPages: List<Page>): Document =
        copy(pages = newPages)

    companion object {
        /**
         * Create an empty document with default settings.
         */
        fun empty(sourceFormat: SourceFormat = SourceFormat.UNKNOWN): Document =
            Document(
                metadata = DocumentMetadata.default(),
                pages = emptyList(),
                sourceFormat = sourceFormat,
                layoutState = LayoutState.UNPAGINATED
            )
    }
}

/**
 * Document metadata including dimensions and authoring info.
 */
data class DocumentMetadata(
    val title: String?,
    val author: String?,
    val createdAt: Long?,
    val modifiedAt: Long?,
    val pageWidth: Float,       // Points (1/72 inch)
    val pageHeight: Float,      // Points
    val marginTop: Float,
    val marginBottom: Float,
    val marginLeft: Float,
    val marginRight: Float
) {
    /**
     * Calculate available content width.
     */
    val contentWidth: Float
        get() = pageWidth - marginLeft - marginRight

    /**
     * Calculate available content height.
     */
    val contentHeight: Float
        get() = pageHeight - marginTop - marginBottom

    companion object {
        // US Letter size in points (8.5 x 11 inches)
        private const val LETTER_WIDTH = 612f
        private const val LETTER_HEIGHT = 792f

        // Default 1-inch margins
        private const val DEFAULT_MARGIN = 72f

        /**
         * Create default metadata (US Letter, 1-inch margins).
         */
        fun default(): DocumentMetadata = DocumentMetadata(
            title = null,
            author = null,
            createdAt = null,
            modifiedAt = null,
            pageWidth = LETTER_WIDTH,
            pageHeight = LETTER_HEIGHT,
            marginTop = DEFAULT_MARGIN,
            marginBottom = DEFAULT_MARGIN,
            marginLeft = DEFAULT_MARGIN,
            marginRight = DEFAULT_MARGIN
        )

        /**
         * Create from page dimensions in inches.
         */
        fun fromInches(
            widthInches: Float,
            heightInches: Float,
            marginInches: Float = 1f
        ): DocumentMetadata = DocumentMetadata(
            title = null,
            author = null,
            createdAt = null,
            modifiedAt = null,
            pageWidth = widthInches * 72f,
            pageHeight = heightInches * 72f,
            marginTop = marginInches * 72f,
            marginBottom = marginInches * 72f,
            marginLeft = marginInches * 72f,
            marginRight = marginInches * 72f
        )
    }
}

/**
 * Source format of the document.
 */
enum class SourceFormat(val extension: String, val mimeType: String) {
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    PDF("pdf", "application/pdf"),
    UNKNOWN("", "application/octet-stream");

    companion object {
        /**
         * Detect format from file extension.
         */
        fun fromExtension(ext: String): SourceFormat = when (ext.lowercase()) {
            "docx" -> DOCX
            "pdf" -> PDF
            else -> UNKNOWN
        }

        /**
         * Detect format from MIME type.
         */
        fun fromMimeType(mime: String): SourceFormat = entries.find {
            it.mimeType.equals(mime, ignoreCase = true)
        } ?: UNKNOWN
    }
}

/**
 * Layout state of the document.
 *
 * UNPAGINATED: Raw content without page breaks (e.g., freshly parsed DOCX).
 * PAGINATED: Page breaks have been calculated (ready for rendering).
 */
enum class LayoutState {
    /**
     * Content flows continuously without page boundaries.
     * Typical for documents parsed from flow-based formats like DOCX.
     */
    UNPAGINATED,

    /**
     * Page boundaries have been calculated or were explicit in source.
     * Required before rendering to PDF.
     */
    PAGINATED
}
