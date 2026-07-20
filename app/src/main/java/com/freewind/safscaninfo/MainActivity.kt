package com.freewind.safscaninfo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.TextButton
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

/** 为何 Demo 只扫当前目录一层；详 README「为何只扫当前目录一层」 */
private const val SCAN_SCOPE_NOTE = """
为何只扫当前目录一层（不递归）：

DocumentFile.listFiles() 返回的子项，内存里基本只有 uri。要区分文件/目录，必须再调 isDirectory() 等——每一项都会再打 ContentResolver/DocumentProvider。大目录等于全量二次访问，极慢。

ContentResolver.query 一次 cursor 即可拿到 displayName、size、mimeType；mimeType == MIME_TYPE_DIR 即可判目录，无需逐项再查。

若递归进子目录，DocumentFile 会在「list + 每项 isDirectory + 递归」上累积，与 query 的性能差距会非常大。本 Demo 只 list/query 当前层全部子项，DocumentFile 路径不调 isDirectory()；含子目录若要递归整树，上述差异仍然存在。
"""

/** Demo 入口：选 SAF 目录 → 两种扫描方式对比（仅当前层，见 SCAN_SCOPE_NOTE） */
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
    var scopeNoteExpanded by remember { mutableStateOf(false) }

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
        resultText = "已选目录：\n$uri\n\n点下方按钮对比两种扫法（仅当前层）。"
    }

    fun runDocumentFileScan() {
        val treeUri = selectedTreeUri ?: return
        isScanning = true
        progressText = "准备 DocumentFile 扫描…"
        resultText = ""
        scope.launch {
            val onProgress: (String) -> Unit = { message ->
                scope.launch(Dispatchers.Main.immediate) {
                    progressText = message
                }
            }
            val onAppend: (String) -> Unit = { chunk ->
                scope.launch(Dispatchers.Main.immediate) {
                    resultText = if (resultText.isEmpty()) chunk else "$resultText\n$chunk"
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    DocumentFileDirectoryScanner.inspect(
                        context = activity,
                        treeUri = treeUri,
                        onProgress = onProgress,
                        onAppend = onAppend,
                    )
                }
            } catch (error: Exception) {
                progressText = "扫描失败"
                resultText = "扫描失败：${error.message ?: error.javaClass.simpleName}"
            }
            isScanning = false
        }
    }

    fun runDocumentsContractQueryScan() {
        val treeUri = selectedTreeUri ?: return
        isScanning = true
        progressText = "准备 ContentResolver.query 扫描…"
        resultText = ""
        scope.launch {
            val onProgress: (String) -> Unit = { message ->
                scope.launch(Dispatchers.Main.immediate) {
                    progressText = message
                }
            }
            val onAppend: (String) -> Unit = { chunk ->
                scope.launch(Dispatchers.Main.immediate) {
                    resultText = if (resultText.isEmpty()) chunk else "$resultText\n$chunk"
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    DocumentsContractQueryDirectoryScanner.inspect(
                        context = activity,
                        treeUri = treeUri,
                        onProgress = onProgress,
                        onAppend = onAppend,
                    )
                }
            } catch (error: Exception) {
                progressText = "扫描失败"
                resultText = "扫描失败：${error.message ?: error.javaClass.simpleName}"
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
            text = "对比 DocumentFile.listFiles 与 ContentResolver.query 在当前目录一层的耗时；每类只显示前 5 条。",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(
            onClick = { scopeNoteExpanded = !scopeNoteExpanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (scopeNoteExpanded) {
                    "▲ 收起：为何只扫当前目录一层"
                } else {
                    "▼ 展开：为何只扫当前目录一层"
                },
            )
        }
        AnimatedVisibility(visible = scopeNoteExpanded) {
            Text(
                text = SCAN_SCOPE_NOTE.trim(),
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp,
            )
        }
        OutlinedButton(onClick = { openTreeLauncher.launch(null) }) {
            Text("选择目录")
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedTreeUri != null && !isScanning,
            onClick = { runDocumentFileScan() },
        ) {
            Text("DocumentFile.listFiles()")
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedTreeUri != null && !isScanning,
            onClick = { runDocumentsContractQueryScan() },
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
