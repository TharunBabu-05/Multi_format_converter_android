package com.converter.renderer.pdf

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.converter.core.error.ConversionError
import com.converter.core.model.*
import com.converter.core.renderer.RenderResult
import com.converter.core.renderer.RenderWarning
import com.converter.core.renderer.Renderer
import com.converter.core.renderer.RendererConfig
import kotlinx.coroutines.ensureActive
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Renders documents to PDF using Android's PdfDocument API.
 *
 * Uses Android Canvas for drawing text with styles.
 * Handles pagination, margins, and text wrapping.
 *
 * Requirements:
 * - Document must be PAGINATED before rendering
 * - Uses system fonts only (no embedding)
 */
class PdfRenderer : Renderer {

    override val targetFormat = SourceFormat.PDF
    override val rendererName = "Android PDF Renderer"

    private val warnings = mutableListOf<RenderWarning>()

    private data class CursorState(
        val x: Float,
        val y: Float,
        val isFirstLine: Boolean
    )

    override suspend fun render(
        document: Document,
        output: OutputStream,
        config: RendererConfig,
        onProgress: (Float) -> Unit
    ): Result<RenderResult> = runCatching {
        require(document.layoutState == LayoutState.PAGINATED) {
            "Document must be paginated before PDF rendering"
        }

        warnings.clear()
        val startTime = System.currentTimeMillis()
        coroutineContext.ensureActive()
        onProgress(0f)

        val pdfDocument = PdfDocument()

        try {
            val meta = document.metadata
            val totalPages = document.pages.size

            for ((index, page) in document.pages.withIndex()) {
                coroutineContext.ensureActive()

                // Create PDF page
                val pageInfo = PdfDocument.PageInfo.Builder(
                    meta.pageWidth.toInt(),
                    meta.pageHeight.toInt(),
                    index + 1
                ).create()

                val pdfPage = pdfDocument.startPage(pageInfo)

                try {
                    renderPage(pdfPage.canvas, page, meta, config)
                } catch (e: Exception) {
                    warnings.add(RenderWarning(
                        type = com.converter.core.renderer.RenderWarningType.CONTENT_CLIPPED,
                        message = "Error rendering page ${index + 1}: ${e.message}",
                        pageNumber = index + 1
                    ))
                }

                pdfDocument.finishPage(pdfPage)
                onProgress((index + 1).toFloat() / totalPages)
            }

            // Write to output
            pdfDocument.writeTo(output)
            output.flush()

            val duration = System.currentTimeMillis() - startTime

            RenderResult(
                bytesWritten = 0,  // PdfDocument doesn't report this
                pageCount = totalPages,
                warnings = warnings.toList(),
                durationMillis = duration
            )

        } finally {
            pdfDocument.close()
        }
    }

    /**
     * Render a single page to Canvas.
     */
    private fun renderPage(
        canvas: Canvas,
        page: Page,
        meta: DocumentMetadata,
        config: RendererConfig
    ) {
        val paint = Paint().apply {
            isAntiAlias = config.quality != com.converter.core.renderer.RenderQuality.DRAFT
            isSubpixelText = config.quality == com.converter.core.renderer.RenderQuality.HIGH
        }

        var yPosition = meta.marginTop

        for (block in page.blocks) {
            when (block) {
                is Paragraph -> {
                    yPosition += block.spacingBefore
                    yPosition = renderParagraph(canvas, block, yPosition, meta, paint)
                    yPosition += block.spacingAfter
                }
            }

            // Check if we've gone past the page
            if (yPosition > meta.pageHeight - meta.marginBottom) {
                break
            }
        }
    }

