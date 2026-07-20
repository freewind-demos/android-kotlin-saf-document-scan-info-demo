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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
        resultText = "已选目录：\n$uri\n\n可选两种 API 扫描对比字段差异。"
    }

    fun startScan(method: ScanMethod) {
        val treeUri = selectedTreeUri ?: return
        isScanning = true
        resultText = "扫描中…"
        scope.launch {
            resultText = try {
                withContext(Dispatchers.IO) {
                    when (method) {
                        ScanMethod.DOCUMENT_FILE -> {
                            val files = SafDirectoryScanner.scanWithDocumentFile(activity, treeUri)
                            formatDocumentFileScanResult(files)
                        }
                        ScanMethod.DOCUMENTS_CONTRACT_QUERY -> {
                            val files = SafDirectoryScanner.scanWithDocumentsContractQuery(activity, treeUri)
                            formatDocumentsContractQueryScanResult(files)
                        }
                    }
                }
            } catch (error: Exception) {
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
            text = "对比 DocumentFile.listFiles() 与 ContentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(...))。",
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
        if (isScanning) {
            CircularProgressIndicator()
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
