package com.freewind.safscaninfo

enum class ScanMethod {
    DOCUMENT_FILE,
    DOCUMENTS_CONTRACT_QUERY,
}

typealias ScanProgressCallback = (message: String) -> Unit
