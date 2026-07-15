package com.nous.wxhook.backup

data class NativeArchivePlan(
    val outputPath: String,
    val sources: List<Source>,
) {
    data class Source(val sourcePath: String, val archivePath: String)

    /** Write source pairs to a simple text file (tab-separated, one per line).
     *  Avoids Binder transaction buffer overflow for large source lists. */
    fun writePairsFile(pairsPath: String): Boolean {
        return try {
            java.io.File(pairsPath).writeText(sources.joinToString("\n") { "${it.sourcePath}\t${it.archivePath}" })
            true
        } catch (_: Exception) { false }
    }

    fun toPairsContent(): String =
        sources.joinToString("\n") { "${it.sourcePath}\t${it.archivePath}" }
}
