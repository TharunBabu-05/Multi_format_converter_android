package com.converter.core.error

import java.io.FileNotFoundException

/**
 * Sealed hierarchy of conversion errors.
 *
 * Each error type carries enough context for:
 * 1. User-friendly error messages
 * 2. Debugging information
 * 3. Potential recovery actions
 *
 * Usage:
 * ```
 * when (error) {
 *     is ConversionError.InputError.FileNotFound -> showFileNotFoundDialog()
 *     is ConversionError.ParseError -> showParseErrorDetails(error)
 *     // ...
 * }
 * ```
 */
sealed class ConversionError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * Get user-friendly error message.
     */
    abstract val userMessage: String

    /**
     * Get technical details for logging.
     */
    open val technicalDetails: String
        get() = "$message${cause?.let { " [Cause: ${it.message}]" } ?: ""}"

    /**
     * Whether this error might be recoverable.
     */
    open val isRecoverable: Boolean = false

    // ========== Input Validation Errors ==========

    /**
     * Errors related to input file validation.
     */
    sealed class InputError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ConversionError(message, cause) {

        /**
         * Input file does not exist.
         */
        data class FileNotFound(val path: String) : InputError("File not found: $path") {
            override val userMessage = "The selected file could not be found."
            override val isRecoverable = true
        }

        /**
         * Input file cannot be read (permissions, locked, etc.).
         */
        data class FileNotReadable(
            val path: String,
            override val cause: Throwable? = null
        ) : InputError("Cannot read file: $path", cause) {
            override val userMessage = "Unable to read the file. Please check permissions."
            override val isRecoverable = true
        }

        /**
         * File format does not match expected format.
         */
        data class InvalidFormat(
            val expectedFormat: String,
            val actualSignature: String?
        ) : InputError("Invalid format. Expected $expectedFormat, got: ${actualSignature ?: "unknown"}") {
            override val userMessage = "The file format is not supported or the file is corrupted."
        }

        /**
         * File exceeds size limits.
         */
        data class FileTooLarge(
            val sizeBytes: Long,
            val maxBytes: Long
        ) : InputError("File too large: ${sizeBytes / 1024}KB (max: ${maxBytes / 1024}KB)") {
            override val userMessage = "The file is too large. Maximum size is ${maxBytes / 1024 / 1024}MB."
        }

        /**
         * Document is encrypted/password protected.
         */
        data class EncryptedDocument(val path: String) : InputError("Document is encrypted: $path") {
            override val userMessage = "This document is password protected and cannot be converted."
        }

        /**
         * Document appears to be scanned/image-only.
         */
        data class ScannedDocument(val path: String) : InputError("Document appears to be scanned: $path") {
            override val userMessage = "This appears to be a scanned document. Text extraction is not supported."
        }

        /**
         * Output location is not writable.
         */
        data class OutputNotWritable(
            val path: String,
            override val cause: Throwable? = null
        ) : InputError("Cannot write to output: $path", cause) {
            override val userMessage = "Unable to save the file. Please choose a different location."
            override val isRecoverable = true
        }
    }

    // ========== Parsing Errors ==========

    /**
     * Errors during document parsing.
     */
    sealed class ParseError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ConversionError(message, cause) {

        /**
         * Required entry missing from archive (DOCX).
         */
        data class MissingRequiredEntry(val entryName: String) : 
            ParseError("Missing required entry in archive: $entryName") {
            override val userMessage = "The document structure is incomplete or corrupted."
        }

        /**
         * XML parsing failed.
         */
        data class MalformedXml(
            val location: String,
            val details: String,
            override val cause: Throwable? = null
        ) : ParseError("Malformed XML at $location: $details", cause) {
            override val userMessage = "The document contains invalid data and cannot be processed."
        }

        /**
         * PDF structure is invalid.
         */
        data class InvalidPdfStructure(
            val details: String,
            override val cause: Throwable? = null
        ) : ParseError("Invalid PDF structure: $details", cause) {
            override val userMessage = "The PDF file is corrupted or uses unsupported features."
        }

        /**
         * Unsupported feature encountered in PDF.
         */
        data class UnsupportedPdfFeature(val feature: String) : 
            ParseError("Unsupported PDF feature: $feature") {
            override val userMessage = "This PDF uses advanced features that are not supported."
        }

        /**
         * No extractable text content found.
         */
        data class NoTextContent(val details: String) : 
            ParseError("No extractable text found: $details") {
            override val userMessage = "No text content could be extracted from this document."
        }

        /**
         * Encoding/charset issue.
         */
        data class EncodingError(
            val details: String,
            override val cause: Throwable? = null
        ) : ParseError("Encoding error: $details", cause) {
            override val userMessage = "The document contains text that could not be decoded."
        }
    }

    // ========== Rendering Errors ==========

    /**
     * Errors during document rendering.
     */
    sealed class RenderError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ConversionError(message, cause) {

        /**
         * Canvas/drawing operation failed.
         */
        data class CanvasError(
            val details: String,
            override val cause: Throwable
        ) : RenderError("Canvas rendering failed: $details", cause) {
            override val userMessage = "An error occurred while creating the output document."
        }

        /**
         * ZIP packaging failed (DOCX output).
         */
        data class ZipError(
            val details: String,
            override val cause: Throwable
        ) : RenderError("ZIP packaging failed: $details", cause) {
            override val userMessage = "Failed to create the output file."
        }

        /**
         * Disk space insufficient.
         */
        data class DiskFull(val requiredBytes: Long) : 
            RenderError("Insufficient disk space. Need: ${requiredBytes / 1024}KB") {
            override val userMessage = "Not enough storage space to save the file."
            override val isRecoverable = true
        }

        /**
         * Page rendering failed.
         */
        data class PageRenderError(
            val pageNumber: Int,
            val details: String,
            override val cause: Throwable? = null
        ) : RenderError("Failed to render page $pageNumber: $details", cause) {
            override val userMessage = "Failed to convert page $pageNumber."
        }
    }

    // ========== Layout Errors ==========

    /**
     * Errors during layout/pagination.
     */
    sealed class LayoutError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ConversionError(message, cause) {

        /**
         * Document is not in expected layout state.
         */
        data class InvalidLayoutState(
            val expected: String,
            val actual: String
        ) : LayoutError("Invalid layout state. Expected: $expected, got: $actual") {
            override val userMessage = "Internal error during document processing."
        }

        /**
         * Text measurement failed.
         */
        data class MeasurementError(
            val details: String,
            override val cause: Throwable? = null
        ) : LayoutError("Text measurement failed: $details", cause) {
            override val userMessage = "Failed to calculate document layout."
        }
    }

    // ========== System Errors ==========

    /**
     * System-level errors.
     */
    sealed class SystemError(
        override val message: String,
        override val cause: Throwable? = null
    ) : ConversionError(message, cause) {

        /**
         * Out of memory.
         */
        data class OutOfMemory(val attemptedAllocation: Long) : 
            SystemError("Out of memory. Attempted: ${attemptedAllocation / 1024}KB") {
            override val userMessage = "The document is too large to process. Try a smaller file."
        }

        /**
         * Operation timed out.
         */
        data class Timeout(
            val elapsedMillis: Long,
            val limitMillis: Long
        ) : SystemError("Operation timed out after ${elapsedMillis}ms (limit: ${limitMillis}ms)") {
            override val userMessage = "The operation took too long and was cancelled."
            override val isRecoverable = true
        }

        /**
         * Operation was cancelled by user.
         */
        data object Cancelled : SystemError("Operation cancelled by user") {
            override val userMessage = "Conversion was cancelled."
            override val isRecoverable = true
        }

        /**
         * Unknown/unexpected error.
         */
        data class Unknown(override val cause: Throwable) : 
            SystemError("Unexpected error: ${cause.message}", cause) {
            override val userMessage = "An unexpected error occurred. Please try again."
        }
    }

    companion object {
        /**
         * Convert any exception to ConversionError.
         */
        fun fromException(e: Throwable): ConversionError = when (e) {
            is ConversionError -> e
            is OutOfMemoryError -> SystemError.OutOfMemory(0)
            is FileNotFoundException -> InputError.FileNotFound(e.message ?: "unknown")
            is SecurityException -> InputError.FileNotReadable(e.message ?: "unknown", e)
            is kotlinx.coroutines.CancellationException -> SystemError.Cancelled
            else -> SystemError.Unknown(e)
        }
    }
}
