package com.nous.wxhook.ui.status

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.nous.wxhook.db.DbCleanup
import com.nous.wxhook.ui.M3
import java.io.File

class StatusActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "状态检测"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scrollView = android.widget.ScrollView(this)
        val root = M3.vLayout(this)

        // Status card
        val card = M3.card(this)
        card.addView(M3.titleMedium(this, "📊 当前状态"))

        val sb = StringBuilder()

        // Key detection
        var key: String? = null
        try {
            val hex = File("/data/local/tmp/.wechat_key").readText()
                .lines().find { it.startsWith("key=") }?.removePrefix("key=")
            if (hex != null) key = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        } catch (_: Exception) {}
        sb.appendLine("密钥: ${if (key != null) "✅ $key" else "❌ 未捕获"}")

        // DB copy status
        val dbFile = File("/sdcard/Download/EnMicroMsg.db")
        if (dbFile.exists()) {
            val sizeMb = "%.1f".format(dbFile.length().toFloat() / (1024 * 1024))
            sb.appendLine("数据库副本: ✅ ${sizeMb}MB")
        } else {
            sb.appendLine("数据库副本: ❌ 不存在")
        }

        // Disk info
        try {
            val disk = DbCleanup.getDiskInfo()
            if (disk.isNotEmpty()) sb.appendLine(disk)
        } catch (_: Exception) {}

        card.addView(M3.monoBody(this, sb.toString()).apply {
            setPadding(0, M3.dp(this@StatusActivity, 8), 0, 0)
        })
        root.addView(card)
        root.addView(M3.sp(this, 12))

        // Command help card (when key is available)
        if (key != null) {
            val helpCard = M3.outlinedCard(this)
            helpCard.addView(M3.titleMedium(this, "📋 快捷命令"))
            val cmdSb = StringBuilder()
            cmdSb.appendLine("复制数据库:")
            cmdSb.appendLine("su -c 'dd if=/proc/\\$(pidof com.tencent.mm)/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3/EnMicroMsg.db of=/sdcard/Download/EnMicroMsg.db bs=1M'")
            cmdSb.appendLine()
            cmdSb.appendLine("解密查询:")
            cmdSb.appendLine("echo \"PRAGMA key='$key';PRAGMA cipher_compatibility=3;PRAGMA cipher_page_size=1024;PRAGMA kdf_iter=4000;PRAGMA cipher_use_hmac=OFF;SELECT count(*) FROM message;\" | sqlcipher /sdcard/Download/EnMicroMsg.db")
            helpCard.addView(M3.monoBody(this, cmdSb.toString()).apply {
                textSize = 11f
                setPadding(0, M3.dp(this@StatusActivity, 8), 0, 0)
            })
            root.addView(helpCard)
            root.addView(M3.sp(this, 16))
        }

        // Action buttons
        val btnRow = M3.hLayout(this)
        val ctx0 = this@StatusActivity
        btnRow.addView(M3.filledButton(this, "🔄 刷新") {
            recreate()
        }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, M3.dp(ctx0, 48), 1f)
        })
        btnRow.addView(M3.sp(this, 12))
        btnRow.addView(M3.outlinedButton(this, "📁 打开备份目录") {
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            } catch (_: Exception) {
                android.widget.Toast.makeText(ctx0, "无法打开设置", android.widget.Toast.LENGTH_SHORT).show()
            }
        }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, M3.dp(ctx0, 48), 1f)
        })
        root.addView(btnRow)

        scrollView.addView(root)
        setContentView(scrollView)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
