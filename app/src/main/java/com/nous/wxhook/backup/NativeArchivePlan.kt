package com.nous.wxhook.backup

/** Immutable native archive request. Source/archive pairs avoid shell paths and `-C` state. */
data class NativeArchivePlan(
    val outputPath: String,
    val sources: List<Source>,
) {
    data class Source(val sourcePath: String, val archivePath: String)

    fun nativePairs(): Array<String> {
        require(outputPath.startsWith("/")) { "outputPath must be absolute" }
        require(sources.isNotEmpty()) { "archive must contain a source" }
        return sources.flatMap { source ->
            require(source.sourcePath.startsWith("/")) { "sourcePath must be absolute" }
            require(isSafeArchivePath(source.archivePath)) { "unsafe archive path: ${source.archivePath}" }
            listOf(source.sourcePath, source.archivePath)
        }.toTypedArray()
    }

    private fun isSafeArchivePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith('/') &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." }
}
