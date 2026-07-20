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
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        onProgress("DocumentsContract.getTreeDocumentId() → $rootDocumentId")
        val listSections = mutableListOf<String>()
        val accessCandidates = mutableListOf<FoundFile>()
        listAllDirectories(
            contentResolver = context.contentResolver,
            treeUri = treeUri,
            parentDocumentId = rootDocumentId,
            directoryPath = "",
            listSections = listSections,
            accessCandidates = accessCandidates,
            onProgress = onProgress,
        )
        if (accessCandidates.isEmpty()) {
            throw IllegalArgumentException("目录下未找到任何文件")
        }
        val accessSections = accessCandidates.mapIndexed { index, foundFile ->
            onProgress("access (${index + 1}/${accessCandidates.size}) ${foundFile.path} …")
            AccessSection(
                path = foundFile.path,
                uri = foundFile.child.uri.toString(),
                fields = readAccessFields(context.contentResolver, foundFile.child.uri, onProgress),
            )
        }
        onProgress("扫描完成")
        return formatInspectReport(
            title = "ContentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(...)) (full tree) + first-$ACCESS_FILE_LIMIT-files access",
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

    /** 整棵树 list；顺带记下前 ACCESS_FILE_LIMIT 个文件供后续单独 access */
    private fun listAllDirectories(
        contentResolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        directoryPath: String,
        listSections: MutableList<String>,
        accessCandidates: MutableList<FoundFile>,
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
        listSections += formatListSection(
            listApi = "ContentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(...), DocumentsContract.Document.COLUMN_*, null, null, null)",
            listApiResult = children.size,
            directory = directory,
            items = children.map { readListItem(it) },
        )
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
                    listSections = listSections,
                    accessCandidates = accessCandidates,
                    onProgress = onProgress,
                )
                continue
            }
            if (accessCandidates.size < ACCESS_FILE_LIMIT) {
                onProgress("access 候选 (${accessCandidates.size + 1}/$ACCESS_FILE_LIMIT)：$childPath")
                accessCandidates += FoundFile(path = childPath, child = child)
            }
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
        onProgress: (String) -> Unit,
    ): List<Pair<String, Any?>> {
        val fields = mutableListOf<Pair<String, Any?>>()
        onProgress("access DocumentsContract.getDocumentId($fileUri) …")
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
        onProgress("buildChildDocumentsUriUsingTree → $childrenUri")
        val children = mutableListOf<ListedChild>()
        onProgress("ContentResolver.query($childrenUri) …")
        contentResolver.query(childrenUri, documentProjection, null, null, null)?.use { rows ->
            onProgress("query cursor opened，count=${rows.count}，开始 moveToNext")
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
                onProgress("cursor $directory [$index] document_id=$documentId")
                val displayName = rows.getString(nameIndex).orEmpty()
                onProgress("cursor $directory [$index] display_name=$displayName")
                val mimeType = rows.getString(mimeIndex)
                onProgress("cursor $directory [$index] mime_type=$mimeType")
                val size = if (rows.isNull(sizeIndex)) 0L else rows.getLong(sizeIndex)
                onProgress("cursor $directory [$index] size=$size")
                val lastModified = readLongColumn(rows, modifiedIndex)
                onProgress("cursor $directory [$index] last_modified=$lastModified")
                val flags = readIntColumn(rows, flagsIndex)
                onProgress("cursor $directory [$index] flags=$flags")
                val summary = readStringColumn(rows, summaryIndex)
                onProgress("cursor $directory [$index] summary=$summary")
                val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                onProgress("cursor $directory [$index] uri=$uri")
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
            onProgress("cursor $directory 读完，共 $index 行")
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
