package com.nous.wxhook.backup

data class NativeArchivePlan(
    val outputPath: String,
    val sources: List<Source>,
) {
    data class Source(val sourcePath: String, val archivePath: String)

    /** Write source pairs to a simple text file (tab-separated, one per line).
     *  Avoids Binder transaction buffer overflow for large source lists. */
    /**
     * A tar may contain duplicate entry names, but readers disagree on whether the
     * first or the last one wins. Keep one source per archive path deterministically.
     */
    fun uniqueSources(): List<Source> = sources.distinctBy { it.archivePath }

    fun writePairsFile(pairsPath: String): Boolean {
        return try {
            java.io.File(pairsPath).writeText(toPairsContent())
            true
        } catch (_: Exception) { false }
    }

    fun toPairsContent(): String =
        uniqueSources().joinToString("\n") { "${it.sourcePath}\t${it.archivePath}" }
}
