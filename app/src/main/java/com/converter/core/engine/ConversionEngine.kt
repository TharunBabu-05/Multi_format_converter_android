package com.converter.core.engine

import android.content.Context
import android.net.Uri
import com.converter.core.error.ConversionError
import com.converter.core.error.WarningCollector
import com.converter.core.layout.LayoutEngine
import com.converter.core.model.Document
import com.converter.core.model.LayoutState
import com.converter.core.model.SourceFormat
import com.converter.core.parser.Parser
import com.converter.core.parser.ParserConfig
import com.converter.core.renderer.Renderer
import com.converter.core.renderer.RendererConfig
import com.converter.infrastructure.CoroutineManager
import com.converter.infrastructure.ProgressReporter
import com.converter.infrastructure.TempFileManager
import com.converter.parser.docx.DocxParser
import com.converter.parser.pdf.PdfParser
import com.converter.renderer.docx.DocxWriter
import com.converter.renderer.pdf.PdfRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Main orchestrator for document conversion operations.
 *
 * This is the primary entry point for all conversions.
 * Handles:
 * - Format detection
 * - Parser/Renderer selection
 * - Layout processing
 * - Progress reporting
 * - Error handling
 * - Resource cleanup
 */
class ConversionEngine(
    private val context: Context,
    private val coroutineManager: CoroutineManager = CoroutineManager.default
) {
    private val tempFileManager = TempFileManager(context)
    private val layoutEngine = LayoutEngine(context)
    private val warningCollector = WarningCollector()

    // Parsers
    private val docxParser = DocxParser()
    private val pdfParser = PdfParser(context)

    // Renderers
    private val pdfRenderer = PdfRenderer()
    private val docxWriter = DocxWriter()

    /**
     * Convert a document from one format to another.
     *
     * @param request Conversion parameters
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return Conversion result
     */
    suspend fun convert(
        request: ConversionRequest,
        onProgress: suspend (Float) -> Unit = {}
    ): ConversionResult = withContext(coroutineManager.ioDispatcher) {
        val startTime = System.currentTimeMillis()
        warningCollector.clear()

        try {
            // Create progress reporter with main thread dispatching
            val progressReporter = ProgressReporter(coroutineManager.mainDispatcher, onProgress)

            // Execute with timeout if configured
            val result = if (request.options.timeoutMillis > 0) {
                withTimeout(request.options.timeoutMillis) {
                    executeConversion(request, progressReporter)
                }
            } else {
                executeConversion(request, progressReporter)
            }

            val duration = System.currentTimeMillis() - startTime

            ConversionResult.Success(
                outputUri = request.outputUri,
                pageCount = result.document.pages.size,
                warnings = warningCollector.warnings,
                durationMillis = duration
            )

        } catch (e: CancellationException) {
            throw e  // Propagate cancellation
        } catch (e: ConversionError) {
            ConversionResult.Failure(error = e, partialOutput = null)
        } catch (e: Exception) {
            ConversionResult.Failure(
                error = ConversionError.fromException(e),
                partialOutput = null
            )
        } finally {
            // Cleanup temp files
            tempFileManager.cleanupOldFiles()
        }
    }

    private suspend fun executeConversion(
        request: ConversionRequest,
        progressReporter: ProgressReporter
    ): ConversionOutput {
        coroutineContext.ensureActive()

        // Phase 1: Parse source document
        var parsingProgress = 0f
        val parser = getParser(request.sourceFormat)
        
        val inputStream = context.contentResolver.openInputStream(request.inputUri)
            ?: throw ConversionError.InputError.FileNotReadable(request.inputUri.toString())

        val document = inputStream.use { input ->
            parser.parse(input, request.parserConfig) { progress ->
                parsingProgress = progress
            }.getOrElse { throw ConversionError.fromException(it) }
        }
        progressReporter.report(0.4f)

        coroutineContext.ensureActive()

        // Phase 2: Layout (if needed)
        val paginatedDocument = if (document.layoutState == LayoutState.UNPAGINATED) {
            layoutEngine.paginate(document) { progress ->
                // Layout progress is synchronous
            }
        } else {
            document
        }
        progressReporter.report(0.6f)

        coroutineContext.ensureActive()

        // Phase 3: Render to target format
        val renderer = getRenderer(request.targetFormat)

        val outputStream = context.contentResolver.openOutputStream(request.outputUri)
            ?: throw ConversionError.InputError.OutputNotWritable(request.outputUri.toString())

        outputStream.use { output ->
            renderer.render(paginatedDocument, output, request.rendererConfig) { progress ->
                // Render progress is synchronous
            }.getOrElse { throw ConversionError.fromException(it) }
        }
        progressReporter.report(1f)

        return ConversionOutput(paginatedDocument)
    }

    private fun getParser(format: SourceFormat): Parser = when (format) {
        SourceFormat.DOCX -> docxParser
        SourceFormat.PDF -> pdfParser
        SourceFormat.UNKNOWN -> throw ConversionError.InputError.InvalidFormat("known format", null)
    }

    private fun getRenderer(format: SourceFormat): Renderer = when (format) {
        SourceFormat.PDF -> pdfRenderer
        SourceFormat.DOCX -> docxWriter
        SourceFormat.UNKNOWN -> throw ConversionError.InputError.InvalidFormat("known format", null)
    }

    /**
     * Quick validation of source file.
     */
    suspend fun validateSource(uri: Uri, expectedFormat: SourceFormat): Boolean {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            input.use { getParser(expectedFormat).validate(it) }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get supported conversion paths.
     */
    fun getSupportedConversions(): List<ConversionPath> = listOf(
        ConversionPath(SourceFormat.DOCX, SourceFormat.PDF),
        ConversionPath(SourceFormat.PDF, SourceFormat.DOCX)
    )

    /**
     * Check if conversion is supported.
     */
    fun isConversionSupported(source: SourceFormat, target: SourceFormat): Boolean {
        return getSupportedConversions().any { it.source == source && it.target == target }
    }

    private data class ConversionOutput(val document: Document)
}

/**
 * Request for a conversion operation.
 */
data class ConversionRequest(
    val inputUri: Uri,
    val outputUri: Uri,
    val sourceFormat: SourceFormat,
    val targetFormat: SourceFormat,
    val options: ConversionOptions = ConversionOptions(),
    val parserConfig: ParserConfig = ParserConfig(),
    val rendererConfig: RendererConfig = RendererConfig()
)

/**
 * Options for conversion.
 */
data class ConversionOptions(
    val timeoutMillis: Long = 5 * 60 * 1000,  // 5 minutes
    val preserveFormatting: Boolean = true,
    val maxPages: Int = Int.MAX_VALUE
)

/**
 * Result of a conversion operation.
 */
sealed class ConversionResult {
    data class Success(
        val outputUri: Uri,
        val pageCount: Int,
        val warnings: List<com.converter.core.error.ConversionWarning>,
        val durationMillis: Long
    ) : ConversionResult() {
        val hasWarnings: Boolean get() = warnings.isNotEmpty()
    }

    data class Failure(
        val error: ConversionError,
        val partialOutput: Uri?
    ) : ConversionResult() {
        val userMessage: String get() = error.userMessage
    }
}

/**
 * Represents a supported conversion path.
 */
data class ConversionPath(
    val source: SourceFormat,
    val target: SourceFormat
) {
    val displayName: String
        get() = "${source.extension.uppercase()} → ${target.extension.uppercase()}"
}
