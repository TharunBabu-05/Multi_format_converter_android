package com.converter.core.layout

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import com.converter.core.model.*

/**
 * The Layout Engine transforms UNPAGINATED documents into PAGINATED ones.
 *
 * This is essential for DOCX → PDF where source has no page breaks,
 * but target requires explicit pages.
 *
 * Responsibilities:
 * - Calculate text measurements using Android Paint
 * - Perform line wrapping within paragraphs
 * - Split content across pages when overflow occurs
 * - Handle paragraph spacing and indentation
 */
class LayoutEngine(private val context: Context) {

    private val textMeasurer = TextMeasurer()

    /**
     * Paginate a document based on its metadata dimensions.
     *
     * @param document Document with layoutState = UNPAGINATED
     * @param onProgress Progress callback
     * @return Paginated document
     */
    suspend fun paginate(
        document: Document,
        onProgress: suspend (Float) -> Unit = {}
    ): Document {
        require(document.layoutState == LayoutState.UNPAGINATED) {
            "Document already paginated"
        }

        val meta = document.metadata
        val contentWidth = meta.contentWidth
        val contentHeight = meta.contentHeight

        val allBlocks = document.allBlocks
        val totalBlocks = allBlocks.size

        val paginatedPages = mutableListOf<Page>()
        var currentPageBlocks = mutableListOf<Block>()
        var currentPageHeight = 0f
        var pageNumber = 1

        for ((index, block) in allBlocks.withIndex()) {
            val blockHeight = calculateBlockHeight(block, contentWidth)

            // Check if block fits on current page
            if (currentPageHeight + blockHeight > contentHeight && currentPageBlocks.isNotEmpty()) {
                // Finalize current page and start new one
                paginatedPages.add(
                    Page(
                        pageNumber = pageNumber,
                        blocks = currentPageBlocks.toList(),
                        pageHeight = currentPageHeight,
                        explicitBreak = false
                    )
                )
                pageNumber++
                currentPageBlocks = mutableListOf()
                currentPageHeight = 0f
            }

            currentPageBlocks.add(block)
            currentPageHeight += blockHeight

            // Report progress
            onProgress((index + 1).toFloat() / totalBlocks)
        }

        // Add final page if has content
        if (currentPageBlocks.isNotEmpty()) {
            paginatedPages.add(
                Page(
                    pageNumber = pageNumber,
                    blocks = currentPageBlocks.toList(),
                    pageHeight = currentPageHeight,
                    explicitBreak = false
                )
            )
        }

        // Handle empty document
        if (paginatedPages.isEmpty()) {
            paginatedPages.add(Page.empty(1))
        }

        return document.copy(
            pages = paginatedPages,
            layoutState = LayoutState.PAGINATED
        )
    }

    /**
     * Calculate total height of a block.
     */
    private fun calculateBlockHeight(block: Block, maxWidth: Float): Float {
        return when (block) {
            is Paragraph -> calculateParagraphHeight(block, maxWidth)
        }
    }

    /**
     * Calculate total height of a paragraph including spacing and wrapped lines.
     */
    private fun calculateParagraphHeight(paragraph: Paragraph, maxWidth: Float): Float {
        if (paragraph.isEmpty) {
            // Empty paragraph still has spacing
            return paragraph.spacingBefore + paragraph.spacingAfter + 12f // Default line height
        }

        var totalHeight = paragraph.spacingBefore
        val effectiveWidth = maxWidth - paragraph.indentation.left - paragraph.indentation.right

        // Calculate wrapped lines
        val lines = wrapParagraphToLines(paragraph, effectiveWidth)
        
        for (line in lines) {
            val lineHeight = line.maxOfOrNull { textMeasurer.getLineHeight(it.style) } ?: 12f
            totalHeight += lineHeight * paragraph.lineSpacing.multiplier
        }

        totalHeight += paragraph.spacingAfter

        return totalHeight
    }

