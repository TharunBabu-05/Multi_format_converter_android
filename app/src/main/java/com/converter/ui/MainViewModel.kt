package com.converter.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.converter.core.engine.ConversionEngine
import com.converter.core.engine.ConversionRequest
import com.converter.core.engine.ConversionResult
import com.converter.core.model.SourceFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for document conversion.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = ConversionEngine(application)

    private val _uiState = MutableStateFlow<ConversionUiState>(ConversionUiState.Idle)
    val uiState: StateFlow<ConversionUiState> = _uiState.asStateFlow()

    private var conversionJob: Job? = null

    /**
     * Start conversion process.
     */
    fun convert(
        inputUri: Uri,
        outputUri: Uri,
        sourceFormat: SourceFormat,
        targetFormat: SourceFormat
    ) {
        conversionJob?.cancel()

        conversionJob = viewModelScope.launch {
            _uiState.value = ConversionUiState.Converting(0f, "Preparing...")

            val request = ConversionRequest(
                inputUri = inputUri,
                outputUri = outputUri,
                sourceFormat = sourceFormat,
                targetFormat = targetFormat
            )

            val result = engine.convert(request) { progress ->
                val status = when {
                    progress < 0.4f -> "Parsing document..."
                    progress < 0.6f -> "Processing layout..."
                    progress < 1f -> "Generating output..."
                    else -> "Finalizing..."
                }
                _uiState.value = ConversionUiState.Converting(progress, status)
            }

            _uiState.value = when (result) {
                is ConversionResult.Success -> ConversionUiState.Success(
                    outputUri = result.outputUri,
                    pageCount = result.pageCount,
                    durationMs = result.durationMillis,
                    warningCount = result.warnings.size
                )
                is ConversionResult.Failure -> ConversionUiState.Error(
                    message = result.userMessage
                )
            }
        }
    }

    /**
     * Cancel ongoing conversion.
     */
    fun cancelConversion() {
        conversionJob?.cancel()
        _uiState.value = ConversionUiState.Idle
    }

    /**
     * Reset to idle state.
     */
    fun reset() {
        _uiState.value = ConversionUiState.Idle
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

/**
 * UI state for conversion screen.
 */
sealed class ConversionUiState {
    data object Idle : ConversionUiState()

    data class Converting(
        val progress: Float,
        val status: String
    ) : ConversionUiState()

    data class Success(
        val outputUri: Uri,
        val pageCount: Int,
        val durationMs: Long,
        val warningCount: Int
    ) : ConversionUiState()

    data class Error(
        val message: String
    ) : ConversionUiState()
}
