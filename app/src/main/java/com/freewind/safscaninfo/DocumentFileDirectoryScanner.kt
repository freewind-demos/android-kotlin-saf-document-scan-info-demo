package com.freewind.safscaninfo

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * DocumentFile.listFiles()：
 * 1) list 阶段只收集内存里已有的 uri（不读 name/size）
 * 2) 再单独遍历，用 ContentResolver.query 高效取 name+size
 * 两阶段分别计时；展示区各大步空行分隔，每类只显示前 5 条。
 */
object DocumentFileDirectoryScanner {

    private const val DISPLAY_LIMIT = 5

    private val nameSizeProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    fun inspect(
        context: Context,
        treeUri: Uri,
        onProgress: (String) -> Unit = {},
        onAppend: (String) -> Unit = {},
    ) {
        onProgress("DocumentFile.fromTreeUri() …")
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("DocumentFile.fromTreeUri() 无法打开目录")

        onAppend(
            buildString {
                appendLine("=== 内存可直接拿到的信息（DocumentFile.listFiles）===")
                appendLine("listFiles() 内部只 query DOCUMENT_ID，再拼成 DocumentFile。")
                appendLine("不访问 provider 时，每个子项内存里只有：")
                appendLine("- uri（由 documentId 拼出，存在 DocumentFile 对象里）")
                appendLine("以下字段访问时会再打 ContentResolver，不算内存直取：")
                appendLine("- name / type / isDirectory / isFile / length / lastModified / exists / canRead / canWrite …")
            }.trimEnd(),
        )
        onAppend("")

        onProgress("list 阶段：收集全部文件 URI …")
        val listStartedAt = System.nanoTime()
        val fileUris = mutableListOf<Uri>()
        listAllFileUris(
            dir = root,
            directoryPath = "",
            onFileUri = { uri -> fileUris += uri },
            onProgress = onProgress,
        )
        val listElapsedMs = elapsedMs(listStartedAt)

        if (fileUris.isEmpty()) {
            throw IllegalArgumentException("目录下未找到任何文件")
        }

        onProgress("list 完成：文件 ${fileUris.size}，${listElapsedMs} ms")
        onAppend(
            buildString {
                appendLine("=== list 阶段（仅收集 URI，不读 name/size）===")
                appendLine("耗时: ${listElapsedMs} ms")
                appendLine("fileCount: ${fileUris.size}")
                appendLine("前 $DISPLAY_LIMIT 个 URI:")
                fileUris.take(DISPLAY_LIMIT).forEachIndexed { index, uri ->
                    appendLine("  ${index + 1}. $uri")
                }
            }.trimEnd(),
        )
        onAppend("")

        onProgress("取 name+size：重新遍历 ${fileUris.size} 个 URI …")
        val metaStartedAt = System.nanoTime()
        val nameSizes = fileUris.mapIndexed { index, uri ->
            onProgress("name+size (${index + 1}/${fileUris.size})")
            queryNameAndSize(context.contentResolver, uri)
        }
        val metaElapsedMs = elapsedMs(metaStartedAt)

        onProgress("扫描完成：name+size ${metaElapsedMs} ms")
        onAppend(
            buildString {
                appendLine("=== 取 name+size 阶段（重新遍历，ContentResolver.query）===")
                appendLine("耗时: ${metaElapsedMs} ms")
                appendLine("fileCount: ${nameSizes.size}")
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

    /** list：只 listFiles + 用 isDirectory 决定递归；文件只记 uri，不碰 name/size */
    private fun listAllFileUris(
        dir: DocumentFile,
        directoryPath: String,
        onFileUri: (Uri) -> Unit,
        onProgress: (String) -> Unit,
    ) {
        val directory = directoryPath.ifEmpty { "/" }
        onProgress("listFiles() → $directory …")
        val children = dir.listFiles()
        onProgress("listFiles() → $directory → ${children.size} 项")

        for ((index, child) in children.withIndex()) {
            // isDirectory 会打 provider，但递归必须；刻意不读 name/size
            if (child.isDirectory) {
                val childPath = if (directoryPath.isEmpty()) {
                    "dir-$index"
                } else {
                    "$directoryPath/dir-$index"
                }
                onProgress("进入子目录 $childPath")
                listAllFileUris(
                    dir = child,
                    directoryPath = childPath,
                    onFileUri = onFileUri,
                    onProgress = onProgress,
                )
                continue
            }
            onFileUri(child.uri)
        }
    }

    private fun queryNameAndSize(contentResolver: ContentResolver, uri: Uri): NameSize {
        contentResolver.query(uri, nameSizeProjection, null, null, null)?.use { rows ->
            if (!rows.moveToFirst()) {
                throw IllegalStateException("query name/size 空 cursor: $uri")
            }
            val nameIndex = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIndex = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val name = rows.getString(nameIndex)
                ?: throw IllegalStateException("DISPLAY_NAME 为 null: $uri")
            val size = if (rows.isNull(sizeIndex)) {
                throw IllegalStateException("SIZE 为 null: $uri")
            } else {
                rows.getLong(sizeIndex)
            }
            return NameSize(name = name, size = size)
        } ?: throw IllegalStateException("query name/size 返回 null cursor: $uri")
    }

    private fun elapsedMs(startedAtNano: Long): Long =
        (System.nanoTime() - startedAtNano) / 1_000_000L
}
