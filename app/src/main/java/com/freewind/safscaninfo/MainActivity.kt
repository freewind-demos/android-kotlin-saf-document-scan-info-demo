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

/** Demo 入口：选 SAF 目录 → 扫描 → 列出每个文件的全部可读信息 */
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
    var resultText by remember { mutableStateOf("请先选择目录，再点「开始扫描」。") }
    var isScanning by remember { mutableStateOf(false) }

    val openTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            resultText = "未选择目录。"
            return@rememberLauncherForActivityResult
        }
        val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        activity.contentResolver.takePersistableUriPermission(uri, readFlag)
        selectedTreeUri = uri
        resultText = "已选目录：\n$uri\n\n点「开始扫描」查看文件信息。"
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
            text = "与 freewind-music-player 相同：DocumentsContract + Cursor 列目录；并补充 DocumentFile 可读字段。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { openTreeLauncher.launch(null) }) {
                Text("选择目录")
            }
            Button(
                enabled = selectedTreeUri != null && !isScanning,
                onClick = {
                    val treeUri = selectedTreeUri ?: return@Button
                    isScanning = true
                    resultText = "扫描中…"
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            val files = SafDirectoryScanner.scanAllFiles(activity, treeUri)
                            formatScanResult(files)
                        }
                        resultText = text
                        isScanning = false
                    }
                },
            ) {
                Text("开始扫描")
            }
            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
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
