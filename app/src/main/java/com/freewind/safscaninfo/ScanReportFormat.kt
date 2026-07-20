package com.freewind.safscaninfo

fun formatInspectReport(
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

fun formatListSection(
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
