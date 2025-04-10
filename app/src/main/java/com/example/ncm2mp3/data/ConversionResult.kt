package com.example.ncm2mp3.data

data class ConversionResult(
    val success: Boolean,
    val outputPath: String? = null,
    val format: String? = null,
    val error: String? = null,
    val metadata: Map<String, Any>? = null
) 