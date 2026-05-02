package com.converter.infrastructure

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Manages temporary files for conversion operations.
 *
 * Ensures proper cleanup of temporary files to prevent storage leaks.
 * All temp files are created in app's cache directory.
 */
class TempFileManager(private val context: Context) {

    private val tempDir: File by lazy {
        File(context.cacheDir, TEMP_DIR_NAME).also { it.mkdirs() }
    }

    private val activeFiles = mutableSetOf<File>()
    private val lock = Any()

    /**
     * Create a new temporary file.
     *
     * @param prefix File name prefix
     * @param suffix File extension (include the dot)
     * @return New temporary file
     */
    fun createTempFile(prefix: String = "conv", suffix: String = ".tmp"): File {
        val fileName = "${prefix}_${UUID.randomUUID()}$suffix"
        val file = File(tempDir, fileName)

        synchronized(lock) {
            activeFiles.add(file)
        }

        return file
    }

    /**
     * Create a temporary file with content from input stream.
     */
    fun createTempFileFrom(
        input: InputStream,
        prefix: String = "conv",
        suffix: String = ".tmp"
    ): File {
        val file = createTempFile(prefix, suffix)
        file.outputStream().buffered().use { output ->
            input.copyTo(output, BUFFER_SIZE)
        }
        return file
    }

    /**
     * Get output stream for a temporary file.
     */
    fun createTempOutputStream(prefix: String = "conv", suffix: String = ".tmp"): Pair<File, OutputStream> {
        val file = createTempFile(prefix, suffix)
        return file to file.outputStream().buffered()
    }

    /**
     * Delete a specific temporary file.
     */
    fun delete(file: File): Boolean {
        synchronized(lock) {
            activeFiles.remove(file)
        }
        return file.delete()
    }

    /**
     * Delete all temporary files created by this manager.
     */
    fun deleteAll() {
        synchronized(lock) {
            activeFiles.forEach { it.delete() }
            activeFiles.clear()
        }
    }

    /**
     * Clean up old temporary files (older than maxAge).
     *
     * @param maxAgeMillis Maximum age of files to keep (default: 1 hour)
     */
    fun cleanupOldFiles(maxAgeMillis: Long = DEFAULT_MAX_AGE) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        tempDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
                synchronized(lock) {
                    activeFiles.remove(file)
                }
            }
        }
    }

    /**
     * Get total size of temporary files.
     */
    fun getTotalSize(): Long {
        return tempDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Get count of active temporary files.
     */
    fun getActiveFileCount(): Int {
        return synchronized(lock) { activeFiles.size }
    }

    /**
     * Check available space in temp directory.
     */
    fun getAvailableSpace(): Long {
        return tempDir.usableSpace
    }

    /**
     * Check if there's enough space for an operation.
     */
    fun hasSpaceFor(requiredBytes: Long): Boolean {
        return getAvailableSpace() > requiredBytes + MIN_FREE_SPACE
    }

    companion object {
        private const val TEMP_DIR_NAME = "converter_temp"
        private const val BUFFER_SIZE = 8192
        private const val DEFAULT_MAX_AGE = 60 * 60 * 1000L  // 1 hour
        private const val MIN_FREE_SPACE = 50 * 1024 * 1024L  // 50MB buffer
    }
}

/**
 * Use block pattern for automatic cleanup of temp files.
 */
inline fun <T> TempFileManager.withTempFile(
    prefix: String = "conv",
    suffix: String = ".tmp",
    block: (File) -> T
): T {
    val file = createTempFile(prefix, suffix)
    return try {
        block(file)
    } finally {
        delete(file)
    }
}
