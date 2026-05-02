package com.converter.parser.docx

import com.converter.core.model.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.UUID

/**
 * Handles XML parsing for DOCX documents.
 *
 * Uses Android's XmlPullParser for streaming XML parsing.
 * This is more memory-efficient than DOM parsing for large documents.
 */
class DocxXmlHandler {

    private val parserFactory: XmlPullParserFactory by lazy {
        XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }
    }

    /**
     * Parse document.xml to extract paragraphs.
     */
    fun parseDocumentXml(
        xml: String,
        styleMap: Map<String, StyleDefinition>,
        onProgress: (Float) -> Unit = {}
    ): List<Paragraph> {
        val paragraphs = mutableListOf<Paragraph>()
        val parser = createParser(xml)

        var eventType = parser.eventType
        var currentParagraph: ParagraphBuilder? = null
        var currentRun: RunBuilder? = null
        var inBody = false

        // Rough progress tracking
        val totalLength = xml.length.toFloat()
        var processedChars = 0

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    when {
                        name == "body" -> inBody = true
                        name == "p" && inBody -> {
                            currentParagraph = ParagraphBuilder()
                        }
                        name == "pPr" && currentParagraph != null -> {
                            parseParagraphProperties(parser, currentParagraph, styleMap)
                        }
                        name == "r" && currentParagraph != null -> {
                            currentRun = RunBuilder()
                        }
                        name == "rPr" && currentRun != null -> {
                            parseRunProperties(parser, currentRun, styleMap)
                        }
                        name == "t" && currentRun != null -> {
                            // Next text event will contain the content
                        }
                        name == "br" && currentRun != null -> {
                            // Handle line break
                            val brType = parser.getAttributeValue(NS_WORD, "type")
                            if (brType == "page") {
                                // Page break - handled at paragraph level
                                currentParagraph?.hasPageBreak = true
                            } else {
                                // Line break within paragraph
                                currentRun.text.append("\n")
                            }
                        }
                        name == "tab" && currentRun != null -> {
                            currentRun.text.append("\t")
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    if (currentRun != null && parser.text.isNotEmpty()) {
                        currentRun.text.append(parser.text)
                    }
                }

                XmlPullParser.END_TAG -> {
                    val name = parser.name
                    when {
                        name == "body" -> inBody = false
                        name == "r" && currentRun != null && currentParagraph != null -> {
                            // Finalize run
                            if (currentRun.text.isNotEmpty()) {
                                currentParagraph.runs.add(currentRun.build())
                            }
                            currentRun = null
                        }
                        name == "p" && currentParagraph != null -> {
                            // Finalize paragraph
                            paragraphs.add(currentParagraph.build())
                            currentParagraph = null

                            // Update progress
                            processedChars = (parser as? LocationAwareParser)?.location ?: processedChars
                            onProgress(minOf(processedChars / totalLength, 1f))
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        onProgress(1f)
        return paragraphs
    }

    /**
     * Parse paragraph properties (<w:pPr>).
     */
    private fun parseParagraphProperties(
        parser: XmlPullParser,
        builder: ParagraphBuilder,
        styleMap: Map<String, StyleDefinition>
    ) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name) {
                        "pStyle" -> {
                            val styleId = parser.getAttributeValue(NS_WORD, "val")
                            styleMap[styleId]?.let { style ->
                                builder.applyStyle(style)
                            }
                        }
                        "jc" -> {
                            val alignment = parser.getAttributeValue(NS_WORD, "val")
                            builder.alignment = ParagraphAlignment.fromDocxValue(alignment)
                        }
                        "spacing" -> {
                            parser.getAttributeValue(NS_WORD, "before")?.toIntOrNull()?.let {
                                builder.spacingBefore = twipsToPoints(it)
                            }
                            parser.getAttributeValue(NS_WORD, "after")?.toIntOrNull()?.let {
                                builder.spacingAfter = twipsToPoints(it)
                            }
                            parser.getAttributeValue(NS_WORD, "line")?.toIntOrNull()?.let { line ->
                                val lineRule = parser.getAttributeValue(NS_WORD, "lineRule")
                                builder.lineSpacing = parseLineSpacing(line, lineRule)
                            }
                        }
                        "ind" -> {
                            val firstLine = parser.getAttributeValue(NS_WORD, "firstLine")?.toIntOrNull() ?: 0
                            val hanging = parser.getAttributeValue(NS_WORD, "hanging")?.toIntOrNull() ?: 0
                            val left = parser.getAttributeValue(NS_WORD, "left")?.toIntOrNull() ?: 0
                            val right = parser.getAttributeValue(NS_WORD, "right")?.toIntOrNull() ?: 0

                            builder.indentation = Indentation(
                                firstLine = if (hanging > 0) -twipsToPoints(hanging) else twipsToPoints(firstLine),
                                left = twipsToPoints(left),
                                right = twipsToPoints(right)
                            )
                        }
                    }
                }
                XmlPullParser.END_TAG -> depth--
            }
        }
    }

    /**
     * Parse run properties (<w:rPr>).
     */
    private fun parseRunProperties(
        parser: XmlPullParser,
        builder: RunBuilder,
        styleMap: Map<String, StyleDefinition>
    ) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name) {
                        "rStyle" -> {
                            val styleId = parser.getAttributeValue(NS_WORD, "val")
                            styleMap[styleId]?.runStyle?.let { style ->
                                builder.style = builder.style.mergeWith(style)
                            }
                        }
                        "b" -> {
                            // Bold - check for explicit off
                            val value = parser.getAttributeValue(NS_WORD, "val")
                            if (value != "0" && value != "false") {
                                builder.style = builder.style.copy(fontWeight = FontWeight.BOLD)
                            }
                        }
                        "i" -> {
                            val value = parser.getAttributeValue(NS_WORD, "val")
                            if (value != "0" && value != "false") {
                                builder.style = builder.style.copy(isItalic = true)
                            }
                        }
                        "u" -> {
                            val value = parser.getAttributeValue(NS_WORD, "val")
                            if (value != "none") {
                                builder.style = builder.style.copy(isUnderline = true)
                            }
                        }
                        "strike" -> {
                            val value = parser.getAttributeValue(NS_WORD, "val")
                            if (value != "0" && value != "false") {
                                builder.style = builder.style.copy(isStrikethrough = true)
                            }
                        }
                        "sz" -> {
                            // Font size in half-points
                            parser.getAttributeValue(NS_WORD, "val")?.toFloatOrNull()?.let { halfPoints ->
                                builder.style = builder.style.copy(fontSize = halfPoints / 2f)
                            }
                        }
                        "rFonts" -> {
                            val fontName = parser.getAttributeValue(NS_WORD, "ascii")
                                ?: parser.getAttributeValue(NS_WORD, "hAnsi")
                                ?: parser.getAttributeValue(NS_WORD, "cs")
                            fontName?.let {
                                builder.style = builder.style.copy(fontFamily = FontFamily.fromName(it))
                            }
                        }
                        "color" -> {
                            val colorVal = parser.getAttributeValue(NS_WORD, "val")
                            if (colorVal != null && colorVal != "auto") {
                                builder.style = builder.style.copy(textColor = TextColor.fromHex(colorVal))
                            }
                        }
                        "vertAlign" -> {
                            when (parser.getAttributeValue(NS_WORD, "val")) {
                                "subscript" -> builder.style = builder.style.copy(isSubscript = true)
                                "superscript" -> builder.style = builder.style.copy(isSuperscript = true)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> depth--
            }
        }
    }

    /**
     * Parse styles.xml to build style map.
     */
    fun parseStyles(xml: String): Map<String, StyleDefinition> {
        val styles = mutableMapOf<String, StyleDefinition>()
        val parser = createParser(xml)

        var eventType = parser.eventType
        var currentStyle: StyleDefinitionBuilder? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "style" -> {
                            val styleId = parser.getAttributeValue(NS_WORD, "styleId")
                            val type = parser.getAttributeValue(NS_WORD, "type")
                            if (styleId != null) {
                                currentStyle = StyleDefinitionBuilder(styleId, type ?: "paragraph")
                            }
                        }
                        "basedOn" -> {
                            currentStyle?.basedOn = parser.getAttributeValue(NS_WORD, "val")
                        }
                        "name" -> {
                            currentStyle?.name = parser.getAttributeValue(NS_WORD, "val")
                        }
                        // Parse run properties within style
                        "b" -> currentStyle?.let { style ->
                            val value = parser.getAttributeValue(NS_WORD, "val")
                            if (value != "0" && value != "false") {
                                style.runStyle = style.runStyle.copy(fontWeight = FontWeight.BOLD)
                            }
                        }
                        "i" -> currentStyle?.let { style ->
                            val value = parser.getAttributeValue(NS_WORD, "val")
                            if (value != "0" && value != "false") {
                                style.runStyle = style.runStyle.copy(isItalic = true)
                            }
                        }
                        "sz" -> currentStyle?.let { style ->
                            parser.getAttributeValue(NS_WORD, "val")?.toFloatOrNull()?.let { halfPoints ->
                                style.runStyle = style.runStyle.copy(fontSize = halfPoints / 2f)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "style") {
                        currentStyle?.let { style ->
                            styles[style.styleId] = style.build()
                        }
                        currentStyle = null
                    }
                }
            }
            eventType = parser.next()
        }

        // Resolve inheritance
        return resolveStyleInheritance(styles)
    }

    /**
     * Resolve style inheritance chains.
     */
    private fun resolveStyleInheritance(
        styles: Map<String, StyleDefinition>
    ): Map<String, StyleDefinition> {
        val resolved = mutableMapOf<String, StyleDefinition>()

        for ((id, style) in styles) {
            resolved[id] = resolveStyle(style, styles, mutableSetOf())
        }

        return resolved
    }

    private fun resolveStyle(
        style: StyleDefinition,
        allStyles: Map<String, StyleDefinition>,
        visited: MutableSet<String>
    ): StyleDefinition {
        if (style.styleId in visited) return style  // Circular reference protection
        visited.add(style.styleId)

        val baseStyle = style.basedOn?.let { allStyles[it] }
        return if (baseStyle != null) {
            val resolvedBase = resolveStyle(baseStyle, allStyles, visited)
            style.copy(
                runStyle = resolvedBase.runStyle.mergeWith(style.runStyle)
            )
        } else {
            style
        }
    }

    /**
     * Parse metadata from core.xml (Dublin Core).
     */
    fun parseMetadata(xml: String): DocumentMetadata {
        val parser = createParser(xml)
        var title: String? = null
        var author: String? = null
        var created: Long? = null
        var modified: Long? = null

        var eventType = parser.eventType
        var currentElement: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentElement = parser.name
                }
                XmlPullParser.TEXT -> {
                    when (currentElement) {
                        "title" -> title = parser.text
                        "creator" -> author = parser.text
                        "created" -> created = parseDateTime(parser.text)
                        "modified" -> modified = parseDateTime(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> currentElement = null
            }
            eventType = parser.next()
        }

        return DocumentMetadata.default().copy(
            title = title,
            author = author,
            createdAt = created,
            modifiedAt = modified
        )
    }

    /**
     * Parse page settings from sectPr in document.xml.
     */
    fun parsePageSettings(xml: String): PageSettings? {
        val parser = createParser(xml)
        var eventType = parser.eventType
        var inSectPr = false

        var pageWidth: Float? = null
        var pageHeight: Float? = null
        var marginTop: Float? = null
        var marginBottom: Float? = null
        var marginLeft: Float? = null
        var marginRight: Float? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "sectPr" -> inSectPr = true
                        "pgSz" -> if (inSectPr) {
                            parser.getAttributeValue(NS_WORD, "w")?.toIntOrNull()?.let {
                                pageWidth = twipsToPoints(it)
                            }
                            parser.getAttributeValue(NS_WORD, "h")?.toIntOrNull()?.let {
                                pageHeight = twipsToPoints(it)
                            }
                        }
                        "pgMar" -> if (inSectPr) {
                            parser.getAttributeValue(NS_WORD, "top")?.toIntOrNull()?.let {
                                marginTop = twipsToPoints(it)
                            }
                            parser.getAttributeValue(NS_WORD, "bottom")?.toIntOrNull()?.let {
                                marginBottom = twipsToPoints(it)
                            }
                            parser.getAttributeValue(NS_WORD, "left")?.toIntOrNull()?.let {
                                marginLeft = twipsToPoints(it)
                            }
                            parser.getAttributeValue(NS_WORD, "right")?.toIntOrNull()?.let {
                                marginRight = twipsToPoints(it)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "sectPr") inSectPr = false
                }
            }
            eventType = parser.next()
        }

        return if (pageWidth != null || pageHeight != null) {
            PageSettings(
                pageWidth = pageWidth ?: 612f,
                pageHeight = pageHeight ?: 792f,
                marginTop = marginTop ?: 72f,
                marginBottom = marginBottom ?: 72f,
                marginLeft = marginLeft ?: 72f,
                marginRight = marginRight ?: 72f
            )
        } else null
    }

    // Helper functions

    private fun createParser(xml: String): XmlPullParser {
        return parserFactory.newPullParser().apply {
            setInput(StringReader(xml))
        }
    }

    private fun twipsToPoints(twips: Int): Float {
        // 1 point = 20 twips
        return twips / 20f
    }

    private fun parseLineSpacing(value: Int, rule: String?): LineSpacing {
        return when (rule) {
            "exact" -> LineSpacing.exact(twipsToPoints(value))
            "atLeast" -> LineSpacing.atLeast(twipsToPoints(value))
            else -> {
                // Value is in 240ths of a line
                val multiplier = value / 240f
                when {
                    multiplier <= 1.1f -> LineSpacing.single()
                    multiplier <= 1.6f -> LineSpacing.onePointFive()
                    multiplier <= 2.1f -> LineSpacing.double()
                    else -> LineSpacing.multiple(multiplier)
                }
            }
        }
    }

    private fun parseDateTime(value: String): Long? {
        return try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val NS_WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    }
}

