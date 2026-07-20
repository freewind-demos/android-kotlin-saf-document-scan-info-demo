package com.freewind.safscaninfo

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * ContentResolver.query：projection 一次指定要的列 → 一趟 cursor 同时拿到 name+size。
 * 整树扫完计一次时；展示区各大步空行分隔，每类只显示前 5 条。
 */
object DocumentsContractQueryDirectoryScanner {

    private const val DISPLAY_LIMIT = 5

    private val documentProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    fun inspect(
        context: Context,
        treeUri: Uri,
        onProgress: (String) -> Unit = {},
        onAppend: (String) -> Unit = {},
    ) {
        onAppend(
            buildString {
                appendLine("=== 内存可直接拿到的信息（ContentResolver.query）===")
                appendLine("query 必须在 projection 里声明要查的列；一次 query 后 cursor 行内已有：")
                appendLine("- documentId")
                appendLine("- displayName（name）")
                appendLine("- mimeType（可判断 isDirectory）")
                appendLine("- size")
                appendLine("- uri（由 documentId + treeUri 在内存里拼出，不再打 provider）")
                appendLine("因此 name+size 与列目录同属一次操作，不必再拆第二趟。")
            }.trimEnd(),
        )
        onAppend("")

        onProgress("DocumentsContract.getTreeDocumentId() …")
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)

        onProgress("query 阶段：整树一次拿 name+size …")
        val queryStartedAt = System.nanoTime()
        val files = mutableListOf<ListedFile>()
        listAllFiles(
            contentResolver = context.contentResolver,
            treeUri = treeUri,
            parentDocumentId = rootDocumentId,
            directoryPath = "",
            onFile = { files += it },
            onProgress = onProgress,
        )
        val queryElapsedMs = elapsedMs(queryStartedAt)

        if (files.isEmpty()) {
            throw IllegalArgumentException("目录下未找到任何文件")
        }

        onProgress("扫描完成：文件 ${files.size}，${queryElapsedMs} ms")
        onAppend(
            buildString {
                appendLine("=== query 阶段（一次拿到 name+size）===")
                appendLine("耗时: ${queryElapsedMs} ms")
                appendLine("fileCount: ${files.size}")
                appendLine("前 $DISPLAY_LIMIT 个:")
                files.take(DISPLAY_LIMIT).forEachIndexed { index, file ->
                    appendLine("  ${index + 1}. name=${file.name}  size=${file.size}  uri=${file.uri}")
                }
            }.trimEnd(),
        )
    }

    private data class ListedFile(
        val name: String,
        val size: Long,
        val uri: Uri,
    )

    private fun listAllFiles(
        contentResolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        directoryPath: String,
        onFile: (ListedFile) -> Unit,
        onProgress: (String) -> Unit,
    ) {
        val directory = directoryPath.ifEmpty { "/" }
        onProgress("query children → $directory …")
        val children = listChildren(
            contentResolver = contentResolver,
            treeUri = treeUri,
            parentDocumentId = parentDocumentId,
            directory = directory,
            onProgress = onProgress,
        )
        onProgress("query children → $directory → ${children.size} 项")

        for (child in children) {
            val childPath = if (directoryPath.isEmpty()) {
                child.name
            } else {
                "$directoryPath/${child.name}"
            }
            if (child.isDirectory) {
                onProgress("进入子目录 $childPath")
                listAllFiles(
                    contentResolver = contentResolver,
                    treeUri = treeUri,
                    parentDocumentId = child.documentId,
                    directoryPath = childPath,
                    onFile = onFile,
                    onProgress = onProgress,
                )
                continue
            }
            onFile(
                ListedFile(
                    name = child.name,
                    size = child.size,
                    uri = child.uri,
                ),
            )
        }
    }

    private data class ListedChild(
        val documentId: String,
        val name: String,
        val size: Long,
        val isDirectory: Boolean,
        val uri: Uri,
    )

    private fun listChildren(
        contentResolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        directory: String,
        onProgress: (String) -> Unit,
    ): List<ListedChild> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val children = mutableListOf<ListedChild>()
        contentResolver.query(childrenUri, documentProjection, null, null, null)?.use { rows ->
            val idIndex = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            var index = 0
            while (rows.moveToNext()) {
                index += 1
                val documentId = rows.getString(idIndex)
                    ?: throw IllegalStateException("DOCUMENT_ID 为 null ($directory #$index)")
                val name = rows.getString(nameIndex)
                    ?: throw IllegalStateException("DISPLAY_NAME 为 null ($directory #$index)")
                val mimeType = rows.getString(mimeIndex)
                val size = if (rows.isNull(sizeIndex)) {
                    0L
                } else {
                    rows.getLong(sizeIndex)
                }
                val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                onProgress("list $directory [$index] $name")
                children += ListedChild(
                    documentId = documentId,
                    name = name,
                    size = size,
                    isDirectory = isDirectory,
                    uri = uri,
                )
            }
        } ?: throw IllegalStateException("query children 返回 null cursor: $directory")
        return children
    }

    private fun elapsedMs(startedAtNano: Long): Long =
        (System.nanoTime() - startedAtNano) / 1_000_000L
}
