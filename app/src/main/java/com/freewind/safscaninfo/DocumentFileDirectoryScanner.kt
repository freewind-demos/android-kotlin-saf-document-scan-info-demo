package com.freewind.safscaninfo

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * DocumentFile.listFiles() 一次性返回整目录数组 → list 阶段 progress 只报数量。
 * 扫完整棵树后输出摘要 + 前 5 个文件的 list 快照；再单独 access 这 5 个并 append 详情。
 */
object DocumentFileDirectoryScanner {

    private const val ACCESS_FILE_LIMIT = 5

    fun inspect(
        context: Context,
        treeUri: Uri,
        onProgress: (String) -> Unit = {},
        onAppend: (String) -> Unit = {},
    ) {
        onProgress("DocumentFile.fromTreeUri() …")
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("DocumentFile.fromTreeUri() 无法打开目录")

        var directoryCount = 0
        var fileCount = 0
        val firstFiles = mutableListOf<FoundFile>()

        listAllDirectories(
            dir = root,
            directoryPath = "",
            directoryCount = { directoryCount += 1 },
            onFile = { path, file ->
                fileCount += 1
                if (firstFiles.size < ACCESS_FILE_LIMIT) {
                    firstFiles += FoundFile(
                        path = path,
                        file = file,
                        listFields = snapshotListFields(file),
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
                appendLine("=== list (DocumentFile.listFiles，整树) ===")
                appendLine("listApi: DocumentFile.listFiles() — 一次性返回当前目录全部子项")
                appendLine("directoryCount: $directoryCount")
                appendLine("fileCount: $fileCount")
                appendLine()
                appendLine("=== first $ACCESS_FILE_LIMIT files from list snapshot ===")
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
        onAppend("=== access (DocumentFile.fromSingleUri，仅前 ${firstFiles.size} 个) ===")
        firstFiles.forEachIndexed { index, found ->
            onProgress("access (${index + 1}/${firstFiles.size}) ${found.path} …")
            val accessFields = readAccessFields(context, found.file, onProgress)
            onAppend(
                buildString {
                    appendLine()
                    appendLine("--- access file ${index + 1} ---")
                    appendLine("path: ${found.path}")
                    appendLine("uri: ${found.file.uri}")
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
        val file: DocumentFile,
        val listFields: List<Pair<String, Any?>>,
    )

    private fun listAllDirectories(
        dir: DocumentFile,
        directoryPath: String,
        directoryCount: () -> Unit,
        onFile: (path: String, file: DocumentFile) -> Unit,
        onProgress: (String) -> Unit,
    ) {
        val directory = directoryPath.ifEmpty { "/" }
        if (directoryPath.isNotEmpty()) {
            directoryCount()
        }
        onProgress("listFiles() → $directory …")
        val children = dir.listFiles()
        // listFiles 一次性返回整数组，无法逐项流式拿 → 只报数量
        onProgress("listFiles() → $directory → ${children.size} 项")

        val sortedChildren = children.sortedBy { it.name?.lowercase().orEmpty() }
        for (child in sortedChildren) {
            val name = child.name ?: continue
            val childPath = if (directoryPath.isEmpty()) name else "$directoryPath/$name"
            if (child.isDirectory) {
                onProgress("进入子目录 $childPath")
                listAllDirectories(
                    dir = child,
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

    /** list 阶段快照：来自 listFiles() 返回的 DocumentFile，不是单独 access */
    private fun snapshotListFields(file: DocumentFile): List<Pair<String, Any?>> = listOf(
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

    private fun readAccessFields(
        context: Context,
        listedFile: DocumentFile,
        onProgress: (String) -> Unit,
    ): List<Pair<String, Any?>> {
        val uri = listedFile.uri
        onProgress("access fromSingleUri($uri) …")
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
}
