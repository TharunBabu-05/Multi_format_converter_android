package com.converter.parser.pdf

import android.content.Context
import com.converter.core.error.ConversionError
import com.converter.core.model.*
import com.converter.core.parser.Parser
import com.converter.core.parser.ParserConfig
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import kotlin.coroutines.coroutineContext
import java.io.StringWriter

class PdfParser(private val context: Context) : Parser {

    override val supportedFormat = SourceFormat.PDF
    override val parserName = "PDF Parser (Native Stripper)"
    override val estimatedMemoryPerPage = 100_000L

    private var initialized = false

    private fun ensureInitialized() {
        if (!initialized) {
            PDFBoxResourceLoader.init(context)
            initialized = true
        }
    }

    override suspend fun parse(
        input: InputStream,
        config: ParserConfig,
        onProgress: (Float) -> Unit
    ): Result<Document> = runCatching {
        ensureInitialized()
        coroutineContext.ensureActive()
        onProgress(0f)

        val pdfDocument = PDDocument.load(input)

        try {
            if (pdfDocument.isEncrypted) {
                throw ConversionError.InputError.EncryptedDocument("PDF is encrypted")
            }

            val totalPages = pdfDocument.numberOfPages
            val pagesToProcess = minOf(totalPages, config.maxPages)

            if (pagesToProcess == 0) {
                throw ConversionError.ParseError.NoTextContent("PDF has no pages")
            }

            val pages = mutableListOf<Page>()

            for (pageIndex in 0 until pagesToProcess) {
                coroutineContext.ensureActive()

                val stripper = StyledPdfStripper()
                stripper.startPage = pageIndex + 1
                stripper.endPage = pageIndex + 1
                
                // We use a dummy writer since our stripper collects to internal 'paragraphs'
                stripper.writeText(pdfDocument, StringWriter())

                pages.add(
                    Page(
                        pageNumber = pageIndex + 1,
                        blocks = stripper.paragraphs,
                        pageHeight = pdfDocument.getPage(pageIndex).mediaBox.height,
                        explicitBreak = true
                    )
                )

                onProgress((pageIndex + 1).toFloat() / pagesToProcess)
            }

            val totalText = pages.flatMap { it.paragraphs }.sumOf { it.characterCount }
            if (totalText == 0) {
                throw ConversionError.ParseError.NoTextContent("No text extracted. Scanned/image document?")
            }

            val pdfInfo = pdfDocument.documentInformation
            val metadata = DocumentMetadata(
                title = pdfInfo?.title,
                author = pdfInfo?.author,
                createdAt = pdfInfo?.creationDate?.time?.time,
                modifiedAt = pdfInfo?.modificationDate?.time?.time,
                pageWidth = 612f,
                pageHeight = 792f,
                marginTop = 72f,
                marginBottom = 72f,
                marginLeft = 72f,
                marginRight = 72f
            )

            Document(
                metadata = metadata,
                pages = pages,
                sourceFormat = SourceFormat.PDF,
                layoutState = LayoutState.PAGINATED
            )
        } finally {
            pdfDocument.close()
        }
    }

