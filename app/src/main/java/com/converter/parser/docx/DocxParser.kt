package com.converter.parser.docx

import com.converter.core.error.ConversionError
import com.converter.core.model.*
import com.converter.core.parser.Parser
import com.converter.core.parser.ParserConfig
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Parser for DOCX (Office Open XML) documents.
 *
 * DOCX is a ZIP archive containing XML files:
 * - word/document.xml — Main content
 * - word/styles.xml — Style definitions
 * - docProps/core.xml — Metadata
 *
 * This parser extracts:
 * - Paragraphs and text runs
 * - Basic formatting (bold, italic, font size)
 * - Document metadata
 *
 * Limitations:
 * - No image extraction
 * - No table support
 * - No complex styles (themes, etc.)
 */
class DocxParser : Parser {

    override val supportedFormat = SourceFormat.DOCX
    override val parserName = "DOCX Parser"
    override val estimatedMemoryPerPage = 60_000L  // ~60KB per page

    private val xmlHandler = DocxXmlHandler()

    override suspend fun parse(
        input: InputStream,
        config: ParserConfig,
        onProgress: (Float) -> Unit
    ): Result<Document> = runCatching {
        coroutineContext.ensureActive()
        onProgress(0f)

        // Read ZIP entries
        val zipReader = DocxZipReader()
        val entries = zipReader.readEntries(input)

        onProgress(0.2f)
        coroutineContext.ensureActive()

        // Extract required content
        val documentXml = entries[DOCUMENT_XML_PATH]
            ?: throw ConversionError.ParseError.MissingRequiredEntry(DOCUMENT_XML_PATH)

        val stylesXml = entries[STYLES_XML_PATH]
        val coreXml = entries[CORE_XML_PATH]

        onProgress(0.3f)
        coroutineContext.ensureActive()

        // Parse styles first (needed for inheritance resolution)
        val styleMap = if (stylesXml != null) {
            xmlHandler.parseStyles(stylesXml)
        } else {
            emptyMap()
        }

        onProgress(0.4f)
        coroutineContext.ensureActive()

        // Parse document content
        val paragraphs = xmlHandler.parseDocumentXml(documentXml, styleMap) { progress ->
            onProgress(0.4f + progress * 0.4f)
        }

        onProgress(0.8f)
        coroutineContext.ensureActive()

        // Parse metadata
        val metadata = if (coreXml != null && config.extractMetadata) {
            xmlHandler.parseMetadata(coreXml)
        } else {
            DocumentMetadata.default()
        }

        // Extract page settings from document.xml if available
        val pageSettings = xmlHandler.parsePageSettings(documentXml)
        val finalMetadata = metadata.copy(
            pageWidth = pageSettings?.pageWidth ?: metadata.pageWidth,
            pageHeight = pageSettings?.pageHeight ?: metadata.pageHeight,
            marginTop = pageSettings?.marginTop ?: metadata.marginTop,
            marginBottom = pageSettings?.marginBottom ?: metadata.marginBottom,
            marginLeft = pageSettings?.marginLeft ?: metadata.marginLeft,
            marginRight = pageSettings?.marginRight ?: metadata.marginRight
        )

        onProgress(1f)

        // Create unpaginated document (single logical page with all content)
        Document(
            metadata = finalMetadata,
            pages = listOf(
                Page(
                    pageNumber = 1,
                    blocks = paragraphs,
                    pageHeight = 0f,  // Will be calculated during layout
                    explicitBreak = false
                )
            ),
            sourceFormat = SourceFormat.DOCX,
            layoutState = LayoutState.UNPAGINATED
        )
    }

    override suspend fun validate(input: InputStream): Boolean {
        return try {
            val zipReader = DocxZipReader()
            val entries = zipReader.readEntries(input)
            entries.containsKey(DOCUMENT_XML_PATH)
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val DOCUMENT_XML_PATH = "word/document.xml"
        const val STYLES_XML_PATH = "word/styles.xml"
        const val CORE_XML_PATH = "docProps/core.xml"
        const val CONTENT_TYPES_PATH = "[Content_Types].xml"
    }
}

/**
 * Reads and extracts entries from DOCX ZIP archive.
 */
class DocxZipReader {

    /**
     * Read all text-based entries from ZIP.
     *
     * @param input ZIP input stream
     * @return Map of entry path to content string
     */
    fun readEntries(input: InputStream): Map<String, String> {
        val entries = mutableMapOf<String, String>()

        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && isTextEntry(entry.name)) {
                    val content = zip.bufferedReader().readText()
                    entries[entry.name] = content
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        return entries
    }

    /**
     * Check if entry should be read as text.
     */
    private fun isTextEntry(name: String): Boolean {
        return name.endsWith(".xml") || name.endsWith(".rels")
    }
}
