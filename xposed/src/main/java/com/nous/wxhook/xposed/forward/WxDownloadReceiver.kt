package com.nous.wxhook.xposed.forward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * CLI 调试入口（不需要模拟点击）：
 *   su -c 'am broadcast -a com.nous.wxhook.DOWNLOAD_IMG -p com.tencent.mm \
 *       --es svrId 123456 --es talker xxx@chatroom [--es md5 abc]'
 */
class WxDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val svrId = intent.getStringExtra("svrId")?.toLongOrNull() ?: 0L
        val talker = intent.getStringExtra("talker").orEmpty()
        val md5 = intent.getStringExtra("md5").orEmpty()
        WxDownloader.fileLog(context, "CLI download svr=$svrId talker=$talker md5=$md5")
        WxDownloader.downloadViaImageService(context, svrId, talker)
        val path = WxDownloader.findImgInfo2Original(context, svrId)
        WxDownloader.fileLog(context, "CLI after download path=$path")
    }

    companion object {
        const val ACTION = "com.nous.wxhook.DOWNLOAD_IMG"
    }
}
