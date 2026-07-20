package com.freewind.safscaninfo

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 单个文件在 SAF 扫描过程中能读到的全部字段 */
data class ScannedFileInfo(
    val relativePath: String,
    val rootUri: String,
    val parentUri: String,
    val cursorDocumentId: String,
    val cursorDisplayName: String,
    val cursorMimeType: String?,
    val cursorSizeBytes: Long,
    val cursorIsDirectory: Boolean,
    val cursorLastModifiedMs: Long?,
    val cursorFlags: Int?,
    val cursorSummary: String?,
    val cursorUri: String,
    val contractDocumentId: String?,
    val documentFileName: String?,
    val documentFileType: String?,
    val documentFileUri: String?,
    val documentFileExists: Boolean?,
    val documentFileIsDirectory: Boolean?,
    val documentFileIsFile: Boolean?,
    val documentFileCanRead: Boolean?,
    val documentFileCanWrite: Boolean?,
    val documentFileLength: Long?,
    val documentFileLastModifiedMs: Long?,
    val documentFileParentUri: String?,
) {
    /** 格式化成「一行一个字段，文件之间空一行」的纯文本 */
    fun formatLines(): List<String> {
        val lines = mutableListOf<String>()
        lines += "文件名: ${cursorDisplayName.ifBlank { relativePath }}"
        lines += "relativePath: $relativePath"
        lines += "rootUri: $rootUri"
        lines += "parentUri: $parentUri"
        lines += "--- DocumentsContract Cursor（music-player 扫描用的 4 列 + 扩展列）---"
        lines += "documentId: $cursorDocumentId"
        lines += "displayName: $cursorDisplayName"
        lines += "mimeType: ${cursorMimeType ?: "(null)"}"
        lines += "sizeBytes: $cursorSizeBytes"
        lines += "isDirectory: $cursorIsDirectory"
        lines += "lastModified: ${formatTime(cursorLastModifiedMs)}"
        lines += "flags: ${cursorFlags?.toString() ?: "(null)"} ${describeFlags(cursorFlags)}"
        lines += "summary: ${cursorSummary ?: "(null)"}"
        lines += "uri: $cursorUri"
        lines += "DocumentsContract.getDocumentId(uri): ${contractDocumentId ?: "(null)"}"
        lines += "--- DocumentFile（单文件 API 额外可读字段）---"
        lines += "DocumentFile.name: ${documentFileName ?: "(null)"}"
        lines += "DocumentFile.type: ${documentFileType ?: "(null)"}"
        lines += "DocumentFile.uri: ${documentFileUri ?: "(null)"}"
        lines += "DocumentFile.exists: ${documentFileExists?.toString() ?: "(null)"}"
        lines += "DocumentFile.isDirectory: ${documentFileIsDirectory?.toString() ?: "(null)"}"
        lines += "DocumentFile.isFile: ${documentFileIsFile?.toString() ?: "(null)"}"
        lines += "DocumentFile.canRead: ${documentFileCanRead?.toString() ?: "(null)"}"
        lines += "DocumentFile.canWrite: ${documentFileCanWrite?.toString() ?: "(null)"}"
        lines += "DocumentFile.length: ${documentFileLength?.toString() ?: "(null)"}"
        lines += "DocumentFile.lastModified: ${formatTime(documentFileLastModifiedMs)}"
        lines += "DocumentFile.parentUri: ${documentFileParentUri ?: "(null)"}"
        return lines
    }

    private fun formatTime(epochMs: Long?): String {
        if (epochMs == null || epochMs <= 0L) return "(无)"
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return "${formatter.format(Date(epochMs))} ($epochMs ms)"
    }

    private fun describeFlags(flags: Int?): String {
        if (flags == null) return ""
        val parts = mutableListOf<String>()
        if (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) {
            parts += "DIR_SUPPORTS_CREATE"
        }
        if (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0) {
            parts += "SUPPORTS_WRITE"
        }
        if (flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0) {
            parts += "SUPPORTS_DELETE"
        }
        if (flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0) {
            parts += "SUPPORTS_RENAME"
        }
        if (flags and DocumentsContract.Document.FLAG_SUPPORTS_COPY != 0) {
            parts += "SUPPORTS_COPY"
        }
        if (flags and DocumentsContract.Document.FLAG_SUPPORTS_MOVE != 0) {
            parts += "SUPPORTS_MOVE"
        }
        if (flags and DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL != 0) {
            parts += "SUPPORTS_THUMBNAIL"
        }
        return if (parts.isEmpty()) "" else "[${parts.joinToString(", ")}]"
    }
}

/** 与 freewind-music-player 相同的 SAF 目录扫描方式，并收集更多可读字段 */
object SafDirectoryScanner {

