package com.converter.renderer.docx

import com.converter.core.model.*
import com.converter.core.renderer.RenderResult
import com.converter.core.renderer.RenderWarning
import com.converter.core.renderer.Renderer
import com.converter.core.renderer.RendererConfig
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Generates DOCX files from Internal Document Model.
 *
 * DOCX is a ZIP archive containing XML files following the
 * Office Open XML (OOXML) specification.
 *
 * This writer generates:
 * - Minimal required structure files
 * - Document content with paragraphs and runs
 * - Basic formatting (bold, italic, font size, color)
 */
class DocxWriter : Renderer {

    override val targetFormat = SourceFormat.DOCX
    override val rendererName = "DOCX Writer"

    private val warnings = mutableListOf<RenderWarning>()

    override suspend fun render(
        document: Document,
        output: OutputStream,
        config: RendererConfig,
        onProgress: (Float) -> Unit
    ): Result<RenderResult> = runCatching {
        warnings.clear()
        val startTime = System.currentTimeMillis()
        coroutineContext.ensureActive()
        onProgress(0f)

        var bytesWritten = 0L

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            // 1. Write [Content_Types].xml
            writeContentTypes(zip)
            onProgress(0.1f)
            coroutineContext.ensureActive()

            // 2. Write _rels/.rels
            writeRootRels(zip)
            onProgress(0.2f)
            coroutineContext.ensureActive()

            // 3. Write word/_rels/document.xml.rels
            writeDocumentRels(zip)
            onProgress(0.3f)
            coroutineContext.ensureActive()

            // 4. Write word/styles.xml (if configured)
            if (config.includeStyles) {
                writeStyles(zip)
            }
            onProgress(0.4f)
            coroutineContext.ensureActive()

            // 5. Write word/document.xml (main content)
            val totalBlocks = document.allBlocks.size
            writeDocumentXml(zip, document) { blockProgress ->
                onProgress(0.4f + blockProgress * 0.5f)
            }
            coroutineContext.ensureActive()

            // 6. Write docProps/core.xml
            writeCoreProperties(zip, document.metadata)
            onProgress(0.95f)
            coroutineContext.ensureActive()

            // 7. Write docProps/app.xml
            writeAppProperties(zip)
            onProgress(1f)
        }

        val duration = System.currentTimeMillis() - startTime

        RenderResult(
            bytesWritten = bytesWritten,
            pageCount = document.pages.size,
            warnings = warnings.toList(),
            durationMillis = duration
        )
    }

    /**
     * Write [Content_Types].xml - declares MIME types for parts.
     */
    private fun writeContentTypes(zip: ZipOutputStream) {
        zip.putNextEntry(ZipEntry("[Content_Types].xml"))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)
        writer.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
    <Default Extension="xml" ContentType="application/xml"/>
    <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
    <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
    <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
    <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>""")
        writer.flush()
        zip.closeEntry()
    }

    /**
     * Write _rels/.rels - root relationships.
     */
    private fun writeRootRels(zip: ZipOutputStream) {
        zip.putNextEntry(ZipEntry("_rels/.rels"))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)
        writer.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
    <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>""")
        writer.flush()
        zip.closeEntry()
    }

    /**
     * Write word/_rels/document.xml.rels - document relationships.
     */
    private fun writeDocumentRels(zip: ZipOutputStream) {
        zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)
        writer.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>""")
        writer.flush()
        zip.closeEntry()
    }

    /**
     * Write word/styles.xml - style definitions.
     */
    private fun writeStyles(zip: ZipOutputStream) {
        zip.putNextEntry(ZipEntry("word/styles.xml"))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)
        writer.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
    <w:docDefaults>
        <w:rPrDefault>
            <w:rPr>
                <w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:cs="Arial"/>
                <w:sz w:val="24"/>
                <w:szCs w:val="24"/>
                <w:lang w:val="en-US"/>
            </w:rPr>
        </w:rPrDefault>
        <w:pPrDefault>
            <w:pPr>
                <w:spacing w:after="200" w:line="276" w:lineRule="auto"/>
            </w:pPr>
        </w:pPrDefault>
    </w:docDefaults>
    <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
        <w:name w:val="Normal"/>
    </w:style>
    <w:style w:type="paragraph" w:styleId="Heading1">
        <w:name w:val="Heading 1"/>
        <w:basedOn w:val="Normal"/>
        <w:pPr>
            <w:spacing w:before="240" w:after="60"/>
        </w:pPr>
        <w:rPr>
            <w:b/>
            <w:sz w:val="48"/>
        </w:rPr>
    </w:style>
    <w:style w:type="paragraph" w:styleId="Heading2">
        <w:name w:val="Heading 2"/>
        <w:basedOn w:val="Normal"/>
        <w:pPr>
            <w:spacing w:before="200" w:after="40"/>
        </w:pPr>
        <w:rPr>
            <w:b/>
            <w:sz w:val="36"/>
        </w:rPr>
    </w:style>
</w:styles>""")
        writer.flush()
        zip.closeEntry()
    }

    /**
     * Write word/document.xml - main document content.
     */
    private suspend fun writeDocumentXml(
        zip: ZipOutputStream,
        document: Document,
        onProgress: suspend (Float) -> Unit
    ) {
        zip.putNextEntry(ZipEntry("word/document.xml"))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)

        // XML header and document start
        writer.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<w:body>
