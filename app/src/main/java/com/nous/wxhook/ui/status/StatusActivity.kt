package com.nous.wxhook.ui.status

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.nous.wxhook.db.DbCleanup
import java.io.File

class StatusActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply { text = "wxhook"; textSize = 20f })

        val sb = StringBuilder()

        // Key
        var key: String? = null
        try {
            val hex = File("/data/local/tmp/.wechat_key").readText()
                .lines().find { it.startsWith("key=") }?.removePrefix("key=")
            if (hex != null) key = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        } catch (_: Exception) {}
        sb.appendLine("密钥: ${if (key != null) "✓ $key" else "✗ 未捕获"}")
        sb.appendLine()

        // DB copy status
        val dbFile = File("/sdcard/Download/EnMicroMsg.db")
        if (dbFile.exists()) {
            sb.appendLine("数据库副本: ✓ ${dbFile.length() / 1024 / 1024} MB")
        } else {
            sb.appendLine("数据库副本: ✗ 不存在")
        }

        // Disk info
        sb.appendLine(DbCleanup.getDiskInfo())
        sb.appendLine()

        // Instructions
        if (key != null) {
            sb.appendLine("复制命令:")
            sb.appendLine("su -c 'dd if=/proc/\$(pidof com.tencent.mm)/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db of=/sdcard/Download/EnMicroMsg.db bs=1M'")
            sb.appendLine()
            sb.appendLine("解密命令:")
            sb.appendLine("echo \"PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT count(*) FROM message;\" | sqlcipher /sdcard/Download/EnMicroMsg.db")
        }

        layout.addView(TextView(this).apply {
            text = sb.toString()
            textSize = 12f
            setPadding(0, 32, 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        })

        setContentView(layout)
    }
}
