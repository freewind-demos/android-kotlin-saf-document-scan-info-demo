internal object ScanStepOutput {

    fun begin(
        onAppend: (String) -> Unit,
        onProgress: (String) -> Unit,
        message: String,
    ) {
        onAppend("")
        onAppend(">>> 开始：$message")
        onProgress(message)
    }
}