    /**
     * Wrap paragraph into lines based on available width.
     * Returns list of lines, where each line is a list of TextRuns.
     */
    private fun wrapParagraphToLines(
        paragraph: Paragraph,
        maxWidth: Float
    ): List<List<TextRun>> {
        val lines = mutableListOf<MutableList<TextRun>>()
        var currentLine = mutableListOf<TextRun>()
        var currentLineWidth = paragraph.indentation.firstLine

        for (run in paragraph.runs) {
            if (run.text.isEmpty()) continue

            // Handle explicit line breaks
            if (run.text.contains('\n')) {
                val parts = run.text.split('\n')
                for ((partIndex, part) in parts.withIndex()) {
                    if (part.isNotEmpty()) {
                        val partRun = run.withText(part)
                        val result = addRunToLine(
                            partRun, currentLine, currentLineWidth, maxWidth,
                            paragraph.indentation.left, lines
                        )
                        currentLine = result.first
                        currentLineWidth = result.second
                    }
                    // Add line break after each part except last
                    if (partIndex < parts.lastIndex) {
                        if (currentLine.isNotEmpty()) {
                            lines.add(currentLine)
                        }
                        currentLine = mutableListOf()
                        currentLineWidth = paragraph.indentation.left
                    }
                }
            } else {
                val result = addRunToLine(
                    run, currentLine, currentLineWidth, maxWidth,
                    paragraph.indentation.left, lines
                )
                currentLine = result.first
                currentLineWidth = result.second
            }
        }

        // Add final line
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }

        return lines
    }

    /**
     * Add a run to the current line, wrapping if necessary.
     */
    private fun addRunToLine(
        run: TextRun,
        currentLine: MutableList<TextRun>,
        currentWidth: Float,
        maxWidth: Float,
        leftIndent: Float,
        lines: MutableList<MutableList<TextRun>>
    ): Pair<MutableList<TextRun>, Float> {
        var line = currentLine
        var lineWidth = currentWidth

        val words = run.text.split(" ")
        var currentWordRun = StringBuilder()

        for ((wordIndex, word) in words.withIndex()) {
            val wordWithSpace = if (wordIndex < words.lastIndex) "$word " else word
            val wordWidth = textMeasurer.measureWidth(wordWithSpace, run.style)

            if (lineWidth + wordWidth > maxWidth && line.isNotEmpty()) {
                // Wrap to new line
                if (currentWordRun.isNotEmpty()) {
                    line.add(run.withText(currentWordRun.toString()))
                    currentWordRun = StringBuilder()
                }
                lines.add(line)
                line = mutableListOf()
                lineWidth = leftIndent
            }

            currentWordRun.append(wordWithSpace)
            lineWidth += wordWidth
        }

        // Add remaining text
        if (currentWordRun.isNotEmpty()) {
            line.add(run.withText(currentWordRun.toString()))
        }

        return Pair(line, lineWidth)
    }

    /**
     * Text measurement using Android Paint.
     */
    inner class TextMeasurer {
        private val paint = Paint().apply {
            isAntiAlias = true
        }

        /**
         * Measure width of text with given style.
         */
        fun measureWidth(text: String, style: TextStyle): Float {
            applyStyle(style)
            return paint.measureText(text)
        }

        /**
         * Get line height for given style.
         */
        fun getLineHeight(style: TextStyle): Float {
            applyStyle(style)
            val fm = paint.fontMetrics
            return fm.descent - fm.ascent + fm.leading
        }

        /**
         * Get full text metrics.
         */
        fun measure(text: String, style: TextStyle): TextMetrics {
            applyStyle(style)
            val fm = paint.fontMetrics
            return TextMetrics(
                width = paint.measureText(text),
                height = fm.descent - fm.ascent + fm.leading,
                ascent = -fm.ascent,
                descent = fm.descent
            )
        }

        private fun applyStyle(style: TextStyle) {
            // PDF and DOCX layout use point units (1/72 inch); keep measurements in points.
            paint.textSize = style.fontSize
            paint.typeface = Typeface.create(
                style.fontFamily.toTypeface(),
                style.typefaceStyle
            )
        }
    }

    /**
     * Text metrics result.
     */
    data class TextMetrics(
        val width: Float,
        val height: Float,
        val ascent: Float,
        val descent: Float
    )
}
