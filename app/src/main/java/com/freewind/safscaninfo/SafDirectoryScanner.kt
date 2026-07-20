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

/** DocumentFile.listFiles() 递归扫描，只读 DocumentFile 自身 API 字段 */
data class DocumentFileScanResult(
    val sortKey: String,
    val name: String?,
    val type: String?,
    val uri: String,
    val exists: Boolean,
    val isDirectory: Boolean,
    val isFile: Boolean,
    val canRead: Boolean,
    val canWrite: Boolean,
    val length: Long,
    val lastModified: Long,
    val parentFileUri: String?,
) {
    fun formatLines(): List<String> = listOf(
        fieldLine("name", name),
        fieldLine("type", type),
        fieldLine("uri", uri),
        fieldLine("exists", exists),
        fieldLine("isDirectory", isDirectory),
        fieldLine("isFile", isFile),
        fieldLine("canRead", canRead),
        fieldLine("canWrite", canWrite),
        fieldLine("length", length),
        fieldLine("lastModified", lastModified),
        fieldLine("parentFile", parentFileUri),
    )
}

/** ContentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree) 递归扫描 */
data class DocumentsContractQueryScanResult(
    val sortKey: String,
    val documentId: String,
    val displayName: String,
    val mimeType: String?,
    val size: Long,
    val lastModified: Long?,
    val flags: Int?,
    val summary: String?,
    val uri: String,
) {
    fun formatLines(): List<String> = listOf(
        fieldLine(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId),
        fieldLine(DocumentsContract.Document.COLUMN_DISPLAY_NAME, displayName),
        fieldLine(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType),
        fieldLine(DocumentsContract.Document.COLUMN_SIZE, size),
        fieldLine(DocumentsContract.Document.COLUMN_LAST_MODIFIED, lastModified),
        fieldLine(DocumentsContract.Document.COLUMN_FLAGS, flags),
        fieldLine(DocumentsContract.Document.COLUMN_SUMMARY, summary),
        fieldLine("uri", uri),
    )
}

/** SAF 目录扫描：DocumentFile 与 DocumentsContract 两种独立路径 */
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

    fun scanWithDocumentFile(
        context: Context,
        treeUri: Uri,
    ): List<DocumentFileScanResult> {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("DocumentFile.fromTreeUri 无法打开目录")
        val results = mutableListOf<DocumentFileScanResult>()
        walkDocumentFileTree(root, sortPrefix = "", onFileFound = { results += it })
        return results.sortedBy { it.sortKey.lowercase() }
    }

    fun scanWithDocumentsContractQuery(
        context: Context,
        treeUri: Uri,
    ): List<DocumentsContractQueryScanResult> {
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val results = mutableListOf<DocumentsContractQueryScanResult>()
        walkDocumentsContractQueryTree(
            contentResolver = context.contentResolver,
            treeUri = treeUri,
            parentDocumentId = rootDocumentId,
            sortPrefix = "",
            onFileFound = { results += it },
        )
        return results.sortedBy { it.sortKey.lowercase() }
    }

    private fun walkDocumentFileTree(
        dir: DocumentFile,
        sortPrefix: String,
        onFileFound: (DocumentFileScanResult) -> Unit,
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val nextSortKey = if (sortPrefix.isEmpty()) name else "$sortPrefix/$name"
            if (child.isDirectory) {
                walkDocumentFileTree(child, nextSortKey, onFileFound)
                continue
            }
            onFileFound(
                DocumentFileScanResult(
                    sortKey = nextSortKey,
                    name = child.name,
                    type = child.type,
                    uri = child.uri.toString(),
                    exists = child.exists(),
                    isDirectory = child.isDirectory,
                    isFile = child.isFile,
                    canRead = child.canRead(),
                    canWrite = child.canWrite(),
                    length = child.length(),
                    lastModified = child.lastModified(),
                    parentFileUri = child.parentFile?.uri?.toString(),
                ),
            )
        }
    }

    private fun walkDocumentsContractQueryTree(
        contentResolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        sortPrefix: String,
        onFileFound: (DocumentsContractQueryScanResult) -> Unit,
    ) {
        val children = listChildren(contentResolver, treeUri, parentDocumentId)
        children.forEach { child ->
            if (child.isDirectory) {
                val nextSortKey = if (sortPrefix.isEmpty()) {
                    child.displayName
                } else {
                    "$sortPrefix/${child.displayName}"
                }
                walkDocumentsContractQueryTree(
                    contentResolver = contentResolver,
                    treeUri = treeUri,
                    parentDocumentId = child.documentId,
                    sortPrefix = nextSortKey,
                    onFileFound = onFileFound,
                )
                return@forEach
            }
            val sortKey = if (sortPrefix.isEmpty()) {
                child.displayName
            } else {
                "$sortPrefix/${child.displayName}"
            }
            onFileFound(
                DocumentsContractQueryScanResult(
                    sortKey = sortKey,
                    documentId = child.documentId,
                    displayName = child.displayName,
                    mimeType = child.mimeType,
                    size = child.size,
                    lastModified = child.lastModified,
                    flags = child.flags,
                    summary = child.summary,
                    uri = child.uri.toString(),
                ),
            )
        }
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

fun formatDocumentFileScanResult(files: List<DocumentFileScanResult>): String {
    if (files.isEmpty()) {
        return "未找到任何文件。\n\n请确认目录非空，且已授予读取权限。"
    }
    return buildString {
        appendLine("DocumentFile.listFiles()")
        appendLine("count: ${files.size}")
        appendLine()
        append(files.joinToString("\n\n") { it.formatLines().joinToString("\n") })
    }
}

fun formatDocumentsContractQueryScanResult(files: List<DocumentsContractQueryScanResult>): String {
    if (files.isEmpty()) {
        return "未找到任何文件。\n\n请确认目录非空，且已授予读取权限。"
    }
    return buildString {
        appendLine("ContentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(...))")
        appendLine("count: ${files.size}")
        appendLine()
        append(files.joinToString("\n\n") { it.formatLines().joinToString("\n") })
    }
}

private fun fieldLine(field: String, value: Any?): String = "$field: $value"
