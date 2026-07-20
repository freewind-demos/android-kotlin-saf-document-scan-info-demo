package com.freewind.safscaninfo

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object DocumentFileDirectoryScanner {

    fun inspect(
        context: Context,
        treeUri: Uri,
        onProgress: (String) -> Unit = {},
    ): String {
        onProgress("DocumentFile.fromTreeUri() …")
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("DocumentFile.fromTreeUri() 无法打开目录")
        val listSections = mutableListOf<String>()
        val firstFile = findFirstFile(
            dir = root,
            directoryPath = "",
            listSections = listSections,
            onProgress = onProgress,
        ) ?: throw IllegalArgumentException("目录下未找到任何文件")
        onProgress("读取第一个文件 access：${firstFile.path}")
        val accessFields = readAccessFields(context, firstFile.file)
        onProgress("扫描完成")
        return formatInspectReport(
            title = "DocumentFile.listFiles() + first-file access",
            firstFilePath = firstFile.path,
            firstFileUri = firstFile.file.uri.toString(),
            listSections = listSections,
            accessFields = accessFields,
        )
    }

    private data class FirstFile(
        val path: String,
        val file: DocumentFile,
    )

    private fun findFirstFile(
        dir: DocumentFile,
        directoryPath: String,
        listSections: MutableList<String>,
        onProgress: (String) -> Unit,
    ): FirstFile? {
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
            val name = child.name ?: continue
            val childPath = if (directoryPath.isEmpty()) name else "$directoryPath/$name"
            if (child.isDirectory) {
                onProgress("进入子目录 $childPath")
                findFirstFile(
                    dir = child,
                    directoryPath = childPath,
                    listSections = listSections,
                    onProgress = onProgress,
                )?.let { return it }
                continue
            }
            onProgress("找到第一个文件：$childPath")
            return FirstFile(path = childPath, file = child)
        }
        return null
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

    /** 第一个文件：方法名() 或 API 全名作 key，返回值作 value */
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
}
