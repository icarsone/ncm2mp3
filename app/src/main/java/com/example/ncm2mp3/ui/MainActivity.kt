package com.example.ncm2mp3.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(applicationContext) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Ncm2Mp3App(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndRequestStoragePermission(this, viewModel)
    }
}

fun checkAndRequestStoragePermission(activity: ComponentActivity, viewModel: MainViewModel) {
    when {
        // Android 11 (API 30)及以上版本
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
            val hasPermission = Environment.isExternalStorageManager()
            viewModel.updateStoragePermission(hasPermission)
            
            if (!hasPermission) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            }
        }
        // Android 10 (API 29)及以下版本
        else -> {
            val writePermission = Manifest.permission.WRITE_EXTERNAL_STORAGE
            val hasPermission = ContextCompat.checkSelfPermission(
                activity,
                writePermission
            ) == PackageManager.PERMISSION_GRANTED
            
            viewModel.updateStoragePermission(hasPermission)
            
            if (!hasPermission) {
                activity.requestPermissions(
                    arrayOf(writePermission),
                    1001
                )
            }
        }
    }
}

@Composable
fun Ncm2Mp3App(viewModel: MainViewModel) {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            MainScreen(viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted: Boolean ->
            viewModel.updateStoragePermission(isGranted)
        }
    )

    val multipleFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris: List<Uri> ->
            val validFiles = uris.mapNotNull { uri ->
                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    cursor.moveToFirst()
                    cursor.getString(nameIndex)
                } ?: ""

                if (fileName.endsWith(".ncm", ignoreCase = true)) {
                    uri to fileName
                } else {
                    null
                }
            }

            if (validFiles.isNotEmpty()) {
                // 检查是否有重复文件
                val duplicateFiles = validFiles.filter { (_, fileName) ->
                    uiState.selectedFiles.any { it.fileName == fileName }
                }
                
                if (duplicateFiles.isNotEmpty()) {
                    Toast.makeText(
                        context,
                        "已跳过${duplicateFiles.size}个重复文件",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                
                viewModel.addFiles(validFiles)
            }
            
            val invalidFiles = uris.size - validFiles.size
            if (invalidFiles > 0) {
                Toast.makeText(
                    context,
                    "已过滤掉${invalidFiles}个非NCM格式的文件",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    )

    LaunchedEffect(Unit) {
        checkAndRequestStoragePermission(context as ComponentActivity, viewModel)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NCM转换器") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!uiState.hasStoragePermission) {
                StoragePermissionCard(context, permissionLauncher)
            }
            
            FileSelectionCard(
                hasPermission = uiState.hasStoragePermission,
                onSelectFiles = {
                    multipleFilePicker.launch(arrayOf("*/*"))
                },
                onScanFiles = {
                    viewModel.scanNcmFiles(context)
                }
            )

            if (uiState.selectedFiles.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "已选择的文件",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(
                                onClick = { viewModel.clearFiles() }
                            ) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "清除所有文件",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Button(
                            onClick = { viewModel.convertFiles(context) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = uiState.selectedFiles.any { !it.isConverting && it.result == null }
                        ) {
                            Text("开始转换")
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        LazyColumn(
                            modifier = Modifier.weight(1f, false),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(uiState.selectedFiles) { fileState ->
                                FileConversionItem(fileState)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StoragePermissionCard(context: android.content.Context, permissionLauncher: androidx.activity.result.ActivityResultLauncher<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "需要存储权限来转换文件",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } else {
                        permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("授予权限")
            }
        }
    }
}

@Composable
fun FileSelectionCard(
    hasPermission: Boolean,
    onSelectFiles: () -> Unit,
    onScanFiles: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "选择要转换的NCM文件",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSelectFiles,
                    enabled = hasPermission,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "添加文件",
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(if (hasPermission) "选择文件" else "请先授予权限")
                }
                
                Button(
                    onClick = onScanFiles,
                    enabled = hasPermission,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "扫描文件",
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("扫描文件")
                }
            }
        }
    }
}

@Composable
fun FileConversionItem(fileState: FileConversionState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                fileState.isConverting -> MaterialTheme.colorScheme.primaryContainer
                fileState.result?.success == true -> MaterialTheme.colorScheme.primaryContainer
                fileState.result?.success == false -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = fileState.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            when {
                fileState.isConverting -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                fileState.result != null -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    if (fileState.result.success) {
                        Text(
                            "转换成功！",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                        fileState.result.outputPath?.let { path ->
                            Text(
                                "保存至：$path",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        Text(
                            "转换失败：${fileState.result.error ?: "未知错误"}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
} 