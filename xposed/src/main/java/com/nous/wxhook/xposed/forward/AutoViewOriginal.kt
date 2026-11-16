package com.nous.wxhook.xposed.forward

import android.widget.Button
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 照 WAuxiliary 的 AutoViewOriginalPhotoHook：
 * 打开图片/视频预览时，自动点击可见的「查看原图」按钮，让微信自己下载原图。
 */
object AutoViewOriginal {

    private const val TAG = "[wxhook:AutoView]"
    private val keywords = listOf(
        "查看原图", "Full Image", "View Full Image",
        "查看原视频", "Original quality", "View Full Video"
    )

    fun install(loader: ClassLoader) {
        try {
            val cls = Class.forName("com.tencent.mm.ui.chatting.gallery.ImageGalleryUI", false, loader)
            var hits = 0
            // 8.0.79 = K9()（setHdImageActionDownloadable），旧版本 = setImageHdImgBtnVisibility
            listOf("K9", "setImageHdImgBtnVisibility", "setHdImageActionDownloadable").forEach { name ->
                try {
                    XposedBridge.hookAllMethods(cls, name, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            clickOriginalButton(param.thisObject)
                        }
                    })
                    hits++
                } catch (_: Throwable) {
                }
            }
            XposedBridge.log("$TAG installed hits=$hits")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG install failed: ${e.message}")
        }
    }

    private fun clickOriginalButton(activity: Any?) {
        if (activity == null) return
        try {
            var k: Class<*>? = activity.javaClass
            while (k != null && k != Any::class.java) {
                for (f in k.declaredFields) {
                    if (!Button::class.java.isAssignableFrom(f.type)) continue
                    try {
                        f.isAccessible = true
                        val b = f.get(activity) as? Button ?: continue
                        if (!b.isShown) continue
                        val text = b.text?.toString() ?: continue
                        if (keywords.any { text.contains(it, ignoreCase = true) }) {
                            XposedBridge.log("$TAG click original button: $text")
                            b.performClick()
                        }
                    } catch (_: Throwable) {
                    }
                }
                k = k.superclass
            }
        } catch (_: Throwable) {
        }
    }
}