    /**
     * Render a paragraph, handling line wrapping.
     */
    private fun renderParagraph(
        canvas: Canvas,
        paragraph: Paragraph,
        startY: Float,
        meta: DocumentMetadata,
        paint: Paint
    ): Float {
        if (paragraph.isEmpty) {
            // Empty paragraph - just add line height
            return startY + 12f * paragraph.lineSpacing.multiplier
        }

        val contentWidth = meta.contentWidth - paragraph.indentation.left - paragraph.indentation.right
        var x = meta.marginLeft + paragraph.indentation.left + paragraph.indentation.firstLine
        // Compute initial baseline using the first run's font metrics
        var y = startY
        var isFirstLine = true
        var maxLineHeight = 0f

        // Calculate line height from first run and compute baseline offset
        if (paragraph.runs.isNotEmpty()) {
            paint.applyStyle(paragraph.runs.first().style)
            maxLineHeight = paint.fontSpacing
            // startY is treated as the top of the first line; convert to baseline
            val fm = paint.fontMetrics
            y = startY - fm.ascent
        }

        // Render each run
        for (run in paragraph.runs) {
            if (run.isEmpty) continue

            paint.applyStyle(run.style)
            val lineHeight = paint.fontSpacing
            maxLineHeight = maxOf(maxLineHeight, lineHeight)

            // Handle explicit line breaks
            if (run.text.contains('\n')) {
                val parts = run.text.split('\n')
                for ((partIndex, part) in parts.withIndex()) {
                    if (part.isNotEmpty()) {
                        val cursor = renderWords(
                            canvas = canvas,
                            text = part,
                            startX = x,
                            startY = y,
                            maxWidth = contentWidth,
                            meta = meta,
                            paragraph = paragraph,
                            paint = paint,
                            isFirstLine = isFirstLine
                        )
                        x = cursor.x
                        y = cursor.y
                        isFirstLine = cursor.isFirstLine
                    }
                    if (partIndex < parts.lastIndex) {
                        // Line break
                        x = meta.marginLeft + paragraph.indentation.left
                        y += maxLineHeight * paragraph.lineSpacing.multiplier
                        isFirstLine = false
                    }
                }
            } else {
                val cursor = renderWords(
                    canvas = canvas,
                    text = run.text,
                    startX = x,
                    startY = y,
                    maxWidth = contentWidth,
                    meta = meta,
                    paragraph = paragraph,
                    paint = paint,
                    isFirstLine = isFirstLine
                )
                x = cursor.x
                y = cursor.y
                isFirstLine = cursor.isFirstLine
            }
        }

        // Return Y position after paragraph
        return y + maxLineHeight * paragraph.lineSpacing.multiplier
    }

    /**
     * Render words with wrapping.
     */
    private fun renderWords(
        canvas: Canvas,
        text: String,
        startX: Float,
        startY: Float,
        maxWidth: Float,
        meta: DocumentMetadata,
        paragraph: Paragraph,
        paint: Paint,
        isFirstLine: Boolean
    ): CursorState {
        var x = startX
        var y = startY
        var currentIsFirstLine = isFirstLine
        val lineHeight = paint.fontSpacing

        val words = text.split(" ")
        for ((wordIndex, word) in words.withIndex()) {
            if (word.isEmpty()) continue

            val wordWidth = paint.measureText(word)
            val spaceWidth = paint.measureText(" ")

            // Check if word needs to wrap
            val lineStartX = if (currentIsFirstLine) {
                meta.marginLeft + paragraph.indentation.left + paragraph.indentation.firstLine
            } else {
                meta.marginLeft + paragraph.indentation.left
            }

            if (x + wordWidth > lineStartX + maxWidth && x > lineStartX) {
                // Wrap to next line
                x = meta.marginLeft + paragraph.indentation.left
                y += lineHeight * paragraph.lineSpacing.multiplier
                currentIsFirstLine = false
            }

            // Draw the word
            canvas.drawText(word, x, y, paint)
            x += wordWidth

            // Add space after word (except last)
            if (wordIndex < words.lastIndex) {
                x += spaceWidth
            }
        }

        return CursorState(x = x, y = y, isFirstLine = currentIsFirstLine)
    }

    /**
     * Render underline decoration.
     */
    private fun renderUnderline(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        paint: Paint
    ) {
        val underlineY = y + paint.fontMetrics.descent / 2
        val originalStrokeWidth = paint.strokeWidth
        paint.strokeWidth = paint.textSize / 15
        canvas.drawLine(x, underlineY, x + width, underlineY, paint)
        paint.strokeWidth = originalStrokeWidth
    }

    /**
     * Render strikethrough decoration.
     */
    private fun renderStrikethrough(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        paint: Paint
    ) {
        val strikeY = y - paint.fontMetrics.ascent / 3
        val originalStrokeWidth = paint.strokeWidth
        paint.strokeWidth = paint.textSize / 20
        canvas.drawLine(x, strikeY, x + width, strikeY, paint)
        paint.strokeWidth = originalStrokeWidth
    }
}

/**
 * Extension to apply TextStyle to Paint.
 */
private fun Paint.applyStyle(style: TextStyle) {
    // Keep text size in points to match DOCX units and PDF page units.
    textSize = style.fontSize
    typeface = Typeface.create(
        style.fontFamily.toTypeface(),
        style.typefaceStyle
    )
    color = style.textColor.toArgb()
    isUnderlineText = style.isUnderline
    isStrikeThruText = style.isStrikethrough
}

/**
 * Get font spacing (line height) from Paint.
 */
private val Paint.fontSpacing: Float
    get() {
        val fm = fontMetrics
        return fm.descent - fm.ascent + fm.leading
    }
