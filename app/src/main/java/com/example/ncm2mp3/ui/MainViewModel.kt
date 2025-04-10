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

data class FileConversionState(
    val uri: Uri,
    val fileName: String,
    val isConverting: Boolean = false,
    val result: ConversionResult? = null
)

data class MainUiState(
    val selectedFiles: List<FileConversionState> = emptyList(),
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

    fun addFiles(files: List<Pair<Uri, String>>) {
        val currentFiles = _uiState.value.selectedFiles
        
        // 过滤掉已经存在的文件
        val duplicateFiles = files.filter { (_, fileName) ->
            currentFiles.any { it.fileName == fileName }
        }
        
        val newFiles = files.filter { (_, fileName) ->
            !currentFiles.any { it.fileName == fileName }
        }.map { (uri, fileName) ->
            FileConversionState(uri = uri, fileName = fileName)
        }
        
        if (newFiles.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                selectedFiles = _uiState.value.selectedFiles + newFiles
            )
        }
        
        // 返回重复文件的数量，以便在UI中显示提示
        if (duplicateFiles.isNotEmpty()) {
            Log.w(TAG, "Duplicate files found: ${duplicateFiles.map { it.second }}")
        }
    }

    fun clearFiles() {
        _uiState.value = _uiState.value.copy(selectedFiles = emptyList())
    }

    fun convertFiles(context: Context) {
        val files = _uiState.value.selectedFiles
        files.forEachIndexed { index, file ->
            if (!file.isConverting && file.result == null) {
                convertSingleFile(context, file, index)
            }
        }
    }

    private fun convertSingleFile(context: Context, fileState: FileConversionState, index: Int) {
        viewModelScope.launch {
            Log.d(TAG, "Starting file conversion process for ${fileState.fileName}...")
            
            // 更新文件状态为正在转换
            updateFileState(index) { it.copy(isConverting = true) }
            
            try {
                // 获取不带后缀的文件名
                val baseFileName = fileState.fileName.removeSuffix(".ncm")
                Log.d(TAG, "Base file name: $baseFileName")
                
                // 创建临时文件
                val inputFile = File(context.cacheDir, "temp_${index}.ncm")
                Log.d(TAG, "Creating temporary file: ${inputFile.absolutePath}")
                
                context.contentResolver.openInputStream(fileState.uri)?.use { input ->
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

                // 转换文件，传入期望的输出文件名（不带后缀）
                Log.d(TAG, "Converting file with output name: $baseFileName")
                val result = repository.convertNcmFile(
                    inputPath = inputFile.absolutePath,
                    outputFolder = outputDir.absolutePath,
                    outputFileName = baseFileName
                )
                Log.d(TAG, "Conversion completed with result: $result")

                // 更新文件状态
                updateFileState(index) { it.copy(
                    isConverting = false,
                    result = result
                )}

                // 清理临时文件
                val deleted = inputFile.delete()
                Log.d(TAG, "Temporary file deleted: $deleted")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during conversion", e)
                updateFileState(index) { it.copy(
                    isConverting = false,
                    result = ConversionResult(
                        success = false,
                        error = e.message ?: "Unknown error occurred"
                    )
                )}
            }
        }
    }

    private fun updateFileState(index: Int, update: (FileConversionState) -> FileConversionState) {
        val currentFiles = _uiState.value.selectedFiles.toMutableList()
        if (index < currentFiles.size) {
            currentFiles[index] = update(currentFiles[index])
            _uiState.value = _uiState.value.copy(selectedFiles = currentFiles)
        }
    }

    fun scanNcmFiles(context: Context) {
        viewModelScope.launch {
            Log.d(TAG, "Starting NCM files scan...")
            
            val scanDirs = listOf(
                File(Environment.getExternalStorageDirectory(), "download"),
                File(Environment.getExternalStorageDirectory(), "netease"),
                File(Environment.getExternalStorageDirectory(), "cloudmusic")
            )
            
            val ncmFiles = mutableListOf<Pair<Uri, String>>()
            
            scanDirs.forEach { dir ->
                if (dir.exists() && dir.isDirectory) {
                    Log.d(TAG, "Scanning directory: ${dir.absolutePath}")
                    dir.walk()
                        .filter { it.isFile && it.name.endsWith(".ncm", ignoreCase = true) }
                        .forEach { file ->
                            try {
                                // 将File转换为Uri
                                val uri = Uri.fromFile(file)
                                Log.d(TAG, "Found NCM file: ${file.name} at ${file.absolutePath}")
                                ncmFiles.add(uri to file.name)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing file: ${file.absolutePath}", e)
                            }
                        }
                } else {
                    Log.d(TAG, "Directory does not exist: ${dir.absolutePath}")
                }
            }
            
            if (ncmFiles.isNotEmpty()) {
                Log.d(TAG, "Found ${ncmFiles.size} NCM files")
                addFiles(ncmFiles)
            } else {
                Log.d(TAG, "No NCM files found")
            }
        }
    }
} 