    /** Cursor 里 music-player 实际查询的 4 列 */
    private val musicPlayerProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    /** Demo 额外查询的列，展示 Cursor 还能拿到什么 */
    private val extendedProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SUMMARY,
    )

    /** 递归扫描目录下所有文件（跳过子目录本身，只列文件） */
    fun scanAllFiles(
        context: Context,
        treeUri: Uri,
    ): List<ScannedFileInfo> {
        val rootUri = treeUri.toString()
        val rootDocumentId = DocumentsContract.getDocumentId(treeUri)
        val results = mutableListOf<ScannedFileInfo>()
        walkTree(
            context = context,
            contentResolver = context.contentResolver,
            treeUri = treeUri,
            rootUri = rootUri,
            parentDocumentId = rootDocumentId,
            parentUri = treeUri.toString(),
            relativePrefix = "",
            onFileFound = { results += it },
        )
        return results.sortedBy { it.relativePath.lowercase() }
    }

    private fun walkTree(
        context: Context,
        contentResolver: ContentResolver,
        treeUri: Uri,
        rootUri: String,
        parentDocumentId: String,
        parentUri: String,
        relativePrefix: String,
        onFileFound: (ScannedFileInfo) -> Unit,
    ) {
        val children = listChildren(contentResolver, treeUri, parentDocumentId)
        children.forEach { child ->
            if (child.isDirectory) {
                val nextPrefix = if (relativePrefix.isEmpty()) {
                    child.displayName
                } else {
                    "$relativePrefix/${child.displayName}"
                }
                walkTree(
                    context = context,
                    contentResolver = contentResolver,
                    treeUri = treeUri,
                    rootUri = rootUri,
                    parentDocumentId = child.documentId,
                    parentUri = child.uri.toString(),
                    relativePrefix = nextPrefix,
                    onFileFound = onFileFound,
                )
                return@forEach
            }
            onFileFound(
                buildFileInfo(
                    context = context,
                    rootUri = rootUri,
                    parentUri = parentUri,
                    relativePrefix = relativePrefix,
                    child = child,
                ),
            )
        }
    }

    /** 与 music-player DocumentsContractApi.listChildren 相同：Cursor 逐行读子项 */
    private fun listChildren(
        contentResolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
    ): List<ListedChild> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val children = mutableListOf<ListedChild>()
        contentResolver.query(childrenUri, extendedProjection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val flagsIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)
            val summaryIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SUMMARY)
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idIndex).orEmpty()
                val displayName = cursor.getString(nameIndex).orEmpty()
                val mimeType = cursor.getString(mimeIndex)
                val sizeBytes = if (cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex)
                val lastModified = if (cursor.isNull(modifiedIndex)) null else cursor.getLong(modifiedIndex)
                val flags = if (cursor.isNull(flagsIndex)) null else cursor.getInt(flagsIndex)
                val summary = cursor.getString(summaryIndex)
                val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                children += ListedChild(
                    documentId = documentId,
                    displayName = displayName,
                    mimeType = mimeType,
                    sizeBytes = sizeBytes,
                    isDirectory = isDirectory,
                    lastModifiedMs = lastModified,
                    flags = flags,
                    summary = summary,
                    uri = uri,
                )
            }
        }
        return children
    }

    private fun buildFileInfo(
        context: Context,
        rootUri: String,
        parentUri: String,
        relativePrefix: String,
        child: ListedChild,
    ): ScannedFileInfo {
        val relativePath = if (relativePrefix.isEmpty()) {
            child.displayName
        } else {
            "$relativePrefix/${child.displayName}"
        }
        val documentFile = DocumentFile.fromSingleUri(context, child.uri)
        val contractDocumentId = runCatching {
            DocumentsContract.getDocumentId(child.uri)
        }.getOrNull()
        return ScannedFileInfo(
            relativePath = relativePath,
            rootUri = rootUri,
            parentUri = parentUri,
            cursorDocumentId = child.documentId,
            cursorDisplayName = child.displayName,
            cursorMimeType = child.mimeType,
            cursorSizeBytes = child.sizeBytes,
            cursorIsDirectory = child.isDirectory,
            cursorLastModifiedMs = child.lastModifiedMs,
            cursorFlags = child.flags,
            cursorSummary = child.summary,
            cursorUri = child.uri.toString(),
            contractDocumentId = contractDocumentId,
            documentFileName = documentFile?.name,
            documentFileType = documentFile?.type,
            documentFileUri = documentFile?.uri?.toString(),
            documentFileExists = documentFile?.exists(),
            documentFileIsDirectory = documentFile?.isDirectory,
            documentFileIsFile = documentFile?.isFile,
            documentFileCanRead = documentFile?.canRead(),
            documentFileCanWrite = documentFile?.canWrite(),
            documentFileLength = documentFile?.length(),
            documentFileLastModifiedMs = documentFile?.lastModified(),
            documentFileParentUri = documentFile?.parentFile?.uri?.toString(),
        )
    }

    /** music-player 扫描阶段从 Cursor 读到的原始子项 */
    private data class ListedChild(
        val documentId: String,
        val displayName: String,
        val mimeType: String?,
        val sizeBytes: Long,
        val isDirectory: Boolean,
        val lastModifiedMs: Long?,
        val flags: Int?,
        val summary: String?,
        val uri: Uri,
    )

    /** 供 README / UI 展示：music-player 实际用的 projection */
    fun musicPlayerProjectionDescription(): String {
        return musicPlayerProjection.joinToString(", ") { column ->
            when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "documentId"
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> "displayName"
                DocumentsContract.Document.COLUMN_MIME_TYPE -> "mimeType"
                DocumentsContract.Document.COLUMN_SIZE -> "sizeBytes"
                else -> column
            }
        }
    }
}

/** 把扫描结果拼成最终展示文本 */
fun formatScanResult(files: List<ScannedFileInfo>): String {
    if (files.isEmpty()) {
        return "未找到任何文件。\n\n请确认目录非空，且已授予读取权限。"
    }
    val chunks = files.map { file ->
        file.formatLines().joinToString("\n")
    }
    return buildString {
        appendLine("共扫描到 ${files.size} 个文件")
        appendLine("music-player 扫描 Cursor 列: ${SafDirectoryScanner.musicPlayerProjectionDescription()}")
        appendLine()
        append(chunks.joinToString("\n\n"))
    }
}
