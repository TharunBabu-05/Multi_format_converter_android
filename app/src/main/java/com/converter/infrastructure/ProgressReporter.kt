package com.converter.infrastructure

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Reports progress of conversion operations.
 *
 * Progress is reported as a float from 0.0 to 1.0.
 * Supports weighted phases for multi-step operations.
 */
class ProgressReporter(
    private val dispatcher: CoroutineDispatcher? = null,
    private val onProgress: suspend (Float) -> Unit
) {
    private var currentPhase: Phase? = null
    private var overallProgress: Float = 0f

    /**
     * Report raw progress (0.0 to 1.0).
     */
    suspend fun report(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        overallProgress = clamped
        dispatchProgress(clamped)
    }

    /**
     * Start a new phase with weight.
     * Returns a phase-scoped reporter.
     */
    fun startPhase(name: String, startProgress: Float, endProgress: Float): PhaseReporter {
        val phase = Phase(name, startProgress, endProgress)
        currentPhase = phase
        return PhaseReporter(this, phase)
    }

    /**
     * Report progress within current phase.
     */
    suspend fun reportPhaseProgress(phase: Phase, phaseProgress: Float) {
        val clamped = phaseProgress.coerceIn(0f, 1f)
        val overall = phase.startProgress + (phase.endProgress - phase.startProgress) * clamped
        overallProgress = overall
        dispatchProgress(overall)
    }

    private suspend fun dispatchProgress(progress: Float) {
        if (dispatcher != null) {
            withContext(dispatcher) {
                onProgress(progress)
            }
        } else {
            onProgress(progress)
        }
    }

    /**
     * Represents a phase of the conversion process.
     */
    data class Phase(
        val name: String,
        val startProgress: Float,
        val endProgress: Float
    ) {
        val range: Float get() = endProgress - startProgress
    }

    /**
     * Reports progress within a specific phase.
     */
    class PhaseReporter(
        private val parent: ProgressReporter,
        private val phase: Phase
    ) {
        /**
         * Report progress within this phase (0.0 to 1.0).
         */
        suspend fun report(progress: Float) {
            parent.reportPhaseProgress(phase, progress)
        }

        /**
         * Report progress as fraction of total items.
         */
        suspend fun report(current: Int, total: Int) {
            if (total > 0) {
                report(current.toFloat() / total)
            }
        }
    }

    companion object {
        /**
         * Create a no-op reporter (for testing or when progress is not needed).
         */
        val NOOP = ProgressReporter { }

        /**
         * Standard phases for DOCX → PDF conversion.
         */
        object DocxToPdfPhases {
            val PARSING = Phase("Parsing DOCX", 0f, 0.4f)
            val LAYOUT = Phase("Layout", 0.4f, 0.6f)
            val RENDERING = Phase("Rendering PDF", 0.6f, 1f)
        }

        /**
         * Standard phases for PDF → DOCX conversion.
         */
        object PdfToDocxPhases {
            val PARSING = Phase("Parsing PDF", 0f, 0.3f)
            val RECONSTRUCTION = Phase("Reconstructing structure", 0.3f, 0.6f)
            val WRITING = Phase("Writing DOCX", 0.6f, 1f)
        }
    }
}
