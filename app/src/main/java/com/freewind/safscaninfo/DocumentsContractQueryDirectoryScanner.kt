package com.freewind.safscaninfo

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract

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
    ): String {
        onProgress("DocumentsContract.getTreeDocumentId() …")
        val listSections = mutableListOf<String>()
        val files = mutableListOf<FoundFile>()
        collectFiles(
            contentResolver = context.contentResolver,
            treeUri = treeUri,
            parentDocumentId = DocumentsContract.getTreeDocumentId(treeUri),
            directoryPath = "",
            listSections = listSections,
            found = files,
            limit = ACCESS_FILE_LIMIT,
            onProgress = onProgress,
        )
        if (files.isEmpty()) {
            throw IllegalArgumentException("目录下未找到任何文件")
        }
        val accessSections = files.mapIndexed { index, foundFile ->
            onProgress("读取文件 access (${index + 1}/${files.size})：${foundFile.path}")
            AccessSection(
                path = foundFile.path,
                uri = foundFile.child.uri.toString(),
                fields = readAccessFields(context.contentResolver, foundFile.child.uri),
            )
        }
        onProgress("扫描完成")
        return formatInspectReport(
            title = "ContentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(...)) + first-$ACCESS_FILE_LIMIT-files access",
            listSections = listSections,
            accessSections = accessSections,
        )
    }

    private data class FoundFile(
        val path: String,
        val child: ListedChild,
    )

    private data class AccessSection(
        val path: String,
        val uri: String,
        val fields: List<Pair<String, Any?>>,
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

    private fun collectFiles(
        contentResolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        directoryPath: String,
        listSections: MutableList<String>,
        found: MutableList<FoundFile>,
        limit: Int,
        onProgress: (String) -> Unit,
    ) {
        if (found.size >= limit) return
        val directory = directoryPath.ifEmpty { "/" }
        onProgress("ContentResolver.query(buildChildDocumentsUriUsingTree) → $directory …")
        val children = listChildren(contentResolver, treeUri, parentDocumentId)
        onProgress("ContentResolver.query() → $directory → ${children.size} 项")
        listSections += formatListSection(
            listApi = "ContentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(...), DocumentsContract.Document.COLUMN_*, null, null, null)",
            listApiResult = children.size,
            directory = directoryPath.ifEmpty { "/" },
            items = children.map { readListItem(it) },
        )
        val sortedChildren = children.sortedBy { it.displayName.lowercase() }
        for (child in sortedChildren) {
            if (found.size >= limit) return
            val childPath = if (directoryPath.isEmpty()) {
                child.displayName
            } else {
                "$directoryPath/${child.displayName}"
            }
            if (child.isDirectory) {
                onProgress("进入子目录 $childPath")
                collectFiles(
                    contentResolver = contentResolver,
                    treeUri = treeUri,
                    parentDocumentId = child.documentId,
                    directoryPath = childPath,
                    listSections = listSections,
                    found = found,
                    limit = limit,
                    onProgress = onProgress,
                )
                continue
            }
            onProgress("找到文件 (${found.size + 1}/$limit)：$childPath")
            found += FoundFile(path = childPath, child = child)
        }
    }

    private fun readListItem(child: ListedChild): List<Pair<String, Any?>> {
        val fields = mutableListOf<Pair<String, Any?>>(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to child.documentId,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to child.displayName,
            DocumentsContract.Document.COLUMN_MIME_TYPE to child.mimeType,
            DocumentsContract.Document.COLUMN_SIZE to child.size,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED to child.lastModified,
            DocumentsContract.Document.COLUMN_FLAGS to child.flags,
            DocumentsContract.Document.COLUMN_SUMMARY to child.summary,
        )
        fields += "DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)" to child.uri.toString()
        return fields
    }

    private fun readAccessFields(
        contentResolver: ContentResolver,
        fileUri: Uri,
    ): List<Pair<String, Any?>> {
        val fields = mutableListOf<Pair<String, Any?>>()
        fields += "DocumentsContract.getDocumentId(uri)" to DocumentsContract.getDocumentId(fileUri)
        fields += queryDocumentAccessFields(contentResolver, fileUri)
        return fields
    }

    private fun queryDocumentAccessFields(
        contentResolver: ContentResolver,
        documentUri: Uri,
    ): List<Pair<String, Any?>> {
        val queryApi =
            "ContentResolver.query(uri, DocumentsContract.Document.COLUMN_*, null, null, null)"
        val fields = mutableListOf<Pair<String, Any?>>()
        contentResolver.query(documentUri, documentProjection, null, null, null)?.use { rows ->
            fields += "$queryApi.getCount()" to rows.count
            if (!rows.moveToFirst()) {
                return fields
            }
            documentProjection.forEach { column ->
                val index = rows.getColumnIndex(column)
                if (index < 0) {
                    fields += column to null
                    return@forEach
                }
                if (rows.isNull(index)) {
                    fields += column to null
                    return@forEach
                }
                fields += when (column) {
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    -> column to rows.getLong(index)
                    DocumentsContract.Document.COLUMN_FLAGS -> column to rows.getInt(index)
                    else -> column to rows.getString(index)
                }
            }
        } ?: run {
            fields += "$queryApi.getCount()" to null
        }
        return fields
    }

    private fun listChildren(
        contentResolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
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
            while (rows.moveToNext()) {
                val documentId = rows.getString(idIndex).orEmpty()
                val displayName = rows.getString(nameIndex).orEmpty()
                val mimeType = rows.getString(mimeIndex)
                val size = if (rows.isNull(sizeIndex)) 0L else rows.getLong(sizeIndex)
                val lastModified = readLongColumn(rows, modifiedIndex)
                val flags = readIntColumn(rows, flagsIndex)
                val summary = readStringColumn(rows, summaryIndex)
                val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
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

    private fun formatInspectReport(
        title: String,
        listSections: List<String>,
        accessSections: List<AccessSection>,
    ): String = buildString {
        appendLine(title)
        appendLine()
        appendLine("accessFileCount: ${accessSections.size}")
        appendLine()
        appendLine("=== list ===")
        listSections.forEach { section ->
            appendLine()
            append(section)
        }
        appendLine()
        appendLine("=== access (first ${accessSections.size} files) ===")
        accessSections.forEachIndexed { index, section ->
            appendLine()
            appendLine("--- file ${index + 1} ---")
            appendLine("path: ${section.path}")
            appendLine("uri: ${section.uri}")
            section.fields.forEach { (key, value) ->
                appendLine("$key: $value")
            }
        }
    }

    private fun formatListSection(
        listApi: String,
        listApiResult: Int,
        directory: String,
        items: List<List<Pair<String, Any?>>>,
    ): String = buildString {
        appendLine("$listApi: $listApiResult")
        appendLine("directory: $directory")
        items.forEachIndexed { index, item ->
            appendLine()
            appendLine("[item $index]")
            item.forEach { (key, value) ->
                appendLine("$key: $value")
            }
        }
    }
}
