package com.nous.wxhook.db

import android.util.Log
import com.nous.wxhook.rootbridge.RootCommandRunner
import java.io.File

/**
 * Merge decrypted WeChat databases via sqlcipher CLI.
 * Uses .mode insert + INSERT OR IGNORE for dedup.
 */
object MergeEngine {

    private const val TAG = "wxhook:Merge"
    private const val SQLCIPHER = "LD_PRELOAD=/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6 /data/local/sqlcipher"

    enum class MergeStrategy {
        UNION,          // INSERT OR IGNORE
        NEWEST_WINS,    // Overlay overwrites base
        BASE_WINS,      // Keep base, skip overlay dupes
        INTERSECTION    // Only messages in both
    }

    data class MergeConfig(
        val strategy: MergeStrategy = MergeStrategy.UNION,
        val key: String = "e9cd2ae"
    )

    data class MergeResult(
        val totalMessages: Long,
        val mergedMessages: Long,
        val duplicatesRemoved: Long,
        val conflicts: List<String>,
        val outputPath: String
    )

    private fun prg(key: String) = """
PRAGMA key='$key';
PRAGMA cipher_compatibility=3;
PRAGMA cipher_page_size=1024;
PRAGMA kdf_iter=4000;
PRAGMA cipher_use_hmac=OFF;
""".trimIndent()

    /** Write SQL to temp file, execute via su, return last output line. */
    private fun execSql(dbPath: String, sql: String, key: String): String {
        val tag = "${System.currentTimeMillis()}_${(1..9999).random()}"
        val sqlFile = File("/data/local/tmp/_mg_sql_$tag.sql")
        sqlFile.writeText(prg(key) + sql)
        try {
            val out = RootCommandRunner.runSuQuiet("$SQLCIPHER '$dbPath' < '${sqlFile.absolutePath}' 2>/dev/null | tail -1").trim()
            return out
        } finally { sqlFile.delete() }
    }

    /** Count messages via CLI. */
    fun cliCount(dbPath: String, key: String): Long {
        return try { execSql(dbPath, "SELECT count(*) FROM message;", key).toLongOrNull() ?: -1L }
        catch (e: Exception) { -1L }
    }

    fun mergeDatabases(
        baseDbPath: String,
        overlayDbPath: String,
        outputPath: String,
        config: MergeConfig = MergeConfig()
    ): MergeResult {
        Log.i(TAG, "Merge: $baseDbPath + $overlayDbPath -> $outputPath (${config.strategy})")

        val baseCount = cliCount(baseDbPath, config.key)
        val overlayCount = cliCount(overlayDbPath, config.key)
        if (baseCount < 0 || overlayCount < 0) {
            return MergeResult(0, 0, 0, listOf("DB not accessible"), outputPath)
        }
        Log.i(TAG, "Base: $baseCount msgs, Overlay: $overlayCount msgs")

        // Copy base to output
        if (baseDbPath != outputPath) {
            RootCommandRunner.runSu("cp '$baseDbPath' '$outputPath'")
        }

        // Phase 1: dump overlay as INSERT statements
        val tag = "${System.currentTimeMillis()}"
        val dumpFile = File("/data/local/tmp/_mg_dump_$tag.sql")
        val dumpSqlFile = File("/data/local/tmp/_mg_dump_script_$tag.sql")
        try {
            dumpSqlFile.writeText(prg(config.key) + """
.mode insert message
.output '${dumpFile.absolutePath}'
SELECT * FROM message;
""".trimIndent())
            RootCommandRunner.runSu("$SQLCIPHER '$overlayDbPath' < '${dumpSqlFile.absolutePath}' 2>/dev/null")
            dumpSqlFile.delete()

            if (!dumpFile.exists() || dumpFile.length() < 10L) {
                return MergeResult(overlayCount, 0, 0, listOf("Dump failed"), outputPath)
            }

            // Phase 2: replace INSERT INTO → INSERT OR IGNORE INTO
            val iorFile = File("/data/local/tmp/_mg_ior_$tag.sql")
            dumpFile.readLines().forEach { line ->
                if (line.startsWith("INSERT INTO message")) {
                    iorFile.appendText(line.replace("INSERT INTO message", "INSERT OR IGNORE INTO message") + "\n")
                }
            }

            val insertCount = iorFile.readLines().count { it.startsWith("INSERT") }
            Log.i(TAG, "Dumped $insertCount INSERT OR IGNORE statements")
            if (insertCount == 0) {
                return MergeResult(overlayCount, 0, overlayCount, emptyList(), outputPath)
            }

            // Phase 3: read IOR SQL into output DB
            val runSqlFile = File("/data/local/tmp/_mg_run_$tag.sql")
            runSqlFile.writeText(prg(config.key) + ".read '${iorFile.absolutePath}'\nSELECT changes();")
            val changes = try {
                val out = RootCommandRunner.runSuQuiet("$SQLCIPHER '$outputPath' < '${runSqlFile.absolutePath}' 2>/dev/null | tail -1").trim()
                out.toLongOrNull() ?: 0L
            } catch (e: Exception) { 0L }

            val duplicates = (overlayCount - changes).coerceAtLeast(0)
            Log.i(TAG, "Merge done: $changes inserted, $duplicates dupes")

            return MergeResult(
                totalMessages = overlayCount,
                mergedMessages = changes,
                duplicatesRemoved = duplicates,
                conflicts = emptyList(),
                outputPath = outputPath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Merge failed: ${e.message}")
            return MergeResult(0, 0, 0, listOf("Error: ${e.message}"), outputPath)
        } finally {
            dumpFile.delete(); dumpSqlFile.delete(); File("/data/local/tmp/_mg_ior_$tag.sql").delete()
            File("/data/local/tmp/_mg_run_$tag.sql").delete()
        }
    }

    fun contentHash(talker: String, content: String?, createTime: Long): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        return digest.digest("$talker|$content|$createTime".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}