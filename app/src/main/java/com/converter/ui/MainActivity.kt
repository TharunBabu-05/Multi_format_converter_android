package com.converter.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import com.converter.R
import com.converter.core.model.SourceFormat
import com.converter.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Main activity for document conversion.
 *
 * Provides:
 * - File selection via system picker
 * - Format selection
 * - Conversion progress display
 * - Result handling
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory(application) }

    private var selectedFileUri: Uri? = null
    private var detectedFormat: SourceFormat = SourceFormat.UNKNOWN

    // File picker launcher
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleFileSelected(it) }
    }

    // Save file launcher
    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let { startConversion(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeViewModel()

        // Handle incoming intents (e.g., "Open with")
        handleIntent(intent)
    }

    private fun setupUI() {
        binding.buttonSelectFile.setOnClickListener {
            openFilePicker()
        }

        binding.buttonConvert.setOnClickListener {
            initiateConversion()
        }

        binding.buttonCancel.setOnClickListener {
            viewModel.cancelConversion()
        }

        // Initially hide progress and result sections
        binding.layoutProgress.visibility = View.GONE
        binding.layoutResult.visibility = View.GONE
        binding.buttonConvert.isEnabled = false
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateUI(state)
            }
        }
    }

    private fun updateUI(state: ConversionUiState) {
        when (state) {
            is ConversionUiState.Idle -> {
                binding.layoutProgress.visibility = View.GONE
                binding.layoutResult.visibility = View.GONE
                binding.buttonConvert.isEnabled = selectedFileUri != null
                binding.buttonSelectFile.isEnabled = true
            }

            is ConversionUiState.Converting -> {
                binding.layoutProgress.visibility = View.VISIBLE
                binding.layoutResult.visibility = View.GONE
                binding.buttonConvert.isEnabled = false
                binding.buttonSelectFile.isEnabled = false
                binding.progressBar.progress = (state.progress * 100).toInt()
                binding.textProgress.text = "${(state.progress * 100).toInt()}%"
                binding.textStatus.text = state.status
            }

            is ConversionUiState.Success -> {
                binding.layoutProgress.visibility = View.GONE
                binding.layoutResult.visibility = View.VISIBLE
                binding.buttonConvert.isEnabled = true
                binding.buttonSelectFile.isEnabled = true
                binding.buttonOpenResult.visibility = View.VISIBLE
                binding.buttonShareResult.visibility = View.VISIBLE

                binding.textResultTitle.text = getString(R.string.conversion_complete)
                binding.textResultDetails.text = buildString {
                    append("Pages: ${state.pageCount}\n")
                    append("Time: ${state.durationMs}ms")
                    if (state.warningCount > 0) {
                        append("\nWarnings: ${state.warningCount}")
                    }
                }

                // Set up share/open buttons
                binding.buttonOpenResult.setOnClickListener {
                    openConvertedFile(state.outputUri)
                }
                binding.buttonShareResult.setOnClickListener {
                    shareConvertedFile(state.outputUri)
                }
            }

            is ConversionUiState.Error -> {
                binding.layoutProgress.visibility = View.GONE
                binding.layoutResult.visibility = View.VISIBLE
                binding.buttonConvert.isEnabled = true
                binding.buttonSelectFile.isEnabled = true

                binding.textResultTitle.text = getString(R.string.conversion_failed)
                binding.textResultDetails.text = state.message

                binding.buttonOpenResult.visibility = View.GONE
                binding.buttonShareResult.visibility = View.GONE
            }
        }
    }

    private fun openFilePicker() {
        val mimeTypes = arrayOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
        filePickerLauncher.launch(mimeTypes)
    }

    private fun handleFileSelected(uri: Uri) {
        selectedFileUri = uri
        viewModel.reset()

        // Take persistable permission
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (securityException: SecurityException) {
            // Some providers do not grant persistable permissions; the transient grant is still usable.
        }

        // Detect format
        val mimeType = contentResolver.getType(uri)
        detectedFormat = SourceFormat.fromMimeType(mimeType ?: "")

        // Update UI
        val fileName = getFileName(uri)
        binding.textSelectedFile.text = fileName
        binding.textSelectedFile.visibility = View.VISIBLE

        // Update conversion button text
        when (detectedFormat) {
            SourceFormat.DOCX -> {
                binding.buttonConvert.text = getString(R.string.docx_to_pdf)
                binding.buttonConvert.isEnabled = true
            }
            SourceFormat.PDF -> {
                binding.buttonConvert.text = getString(R.string.pdf_to_docx)
                binding.buttonConvert.isEnabled = true
            }
            else -> {
                binding.buttonConvert.text = getString(R.string.convert)
                binding.buttonConvert.isEnabled = false
                Toast.makeText(this, "Unsupported format", Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonOpenResult.visibility = View.GONE
        binding.buttonShareResult.visibility = View.GONE
        binding.layoutProgress.visibility = View.GONE
        binding.layoutResult.visibility = View.GONE
    }

    private fun initiateConversion() {
        val sourceUri = selectedFileUri ?: return

        // Determine target format
        val targetFormat = when (detectedFormat) {
            SourceFormat.DOCX -> SourceFormat.PDF
            SourceFormat.PDF -> SourceFormat.DOCX
            else -> return
        }

        // Create output file name
        val inputName = getFileName(sourceUri).substringBeforeLast(".")
        val outputName = "$inputName.${targetFormat.extension}"

        // Launch save file picker
        saveFileLauncher.launch(outputName)
    }

    private fun startConversion(outputUri: Uri) {
        val sourceUri = selectedFileUri ?: return

        val targetFormat = when (detectedFormat) {
            SourceFormat.DOCX -> SourceFormat.PDF
            SourceFormat.PDF -> SourceFormat.DOCX
            else -> return
        }

        viewModel.convert(sourceUri, outputUri, detectedFormat, targetFormat)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            handleFileSelected(uri)
            return
        }

        // Support developer/debug mode: accept raw file paths via intent extras
        // Usage (adb):
        // adb shell am start -n com.converter/.ui.MainActivity --es inputPath "/sdcard/Download/Statement.docx" --es outputPath "/sdcard/Download/Statement.pdf"
        intent?.getStringExtra("inputPath")?.let { inputPath ->
            val outputPath = intent.getStringExtra("outputPath")
            if (outputPath != null) {
                try {
                    val inputFile = File(inputPath)
                    val outputFile = File(outputPath)
                    if (inputFile.exists()) {
                        val inputUri = Uri.fromFile(inputFile)
                        val outputUri = Uri.fromFile(outputFile)

                        // Take a transient permission where possible
                        try {
                            contentResolver.takePersistableUriPermission(
                                inputUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (ignored: Exception) {
                        }

                        handleFileSelected(inputUri)

                        // Start conversion immediately on a background thread
                        val targetFormat = when (detectedFormat) {
                            com.converter.core.model.SourceFormat.DOCX -> com.converter.core.model.SourceFormat.PDF
                            com.converter.core.model.SourceFormat.PDF -> com.converter.core.model.SourceFormat.DOCX
                            else -> null
                        }

                        if (targetFormat != null) {
                            // Bypass save picker and run conversion directly
                            binding.layoutProgress.visibility = View.VISIBLE
                            viewModel.convert(inputUri, outputUri, detectedFormat, targetFormat)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore — debug helper should not crash the app
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment ?: "document"
    }

    private fun openConvertedFile(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, contentResolver.getType(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(this, "No app available to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareConvertedFile(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, "Share"))
    }
}