    override suspend fun validate(input: InputStream): Boolean {
         return try {
            ensureInitialized()
            val header = ByteArray(5)
            input.read(header)
            String(header).startsWith("%PDF-")
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * A custom PDFTextStripper that natively uses PdfBox's incredibly accurate
 * spacing and word-grouping engine, but intercepts styled strings instead of plain text.
 */
class StyledPdfStripper : PDFTextStripper() {
    val paragraphs = mutableListOf<Paragraph>()
    
    private var currentRuns = mutableListOf<TextRun>()
    private var firstInLine = true
    private var lastStyle = TextStyle.default()
    private var paragraphStartX = -1f
    private var lastEndX = 0f

    init {
        sortByPosition = true
        // By default, spacing tolerance is 0.5f. A smaller value forces PdfBox to 
        // recognize smaller gaps as word breaks, fixing concatenated words.
        spacingTolerance = 0.3f
    }

    override fun writeParagraphStart() {
        super.writeParagraphStart()
        if (currentRuns.isNotEmpty()) flushParagraph()
        firstInLine = true
        paragraphStartX = -1f
    }

    override fun writeParagraphEnd() {
        super.writeParagraphEnd()
        flushParagraph()
    }

    override fun writeLineSeparator() {
        super.writeLineSeparator()
        if (!firstInLine && currentRuns.isNotEmpty()) {
            currentRuns.add(TextRun(" ", lastStyle, com.converter.core.model.TextPosition(0f, 0f, 0f, 0f, 0)))
        }
        firstInLine = false
        lastEndX = 0f
    }

    override fun writeWordSeparator() {
        super.writeWordSeparator()
        currentRuns.add(TextRun(" ", lastStyle, com.converter.core.model.TextPosition(0f, 0f, 0f, 0f, 0)))
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>?) {
        super.writeString(text, textPositions)
        
        if (text.isEmpty()) return
        
        // Strip out weird control characters, private use areas, or ridiculously long number strings
        // that often replace bullet points in poorly encoded PDFs.
        var cleanText = text
        if (cleanText.length > 8 && cleanText.matches(Regex("^\\d{8,}.*"))) {
            cleanText = "• " + cleanText.replaceFirst(Regex("^\\d+"), "")
        }
        
        var isBold = false
        var isItalic = false
        var fontSize = 12f
        var x = 0f
        
        if (!textPositions.isNullOrEmpty()) {
            val first = textPositions.first()
            val font = first.font
            val fontName = font?.name?.lowercase() ?: ""
            val descriptor = font?.fontDescriptor
            
            val hasBoldName = fontName.contains("bold") || fontName.contains("bd") || fontName.contains("heavy")
            val isForceBold = descriptor?.isForceBold ?: false
            val fontWeight = descriptor?.fontWeight ?: 400f
            
            isBold = hasBoldName || isForceBold || fontWeight >= 600f
            
            isItalic = fontName.contains("italic") || fontName.contains("oblique") || fontName.contains("it") || (descriptor?.isItalic ?: false)
            fontSize = textPositions.map { it.fontSize }.average().toFloat()
            x = first.x
            
            // Check for large gap to inject a tab character instead of a space
            if (lastEndX > 0f) {
                val gap = x - lastEndX
                // If the gap is larger than roughly 4 spaces, insert a tab to align text rightwards
                if (gap > fontSize * 4f) {
                    if (currentRuns.lastOrNull()?.text == " ") {
                        currentRuns.removeAt(currentRuns.lastIndex) // Replace the single space with tab
                    }
                    currentRuns.add(TextRun("\t", lastStyle, com.converter.core.model.TextPosition(0f, 0f, 0f, 0f, 0)))
                }
            }
            
            lastEndX = textPositions.last().x + textPositions.last().width
            
            if (paragraphStartX < 0f) {
                // Approximate alignment detection based on the start of the first line
                paragraphStartX = x
            }
        } else {
            fontSize = lastStyle.fontSize
            isBold = lastStyle.fontWeight == FontWeight.BOLD
            isItalic = lastStyle.isItalic
        }

        val style = TextStyle(
            fontFamily = FontFamily.SANS_SERIF,
            fontSize = fontSize,
            fontWeight = if (isBold) FontWeight.BOLD else FontWeight.NORMAL,
            isItalic = isItalic
        )
        
        lastStyle = style

        currentRuns.add(TextRun(
            text = cleanText,
            style = style,
            position = com.converter.core.model.TextPosition(x, 0f, 0f, 0f, 0)
        ))
        
        firstInLine = false
    }

    private fun flushParagraph() {
        if (currentRuns.isEmpty()) return
        
        val alignment = when {
            paragraphStartX > 200f -> ParagraphAlignment.CENTER // Crude heuristic
            else -> ParagraphAlignment.LEFT
        }

        paragraphs.add(
            Paragraph(
                runs = currentRuns.toList(),
                alignment = alignment,
                spacingBefore = 0f,
                spacingAfter = 6f,
                lineSpacing = LineSpacing.single()
            )
        )
        currentRuns.clear()
        paragraphStartX = -1f
    }
}