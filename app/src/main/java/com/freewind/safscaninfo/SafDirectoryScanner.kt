package com.freewind.safscaninfo

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

enum class ScanMethod {
    DOCUMENT_FILE,
    DOCUMENTS_CONTRACT_QUERY,
}

object SafDirectoryScanner {

    private val documentProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SUMMARY,
    )

    fun inspectWithDocumentFile(
        context: Context,
        treeUri: Uri,
    ): String {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("DocumentFile.fromTreeUri() 无法打开目录")
        val listSections = mutableListOf<String>()
        val firstFile = findFirstFileWithDocumentFile(root, directoryPath = "", listSections = listSections)
            ?: throw IllegalArgumentException("目录下未找到任何文件")
        val accessFields = readDocumentFileAccessFields(context, firstFile.file)
        return formatInspectReport(
            title = "DocumentFile.listFiles() + first-file access",
            firstFilePath = firstFile.path,
            firstFileUri = firstFile.file.uri.toString(),
            listSections = listSections,
            accessFields = accessFields,
        )
    }

    fun inspectWithDocumentsContractQuery(
        context: Context,
        treeUri: Uri,
    ): String {
        val listSections = mutableListOf<String>()
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val firstFile = findFirstFileWithDocumentsContractQuery(
            contentResolver = context.contentResolver,
            treeUri = treeUri,
            parentDocumentId = rootDocumentId,
            directoryPath = "",
            listSections = listSections,
        ) ?: throw IllegalArgumentException("目录下未找到任何文件")
        val accessFields = readDocumentsContractAccessFields(context.contentResolver, firstFile.child.uri)
        return formatInspectReport(
            title = "ContentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(...)) + first-file access",
            firstFilePath = firstFile.path,
            firstFileUri = firstFile.child.uri.toString(),
            listSections = listSections,
            accessFields = accessFields,
        )
    }

    private data class FirstDocumentFile(
        val path: String,
        val file: DocumentFile,
    )

    private data class FirstListedFile(
        val path: String,
        val child: ListedChild,
    )

    private fun findFirstFileWithDocumentFile(
        dir: DocumentFile,
        directoryPath: String,
        listSections: MutableList<String>,
    ): FirstDocumentFile? {
        val children = dir.listFiles()
        listSections += formatListSection(
            listApi = "DocumentFile.listFiles()",
            listApiResult = children.size,
            directory = directoryPath.ifEmpty { "/" },
            items = children.map { readDocumentFileListItem(it) },
        )
        val sortedChildren = children.sortedBy { it.name?.lowercase().orEmpty() }
        for (child in sortedChildren) {
            val name = child.name ?: continue
            val childPath = if (directoryPath.isEmpty()) name else "$directoryPath/$name"
            if (child.isDirectory) {
                findFirstFileWithDocumentFile(child, childPath, listSections)?.let { return it }
                continue
            }
            return FirstDocumentFile(path = childPath, file = child)
        }
        return null
    }

    private fun findFirstFileWithDocumentsContractQuery(
        contentResolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        directoryPath: String,
        listSections: MutableList<String>,
    ): FirstListedFile? {
        val children = listChildren(contentResolver, treeUri, parentDocumentId)
        listSections += formatListSection(
            listApi = "ContentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(...), DocumentsContract.Document.COLUMN_*, null, null, null)",
            listApiResult = children.size,
            directory = directoryPath.ifEmpty { "/" },
            items = children.map { readListedChildListItem(it) },
        )
        val sortedChildren = children.sortedBy { it.displayName.lowercase() }
        for (child in sortedChildren) {
            val childPath = if (directoryPath.isEmpty()) {
                child.displayName
            } else {
                "$directoryPath/${child.displayName}"
            }
            if (child.isDirectory) {
                findFirstFileWithDocumentsContractQuery(
                    contentResolver = contentResolver,
                    treeUri = treeUri,
                    parentDocumentId = child.documentId,
                    directoryPath = childPath,
                    listSections = listSections,
                )?.let { return it }
                continue
            }
            return FirstListedFile(path = childPath, child = child)
        }
        return null
    }

    /** listFiles() 返回元素：属性原名；方法用 method() 作 key */
    private fun readDocumentFileListItem(file: DocumentFile): List<Pair<String, Any?>> = listOf(
        "name" to file.name,
        "type" to file.type,
        "uri" to file.uri.toString(),
        "isDirectory" to file.isDirectory,
        "isFile" to file.isFile,
        "exists()" to file.exists(),
        "canRead()" to file.canRead(),
        "canWrite()" to file.canWrite(),
        "length()" to file.length(),
        "lastModified()" to file.lastModified(),
        "parentFile" to file.parentFile?.uri?.toString(),
    )

    /** 第一个文件：方法名() 或 API 全名作 key，返回值作 value */
    private fun readDocumentFileAccessFields(
        context: Context,
        listedFile: DocumentFile,
    ): List<Pair<String, Any?>> {
        val uri = listedFile.uri
        val file = DocumentFile.fromSingleUri(context, uri)
            ?: throw IllegalArgumentException("DocumentFile.fromSingleUri() 无法打开文件")
        return listOf(
            "DocumentFile.fromSingleUri(context, uri)" to file.javaClass.simpleName,
            "name" to file.name,
            "type" to file.type,
            "uri" to file.uri.toString(),
            "isDirectory" to file.isDirectory,
            "isFile" to file.isFile,
            "exists()" to file.exists(),
            "canRead()" to file.canRead(),
            "canWrite()" to file.canWrite(),
            "length()" to file.length(),
            "lastModified()" to file.lastModified(),
            "parentFile" to file.parentFile?.uri?.toString(),
        )
    }

    private fun readListedChildListItem(child: ListedChild): List<Pair<String, Any?>> {
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

    private fun readDocumentsContractAccessFields(
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
}

private fun formatInspectReport(
    title: String,
    firstFilePath: String,
    firstFileUri: String,
    listSections: List<String>,
    accessFields: List<Pair<String, Any?>>,
): String = buildString {
    appendLine(title)
    appendLine()
    appendLine("firstFilePath: $firstFilePath")
    appendLine("firstFileUri: $firstFileUri")
    appendLine()
    appendLine("=== list ===")
    listSections.forEach { section ->
        appendLine()
        append(section)
    }
    appendLine()
    appendLine("=== access (first file only) ===")
    appendLine()
    accessFields.forEach { (key, value) ->
        appendLine("$key: $value")
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