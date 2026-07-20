package com.freewind.safscaninfo

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract

/**
 * ContentResolver.query + cursor：可 moveToNext 逐行拿 → list 阶段每拿到一项只报名字。
 * 扫完整棵树后输出摘要 + 前 5 个文件的 cursor 列；再单独 query 这 5 个并 append 详情。
 */
object DocumentsContractQueryDirectoryScanner {

    private const val ACCESS_FILE_LIMIT = 5

    private val documentProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SUMMARY,
    )

    fun inspect(
        context: Context,
        treeUri: Uri,
        onProgress: (String) -> Unit = {},
        onAppend: (String) -> Unit = {},
    ) {
        onProgress("DocumentsContract.getTreeDocumentId() …")
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)

        var directoryCount = 0
        var fileCount = 0
        val firstFiles = mutableListOf<FoundFile>()

        listAllDirectories(
            contentResolver = context.contentResolver,
            treeUri = treeUri,
            parentDocumentId = rootDocumentId,
            directoryPath = "",
            directoryCount = { directoryCount += 1 },
            onFile = { path, child ->
                fileCount += 1
                if (firstFiles.size < ACCESS_FILE_LIMIT) {
                    firstFiles += FoundFile(
                        path = path,
                        child = child,
                        listFields = snapshotListFields(child),
                    )
                }
            },
            onProgress = onProgress,
        )

        if (fileCount == 0) {
            throw IllegalArgumentException("目录下未找到任何文件")
        }

        onProgress("list 完成：子目录 $directoryCount，文件 $fileCount")
        onAppend(
            buildString {
                appendLine("=== list (ContentResolver.query cursor，整树) ===")
                appendLine("listApi: ContentResolver.query(buildChildDocumentsUriUsingTree) — cursor 逐行 moveToNext")
                appendLine("directoryCount: $directoryCount")
                appendLine("fileCount: $fileCount")
                appendLine()
                appendLine("=== first $ACCESS_FILE_LIMIT files from list cursor ===")
                firstFiles.forEachIndexed { index, found ->
                    appendLine()
                    appendLine("--- list file ${index + 1} ---")
                    appendLine("path: ${found.path}")
                    found.listFields.forEach { (key, value) ->
                        appendLine("$key: $value")
                    }
                }
            }.trimEnd(),
        )

        onAppend("")
        onAppend("=== access (query document uri，仅前 ${firstFiles.size} 个) ===")
        firstFiles.forEachIndexed { index, found ->
            onProgress("access (${index + 1}/${firstFiles.size}) ${found.path} …")
            val accessFields = readAccessFields(context.contentResolver, found.child.uri, onProgress)
            onAppend(
                buildString {
                    appendLine()
                    appendLine("--- access file ${index + 1} ---")
                    appendLine("path: ${found.path}")
                    appendLine("uri: ${found.child.uri}")
                    accessFields.forEach { (key, value) ->
                        appendLine("$key: $value")
                    }
                }.trimEnd(),
            )
        }
        onProgress("扫描完成")
    }

    private data class FoundFile(
        val path: String,
        val child: ListedChild,
        val listFields: List<Pair<String, Any?>>,
    )

    private data class ListedChild(
        val documentId: String,
        val displayName: String,
        val mimeType: String?,
        val size: Long,
        val isDirectory: Boolean,
        val lastModified: Long?,
        val flags: Int?,
        val summary: String?,
        val uri: Uri,
    )

    private fun listAllDirectories(
        contentResolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        directoryPath: String,
        directoryCount: () -> Unit,
        onFile: (path: String, child: ListedChild) -> Unit,
        onProgress: (String) -> Unit,
    ) {
        val directory = directoryPath.ifEmpty { "/" }
        if (directoryPath.isNotEmpty()) {
            directoryCount()
        }
        onProgress("query children → $directory …")
        val children = listChildren(
            contentResolver = contentResolver,
            treeUri = treeUri,
            parentDocumentId = parentDocumentId,
            directory = directory,
            onProgress = onProgress,
        )
        onProgress("query children → $directory → ${children.size} 项")

        val sortedChildren = children.sortedBy { it.displayName.lowercase() }
        for (child in sortedChildren) {
            val childPath = if (directoryPath.isEmpty()) {
                child.displayName
            } else {
                "$directoryPath/${child.displayName}"
            }
            if (child.isDirectory) {
                onProgress("进入子目录 $childPath")
                listAllDirectories(
                    contentResolver = contentResolver,
                    treeUri = treeUri,
                    parentDocumentId = child.documentId,
                    directoryPath = childPath,
                    directoryCount = directoryCount,
                    onFile = onFile,
                    onProgress = onProgress,
                )
                continue
            }
            onFile(childPath, child)
        }
    }

    private fun snapshotListFields(child: ListedChild): List<Pair<String, Any?>> = listOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID to child.documentId,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME to child.displayName,
        DocumentsContract.Document.COLUMN_MIME_TYPE to child.mimeType,
        DocumentsContract.Document.COLUMN_SIZE to child.size,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED to child.lastModified,
        DocumentsContract.Document.COLUMN_FLAGS to child.flags,
        DocumentsContract.Document.COLUMN_SUMMARY to child.summary,
        "DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)" to child.uri.toString(),
    )

    private fun readAccessFields(
        contentResolver: ContentResolver,
        fileUri: Uri,
        onProgress: (String) -> Unit,
    ): List<Pair<String, Any?>> {
        val fields = mutableListOf<Pair<String, Any?>>()
        onProgress("access getDocumentId($fileUri) …")
        val documentId = DocumentsContract.getDocumentId(fileUri)
        onProgress("access getDocumentId=$documentId")
        fields += "DocumentsContract.getDocumentId(uri)" to documentId
        fields += queryDocumentAccessFields(contentResolver, fileUri, onProgress)
        return fields
    }

    private fun queryDocumentAccessFields(
        contentResolver: ContentResolver,
        documentUri: Uri,
        onProgress: (String) -> Unit,
    ): List<Pair<String, Any?>> {
        val queryApi =
            "ContentResolver.query(uri, DocumentsContract.Document.COLUMN_*, null, null, null)"
        val fields = mutableListOf<Pair<String, Any?>>()
        onProgress("access query($documentUri) …")
        contentResolver.query(documentUri, documentProjection, null, null, null)?.use { rows ->
            onProgress("access query.getCount()=${rows.count}")
            fields += "$queryApi.getCount()" to rows.count
            if (!rows.moveToFirst()) {
                return fields
            }
            documentProjection.forEach { column ->
                val index = rows.getColumnIndex(column)
                val value: Any? = when {
                    index < 0 -> null
                    rows.isNull(index) -> null
                    column == DocumentsContract.Document.COLUMN_SIZE ||
                        column == DocumentsContract.Document.COLUMN_LAST_MODIFIED -> rows.getLong(index)
                    column == DocumentsContract.Document.COLUMN_FLAGS -> rows.getInt(index)
                    else -> rows.getString(index)
                }
                onProgress("access $column=$value")
                fields += column to value
            }
        } ?: run {
            onProgress("access query → null cursor")
            fields += "$queryApi.getCount()" to null
        }
        return fields
    }

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
            val modifiedIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val flagsIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            val summaryIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_SUMMARY)
            var index = 0
            while (rows.moveToNext()) {
                index += 1
                val documentId = rows.getString(idIndex).orEmpty()
                val displayName = rows.getString(nameIndex).orEmpty()
                val mimeType = rows.getString(mimeIndex)
                val size = if (rows.isNull(sizeIndex)) 0L else rows.getLong(sizeIndex)
                val lastModified = readLongColumn(rows, modifiedIndex)
                val flags = readIntColumn(rows, flagsIndex)
                val summary = readStringColumn(rows, summaryIndex)
                val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                // 可逐行拿：progress 只报当前项名字，不刷详细字段
                onProgress("list $directory [$index] $displayName")
                children += ListedChild(
                    documentId = documentId,
                    displayName = displayName,
                    mimeType = mimeType,
                    size = size,
                    isDirectory = isDirectory,
                    lastModified = lastModified,
                    flags = flags,
                    summary = summary,
                    uri = uri,
                )
            }
        } ?: run {
            onProgress("query → null cursor ($directory)")
        }
        return children
    }

    private fun readLongColumn(rows: Cursor, index: Int): Long? {
        if (index < 0 || rows.isNull(index)) return null
        return rows.getLong(index)
    }

    private fun readIntColumn(rows: Cursor, index: Int): Int? {
        if (index < 0 || rows.isNull(index)) return null
        return rows.getInt(index)
    }

    private fun readStringColumn(rows: Cursor, index: Int): String? {
        if (index < 0 || rows.isNull(index)) return null
        return rows.getString(index)
    }
}
