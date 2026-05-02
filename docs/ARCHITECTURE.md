# Multi-Format Document Converter — Architecture Specification

**Version:** 1.0  
**Phase:** Design & Architecture  
**Platform:** Android (Kotlin)  
**Scope:** DOCX ↔ PDF Conversion (Phase 1)

---

## Table of Contents

1. [Architectural Philosophy](#1-architectural-philosophy)
2. [System Architecture Overview](#2-system-architecture-overview)
3. [Internal Document Model (IDM)](#3-internal-document-model-idm)
4. [Module Specifications](#4-module-specifications)
5. [Conversion Pipelines](#5-conversion-pipelines)
6. [Memory & Threading Strategy](#6-memory--threading-strategy)
7. [Error Handling Architecture](#7-error-handling-architecture)
8. [Limitations & Tradeoffs](#8-limitations--tradeoffs)
9. [Implementation Phases](#9-implementation-phases)

---

## 1. Architectural Philosophy

### 1.1 Core Principle: Format Isolation via Intermediate Representation

The system **never** performs direct format-to-format translation. Every conversion follows a strict two-phase pipeline:

```
┌─────────────┐      ┌─────────────────────┐      ┌──────────────┐
│ SOURCE FILE │ ───► │ INTERNAL DOC MODEL  │ ───► │ TARGET FILE  │
│   (DOCX)    │      │       (IDM)         │      │    (PDF)     │
└─────────────┘      └─────────────────────┘      └──────────────┘
      │                       │                         │
   PARSER                  CORE                     RENDERER
```

**Rationale:**
- Parsers and renderers are **decoupled** — adding a new format requires only one new module
- The IDM serves as a **canonical truth** — all formats are normalized to a common structure
- Testing and debugging become tractable — you can inspect the intermediate state
- Semantic information is **preserved** across transformations where possible

### 1.2 Design Constraints (Non-Negotiable)

| Constraint | Implication |
|------------|-------------|
| Offline-only | No network calls, no cloud dependencies |
| No external converters | Must implement parsing/rendering from scratch |
| Android-only | Limited to Android SDK APIs (PdfDocument, Canvas, ZipInputStream) |
| Memory-safe | Streaming-first design; never load full file into heap |
| English documents only | No RTL, no complex scripts, no font fallback chains |

### 1.3 Accuracy Philosophy

This engine targets **structural correctness over visual fidelity**.

- A paragraph in DOCX should become a paragraph in the IDM
- A bold word should remain bold through conversion
- **Visual pixel-perfection is NOT a goal**

Expected accuracy for simple documents: **85-95%**  
Expected accuracy for complex documents: **<50% (out of scope)**

---

## 2. System Architecture Overview

### 2.1 High-Level Module Diagram

```
┌────────────────────────────────────────────────────────────────────────────┐
│                           ANDROID APPLICATION                               │
├────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                      CONVERSION ENGINE (Core Library)                 │  │
│  │                                                                       │  │
│  │  ┌─────────────┐    ┌───────────────────┐    ┌─────────────────────┐ │  │
│  │  │   INPUT     │    │    DOCUMENT       │    │      OUTPUT         │ │  │
│  │  │   LAYER     │───►│    PROCESSOR      │───►│      LAYER          │ │  │
│  │  │             │    │                   │    │                     │ │  │
│  │  │ • FileReader│    │ • IDM Builder     │    │ • FileWriter        │ │  │
│  │  │ • MimeDetect│    │ • Layout Engine   │    │ • StreamManager     │ │  │
│  │  └─────────────┘    └───────────────────┘    └─────────────────────┘ │  │
│  │         │                    │                        │              │  │
│  │         ▼                    ▼                        ▼              │  │
│  │  ┌─────────────┐    ┌───────────────────┐    ┌─────────────────────┐ │  │
│  │  │   PARSERS   │    │  INTERNAL DOC     │    │     RENDERERS       │ │  │
│  │  │             │    │     MODEL         │    │                     │ │  │
│  │  │ • DocxParser│───►│                   │───►│ • PdfRenderer       │ │  │
│  │  │ • PdfParser │    │ • Document        │    │ • DocxWriter        │ │  │
│  │  │             │    │ • Page            │    │                     │ │  │
│  │  └─────────────┘    │ • Paragraph       │    └─────────────────────┘ │  │
│  │                     │ • TextRun         │                            │  │
│  │                     │ • TextStyle       │                            │  │
│  │                     └───────────────────┘                            │  │
│  │                                                                       │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                      INFRASTRUCTURE LAYER                             │  │
│  │                                                                       │  │
│  │  • CoroutineManager (background execution)                           │  │
│  │  • ProgressReporter (callback-based progress)                        │  │
│  │  • ErrorHandler (structured error types)                             │  │
│  │  • TempFileManager (disk-backed intermediate storage)                │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Package Structure

```
com.converter/
├── core/
│   ├── model/                  # Internal Document Model classes
│   │   ├── Document.kt
│   │   ├── Page.kt
│   │   ├── Paragraph.kt
│   │   ├── TextRun.kt
│   │   └── TextStyle.kt
│   ├── engine/
│   │   ├── ConversionEngine.kt       # Main orchestrator
│   │   ├── ConversionRequest.kt      # Request configuration
│   │   └── ConversionResult.kt       # Result wrapper
│   └── layout/
│       ├── LayoutEngine.kt           # Pagination & line wrapping
│       └── TextMeasurer.kt           # Font metrics provider
│
├── parser/
│   ├── Parser.kt                     # Parser interface
│   ├── docx/
│   │   ├── DocxParser.kt             # DOCX → IDM
│   │   ├── DocxZipReader.kt          # ZIP stream handler
│   │   └── DocxXmlHandler.kt         # XML event parser
│   └── pdf/
│       ├── PdfParser.kt              # PDF → IDM
│       ├── PdfStreamReader.kt        # PDF object stream handler
│       └── TextPositionCollector.kt  # Coordinate-based text extraction
│
├── renderer/
│   ├── Renderer.kt                   # Renderer interface
│   ├── pdf/
│   │   ├── PdfRenderer.kt            # IDM → PDF
│   │   ├── CanvasPainter.kt          # Android Canvas wrapper
│   │   └── PageLayoutCalculator.kt   # Margin & pagination
│   └── docx/
│       ├── DocxWriter.kt             # IDM → DOCX
│       ├── DocxXmlBuilder.kt         # XML construction
│       └── DocxZipPackager.kt        # ZIP assembly
│
├── infrastructure/
│   ├── CoroutineManager.kt           # Dispatcher management
│   ├── ProgressReporter.kt           # Progress callbacks
│   ├── TempFileManager.kt            # Temp file lifecycle
│   └── error/
│       ├── ConversionError.kt        # Sealed error hierarchy
│       └── ErrorRecovery.kt          # Recovery strategies
│
└── util/
    ├── StreamUtils.kt                # Buffered stream helpers
    └── XmlUtils.kt                   # XML parsing utilities
```

---

## 3. Internal Document Model (IDM)

The IDM is the **canonical representation** of any document within the system. It is designed to be:

- **Format-agnostic** — no DOCX or PDF-specific concepts leak into the model
- **Streamable** — can be constructed incrementally without full document in memory
- **Serializable** — can be persisted to disk for debugging or checkpointing
- **Immutable** — once constructed, a document node should not change

### 3.1 Core Classes

#### 3.1.1 Document (Root Container)

```kotlin
/**
 * Root container representing an entire document.
 * 
 * A Document is a sequence of Pages. In flow-based formats like DOCX,
 * page boundaries are computed during layout. In fixed formats like PDF,
 * page boundaries are explicit.
 */
data class Document(
    val metadata: DocumentMetadata,
    val pages: List<Page>,
    val sourceFormat: SourceFormat,
    val layoutState: LayoutState
)

data class DocumentMetadata(
    val title: String?,
    val author: String?,
    val createdAt: Long?,
    val modifiedAt: Long?,
    val pageWidth: Float,      // Points (1/72 inch)
    val pageHeight: Float,     // Points
    val marginTop: Float,
    val marginBottom: Float,
    val marginLeft: Float,
    val marginRight: Float
)

enum class SourceFormat {
    DOCX,
    PDF,
    UNKNOWN
}

enum class LayoutState {
    UNPAGINATED,    // Raw content, no page breaks computed
    PAGINATED       // Page breaks have been calculated
}
```

#### 3.1.2 Page

```kotlin
/**
 * Represents a single page in the document.
 * 
 * Pages contain an ordered list of block-level elements.
 * For Phase 1, blocks are limited to Paragraphs.
 */
data class Page(
    val pageNumber: Int,            // 1-indexed
    val blocks: List<Block>,
    val pageHeight: Float,          // Actual content height (may vary)
    val explicitBreak: Boolean      // True if page break was explicit in source
)

/**
 * Sealed interface for block-level elements.
 * Extensible for future support (tables, images, etc.)
 */
sealed interface Block {
    val id: String                  // Unique identifier for debugging
    val spacingBefore: Float        // Points
    val spacingAfter: Float         // Points
}
```

#### 3.1.3 Paragraph

```kotlin
/**
 * A paragraph is a block containing inline content (TextRuns).
 * 
 * Paragraphs are the primary unit of text flow. Line breaking
 * within a paragraph is handled by the Layout Engine.
 */
data class Paragraph(
    override val id: String,
    override val spacingBefore: Float,
    override val spacingAfter: Float,
    val runs: List<TextRun>,
    val alignment: ParagraphAlignment,
    val lineSpacing: LineSpacing,
    val indentation: Indentation
) : Block

enum class ParagraphAlignment {
    LEFT,
    CENTER,
    RIGHT,
    JUSTIFY
}

data class LineSpacing(
    val type: LineSpacingType,
    val value: Float                // Multiplier or absolute points
)

enum class LineSpacingType {
    SINGLE,         // 1.0x line height
    ONE_POINT_FIVE, // 1.5x
    DOUBLE,         // 2.0x
    EXACT,          // Absolute points
    AT_LEAST        // Minimum points
}

data class Indentation(
    val firstLine: Float,           // Points (can be negative for hanging)
    val left: Float,
    val right: Float
)
```

#### 3.1.4 TextRun

```kotlin
/**
 * A TextRun is a contiguous sequence of characters with uniform styling.
 * 
 * This is the atomic unit of styled text. A paragraph like:
 *   "Hello WORLD everyone"
 * where "WORLD" is bold would be represented as three TextRuns.
 */
data class TextRun(
    val text: String,
    val style: TextStyle,
    val position: TextPosition?     // Non-null only for PDF-sourced content
)

/**
 * For PDF-sourced documents, we preserve original coordinates.
 * This enables reconstruction heuristics.
 */
data class TextPosition(
    val x: Float,                   // Points from left edge
    val y: Float,                   // Points from top edge
    val width: Float,               // Measured width of this run
    val height: Float,              // Line height at this position
    val pageIndex: Int              // 0-indexed page reference
)
```

#### 3.1.5 TextStyle

```kotlin
/**
 * Captures visual styling of text.
 * 
 * Limited to properties that can be:
 * 1. Extracted from both DOCX and PDF
 * 2. Rendered on Android Canvas
 * 3. Written back to both formats
 */
data class TextStyle(
    val fontFamily: FontFamily,
    val fontSize: Float,            // Points
    val fontWeight: FontWeight,
    val isItalic: Boolean,
    val isUnderline: Boolean,
    val isStrikethrough: Boolean,
    val textColor: TextColor,
    val backgroundColor: TextColor?
)

/**
 * Normalized font family representation.
 * Maps source fonts to available Android system fonts.
 */
enum class FontFamily(val androidTypeface: String) {
    SERIF("serif"),
    SANS_SERIF("sans-serif"),
    MONOSPACE("monospace");
    
    companion object {
        fun fromName(name: String): FontFamily {
            return when {
                name.contains("times", ignoreCase = true) -> SERIF
                name.contains("arial", ignoreCase = true) -> SANS_SERIF
                name.contains("helvetica", ignoreCase = true) -> SANS_SERIF
                name.contains("courier", ignoreCase = true) -> MONOSPACE
                name.contains("mono", ignoreCase = true) -> MONOSPACE
                else -> SANS_SERIF  // Safe default
            }
        }
    }
}

enum class FontWeight(val androidWeight: Int) {
    NORMAL(400),
    BOLD(700);
    
    companion object {
        fun fromNumeric(weight: Int): FontWeight {
            return if (weight >= 600) BOLD else NORMAL
        }
    }
}

data class TextColor(
    val red: Int,       // 0-255
    val green: Int,
    val blue: Int,
    val alpha: Int = 255
) {
    fun toArgb(): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    
    companion object {
        val BLACK = TextColor(0, 0, 0)
        val WHITE = TextColor(255, 255, 255)
    }
}
```

### 3.2 IDM Design Principles

| Principle | Implementation |
|-----------|----------------|
| **Normalization** | All measurements are in Points (1/72 inch). Parsers convert from source units. |
| **Lossiness is explicit** | When a source feature cannot be represented, a warning is logged but parsing continues. |
| **Position preservation** | PDF-sourced text retains coordinates for reconstruction. DOCX-sourced text has null positions. |
| **Font normalization** | All fonts map to one of three Android system font families. |
| **No rendering hints** | The IDM contains semantic structure, not rendering instructions. |

### 3.3 IDM Lifecycle

```
DOCX File                              PDF File
    │                                      │
    ▼                                      ▼
┌─────────────┐                    ┌─────────────┐
│ DocxParser  │                    │  PdfParser  │
└─────────────┘                    └─────────────┘
    │                                      │
    │ Produces UNPAGINATED                 │ Produces PAGINATED
    │ Document (logical flow)              │ Document (visual layout)
    ▼                                      ▼
┌───────────────────────────────────────────────────┐
│            INTERNAL DOCUMENT MODEL                 │
│                                                   │
│  ┌─────────────────────────────────────────────┐  │
│  │  Document(layoutState = UNPAGINATED)        │  │
│  │    └─► Pages: [Single logical page]         │  │
│  │          └─► Paragraphs flow continuously   │  │
│  └─────────────────────────────────────────────┘  │
│                        │                          │
│                        ▼ Layout Engine            │
│  ┌─────────────────────────────────────────────┐  │
│  │  Document(layoutState = PAGINATED)          │  │
│  │    └─► Pages: [Page1, Page2, Page3...]      │  │
│  │          └─► Content split at boundaries    │  │
│  └─────────────────────────────────────────────┘  │
│                                                   │
└───────────────────────────────────────────────────┘
    │                                      │
    ▼                                      ▼
┌─────────────┐                    ┌─────────────┐
│ DocxWriter  │                    │ PdfRenderer │
└─────────────┘                    └─────────────┘
    │                                      │
    ▼                                      ▼
DOCX File                              PDF File
```

---

## 4. Module Specifications

### 4.1 Parser Interface

```kotlin
/**
 * Contract for all format parsers.
 * 
 * Parsers are responsible for:
 * 1. Reading source bytes (streaming)
 * 2. Extracting structural information
 * 3. Producing an IDM Document
 * 4. Reporting progress and errors
 */
interface Parser {
    /**
     * Parse input stream into Internal Document Model.
     * 
     * @param input Source byte stream (caller manages lifecycle)
     * @param config Parser-specific configuration
     * @param progress Callback for progress updates (0.0 to 1.0)
     * @return Parsed document or error
     */
    suspend fun parse(
        input: InputStream,
        config: ParserConfig,
        progress: (Float) -> Unit
    ): Result<Document>
    
    /**
     * Supported source format for this parser.
     */
    val supportedFormat: SourceFormat
    
    /**
     * Estimated memory overhead per page (bytes).
     * Used by engine to decide streaming strategy.
     */
    val estimatedMemoryPerPage: Long
}

data class ParserConfig(
    val maxPages: Int = Int.MAX_VALUE,
    val extractMetadata: Boolean = true,
    val strictMode: Boolean = false     // Fail on warnings if true
)
```

### 4.2 DOCX Parser — Technical Design

#### 4.2.1 DOCX Structure Overview

A DOCX file is a ZIP archive with this structure:

```
document.docx (ZIP)
├── [Content_Types].xml         # MIME type declarations
├── _rels/
│   └── .rels                   # Root relationships
├── word/
│   ├── document.xml            # ★ Main document content
│   ├── styles.xml              # Style definitions
│   ├── settings.xml            # Document settings
│   ├── fontTable.xml           # Font declarations
│   └── _rels/
│       └── document.xml.rels   # Document relationships
└── docProps/
    ├── core.xml                # Dublin Core metadata
    └── app.xml                 # Application metadata
```

**Key parsing targets for Phase 1:**
- `word/document.xml` — Primary content
- `word/styles.xml` — Style inheritance resolution
- `docProps/core.xml` — Metadata (title, author)

#### 4.2.2 Parsing Strategy

```kotlin
class DocxParser : Parser {
    
    override suspend fun parse(
        input: InputStream,
        config: ParserConfig,
        progress: (Float) -> Unit
    ): Result<Document> = runCatching {
        
        // Phase 1: Stream ZIP entries, extract relevant parts
        val zipReader = DocxZipReader(input)
        val documentXml = zipReader.extractEntry("word/document.xml")
            ?: throw ParseError.MissingRequiredEntry("word/document.xml")
        val stylesXml = zipReader.extractEntry("word/styles.xml")
        val coreXml = zipReader.extractEntry("docProps/core.xml")
        
        // Phase 2: Parse styles first (needed for inheritance)
        val styleMap = stylesXml?.let { parseStyles(it) } ?: emptyMap()
        
        // Phase 3: Parse document body with style resolution
        val paragraphs = parseDocumentXml(documentXml, styleMap, progress)
        
        // Phase 4: Parse metadata
        val metadata = coreXml?.let { parseMetadata(it) } ?: defaultMetadata()
        
        // Produce UNPAGINATED document
        Document(
            metadata = metadata,
            pages = listOf(Page(
                pageNumber = 1,
                blocks = paragraphs,
                pageHeight = 0f,    // Not yet calculated
                explicitBreak = false
            )),
            sourceFormat = SourceFormat.DOCX,
            layoutState = LayoutState.UNPAGINATED
        )
    }
}
```

#### 4.2.3 XML Element Mapping

| DOCX XML Element | IDM Equivalent | Notes |
|------------------|----------------|-------|
| `<w:body>` | Document.pages[0].blocks | Container for all content |
| `<w:p>` | Paragraph | Block-level element |
| `<w:pPr>` | Paragraph properties | Spacing, alignment, indentation |
| `<w:r>` | TextRun | Inline content container |
| `<w:rPr>` | TextStyle | Font, size, bold, italic |
| `<w:t>` | TextRun.text | Actual text content |
| `<w:br/>` | Line break | Inline break within paragraph |
| `<w:sectPr>` | Page dimensions | Margins, orientation |

#### 4.2.4 Style Inheritance Resolution

DOCX styles have inheritance chains:

```
Character Run
    └─► Run's explicit properties (rPr)
         └─► Paragraph style's run properties
              └─► Linked character style
                   └─► Default paragraph style
                        └─► Document defaults
```

**Implementation approach:**

```kotlin
data class ResolvedStyle(
    val fontFamily: String?,
    val fontSize: Float?,
    val bold: Boolean?,
    val italic: Boolean?,
    val underline: Boolean?
)

fun resolveStyle(
    runProperties: XmlElement?,
    paragraphStyle: String?,
    styleMap: Map<String, StyleDefinition>
): TextStyle {
    // Build inheritance chain
    val chain = mutableListOf<ResolvedStyle>()
    
    // 1. Add run-level explicit properties
    runProperties?.let { chain.add(extractStyleProps(it)) }
    
    // 2. Walk paragraph style inheritance
    var currentStyle = paragraphStyle
    while (currentStyle != null) {
        val def = styleMap[currentStyle]
        def?.runProperties?.let { chain.add(extractStyleProps(it)) }
        currentStyle = def?.basedOn
    }
    
    // 3. Add document defaults
    chain.add(documentDefaults)
    
    // Merge: first non-null value wins
    return mergeStyleChain(chain)
}
```

### 4.3 PDF Parser — Technical Design

#### 4.3.1 PDF Structure Overview

PDF is a **visual format** with no inherent logical structure. Text is positioned absolutely:

```
BT                          % Begin text object
/F1 12 Tf                   % Font F1, size 12
100 700 Td                  % Move to position (100, 700)
(Hello World) Tj            % Draw text
ET                          % End text object
```

**Key challenges:**
- Text order in file ≠ reading order
- Words may be individual characters with spacing
- No paragraph boundaries
- Fonts may be embedded subsets with custom encodings

#### 4.3.2 Android PDF Parsing Limitation

**Critical constraint:** Android SDK does NOT provide PDF text extraction APIs.

`android.graphics.pdf.PdfRenderer` only renders to Bitmap — no text access.

**Solution options for Phase 1:**

| Approach | Pros | Cons |
|----------|------|------|
| **A. Use PdfBox-Android** | Full PDF parsing, text extraction | Large library (~5MB), complex |
| **B. Build minimal PDF parser** | Educational, lightweight | Significant effort, limited support |
| **C. Hybrid approach** | Best of both | More code paths |

**Recommended: Option A (PdfBox-Android)** for Phase 1.

PdfBox-Android is an open-source port of Apache PDFBox. It provides:
- PDF stream parsing
- Text extraction with positions
- Font handling
- No server dependency

```kotlin
// Using PdfBox-Android for text extraction
class PdfParser : Parser {
    
    override suspend fun parse(
        input: InputStream,
        config: ParserConfig,
        progress: (Float) -> Unit
    ): Result<Document> = runCatching {
        
        val pdfDocument = PDDocument.load(input)
        val totalPages = pdfDocument.numberOfPages
        val pages = mutableListOf<Page>()
        
        for (pageIndex in 0 until minOf(totalPages, config.maxPages)) {
            val page = pdfDocument.getPage(pageIndex)
            
            // Extract text with positions
            val stripper = PositionalTextStripper()
            val textPositions = stripper.extractPositions(page)
            
            // Reconstruct paragraphs from positions
            val paragraphs = reconstructParagraphs(textPositions)
            
            pages.add(Page(
                pageNumber = pageIndex + 1,
                blocks = paragraphs,
                pageHeight = page.mediaBox.height,
                explicitBreak = true
            ))
            
            progress((pageIndex + 1).toFloat() / totalPages)
        }
        
        pdfDocument.close()
        
        Document(
            metadata = extractMetadata(pdfDocument),
            pages = pages,
            sourceFormat = SourceFormat.PDF,
            layoutState = LayoutState.PAGINATED
        )
    }
}
```

#### 4.3.3 Paragraph Reconstruction Heuristics

PDF provides **characters at positions**. We must infer structure:

```kotlin
/**
 * Reconstructs logical paragraphs from positioned text.
 * 
 * Heuristics:
 * 1. Sort text by Y position (top to bottom), then X (left to right)
 * 2. Group characters with similar Y into lines
 * 3. Group lines with small vertical gaps into paragraphs
 * 4. Large gaps indicate paragraph breaks
 */
class ParagraphReconstructor {
    
    // Threshold: Y positions within this range are "same line"
    private val lineTolerancePoints = 2.0f
    
    // Threshold: vertical gap larger than this is a paragraph break
    private val paragraphGapThreshold = 18.0f  // ~1.5x typical line height
    
    fun reconstruct(positions: List<TextPosition>): List<Paragraph> {
        // Step 1: Sort by position
        val sorted = positions.sortedWith(
            compareBy({ it.pageIndex }, { -it.y }, { it.x })
        )
        
        // Step 2: Group into lines
        val lines = groupIntoLines(sorted)
        
        // Step 3: Group lines into paragraphs
        return groupIntoParagraphs(lines)
    }
    
    private fun groupIntoLines(positions: List<TextPosition>): List<Line> {
        val lines = mutableListOf<Line>()
        var currentLine = mutableListOf<TextPosition>()
        var currentY: Float? = null
        
        for (pos in positions) {
            if (currentY == null || abs(pos.y - currentY) < lineTolerancePoints) {
                currentLine.add(pos)
                currentY = pos.y
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(Line(currentLine.toList()))
                }
                currentLine = mutableListOf(pos)
                currentY = pos.y
            }
        }
        
        if (currentLine.isNotEmpty()) {
            lines.add(Line(currentLine.toList()))
        }
        
        return lines
    }
    
    private fun groupIntoParagraphs(lines: List<Line>): List<Paragraph> {
        val paragraphs = mutableListOf<Paragraph>()
        var currentRuns = mutableListOf<TextRun>()
        var prevLineBottom: Float? = null
        
        for (line in lines) {
            val lineTop = line.positions.maxOf { it.y }
            
            if (prevLineBottom != null) {
                val gap = prevLineBottom - lineTop
                if (gap > paragraphGapThreshold) {
                    // Paragraph break
                    if (currentRuns.isNotEmpty()) {
                        paragraphs.add(createParagraph(currentRuns))
                        currentRuns = mutableListOf()
                    }
                }
            }
            
            currentRuns.addAll(line.toTextRuns())
            prevLineBottom = line.positions.minOf { it.y - it.height }
        }
        
        if (currentRuns.isNotEmpty()) {
            paragraphs.add(createParagraph(currentRuns))
        }
        
        return paragraphs
    }
}
```

### 4.4 Renderer Interface

```kotlin
/**
 * Contract for all format renderers.
 * 
 * Renderers are responsible for:
 * 1. Receiving a PAGINATED Document
 * 2. Generating output bytes (streaming)
 * 3. Mapping IDM concepts to target format
 * 4. Reporting progress
 */
interface Renderer {
    /**
     * Render document to output stream.
     * 
     * @param document Must have layoutState = PAGINATED
     * @param output Target stream (caller manages lifecycle)
     * @param config Renderer-specific configuration
     * @param progress Callback for progress updates
     */
    suspend fun render(
        document: Document,
        output: OutputStream,
        config: RendererConfig,
        progress: (Float) -> Unit
    ): Result<Unit>
    
    val targetFormat: SourceFormat
}

data class RendererConfig(
    val quality: RenderQuality = RenderQuality.STANDARD,
    val embedFonts: Boolean = false
)

enum class RenderQuality {
    DRAFT,      // Faster, lower fidelity
    STANDARD,   // Balanced
    HIGH        // Slower, better typography
}
```

### 4.5 PDF Renderer — Technical Design

Uses Android's `PdfDocument` API to render pages:

```kotlin
class PdfRenderer : Renderer {
    
    override suspend fun render(
        document: Document,
        output: OutputStream,
        config: RendererConfig,
        progress: (Float) -> Unit
    ): Result<Unit> = runCatching {
        
        require(document.layoutState == LayoutState.PAGINATED) {
            "Document must be paginated before PDF rendering"
        }
        
        val pdfDocument = PdfDocument()
        
        for ((index, page) in document.pages.withIndex()) {
            val pageInfo = PdfDocument.PageInfo.Builder(
                document.metadata.pageWidth.toInt(),
                document.metadata.pageHeight.toInt(),
                index + 1
            ).create()
            
            val pdfPage = pdfDocument.startPage(pageInfo)
            val canvas = pdfPage.canvas
            
            renderPage(canvas, page, document.metadata)
            
            pdfDocument.finishPage(pdfPage)
            progress((index + 1).toFloat() / document.pages.size)
        }
        
        pdfDocument.writeTo(output)
        pdfDocument.close()
    }
    
    private fun renderPage(canvas: Canvas, page: Page, meta: DocumentMetadata) {
        var yPosition = meta.marginTop
        
        for (block in page.blocks) {
            when (block) {
                is Paragraph -> {
                    yPosition += block.spacingBefore
                    yPosition = renderParagraph(canvas, block, yPosition, meta)
                    yPosition += block.spacingAfter
                }
            }
        }
    }
    
    private fun renderParagraph(
        canvas: Canvas,
        paragraph: Paragraph,
        startY: Float,
        meta: DocumentMetadata
    ): Float {
        val paint = Paint().apply {
            isAntiAlias = true
        }
        
        var x = meta.marginLeft + paragraph.indentation.firstLine
        var y = startY
        val maxWidth = meta.pageWidth - meta.marginLeft - meta.marginRight
        
        for (run in paragraph.runs) {
            paint.applyStyle(run.style)
            
            val words = run.text.split(" ")
            for ((wordIndex, word) in words.withIndex()) {
                val wordWidth = paint.measureText(word)
                val spaceWidth = paint.measureText(" ")
                
                // Line wrap check
                if (x + wordWidth > meta.marginLeft + maxWidth) {
                    x = meta.marginLeft + paragraph.indentation.left
                    y += paint.fontSpacing
                }
                
                canvas.drawText(word, x, y, paint)
                x += wordWidth
                
                // Add space after word (except last)
                if (wordIndex < words.lastIndex) {
                    x += spaceWidth
                }
            }
        }
        
        return y + paint.fontSpacing
    }
}

private fun Paint.applyStyle(style: TextStyle) {
    textSize = style.fontSize
    typeface = Typeface.create(
        style.fontFamily.androidTypeface,
        when {
            style.fontWeight == FontWeight.BOLD && style.isItalic -> Typeface.BOLD_ITALIC
            style.fontWeight == FontWeight.BOLD -> Typeface.BOLD
            style.isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
    )
    color = style.textColor.toArgb()
    isUnderlineText = style.isUnderline
    isStrikeThruText = style.isStrikethrough
}
```

### 4.6 DOCX Writer — Technical Design

Generates DOCX by constructing XML and packaging into ZIP:

```kotlin
class DocxWriter : Renderer {
    
    override suspend fun render(
        document: Document,
        output: OutputStream,
        config: RendererConfig,
        progress: (Float) -> Unit
    ): Result<Unit> = runCatching {
        
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            // 1. Write [Content_Types].xml
            writeContentTypes(zip)
            progress(0.1f)
            
            // 2. Write _rels/.rels
            writeRootRels(zip)
            progress(0.2f)
            
            // 3. Write word/_rels/document.xml.rels
            writeDocumentRels(zip)
            progress(0.3f)
            
            // 4. Write word/styles.xml
            writeStyles(zip)
            progress(0.4f)
            
            // 5. Write word/document.xml (main content)
            writeDocumentXml(zip, document, progress)
            
            // 6. Write docProps/core.xml
            writeCoreProperties(zip, document.metadata)
            progress(1.0f)
        }
    }
    
    private fun writeDocumentXml(
        zip: ZipOutputStream,
        document: Document,
        progress: (Float) -> Unit
    ) {
        zip.putNextEntry(ZipEntry("word/document.xml"))
        
        val writer = zip.bufferedWriter()
        writer.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        writer.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
        writer.write("<w:body>")
        
        val totalBlocks = document.pages.flatMap { it.blocks }.size
        var processedBlocks = 0
        
        for (page in document.pages) {
            for (block in page.blocks) {
                when (block) {
                    is Paragraph -> writeParagraph(writer, block)
                }
                processedBlocks++
                progress(0.4f + 0.5f * (processedBlocks.toFloat() / totalBlocks))
            }
            
            // Add page break between pages (except after last)
            if (page != document.pages.last()) {
                writer.write("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
            }
        }
        
        writer.write("</w:body>")
        writer.write("</w:document>")
        writer.flush()
        
        zip.closeEntry()
    }
    
    private fun writeParagraph(writer: Writer, paragraph: Paragraph) {
        writer.write("<w:p>")
        
        // Paragraph properties
        writer.write("<w:pPr>")
        writeParagraphAlignment(writer, paragraph.alignment)
        writeParagraphSpacing(writer, paragraph)
        writeIndentation(writer, paragraph.indentation)
        writer.write("</w:pPr>")
        
        // Text runs
        for (run in paragraph.runs) {
            writeTextRun(writer, run)
        }
        
        writer.write("</w:p>")
    }
    
    private fun writeTextRun(writer: Writer, run: TextRun) {
        writer.write("<w:r>")
        
        // Run properties
        writer.write("<w:rPr>")
        writeRunStyle(writer, run.style)
        writer.write("</w:rPr>")
        
        // Text content (with XML escaping)
        writer.write("<w:t xml:space=\"preserve\">")
        writer.write(escapeXml(run.text))
        writer.write("</w:t>")
        
        writer.write("</w:r>")
    }
    
    private fun writeRunStyle(writer: Writer, style: TextStyle) {
        // Font
        writer.write("""<w:rFonts w:ascii="${style.fontFamily.docxName}"/>""")
        
        // Size (DOCX uses half-points)
        val halfPoints = (style.fontSize * 2).toInt()
        writer.write("""<w:sz w:val="$halfPoints"/>""")
        
        // Bold
        if (style.fontWeight == FontWeight.BOLD) {
            writer.write("<w:b/>")
        }
        
        // Italic
        if (style.isItalic) {
            writer.write("<w:i/>")
        }
        
        // Underline
        if (style.isUnderline) {
            writer.write("""<w:u w:val="single"/>""")
        }
        
        // Color (if not black)
        if (style.textColor != TextColor.BLACK) {
            val hex = String.format("%02X%02X%02X", 
                style.textColor.red, 
                style.textColor.green, 
                style.textColor.blue
            )
            writer.write("""<w:color w:val="$hex"/>""")
        }
    }
}
```

---

## 5. Conversion Pipelines

### 5.1 DOCX → PDF Pipeline

```
┌───────────────────────────────────────────────────────────────────────────┐
│                          DOCX → PDF PIPELINE                               │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  STEP 1: INPUT VALIDATION                                                 │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ • Verify file exists and is readable                                │  │
│  │ • Check ZIP signature (PK header)                                   │  │
│  │ • Verify word/document.xml exists in archive                        │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                              │                                            │
│                              ▼                                            │
│  STEP 2: DOCX PARSING                                                     │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ • Stream ZIP entries (no full extraction)                           │  │
│  │ • Parse styles.xml → build style inheritance map                    │  │
│  │ • Parse document.xml → extract paragraphs and runs                  │  │
│  │ • Resolve styles for each run                                       │  │
│  │ • Extract metadata from docProps/core.xml                           │  │
│  │                                                                     │  │
│  │ OUTPUT: Document(layoutState = UNPAGINATED)                         │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                              │                                            │
│                              ▼                                            │
│  STEP 3: LAYOUT & PAGINATION                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ • Initialize TextMeasurer with Android Paint                        │  │
│  │ • Calculate available content area (page - margins)                 │  │
│  │ • Process paragraphs:                                               │  │
│  │   - Measure each TextRun                                            │  │
│  │   - Apply line wrapping algorithm                                   │  │
│  │   - Calculate paragraph heights                                     │  │
│  │ • Split content across pages when overflow                          │  │
│  │ • Handle orphan/widow control (optional)                            │  │
│  │                                                                     │  │
│  │ OUTPUT: Document(layoutState = PAGINATED)                           │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                              │                                            │
│                              ▼                                            │
│  STEP 4: PDF RENDERING                                                    │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ • Create Android PdfDocument                                        │  │
│  │ • For each page:                                                    │  │
│  │   - Create PdfDocument.Page with dimensions                         │  │
│  │   - Get Canvas from page                                            │  │
│  │   - Render paragraphs with Paint                                    │  │
│  │   - Apply styles (font, size, bold, italic, color)                  │  │
│  │   - Handle underline/strikethrough manually                         │  │
│  │   - Finish page                                                     │  │
│  │ • Write PDF to output stream                                        │  │
│  │ • Close resources                                                   │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                              │                                            │
│                              ▼                                            │
│  STEP 5: OUTPUT FINALIZATION                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ • Verify output file was written                                    │  │
│  │ • Return ConversionResult with stats                                │  │
│  │ • Clean up temp files                                               │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

### 5.2 PDF → DOCX Pipeline

```
┌───────────────────────────────────────────────────────────────────────────┐
│                          PDF → DOCX PIPELINE                               │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  STEP 1: INPUT VALIDATION                                                 │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ • Verify file exists and is readable                                │  │
│  │ • Check PDF signature (%PDF- header)                                │  │
│  │ • Verify PDF is not encrypted (fail if encrypted)                   │  │
│  │ • Verify PDF is not image-only/scanned (warn and fail)              │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                              │                                            │
│                              ▼                                            │
│  STEP 2: PDF PARSING (via PdfBox-Android)                                 │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ • Load PDF document (streaming if possible)                         │  │
│  │ • For each page:                                                    │  │
│  │   - Extract text with positions (x, y, width, height)               │  │
│  │   - Extract font information (size, family hints)                   │  │
│  │   - Preserve original coordinates                                   │  │
│  │ • Extract PDF metadata                                              │  │
│  │                                                                     │  │
│  │ OUTPUT: List<TextPosition> per page                                 │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                              │                                            │
│                              ▼                                            │
│  STEP 3: STRUCTURE RECONSTRUCTION                                         │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ • Sort text positions (Y descending, X ascending)                   │  │
│  │ • Group into lines (Y tolerance: ~2 points)                         │  │
│  │ • Merge adjacent characters into words (X gap analysis)             │  │
│  │ • Group lines into paragraphs (vertical gap analysis)               │  │
│  │   - Small gap → same paragraph (line wrap)                          │  │
│  │   - Large gap → paragraph break                                     │  │
│  │ • Detect style changes within paragraphs → TextRun boundaries       │  │
│  │ • Infer alignment from X positions                                  │  │
│  │                                                                     │  │
│  │ OUTPUT: Document(layoutState = PAGINATED) with inferred structure   │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                              │                                            │
│                              ▼                                            │
│  STEP 4: DOCX GENERATION                                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ • Create ZIP output stream                                          │  │
│  │ • Generate required DOCX structure files:                           │  │
│  │   - [Content_Types].xml                                             │  │
│  │   - _rels/.rels                                                     │  │
│  │   - word/_rels/document.xml.rels                                    │  │
│  │   - word/styles.xml (minimal style definitions)                     │  │
│  │ • Generate word/document.xml:                                       │  │
│  │   - Convert each Paragraph → <w:p>                                  │  │
│  │   - Convert each TextRun → <w:r><w:t>                               │  │
│  │   - Apply style mappings → <w:rPr>                                  │  │
│  │   - Insert page breaks between pages                                │  │
│  │ • Generate docProps/core.xml with metadata                          │  │
│  │ • Finalize ZIP                                                      │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                              │                                            │
│                              ▼                                            │
│  STEP 5: OUTPUT FINALIZATION                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ • Verify output file was written                                    │  │
│  │ • Return ConversionResult with warnings about reconstruction        │  │
│  │ • Clean up temp files                                               │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

### 5.3 Layout Engine — Core Algorithm

```kotlin
/**
 * The Layout Engine transforms UNPAGINATED documents into PAGINATED ones.
 * 
 * This is essential for DOCX → PDF where source has no page breaks,
 * but target requires explicit pages.
 */
class LayoutEngine(
    private val textMeasurer: TextMeasurer
) {
    
    /**
     * Paginate a document based on target page dimensions.
     */
    fun paginate(document: Document): Document {
        require(document.layoutState == LayoutState.UNPAGINATED) {
            "Document already paginated"
        }
        
        val meta = document.metadata
        val contentWidth = meta.pageWidth - meta.marginLeft - meta.marginRight
        val contentHeight = meta.pageHeight - meta.marginTop - meta.marginBottom
        
        val allBlocks = document.pages.flatMap { it.blocks }
        val paginatedPages = mutableListOf<Page>()
        var currentPageBlocks = mutableListOf<Block>()
        var currentPageHeight = 0f
        var pageNumber = 1
        
        for (block in allBlocks) {
            val blockHeight = calculateBlockHeight(block, contentWidth)
            
            if (currentPageHeight + blockHeight > contentHeight) {
                // Start new page
                paginatedPages.add(Page(
                    pageNumber = pageNumber,
                    blocks = currentPageBlocks.toList(),
                    pageHeight = currentPageHeight,
                    explicitBreak = false
                ))
                pageNumber++
                currentPageBlocks = mutableListOf()
                currentPageHeight = 0f
            }
            
            currentPageBlocks.add(block)
            currentPageHeight += blockHeight
        }
        
        // Add final page
        if (currentPageBlocks.isNotEmpty()) {
            paginatedPages.add(Page(
                pageNumber = pageNumber,
                blocks = currentPageBlocks.toList(),
                pageHeight = currentPageHeight,
                explicitBreak = false
            ))
        }
        
        return document.copy(
            pages = paginatedPages,
            layoutState = LayoutState.PAGINATED
        )
    }
    
    private fun calculateBlockHeight(block: Block, maxWidth: Float): Float {
        return when (block) {
            is Paragraph -> calculateParagraphHeight(block, maxWidth)
        }
    }
    
    private fun calculateParagraphHeight(
        paragraph: Paragraph,
        maxWidth: Float
    ): Float {
        var totalHeight = paragraph.spacingBefore
        var lineWidth = paragraph.indentation.firstLine
        var lineHeight = 0f
        var lineCount = 0
        
        for (run in paragraph.runs) {
            val metrics = textMeasurer.measure(run.text, run.style)
            lineHeight = maxOf(lineHeight, metrics.height)
            
            // Word-by-word line breaking
            val words = run.text.split(" ")
            for ((index, word) in words.withIndex()) {
                val wordWidth = textMeasurer.measureWidth(word, run.style)
                val spaceWidth = if (index < words.lastIndex) {
                    textMeasurer.measureWidth(" ", run.style)
                } else 0f
                
                if (lineWidth + wordWidth > maxWidth && lineWidth > 0) {
                    // Line break
                    totalHeight += lineHeight * paragraph.lineSpacing.multiplier
                    lineWidth = paragraph.indentation.left
                    lineCount++
                }
                
                lineWidth += wordWidth + spaceWidth
            }
        }
        
        // Add final line
        if (lineWidth > 0) {
            totalHeight += lineHeight * paragraph.lineSpacing.multiplier
        }
        
        totalHeight += paragraph.spacingAfter
        
        return totalHeight
    }
}

/**
 * Text measurement using Android Paint.
 */
class TextMeasurer(private val context: Context) {
    
    private val paint = Paint().apply {
        isAntiAlias = true
    }
    
    fun measure(text: String, style: TextStyle): TextMetrics {
        paint.applyStyle(style)
        
        val fm = paint.fontMetrics
        val width = paint.measureText(text)
        val height = fm.descent - fm.ascent + fm.leading
        
        return TextMetrics(
            width = width,
            height = height,
            ascent = -fm.ascent,
            descent = fm.descent
        )
    }
    
    fun measureWidth(text: String, style: TextStyle): Float {
        paint.applyStyle(style)
        return paint.measureText(text)
    }
}

data class TextMetrics(
    val width: Float,
    val height: Float,
    val ascent: Float,
    val descent: Float
)
```

---

## 6. Memory & Threading Strategy

### 6.1 Memory Management Principles

| Principle | Implementation |
|-----------|----------------|
| **Streaming input** | Never read entire file into byte array. Use InputStream throughout. |
| **Bounded buffers** | Use BufferedInputStream/BufferedOutputStream with fixed sizes (8KB-64KB). |
| **Page-at-a-time processing** | For large documents, process and release pages individually. |
| **Temp file fallback** | If IDM exceeds memory threshold, serialize to temp file. |
| **Explicit cleanup** | All resources implement Closeable; use `use {}` blocks. |

### 6.2 Memory Budget Estimation

```kotlin
object MemoryBudget {
    // Estimated memory per paragraph (bytes)
    const val BYTES_PER_PARAGRAPH = 500L
    
    // Estimated memory per text run
    const val BYTES_PER_RUN = 200L
    
    // Estimated memory per character
    const val BYTES_PER_CHAR = 4L  // UTF-16
    
    // Maximum IDM size before disk fallback (50MB)
    const val MAX_IDM_MEMORY = 50 * 1024 * 1024L
    
    fun estimateDocumentSize(
        paragraphCount: Int,
        avgRunsPerParagraph: Int,
        avgCharsPerRun: Int
    ): Long {
        return paragraphCount * (
            BYTES_PER_PARAGRAPH +
            avgRunsPerParagraph * (BYTES_PER_RUN + avgCharsPerRun * BYTES_PER_CHAR)
        )
    }
    
    fun shouldUseDiskFallback(estimatedSize: Long): Boolean {
        return estimatedSize > MAX_IDM_MEMORY
    }
}
```

### 6.3 Threading Architecture

```kotlin
/**
 * Coroutine-based execution management.
 * 
 * Conversions run on IO dispatcher (thread pool optimized for blocking I/O).
 * Progress updates are dispatched to Main thread for UI safety.
 */
class ConversionExecutor(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    
    /**
     * Execute conversion with progress reporting.
     * 
     * @param request Conversion parameters
     * @param onProgress Called on Main thread with progress (0.0 to 1.0)
     * @return Result with converted document or error
     */
    suspend fun execute(
        request: ConversionRequest,
        onProgress: (Float) -> Unit
    ): ConversionResult = withContext(ioDispatcher) {
        
        val progressReporter = ProgressReporter { progress ->
            // Dispatch to main thread
            withContext(mainDispatcher) {
                onProgress(progress)
            }
        }
        
        try {
            val engine = ConversionEngine()
            engine.convert(request, progressReporter)
        } catch (e: CancellationException) {
            throw e  // Propagate cancellation
        } catch (e: Exception) {
            ConversionResult.Failure(
                error = ConversionError.fromException(e),
                partialOutput = null
            )
        }
    }
    
    /**
     * Execute with timeout.
     */
    suspend fun executeWithTimeout(
        request: ConversionRequest,
        timeoutMillis: Long,
        onProgress: (Float) -> Unit
    ): ConversionResult = withTimeout(timeoutMillis) {
        execute(request, onProgress)
    }
}

data class ConversionRequest(
    val inputUri: Uri,
    val outputUri: Uri,
    val sourceFormat: SourceFormat,
    val targetFormat: SourceFormat,
    val options: ConversionOptions = ConversionOptions()
)

data class ConversionOptions(
    val maxPages: Int = Int.MAX_VALUE,
    val preserveFormatting: Boolean = true,
    val strictMode: Boolean = false
)

sealed class ConversionResult {
    data class Success(
        val outputUri: Uri,
        val pageCount: Int,
        val warnings: List<ConversionWarning>,
        val durationMillis: Long
    ) : ConversionResult()
    
    data class Failure(
        val error: ConversionError,
        val partialOutput: Uri?
    ) : ConversionResult()
}
```

### 6.4 Cancellation Support

```kotlin
/**
 * All long-running operations check for cancellation.
 */
suspend fun DocxParser.parseWithCancellation(
    input: InputStream,
    config: ParserConfig,
    progress: (Float) -> Unit
): Result<Document> {
    
    return runCatching {
        // Check cancellation at each major step
        ensureActive()
        
        val zipReader = DocxZipReader(input)
        
        ensureActive()
        val documentXml = zipReader.extractEntry("word/document.xml")
        
        ensureActive()
        val stylesXml = zipReader.extractEntry("word/styles.xml")
        
        // ... parsing continues with cancellation checks
        
        ensureActive()
        buildDocument(/*...*/)
    }
}
```

---

## 7. Error Handling Architecture

### 7.1 Error Type Hierarchy

```kotlin
/**
 * Sealed hierarchy of conversion errors.
 * 
 * Each error type carries enough context for:
 * 1. User-friendly error messages
 * 2. Debugging information
 * 3. Potential recovery actions
 */
sealed class ConversionError(
    open val message: String,
    open val cause: Throwable? = null
) {
    
    // Input validation errors
    sealed class InputError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ConversionError(message, cause) {
        
        data class FileNotFound(val path: String) : 
            InputError("File not found: $path")
        
        data class FileNotReadable(val path: String) : 
            InputError("Cannot read file: $path")
        
        data class InvalidFormat(val expected: SourceFormat, val actual: String?) : 
            InputError("Invalid format. Expected $expected, got: ${actual ?: "unknown"}")
        
        data class FileTooLarge(val sizeBytes: Long, val maxBytes: Long) : 
            InputError("File too large: ${sizeBytes / 1024}KB (max: ${maxBytes / 1024}KB)")
        
        data class EncryptedDocument(val path: String) : 
            InputError("Document is encrypted and cannot be processed: $path")
    }
    
    // Parsing errors
    sealed class ParseError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ConversionError(message, cause) {
        
        data class MissingRequiredEntry(val entryName: String) : 
            ParseError("Missing required entry in archive: $entryName")
        
        data class MalformedXml(val location: String, val details: String) : 
            ParseError("Malformed XML at $location: $details")
        
        data class UnsupportedPdfFeature(val feature: String) : 
            ParseError("Unsupported PDF feature: $feature")
        
        data class NoTextContent(val path: String) : 
            ParseError("No extractable text found. Document may be scanned/image-only.")
    }
    
    // Rendering errors
    sealed class RenderError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ConversionError(message, cause) {
        
        data class CanvasError(val details: String, override val cause: Throwable) : 
            RenderError("Canvas rendering failed: $details", cause)
        
        data class ZipError(val details: String, override val cause: Throwable) : 
            RenderError("ZIP packaging failed: $details", cause)
        
        data class DiskFull(val requiredBytes: Long) : 
            RenderError("Insufficient disk space. Need: ${requiredBytes / 1024}KB")
    }
    
    // System errors
    sealed class SystemError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ConversionError(message, cause) {
        
        data class OutOfMemory(val attemptedAllocation: Long) : 
            SystemError("Out of memory while processing document")
        
        data class Timeout(val elapsedMillis: Long, val limitMillis: Long) : 
            SystemError("Operation timed out after ${elapsedMillis}ms (limit: ${limitMillis}ms)")
        
        data class Unknown(override val cause: Throwable) : 
            SystemError("Unexpected error: ${cause.message}", cause)
    }
    
    companion object {
        fun fromException(e: Throwable): ConversionError = when (e) {
            is ConversionError -> e
            is OutOfMemoryError -> SystemError.OutOfMemory(0)
            is FileNotFoundException -> InputError.FileNotFound(e.message ?: "unknown")
            is SecurityException -> InputError.FileNotReadable(e.message ?: "unknown")
            else -> SystemError.Unknown(e)
        }
    }
}
```

### 7.2 Warning System

```kotlin
/**
 * Warnings represent non-fatal issues during conversion.
 * Conversion continues, but user should be informed.
 */
data class ConversionWarning(
    val type: WarningType,
    val message: String,
    val location: WarningLocation?
)

enum class WarningType {
    UNSUPPORTED_FONT,           // Font not available, using fallback
    UNSUPPORTED_FEATURE,        // Feature ignored (e.g., text box)
    APPROXIMATE_LAYOUT,         // Layout is best-effort (PDF→DOCX)
    STYLE_LOST,                 // Style couldn't be preserved
    CONTENT_TRUNCATED,          // Content exceeded limits
    ENCODING_ISSUE              // Character encoding problem
}

data class WarningLocation(
    val pageNumber: Int?,
    val paragraphIndex: Int?,
    val description: String
)

/**
 * Warning collector used during conversion.
 */
class WarningCollector {
    private val warnings = mutableListOf<ConversionWarning>()
    
    fun warn(type: WarningType, message: String, location: WarningLocation? = null) {
        warnings.add(ConversionWarning(type, message, location))
    }
    
    fun getWarnings(): List<ConversionWarning> = warnings.toList()
}
```

### 7.3 Recovery Strategies

```kotlin
/**
 * Defines how to handle specific errors.
 */
enum class RecoveryStrategy {
    FAIL_FAST,          // Stop immediately
    SKIP_AND_CONTINUE,  // Skip problematic element, continue
    USE_FALLBACK,       // Use fallback value/behavior
    RETRY_WITH_LIMIT    // Retry with limits
}

/**
 * Recovery policy configuration.
 */
data class RecoveryPolicy(
    val onMalformedXml: RecoveryStrategy = RecoveryStrategy.FAIL_FAST,
    val onUnsupportedFeature: RecoveryStrategy = RecoveryStrategy.SKIP_AND_CONTINUE,
    val onFontNotFound: RecoveryStrategy = RecoveryStrategy.USE_FALLBACK,
    val maxRetries: Int = 3
)
```

---

## 8. Limitations & Tradeoffs

### 8.1 Fundamental Limitations

| Limitation | Reason | Impact |
|------------|--------|--------|
| **No OCR** | Requires ML libraries, large models | Scanned PDFs will have no text output |
| **No complex tables** | Table reconstruction is an unsolved problem | Tables become paragraphs of text |
| **No images** | Requires image processing, storage | Images are ignored |
| **No forms/annotations** | Complex PDF features | Lost in conversion |
| **Basic fonts only** | Can't embed/subset fonts | Visual differences expected |
| **English only** | RTL, complex scripts require shaping engines | Non-Latin text may render incorrectly |

### 8.2 Format-Specific Tradeoffs

#### DOCX → PDF

| Aspect | DOCX Source | PDF Output | Quality |
|--------|-------------|------------|---------|
| Text content | Semantic | Rendered | ★★★★★ |
| Bold/Italic | Preserved | Rendered | ★★★★★ |
| Font size | Exact | Mapped | ★★★★☆ |
| Font family | Specific | System fallback | ★★★☆☆ |
| Paragraph spacing | Exact | Calculated | ★★★★☆ |
| Page breaks | Logical | Calculated | ★★★☆☆ |
| Headers/Footers | Structured | **Not rendered** | ☆☆☆☆☆ |
| Tables | Structured | **Not rendered** | ☆☆☆☆☆ |
| Lists | Semantic | **Rendered as text** | ★★☆☆☆ |

#### PDF → DOCX

| Aspect | PDF Source | DOCX Output | Quality |
|--------|------------|-------------|---------|
| Text content | Positional | Reconstructed | ★★★★☆ |
| Reading order | Visual | Heuristic | ★★★☆☆ |
| Paragraphs | Inferred | Approximated | ★★★☆☆ |
| Bold/Italic | Font-based | Detected | ★★★☆☆ |
| Font size | Exact | Preserved | ★★★★☆ |
| Multi-column | Visual | **Merged** | ★☆☆☆☆ |
| Whitespace | Exact | Normalized | ★★☆☆☆ |
| Page layout | Fixed | Approximate | ★★☆☆☆ |

### 8.3 Known Edge Cases

```kotlin
/**
 * Document patterns that WILL cause problems.
 */
enum class ProblematicPattern {
    // DOCX patterns
    NESTED_TABLES,              // Tables inside tables
    TEXT_BOXES,                 // Floating text containers
    EMBEDDED_OBJECTS,           // Excel charts, etc.
    COMPLEX_NUMBERING,          // Multi-level lists with styles
    TRACKED_CHANGES,            // Revision marks
    
    // PDF patterns
    MULTI_COLUMN_LAYOUT,        // Text flows across columns
    ROTATED_TEXT,               // Text at angles
    OVERLAPPING_TEXT,           // Text layers on top of each other
    LIGATURES,                  // fi, fl, etc. as single glyphs
    VERTICAL_TEXT,              // Top-to-bottom text
    SHEARED_TEXT,               // Italics via transformation matrix
    
    // Both formats
    RIGHT_TO_LEFT_TEXT,         // Arabic, Hebrew
    MIXED_SCRIPTS,              // Multiple writing systems
    MATHEMATICAL_FORMULAS,      // Equations
    EMBEDDED_FONTS              // Fonts with custom encodings
}
```

### 8.4 Accuracy Expectations (Honest Assessment)

**Simple document** (single-column, basic formatting, English):
- DOCX → PDF: 90-95% accurate
- PDF → DOCX: 75-85% accurate

**Moderate document** (styles, spacing, multiple pages):
- DOCX → PDF: 80-90% accurate
- PDF → DOCX: 60-75% accurate

**Complex document** (tables, images, columns):
- DOCX → PDF: 40-60% accurate (missing features)
- PDF → DOCX: 30-50% accurate (reconstruction errors)

---

## 9. Implementation Phases

### Phase 1: Foundation (Weeks 1-2)

**Goal:** Core data structures and basic file I/O

- [ ] Define all IDM data classes
- [ ] Implement `Parser` and `Renderer` interfaces
- [ ] Create `ConversionEngine` orchestrator skeleton
- [ ] Set up `CoroutineManager` for background execution
- [ ] Implement `TempFileManager` for disk operations
- [ ] Create error type hierarchy

**Deliverable:** Compiling project with interfaces, no functional conversion yet

### Phase 2: DOCX Parser (Weeks 3-4)

**Goal:** Parse DOCX into IDM

- [ ] Implement ZIP stream reading
- [ ] Parse `document.xml` structure
- [ ] Extract paragraphs and runs
- [ ] Parse `styles.xml` and build inheritance resolver
- [ ] Handle basic formatting (bold, italic, size)
- [ ] Extract document metadata
- [ ] Unit tests with sample DOCX files

**Deliverable:** DOCX files converted to IDM (verified via logging)

### Phase 3: PDF Renderer (Weeks 5-6)

**Goal:** Render IDM to PDF

- [ ] Implement `LayoutEngine` for pagination
- [ ] Implement `TextMeasurer` using Android Paint
- [ ] Create PDF pages using `PdfDocument` API
- [ ] Render paragraphs with styles
- [ ] Handle line wrapping and page breaks
- [ ] Integration tests: DOCX → PDF pipeline

**Deliverable:** Working DOCX → PDF conversion

### Phase 4: PDF Parser (Weeks 7-9)

**Goal:** Extract text from PDF into IDM

- [ ] Integrate PdfBox-Android library
- [ ] Extract text with positions
- [ ] Extract font information
- [ ] Implement line grouping algorithm
- [ ] Implement paragraph reconstruction
- [ ] Handle multi-page documents
- [ ] Unit tests with sample PDFs

**Deliverable:** PDF files converted to IDM

### Phase 5: DOCX Writer (Weeks 10-11)

**Goal:** Generate DOCX from IDM

- [ ] Implement ZIP creation with required entries
- [ ] Generate `document.xml` from IDM
- [ ] Apply style mappings to runs
- [ ] Generate minimal `styles.xml`
- [ ] Integration tests: PDF → DOCX pipeline

**Deliverable:** Working PDF → DOCX conversion

### Phase 6: Polish & Hardening (Weeks 12-14)

**Goal:** Production readiness

- [ ] Comprehensive error handling
- [ ] Progress reporting callbacks
- [ ] Memory optimization for large files
- [ ] Performance benchmarking
- [ ] Edge case testing
- [ ] Documentation
- [ ] UI integration (basic)

**Deliverable:** Robust conversion engine ready for app integration

---

## Appendix A: DOCX XML Reference

### Minimal document.xml

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    <w:p>
      <w:pPr>
        <w:jc w:val="left"/>
        <w:spacing w:before="0" w:after="200" w:line="276"/>
      </w:pPr>
      <w:r>
        <w:rPr>
          <w:rFonts w:ascii="Arial"/>
          <w:sz w:val="24"/>
          <w:b/>
        </w:rPr>
        <w:t>Hello World</w:t>
      </w:r>
    </w:p>
  </w:body>
</w:document>
```

### Key Element Reference

| Element | Description | Attributes/Values |
|---------|-------------|-------------------|
| `<w:p>` | Paragraph | Contains pPr and runs |
| `<w:pPr>` | Paragraph properties | Child elements define formatting |
| `<w:jc>` | Justification | val: left, center, right, both |
| `<w:spacing>` | Spacing | before, after (twips), line (240ths) |
| `<w:r>` | Run | Contains rPr and text |
| `<w:rPr>` | Run properties | Child elements define formatting |
| `<w:rFonts>` | Font | ascii, hAnsi, cs attributes |
| `<w:sz>` | Font size | val: half-points (24 = 12pt) |
| `<w:b>` | Bold | Empty element = true |
| `<w:i>` | Italic | Empty element = true |
| `<w:u>` | Underline | val: single, double, etc. |
| `<w:t>` | Text | Content is text value |
| `<w:br>` | Break | type: page, column, textWrapping |

---

## Appendix B: PDF Structure Basics

### PDF Object Structure

```
%PDF-1.7
1 0 obj                    % Object 1, generation 0
<<
  /Type /Catalog
  /Pages 2 0 R             % Reference to page tree
>>
endobj

2 0 obj
<<
  /Type /Pages
  /Kids [3 0 R]            % Array of page references
  /Count 1
>>
endobj

3 0 obj
<<
  /Type /Page
  /MediaBox [0 0 612 792]  % Page dimensions (8.5x11 inches)
  /Contents 4 0 R          % Content stream reference
  /Resources << ... >>
>>
endobj

4 0 obj
<< /Length 44 >>
stream
BT                         % Begin text
/F1 12 Tf                  % Font F1, 12 points
72 720 Td                  % Position (72, 720)
(Hello World) Tj           % Draw text
ET                         % End text
endstream
endobj

xref                       % Cross-reference table
0 5
...
trailer
<<
  /Root 1 0 R
  /Size 5
>>
startxref
...
%%EOF
```

### Text Operators

| Operator | Description | Example |
|----------|-------------|---------|
| `BT` | Begin text object | |
| `ET` | End text object | |
| `Tf` | Set font | `/F1 12 Tf` |
| `Td` | Move text position | `100 700 Td` |
| `Tj` | Show text | `(Hello) Tj` |
| `TJ` | Show text with positioning | `[(H) 20 (ello)] TJ` |
| `Tm` | Set text matrix | `1 0 0 1 100 700 Tm` |

---

## Appendix C: Dependency Evaluation

### Required Dependencies

| Dependency | Purpose | Size | License |
|------------|---------|------|---------|
| **Kotlin Stdlib** | Language runtime | ~1.5MB | Apache 2.0 |
| **Kotlin Coroutines** | Async execution | ~300KB | Apache 2.0 |
| **PdfBox-Android** | PDF parsing | ~5MB | Apache 2.0 |

### Rejected Alternatives

| Library | Reason for Rejection |
|---------|---------------------|
| Apache POI | Too large, desktop-oriented |
| iText | Commercial license for generation |
| Aspose | Commercial, cloud-dependent |
| LibreOffice SDK | Not available on Android |
| Google Docs API | Online-only |

---

*Document Version: 1.0*  
*Last Updated: 2026-02-04*  
*Status: DESIGN PHASE*
