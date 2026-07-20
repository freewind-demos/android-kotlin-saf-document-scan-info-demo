## 简介

这个 Demo 对比 SAF 目录扫描的两种常见写法：

1. **`DocumentFile.listFiles()`** — list 阶段拿全部 DocumentFile，再逐个读 `name` / `length()`
2. **`ContentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(...))`** — 一次 cursor 同时拿 name、size、mimeType 等

界面分段计时，各大步空行分隔，每类只显示前 5 条；扫描中有 progress 提示当前步骤。

## 为何只扫「当前目录一层」（不递归）

**这是刻意设计，不是能力缺失。**

### DocumentFile 的瓶颈

`DocumentFile.listFiles()` 内部对子项通常只 query `DOCUMENT_ID` 再拼成 `DocumentFile`，**内存里基本只有 `uri`**。

要区分「文件 / 子目录」，必须再调 `isDirectory()`、`isFile()`、`name`、`type` 等——**每一项都会再打 ContentResolver / DocumentProvider**。

目录项很多时，等于在 list 之后对**全量子项做二次访问**；若还要递归进子目录，每层都要重复「list + 逐项 isDirectory + 再递归」，会非常慢。

### DocumentsContract query 的优势

`ContentResolver.query` 可在 projection 里一次声明 `DISPLAY_NAME`、`SIZE`、`MIME_TYPE` 等列。

cursor 每一行里已有 `mimeType`，用 `mimeType == DocumentsContract.Document.MIME_TYPE_DIR` 即可判目录，**无需对每个子项再发一次 provider 调用**。

name、size、是否目录同属一趟 query，天然适合大目录。

### 对本 Demo 的含义

| 场景 | DocumentFile | query |
|------|-------------|-------|
| 仅当前层、不递归 | listFiles 只拿 URI，**不调 isDirectory** | 一趟 query 拿全部子项 |
| **含子目录**且需递归整棵树 | list + 逐项 isDirectory + 递归，极慢 | 每层一趟 query，相对快很多 |

**两者在「有子目录、需递归」时的性能差距会非常大。**

本 Demo **不展示递归扫描**，也**不在 DocumentFile 路径调 isDirectory()**——只 list 当前层全部 URI；query 路径同样列出当前层全部子项（mimeType 随 cursor 一并返回，无额外 provider 调用）。

## 快速开始

### 环境要求

- Android Studio 或 JDK 17
- Android SDK 35
- 真机或模拟器（API 26+）

### 运行

```bash
cd android-kotlin-saf-document-scan-info-demo

# 首次：生成 Gradle Wrapper
./android-gradle-wrapper.mts

# 编译 debug APK
./android-build.mts

# 编译 + 安装 + 启动（有真机优先真机）
./android-adb.mts
```

安装后：

1. 点「选择目录」→ 选一个含文件的文件夹并授权
2. 分别点两种扫描按钮 → 对比耗时与输出（仅当前层）

## 注意事项

- 必须用 SAF 选目录，不能直接填 `/sdcard/...` 路径
- 当前层没有任何子项时，会报「当前目录下没有任何子项」
- 大目录 list/query 本身仍可能较慢，progress 会显示当前步骤

## 关键代码

- `DocumentFileDirectoryScanner.kt` — DocumentFile 路径（仅当前层）
- `DocumentsContractQueryDirectoryScanner.kt` — query 路径（仅当前层）
- `MainActivity.kt` — 选目录、触发扫描、progress、结果展示
