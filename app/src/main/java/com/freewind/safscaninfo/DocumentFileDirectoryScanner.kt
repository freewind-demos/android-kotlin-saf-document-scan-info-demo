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
        onProgress("DocumentFile.fromTreeUri() → ok")
        val listSections = mutableListOf<String>()
        val accessCandidates = mutableListOf<FoundFile>()
        listAllDirectories(
            dir = root,
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
                uri = foundFile.file.uri.toString(),
                fields = readAccessFields(context, foundFile.file, onProgress),
            )
        }
        onProgress("扫描完成")
        return formatInspectReport(
            title = "DocumentFile.listFiles() (full tree) + first-$ACCESS_FILE_LIMIT-files access",
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

    /** 整棵树 list；顺带记下前 ACCESS_FILE_LIMIT 个文件供后续单独 access */
    private fun listAllDirectories(
        dir: DocumentFile,
        directoryPath: String,
        listSections: MutableList<String>,
        accessCandidates: MutableList<FoundFile>,
        onProgress: (String) -> Unit,
    ) {
        val directory = directoryPath.ifEmpty { "/" }
        onProgress("listFiles() → $directory …")
        val children = dir.listFiles()
        onProgress("listFiles() → $directory → ${children.size} 项，开始逐项读属性")
        val items = children.mapIndexed { index, child ->
            readListItem(
                file = child,
                directory = directory,
                index = index,
                total = children.size,
                onProgress = onProgress,
            )
        }
        listSections += formatListSection(
            listApi = "DocumentFile.listFiles()",
            listApiResult = children.size,
            directory = directory,
            items = items,
        )
        val sortedChildren = children.sortedBy { it.name?.lowercase().orEmpty() }
        for (child in sortedChildren) {
            val name = child.name ?: continue
            val childPath = if (directoryPath.isEmpty()) name else "$directoryPath/$name"
            if (child.isDirectory) {
                onProgress("进入子目录 $childPath")
                listAllDirectories(
                    dir = child,
                    directoryPath = childPath,
                    listSections = listSections,
                    accessCandidates = accessCandidates,
                    onProgress = onProgress,
                )
                continue
            }
            if (accessCandidates.size < ACCESS_FILE_LIMIT) {
                onProgress("access 候选 (${accessCandidates.size + 1}/$ACCESS_FILE_LIMIT)：$childPath")
                accessCandidates += FoundFile(path = childPath, file = child)
            }
        }
    }

    /** listFiles() 返回元素：每个属性单独读并 onProgress */
    private fun readListItem(
        file: DocumentFile,
        directory: String,
        index: Int,
        total: Int,
        onProgress: (String) -> Unit,
    ): List<Pair<String, Any?>> {
        val prefix = "list $directory [${index + 1}/$total]"
        fun field(key: String, value: Any?): Pair<String, Any?> {
            onProgress("$prefix $key=$value")
            return key to value
        }
        return listOf(
            field("name", file.name),
            field("type", file.type),
            field("uri", file.uri.toString()),
            field("isDirectory", file.isDirectory),
            field("isFile", file.isFile),
            field("exists()", file.exists()),
            field("canRead()", file.canRead()),
            field("canWrite()", file.canWrite()),
            field("length()", file.length()),
            field("lastModified()", file.lastModified()),
            field("parentFile", file.parentFile?.uri?.toString()),
        )
    }

    /** access 文件：每个属性单独读并 onProgress */
    private fun readAccessFields(
        context: Context,
        listedFile: DocumentFile,
        onProgress: (String) -> Unit,
    ): List<Pair<String, Any?>> {
        val uri = listedFile.uri
        onProgress("access DocumentFile.fromSingleUri($uri) …")
        val file = DocumentFile.fromSingleUri(context, uri)
            ?: throw IllegalArgumentException("DocumentFile.fromSingleUri() 无法打开文件")
        fun field(key: String, value: Any?): Pair<String, Any?> {
            onProgress("access $key=$value")
            return key to value
        }
        return listOf(
            field("DocumentFile.fromSingleUri(context, uri)", file.javaClass.simpleName),
            field("name", file.name),
            field("type", file.type),
            field("uri", file.uri.toString()),
            field("isDirectory", file.isDirectory),
            field("isFile", file.isFile),
            field("exists()", file.exists()),
            field("canRead()", file.canRead()),
            field("canWrite()", file.canWrite()),
            field("length()", file.length()),
            field("lastModified()", file.lastModified()),
            field("parentFile", file.parentFile?.uri?.toString()),
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
