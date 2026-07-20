package com.freewind.safscaninfo

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * 仅当前目录一层（不递归；原因见 README / MainActivity.SCAN_SCOPE_NOTE）：
 * 1) list 阶段 listFiles() 拿全部 DocumentFile（不调 isDirectory）
 * 2) 再逐个读 DocumentFile.name / DocumentFile.length()（不用 ContentResolver.query）
 * 两阶段分别计时；展示区各大步空行分隔，每类只显示前 5 条。
 */
object DocumentFileDirectoryScanner {

    private const val DISPLAY_LIMIT = 5

    fun inspect(
        context: Context,
        treeUri: Uri,
        onProgress: (String) -> Unit = {},
        onAppend: (String) -> Unit = {},
    ) {
        ScanStepOutput.begin(onAppend, onProgress, "DocumentFile.fromTreeUri() 打开目录")
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("DocumentFile.fromTreeUri() 无法打开目录")

        ScanStepOutput.begin(onAppend, onProgress, "输出内存直取字段说明")
        onAppend(
            buildString {
                appendLine("=== 内存可直接拿到的信息（DocumentFile.listFiles）===")
                appendLine("listFiles() 内部只 query DOCUMENT_ID，再拼成 DocumentFile。")
                appendLine("不访问 provider 时，每个子项内存里只有：")
                appendLine("- uri（由 documentId 拼出，存在 DocumentFile 对象里）")
                appendLine("name / length() 等访问时会再打 ContentResolver，本 Demo 第二阶段即走这条路径。")
            }.trimEnd(),
        )

        ScanStepOutput.begin(
            onAppend,
            onProgress,
            "list 阶段：listFiles() 收集当前层全部 DocumentFile（不调 isDirectory）",
        )
        val listStartedAt = System.nanoTime()
        onProgress("listFiles() → / …")
        val children = root.listFiles()
        onProgress("listFiles() → / → ${children.size} 项")
        val listElapsedMs = elapsedMs(listStartedAt)

        if (children.isEmpty()) {
            throw IllegalArgumentException("当前目录下没有任何子项")
        }

        onProgress("list 完成：子项 ${children.size}，${listElapsedMs} ms")
        onAppend(
            buildString {
                appendLine("=== list 阶段（listFiles，不读 name/length，不调 isDirectory）===")
                appendLine("耗时: ${listElapsedMs} ms")
                appendLine("itemCount: ${children.size}")
                appendLine("前 $DISPLAY_LIMIT 个 URI:")
                children.take(DISPLAY_LIMIT).forEachIndexed { index, child ->
                    appendLine("  ${index + 1}. ${child.uri}")
                }
            }.trimEnd(),
        )

        ScanStepOutput.begin(
            onAppend,
            onProgress,
            "取 name+size：逐个 DocumentFile 读 name / length()",
        )
        val metaStartedAt = System.nanoTime()
        val nameSizes = children.mapIndexed { index, child ->
            onProgress("name+length (${index + 1}/${children.size})")
            readNameAndLength(child)
        }
        val metaElapsedMs = elapsedMs(metaStartedAt)

        onProgress("扫描完成：name+length ${metaElapsedMs} ms")
        onAppend(
            buildString {
                appendLine("=== 取 name+size 阶段（DocumentFile.name / DocumentFile.length()）===")
                appendLine("耗时: ${metaElapsedMs} ms")
                appendLine("itemCount: ${nameSizes.size}")
                appendLine("前 $DISPLAY_LIMIT 个:")
                nameSizes.take(DISPLAY_LIMIT).forEachIndexed { index, item ->
                    appendLine("  ${index + 1}. name=${item.name}  size=${item.size}")
                }
            }.trimEnd(),
        )
    }

    private data class NameSize(
        val name: String,
        val size: Long,
    )

    private fun readNameAndLength(file: DocumentFile): NameSize {
        val name = file.name
            ?: throw IllegalStateException("DocumentFile.name 为 null: ${file.uri}")
        return NameSize(name = name, size = file.length())
    }

    private fun elapsedMs(startedAtNano: Long): Long =
        (System.nanoTime() - startedAtNano) / 1_000_000L
}
