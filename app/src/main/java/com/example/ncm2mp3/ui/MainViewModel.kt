package com.example.ncm2mp3.ui

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ncm2mp3.data.ConversionResult
import com.example.ncm2mp3.data.NcmRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class MainUiState(
    val isConverting: Boolean = false,
    val conversionResult: ConversionResult? = null,
    val selectedFile: Uri? = null,
    val hasStoragePermission: Boolean = false
)

class MainViewModel(context: Context) : ViewModel() {
    companion object {
        private const val TAG = "MainViewModel"
    }

    private val repository = NcmRepository(context)
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun updateStoragePermission(hasPermission: Boolean) {
        Log.d(TAG, "Storage permission updated: $hasPermission")
        _uiState.value = _uiState.value.copy(hasStoragePermission = hasPermission)
    }

    fun updateSelectedFile(uri: Uri?) {
        Log.d(TAG, "Selected file updated: $uri")
        _uiState.value = _uiState.value.copy(selectedFile = uri)
    }

    fun convertFile(context: Context, inputUri: Uri) {
        viewModelScope.launch {
            Log.d(TAG, "Starting file conversion process...")
            _uiState.value = _uiState.value.copy(isConverting = true)
            
            try {
                // 创建临时文件
                val inputFile = File(context.cacheDir, "temp.ncm")
                Log.d(TAG, "Creating temporary file: ${inputFile.absolutePath}")
                
                context.contentResolver.openInputStream(inputUri)?.use { input ->
                    inputFile.outputStream().use { output ->
                        val bytesCopied = input.copyTo(output)
                        Log.d(TAG, "Copied $bytesCopied bytes to temporary file")
                    }
                }

                // 设置输出目录为外部存储的music文件夹
                val outputDir = File(Environment.getExternalStorageDirectory(), "music")
                Log.d(TAG, "Output directory: ${outputDir.absolutePath}")
                if (!outputDir.exists()) {
                    val created = outputDir.mkdirs()
                    Log.d(TAG, "Created output directory: $created")
                }

                // 检查文件权限
                Log.d(TAG, "Temporary file exists: ${inputFile.exists()}")
                Log.d(TAG, "Temporary file readable: ${inputFile.canRead()}")
                Log.d(TAG, "Temporary file size: ${inputFile.length()}")
                Log.d(TAG, "Output directory exists: ${outputDir.exists()}")
                Log.d(TAG, "Output directory writable: ${outputDir.canWrite()}")

                // 转换文件
                Log.d(TAG, "Converting file...")
                val result = repository.convertNcmFile(
                    inputPath = inputFile.absolutePath,
                    outputFolder = outputDir.absolutePath
                )
                Log.d(TAG, "Conversion completed with result: $result")

                _uiState.value = _uiState.value.copy(
                    isConverting = false,
                    conversionResult = result
                )

                // 清理临时文件
                val deleted = inputFile.delete()
                Log.d(TAG, "Temporary file deleted: $deleted")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during conversion", e)
                _uiState.value = _uiState.value.copy(
                    isConverting = false,
                    conversionResult = ConversionResult(
                        success = false,
                        error = e.message ?: "Unknown error occurred"
                    )
                )
            }
        }
    }
} 