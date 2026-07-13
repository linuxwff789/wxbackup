with open('app/src/main/java/com/nous/wxhook/ui/module/BackupHookLocal.kt') as f:
    txt = f.read()

amp = chr(38)

# Replace both decrypt methods with direct command approach
old1 = """    private fun decryptAndDump(dbPath: String): String {
        // Use base64 script + setsid to survive MIUI background kill
        val tmpDir = "/sdcard/Download/wxhook_backup/tmp"
        val shPath = "/data/local/tmp/decrypt_full.sh"
        val doneFile = "$tmpDir/decrypt_full_done.txt"
        val gzFile = "$tmpDir/EnMicroMsg_baseline.sql.gz"
        return try {
            val pwd = getDbPassword()
            val script = ("#!/system/bin/sh\\n" +
                "mkdir -p $tmpDir\\n" +
                "cp \\"" + dbPath + "\\" $tmpDir/wxhook_dec.db 2>/dev/null\\n" +
                "LD_PRELOAD='/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6' " +
                "/data/local/sqlcipher $tmpDir/wxhook_dec.db " +
                "-cmd 'PRAGMA key = \\"" + pwd + "\\";' " +
                "-cmd 'PRAGMA cipher_compatibility = 3;' " +
                "-cmd 'PRAGMA cipher_page_size = 1024;' " +
                "-cmd 'PRAGMA kdf_iter = 4000;' " +
                "-cmd 'PRAGMA cipher_use_hmac = OFF;' " +
                "-cmd '.mode insert' " +
                "2>/dev/null | gzip -c > $gzFile 2>/dev/null\\n" +
                "chmod 644 $gzFile 2>/dev/null\\n" +
                "rm -f $tmpDir/wxhook_dec.db 2>/dev/null\\n" +
                "date > " + doneFile + "\\n" +
                "echo done >> " + doneFile + "\\n")
            val b64 = android.util.Base64.encodeToString(script.toByteArray(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)
            Runtime.getRuntime().exec(arrayOf("su", "-c", "printf \\"%s\\" " + b64 + " | base64 -d > $shPath && chmod 755 $shPath")).waitFor()
            Runtime.getRuntime().exec(arrayOf("su", "-c", "sh -c '$shPath > /data/local/tmp/decrypt_exec.log 2>&1' &")).waitFor()
            var waited = 0; val maxWait = 300
            while (waited < maxWait) {
                Thread.sleep(1000); waited++
                if (java.io.File(doneFile).exists()) {
                    java.io.File(doneFile).delete()
                    if (java.io.File(gzFile).exists()) return "OK:$gzFile"
                    break
                }
            }
            ""
        } catch (e: Exception) { android.util.Log.e("wxhook:Backup", "decryptAndDump: $e"); "" }
    }"""

new1 = '''    private fun decryptAndDump(dbPath: String): String {
        val pwd = getDbPassword()
        val tmpDir = "/sdcard/Download/wxhook_backup/tmp"
        val doneFile = "$tmpDir/decrypt_full_done.txt"
        val gzFile = "$tmpDir/EnMicroMsg_baseline.sql.gz"
        val cmd = "mkdir -p $tmpDir " + '$' + "amp" + '$' + "amp" + ' "cp \\"" + dbPath + "\\" $tmpDir/wxhook_dec.db ' + '$' + "amp" + '$' + "amp" + ' " +
            '"LD_PRELOAD=\\'/data/local/libz.so.1:/data/local/libcrypto.so.3:/data/local/libedit.so:/data/local/libncursesw.so.6\\' " +
            "/data/local/sqlcipher $tmpDir/wxhook_dec.db " +
            "-cmd \\'PRAGMA key = \\"" + pwd + "\\";\\' " +
            "-cmd \\'PRAGMA cipher_compatibility = 3;\\' " +
            "-cmd \\'PRAGMA cipher_page_size = 1024;\\' " +
            "-cmd \\'PRAGMA kdf_iter = 4000;\\' " +
            "-cmd \\'PRAGMA cipher_use_hmac = OFF;\\' " +
            "-cmd \\'.mode insert\\' " +
            "2>/dev/null | gzip -c > $gzFile ' + '$' + "amp" + '$' + "amp" + ' rm -f $tmpDir/wxhook_dec.db ' + '$' + "amp" + '$' + "amp" + ' echo ok > $doneFile"
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd + " " + '$' + "amp" + "")).waitFor()
            var waited = 0; val maxWait = 300
            while (waited < maxWait) {
                Thread.sleep(1000); waited++
                if (java.io.File(doneFile).exists()) {
                    java.io.File(doneFile).delete()
                    if (java.io.File(gzFile).exists()) return "OK:$gzFile"
                    break
                }
            }
            ""
        } catch (e: Exception) { android.util.Log.e("wxhook:Backup", "decryptAndDump: $e"); "" }
    }'''

txt = txt.replace(old1, new1)

print("Replaced decryptAndDump")
print(f"Result: {'decryptAndDump' in txt}")
