package com.freewind.safscaninfo

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * ContentResolver.query：projection 一次指定要的列 → 一趟 cursor 同时拿到 name+size+mimeType。
 * 仅当前目录一层（不递归；原因见 README / MainActivity.SCAN_SCOPE_NOTE）。
 * mimeType 可直接判目录，无需像 DocumentFile 那样逐项 isDirectory()。
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
        ScanStepOutput.begin(onAppend, onProgress, "输出内存直取字段说明")
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

        ScanStepOutput.begin(onAppend, onProgress, "DocumentsContract.getTreeDocumentId()")
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)

        ScanStepOutput.begin(
            onAppend,
            onProgress,
            "query 阶段：一次 cursor 拿 name + size + mimeType",
        )
        val queryStartedAt = System.nanoTime()
        onProgress("query children → / …")
        val children = listChildren(
            contentResolver = context.contentResolver,
            treeUri = treeUri,
            parentDocumentId = rootDocumentId,
            directory = "/",
            onProgress = onProgress,
        )
        onProgress("query children → / → ${children.size} 项")
        val items = children.map { child ->
            ListedFile(
                name = child.name,
                size = child.size,
                mimeType = child.mimeType,
                uri = child.uri,
            )
        }
        val queryElapsedMs = elapsedMs(queryStartedAt)

        if (items.isEmpty()) {
            throw IllegalArgumentException("当前目录下没有任何子项")
        }

        onProgress("扫描完成：子项 ${items.size}，${queryElapsedMs} ms")
        onAppend(
            buildString {
                appendLine("=== query 阶段（一次拿到 name+size+mimeType，不过滤子目录）===")
                appendLine("耗时: ${queryElapsedMs} ms")
                appendLine("itemCount: ${items.size}")
                appendLine("前 $DISPLAY_LIMIT 个:")
                items.take(DISPLAY_LIMIT).forEachIndexed { index, file ->
                    appendLine("  ${index + 1}. name=${file.name}  size=${file.size}  mimeType=${file.mimeType}  uri=${file.uri}")
                }
            }.trimEnd(),
        )
    }

    private data class ListedFile(
        val name: String,
        val size: Long,
        val mimeType: String?,
        val uri: Uri,
    )

    private data class ListedChild(
        val documentId: String,
        val name: String,
        val size: Long,
        val mimeType: String?,
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
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                onProgress("list $directory [$index] $name")
                children += ListedChild(
                    documentId = documentId,
                    name = name,
                    size = size,
                    mimeType = mimeType,
                    uri = uri,
                )
            }
        } ?: throw IllegalStateException("query children 返回 null cursor: $directory")
        return children
    }

    private fun elapsedMs(startedAtNano: Long): Long =
        (System.nanoTime() - startedAtNano) / 1_000_000L
}
