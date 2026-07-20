## 简介

这个 Demo 演示 **freewind-music-player 扫曲库时，SAF 目录扫描能拿到哪些文件信息**。

music-player 用的是 `DocumentsContract.buildChildDocumentsUriUsingTree` + `ContentResolver.query` 读 Cursor，阶段 1 只取 4 列：`documentId`、`displayName`、`mimeType`、`sizeBytes`，再拼出 `uri` 和 `isDirectory`，并自己算 `relativePath`。

本 Demo 在此基础上：

1. 用同样方式递归扫指定目录下所有**文件**
2. 额外读 Cursor 的 `lastModified`、`flags`、`summary`
3. 对每个文件再调 `DocumentFile.fromSingleUri`，列出 `exists / canRead / canWrite / length / lastModified` 等

界面会把每个文件的字段**一行行列出**，文件之间**空一行**，方便肉眼看。

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

1. 点「选择目录」→ 选一个含音频/任意文件的文件夹并授权
2. 点「开始扫描」→ 下方滚动查看每个文件的全部字段

## 注意事项

- 必须用 SAF 选目录，不能直接填 `/sdcard/...` 路径
- 大目录扫描可能稍慢，属正常
- music-player 阶段 2 还会用 `MediaMetadataRetriever` 读音频元数据（title/artist/duration），**不在本 Demo 范围**——本 Demo 只展示「列目录 / 读 DocumentFile」能拿到的信息

## 教程

### 1. music-player 怎么扫

曲库根目录是用户授权的 **tree URI**（`ACTION_OPEN_DOCUMENT_TREE`）。扫描时对每个子目录调用 `DocumentsContractApi.listChildren`：

- `buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)`
- Cursor projection：`DOCUMENT_ID`, `DISPLAY_NAME`, `MIME_TYPE`, `SIZE`
- 由 `mimeType == MIME_TYPE_DIR` 判断是否目录
- `buildDocumentUriUsingTree` 得到每个条目的 `uri`
- 递归进子目录，文件则生成 `AudioTrack` stub（含 `relativePath`, `sizeBytes`, `mimeType`, `trackUri` 等）

### 2. 本 Demo 多展示了什么

**Cursor 扩展列**（music-player 当前没查，但 provider 通常有）：

- `lastModified`
- `flags`（是否支持写/删/重命名等，Demo 会解码成可读标签）
- `summary`

**DocumentFile**（music-player 在别的流程里也会用，例如 `lastModified`、`exists`）：

- `name`, `type`, `uri`
- `exists`, `isDirectory`, `isFile`
- `canRead`, `canWrite`
- `length`, `lastModified`
- `parentFile.uri`

**扫描时自己拼的**（music-player 同样有）：

- `relativePath` — 相对根目录的路径
- `rootUri` — 用户选的 tree URI
- `parentUri` — 直接父目录 document URI

### 3. 输出示例（节选）

```
共扫描到 3 个文件
music-player 扫描 Cursor 列: documentId, displayName, mimeType, sizeBytes

文件名: song.mp3
relativePath: Music/song.mp3
rootUri: content://...
parentUri: content://...
--- DocumentsContract Cursor（music-player 扫描用的 4 列 + 扩展列）---
documentId: ...
displayName: song.mp3
mimeType: audio/mpeg
sizeBytes: 5242880
...

--- DocumentFile（单文件 API 额外可读字段）---
DocumentFile.name: song.mp3
DocumentFile.length: 5242880
...
```

### 4. 关键代码

- `DocumentFileDirectoryScanner.kt` — `DocumentFile.listFiles()` 扫描（独立）
- `DocumentsContractQueryDirectoryScanner.kt` — `ContentResolver.query` 扫描（独立）
- `MainActivity.kt` — 选目录、触发扫描、展示文本
