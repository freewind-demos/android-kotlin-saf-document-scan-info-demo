package com.freewind.safscaninfo

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object DocumentFileDirectoryScanner {

    private const val ACCESS_FILE_LIMIT = 5

    fun inspect(
        context: Context,
        treeUri: Uri,
        onProgress: (String) -> Unit = {},
    ): String {
        onProgress("DocumentFile.fromTreeUri() …")
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("DocumentFile.fromTreeUri() 无法打开目录")
        val listSections = mutableListOf<String>()
        val files = mutableListOf<FoundFile>()
        collectFiles(
            dir = root,
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
                uri = foundFile.file.uri.toString(),
                fields = readAccessFields(context, foundFile.file),
            )
        }
        onProgress("扫描完成")
        return formatInspectReport(
            title = "DocumentFile.listFiles() + first-$ACCESS_FILE_LIMIT-files access",
            listSections = listSections,
            accessSections = accessSections,
        )
    }

    private data class FoundFile(
        val path: String,
        val file: DocumentFile,
    )

    private data class AccessSection(
        val path: String,
        val uri: String,
        val fields: List<Pair<String, Any?>>,
    )

    private fun collectFiles(
        dir: DocumentFile,
        directoryPath: String,
        listSections: MutableList<String>,
        found: MutableList<FoundFile>,
        limit: Int,
        onProgress: (String) -> Unit,
    ) {
        if (found.size >= limit) return
        val directory = directoryPath.ifEmpty { "/" }
        onProgress("DocumentFile.listFiles() → $directory …")
        val children = dir.listFiles()
        onProgress("DocumentFile.listFiles() → $directory → ${children.size} 项")
        listSections += formatListSection(
            listApi = "DocumentFile.listFiles()",
            listApiResult = children.size,
            directory = directoryPath.ifEmpty { "/" },
            items = children.map { readListItem(it) },
        )
        val sortedChildren = children.sortedBy { it.name?.lowercase().orEmpty() }
        for (child in sortedChildren) {
            if (found.size >= limit) return
            val name = child.name ?: continue
            val childPath = if (directoryPath.isEmpty()) name else "$directoryPath/$name"
            if (child.isDirectory) {
                onProgress("进入子目录 $childPath")
                collectFiles(
                    dir = child,
                    directoryPath = childPath,
                    listSections = listSections,
                    found = found,
                    limit = limit,
                    onProgress = onProgress,
                )
                continue
            }
            onProgress("找到文件 (${found.size + 1}/$limit)：$childPath")
            found += FoundFile(path = childPath, file = child)
        }
    }

    /** listFiles() 返回元素：属性原名；方法用 method() 作 key */
    private fun readListItem(file: DocumentFile): List<Pair<String, Any?>> = listOf(
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

    /** access 文件：方法名() 或 API 全名作 key，返回值作 value */
    private fun readAccessFields(
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