// Builder classes

private class ParagraphBuilder {
    val runs = mutableListOf<TextRun>()
    var alignment: ParagraphAlignment = ParagraphAlignment.LEFT
    var spacingBefore: Float = 0f
    var spacingAfter: Float = 12f
    var lineSpacing: LineSpacing = LineSpacing.single()
    var indentation: Indentation = Indentation.none()
    var hasPageBreak: Boolean = false

    fun applyStyle(style: StyleDefinition) {
        // Apply paragraph-level style properties
        style.runStyle.let { runStyle ->
            // Default run style would be applied to runs
        }
    }

    fun build(): Paragraph = Paragraph(
        id = UUID.randomUUID().toString(),
        spacingBefore = spacingBefore,
        spacingAfter = spacingAfter,
        runs = runs,
        alignment = alignment,
        lineSpacing = lineSpacing,
        indentation = indentation
    )
}

private class RunBuilder {
    val text = StringBuilder()
    var style: TextStyle = TextStyle.default()

    fun build(): TextRun = TextRun(
        text = text.toString(),
        style = style,
        position = null
    )
}

private class StyleDefinitionBuilder(
    val styleId: String,
    val type: String
) {
    var name: String? = null
    var basedOn: String? = null
    var runStyle: TextStyle = TextStyle.default()

    fun build(): StyleDefinition = StyleDefinition(
        styleId = styleId,
        type = type,
        name = name,
        basedOn = basedOn,
        runStyle = runStyle
    )
}

/**
 * Represents a parsed style definition.
 */
data class StyleDefinition(
    val styleId: String,
    val type: String,
    val name: String?,
    val basedOn: String?,
    val runStyle: TextStyle
)

/**
 * Page settings parsed from sectPr.
 */
data class PageSettings(
    val pageWidth: Float,
    val pageHeight: Float,
    val marginTop: Float,
    val marginBottom: Float,
    val marginLeft: Float,
    val marginRight: Float
)

/**
 * Interface for tracking parser location (for progress).
 */
private interface LocationAwareParser {
    val location: Int
}