""")

        val totalBlocks = document.allBlocks.size
        var processedBlocks = 0

        for ((pageIndex, page) in document.pages.withIndex()) {
            for (block in page.blocks) {
                when (block) {
                    is Paragraph -> writeParagraph(writer, block)
                }
                processedBlocks++
                onProgress(processedBlocks.toFloat() / totalBlocks)
            }

            // Add page break between pages (except after last)
            if (pageIndex < document.pages.lastIndex) {
                writer.write("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>
""")
            }
        }

        // Section properties (page size and margins)
        writeSectionProperties(writer, document.metadata)

        // Close document
        writer.write("""</w:body>
</w:document>""")
        writer.flush()
        zip.closeEntry()
    }

    /**
     * Write a paragraph element.
     */
    private fun writeParagraph(writer: OutputStreamWriter, paragraph: Paragraph) {
        writer.write("<w:p>")

        // Paragraph properties
        writer.write("<w:pPr>")
        writeParagraphProperties(writer, paragraph)
        writer.write("</w:pPr>")

        // Text runs
        for (run in paragraph.runs) {
            writeTextRun(writer, run)
        }

        writer.write("</w:p>\n")
    }

    /**
     * Write paragraph properties.
     */
    private fun writeParagraphProperties(writer: OutputStreamWriter, paragraph: Paragraph) {
        // Alignment
        if (paragraph.alignment != ParagraphAlignment.LEFT) {
            writer.write("""<w:jc w:val="${paragraph.alignment.toDocxValue()}"/>""")
        }

        // Spacing
        val beforeTwips = (paragraph.spacingBefore * 20).toInt()
        val afterTwips = (paragraph.spacingAfter * 20).toInt()
        val lineTwips = (paragraph.lineSpacing.multiplier * 240).toInt()
        writer.write("""<w:spacing w:before="$beforeTwips" w:after="$afterTwips" w:line="$lineTwips" w:lineRule="auto"/>""")

        // Indentation
        if (paragraph.indentation != Indentation.none()) {
            val firstLine = (paragraph.indentation.firstLine * 20).toInt()
            val left = (paragraph.indentation.left * 20).toInt()
            val right = (paragraph.indentation.right * 20).toInt()

            if (firstLine < 0) {
                writer.write("""<w:ind w:hanging="${-firstLine}" w:left="$left" w:right="$right"/>""")
            } else {
                writer.write("""<w:ind w:firstLine="$firstLine" w:left="$left" w:right="$right"/>""")
            }
        }
    }

    /**
     * Write a text run.
     */
    private fun writeTextRun(writer: OutputStreamWriter, run: TextRun) {
        if (run.isEmpty) return

        writer.write("<w:r>")

        // Run properties
        writer.write("<w:rPr>")
        writeRunProperties(writer, run.style)
        writer.write("</w:rPr>")

        // Handle text with line breaks
        val parts = run.text.split('\n')
        for ((index, part) in parts.withIndex()) {
            if (part.isNotEmpty()) {
                val tabParts = part.split("\t")
                for ((tabIndex, tabPart) in tabParts.withIndex()) {
                    if (tabPart.isNotEmpty()) {
                        writer.write("""<w:t xml:space="preserve">""")
                        writer.write(escapeXml(tabPart))
                        writer.write("</w:t>")
                    }
                    if (tabIndex < tabParts.lastIndex) {
                        writer.write("<w:tab/>")
                    }
                }
            }
            if (index < parts.lastIndex) {
                writer.write("<w:br/>")
            }
        }

        writer.write("</w:r>")
    }

    /**
     * Write run (character) properties.
     */
    private fun writeRunProperties(writer: OutputStreamWriter, style: TextStyle) {
        // Font family
        writer.write("""<w:rFonts w:ascii="${style.fontFamily.docxName}" w:hAnsi="${style.fontFamily.docxName}"/>""")

        // Font size (half-points)
        val halfPoints = (style.fontSize * 2).toInt()
        writer.write("""<w:sz w:val="$halfPoints"/>""")
        writer.write("""<w:szCs w:val="$halfPoints"/>""")

        // Bold
        if (style.fontWeight == FontWeight.BOLD) {
            writer.write("<w:b/>")
        }

        // Italic
        if (style.isItalic) {
            writer.write("<w:i/>")
        }

        // Character spacing / tracking (twentieths of a point)
        if (style.letterSpacing != 0f) {
            val spacingVal = (style.letterSpacing * 20f).toInt()
            writer.write("""<w:spacing w:val="$spacingVal"/>""")
        }

        // Underline
        if (style.isUnderline) {
            writer.write("""<w:u w:val="single"/>""")
        }

        // Strikethrough
        if (style.isStrikethrough) {
            writer.write("<w:strike/>")
        }

        // Color (if not black)
        if (!style.textColor.isBlack) {
            writer.write("""<w:color w:val="${style.textColor.toHexString()}"/>""")
        }

        // Subscript/Superscript
        if (style.isSubscript) {
            writer.write("""<w:vertAlign w:val="subscript"/>""")
        } else if (style.isSuperscript) {
            writer.write("""<w:vertAlign w:val="superscript"/>""")
        }
    }

    /**
     * Write section properties (page settings).
     */
    private fun writeSectionProperties(writer: OutputStreamWriter, meta: DocumentMetadata) {
        val widthTwips = (meta.pageWidth * 20).toInt()
        val heightTwips = (meta.pageHeight * 20).toInt()
        val topTwips = (meta.marginTop * 20).toInt()
        val bottomTwips = (meta.marginBottom * 20).toInt()
        val leftTwips = (meta.marginLeft * 20).toInt()
        val rightTwips = (meta.marginRight * 20).toInt()

        writer.write("""<w:sectPr>
    <w:pgSz w:w="$widthTwips" w:h="$heightTwips"/>
    <w:pgMar w:top="$topTwips" w:right="$rightTwips" w:bottom="$bottomTwips" w:left="$leftTwips" w:header="720" w:footer="720" w:gutter="0"/>
</w:sectPr>
""")
    }

    /**
     * Write docProps/core.xml - Dublin Core metadata.
     */
    private fun writeCoreProperties(zip: ZipOutputStream, meta: DocumentMetadata) {
        zip.putNextEntry(ZipEntry("docProps/core.xml"))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)

        val now = java.time.Instant.now().toString()
        val created = meta.createdAt?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: now
        val modified = meta.modifiedAt?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: now

        writer.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                   xmlns:dc="http://purl.org/dc/elements/1.1/"
                   xmlns:dcterms="http://purl.org/dc/terms/"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
    <dc:title>${escapeXml(meta.title ?: "Untitled")}</dc:title>
    <dc:creator>${escapeXml(meta.author ?: "Document Converter")}</dc:creator>
    <dcterms:created xsi:type="dcterms:W3CDTF">$created</dcterms:created>
    <dcterms:modified xsi:type="dcterms:W3CDTF">$modified</dcterms:modified>
</cp:coreProperties>""")
        writer.flush()
        zip.closeEntry()
    }

    /**
     * Write docProps/app.xml - application properties.
     */
    private fun writeAppProperties(zip: ZipOutputStream) {
        zip.putNextEntry(ZipEntry("docProps/app.xml"))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)
        writer.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
    <Application>Document Converter</Application>
    <AppVersion>1.0</AppVersion>
</Properties>""")
        writer.flush()
        zip.closeEntry()
    }

    /**
     * Escape special XML characters.
     */
    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
