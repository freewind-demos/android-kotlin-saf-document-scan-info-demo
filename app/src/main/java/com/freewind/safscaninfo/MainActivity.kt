package com.freewind.safscaninfo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Demo 入口：选 SAF 目录 → 两种扫描方式对比 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SafScanInfoScreen(activity = this@MainActivity)
                }
            }
        }
    }
}

@Composable
private fun SafScanInfoScreen(activity: ComponentActivity) {
    val scope = rememberCoroutineScope()
    var selectedTreeUri by remember { mutableStateOf<Uri?>(null) }
    var resultText by remember { mutableStateOf("请先选择目录，再选一种扫描方式。") }
    var isScanning by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }

    val openTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            resultText = "未选择目录。"
            return@rememberLauncherForActivityResult
        }
        val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching {
            activity.contentResolver.takePersistableUriPermission(uri, readFlag)
        }.onFailure { error ->
            resultText = "目录权限保存失败：${error.message ?: error.javaClass.simpleName}"
            return@rememberLauncherForActivityResult
        }
        selectedTreeUri = uri
        resultText = "已选目录：\n$uri\n\n可选两种 API：先看 list 返回，再看第一个文件逐 API access。"
    }

    fun startScan(method: ScanMethod) {
        val treeUri = selectedTreeUri ?: return
        isScanning = true
        progressText = when (method) {
            ScanMethod.DOCUMENT_FILE -> "准备 DocumentFile 扫描…"
            ScanMethod.DOCUMENTS_CONTRACT_QUERY -> "准备 ContentResolver.query 扫描…"
        }
        scope.launch {
            val onProgress: (String) -> Unit = { message ->
                scope.launch(Dispatchers.Main.immediate) {
                    progressText = message
                }
            }
            resultText = try {
                withContext(Dispatchers.IO) {
                    when (method) {
                        ScanMethod.DOCUMENT_FILE ->
                            DocumentFileDirectoryScanner.inspect(
                                context = activity,
                                treeUri = treeUri,
                                onProgress = onProgress,
                            )
                        ScanMethod.DOCUMENTS_CONTRACT_QUERY ->
                            DocumentsContractQueryDirectoryScanner.inspect(
                                context = activity,
                                treeUri = treeUri,
                                onProgress = onProgress,
                            )
                    }
                }
            } catch (error: Exception) {
                progressText = "扫描失败"
                "扫描失败：${error.message ?: error.javaClass.simpleName}"
            }
            isScanning = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "SAF 文件信息扫描 Demo",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "展示 list 阶段返回值，以及第一个文件逐个 API access 的 key-value。",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { openTreeLauncher.launch(null) }) {
            Text("选择目录")
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedTreeUri != null && !isScanning,
            onClick = { startScan(ScanMethod.DOCUMENT_FILE) },
        ) {
            Text("DocumentFile.listFiles()")
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedTreeUri != null && !isScanning,
            onClick = { startScan(ScanMethod.DOCUMENTS_CONTRACT_QUERY) },
        ) {
            Text("ContentResolver.query()")
        }
        if (isScanning || progressText.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
                Text(
                    text = progressText.ifEmpty { "…" },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp,
                )
            }
        }
        Text(
            text = resultText,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}
