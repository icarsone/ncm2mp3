package com.example.ncm2mp3.data

import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.chaquo.python.PyObject
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NcmRepository(private val context: Context) {
    companion object {
        private const val TAG = "NcmRepository"
    }
    
    init {
        if (!Python.isStarted()) {
            Log.d(TAG, "Starting Python...")
            Python.start(AndroidPlatform(context))
            Log.d(TAG, "Python started successfully")
        }
    }
    
    suspend fun convertNcmFile(
        inputPath: String, 
        outputFolder: String,
        outputFileName: String? = null
    ): ConversionResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting file conversion...")
            Log.d(TAG, "Input path: $inputPath")
            Log.d(TAG, "Output folder: $outputFolder")
            Log.d(TAG, "Output file name: $outputFileName")
            
            val py = Python.getInstance()
            val converter = py.getModule("ncm_converter")
            
            Log.d(TAG, "Calling Python convert_file function...")
            val pyResult = if (outputFileName != null) {
                converter.callAttr("convert_file", inputPath, outputFolder, outputFileName)
            } else {
                converter.callAttr("convert_file", inputPath, outputFolder)
            }
            
            // 将PyObject转换为Map并安全地处理类型
            @Suppress("UNCHECKED_CAST")
            val rawMap = pyResult.asMap() as Map<PyObject, PyObject>
            val resultMap = rawMap.mapKeys { it.key.toString() }
            
            Log.d(TAG, "Python conversion result raw: $pyResult")
            Log.d(TAG, "Python conversion result map: $resultMap")
            
            // 提取各个字段，确保类型安全
            val success = resultMap["success"]?.toString()?.toBoolean() ?: false
            val outputPath = resultMap["output_path"]?.toString()
            val format = resultMap["format"]?.toString()
            val error = resultMap["error"]?.toString()
            
            // 处理元数据，确保类型安全
            val metadataObj = resultMap["meta_data"]
            val metadata: Map<String, String>? = if (metadataObj != null) {
                try {
                    val metadataMap = metadataObj.asMap()
                    metadataMap.mapKeys { it.key.toString() }
                              .mapValues { it.value.toString() }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to convert metadata to map", e)
                    null
                }
            } else {
                null
            }
            
            val conversionResult = ConversionResult(
                success = success,
                outputPath = outputPath,
                format = format,
                error = error,
                metadata = metadata
            )
            
            Log.d(TAG, "Final conversion result: $conversionResult")
            return@withContext conversionResult
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during conversion", e)
            return@withContext ConversionResult(
                success = false,
                error = e.message ?: "Unknown error occurred"
            )
        }
    }
    
    fun getPythonVersion(): String {
        val py = Python.getInstance()
        val converter = py.getModule("ncm_converter")
        return converter.callAttr("get_version").toString()
    }
} 