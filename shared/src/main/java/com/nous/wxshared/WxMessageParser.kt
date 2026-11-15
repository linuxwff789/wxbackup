package com.nous.wxshared

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 微信消息 content 解析（type + XML → 展示文本 + 媒体元数据）。
 *
 * 不依赖微信内部类，可在插件进程和笔记 App 里共用，
 * 也能在导入历史记录时离线重放。
 */
object WxMessageParser {

    const val TYPE_TEXT = 1
    const val TYPE_IMAGE = 3
    const val TYPE_VOICE = 34
    const val TYPE_CARD = 42
    const val TYPE_VIDEO = 43
    const val TYPE_EMOJI = 47
    const val TYPE_LOCATION = 48
    const val TYPE_APP = 49
    const val TYPE_VOIP = 50
    const val TYPE_MINI_PROGRAM = 36
    const val TYPE_SYSTEM = 10000
    const val TYPE_REVOKE = 10002
    const val TYPE_NOTICE = 10000

    const val APP_LINK = 5
    const val APP_FILE = 6
    const val APP_IMAGE = 8
    const val APP_LOCATION = 13
    const val APP_COLLECTION = 14
    const val APP_MERGE_FORWARD = 19
    const val APP_MINI_PROGRAM = 20
    const val APP_MUSIC = 24
    const val APP_MINI_CARD = 33
    const val APP_VIDEO_CHANNEL = 51
    const val APP_VIDEO_CHANNEL_LIVE = 52
    const val APP_QUOTE = 57
    const val APP_TRANSFER = 2000
    const val APP_RED_PACKET = 2001
    const val APP_GROUP_SOLITAIRE = 2003

    data class Parsed(
        val type: Int,
        val subType: Int,
        /** 用于列表预览、搜索、笔记正文的纯文本 */
        val text: String,
        val title: String = "",
        val desc: String = "",
        val url: String = "",
        val mediaKind: String = ForwardProtocol.MEDIA_NONE,
        val fileName: String = "",
        val fileSize: Long = 0,
        val durationMs: Long = 0,
        /** 引用消息原文 */
        val quoteText: String = "",
        /** 微信 XML 里给出的本地/远程资源线索 */
        val mediaHint: String = "",
        /** 图片/视频的 md5（用于拼微信缓存路径） */
        val mediaMd5: String = ""
    )

    fun parse(type: Int, content: String?, forcedSubType: Int = 0): Parsed {
        val raw = content.orEmpty()
        // 微信 8.0.79 对部分消息用新编码（如文件 0x41000001），按 content 结构反推真实类型
        val effectiveType = if (type in 1..10002) type else inferType(raw)
        return when (effectiveType) {
            TYPE_TEXT -> Parsed(effectiveType, forcedSubType, raw.ifBlank { "" })

            TYPE_IMAGE -> {
                val img = findFirst(raw, "img")
                Parsed(
                    type = effectiveType, subType = forcedSubType,
                    text = "[图片]",
                    mediaKind = ForwardProtocol.MEDIA_IMAGE,
                    fileSize = img?.attr("length")?.toLongOrNull() ?: 0,
                    mediaHint = img?.attr("cdnbigimgurl").orEmpty(),
                    mediaMd5 = img?.attr("md5").orEmpty()
                )
            }

            TYPE_VOICE -> {
                val v = findFirst(raw, "voicemsg")
                val len = v?.attr("voicelength")?.toLongOrNull() ?: 0
                val trans = findFirst(raw, "voicetrans")?.text?.trim().orEmpty()
                Parsed(
                    type = effectiveType, subType = forcedSubType,
                    text = if (trans.isNotBlank()) "[语音] $trans" else "[语音] ${formatDuration(len)}",
                    desc = trans,
                    mediaKind = ForwardProtocol.MEDIA_VOICE,
                    durationMs = len,
                    mediaHint = v?.attr("voiceformat").orEmpty()
                )
            }

            TYPE_VIDEO -> {
                val v = findFirst(raw, "videomsg")
                val len = v?.attr("playlength")?.toLongOrNull() ?: 0
                Parsed(
                    type = effectiveType, subType = forcedSubType,
                    text = "[视频]",
                    mediaKind = ForwardProtocol.MEDIA_VIDEO,
                    fileSize = v?.attr("length")?.toLongOrNull() ?: 0,
                    durationMs = len,
                    mediaHint = v?.attr("cdnvideourl").orEmpty()
                )
            }

            TYPE_EMOJI -> Parsed(
                type = effectiveType, subType = forcedSubType,
                text = "[动画表情]",
                mediaKind = ForwardProtocol.MEDIA_EMOJI,
                mediaHint = findFirst(raw, "emoji")?.attr("cdnurl").orEmpty()
            )

            TYPE_LOCATION -> {
                val loc = findFirst(raw, "location")
                val label = loc?.attr("label").orEmpty()
                val poi = loc?.attr("poiname").orEmpty()
                val name = listOf(poi, label).firstOrNull { it.isNotBlank() }.orEmpty()
                Parsed(
                    type = effectiveType, subType = forcedSubType,
                    text = "[位置] $name".trim(),
                    desc = label
                )
            }

            TYPE_CARD -> {
                val node = findFirst(raw, "msg")
                val nick = node?.attr("nickname").orEmpty()
                Parsed(
                    type = effectiveType, subType = forcedSubType,
                    text = "[名片] $nick".trim(),
                    desc = node?.attr("username").orEmpty()
                )
            }

            TYPE_MINI_PROGRAM -> parseApp(raw, forcedSubType)

            TYPE_APP -> parseApp(raw, forcedSubType)

            TYPE_VOIP -> Parsed(effectiveType, forcedSubType, "[语音通话]")

            TYPE_SYSTEM, TYPE_NOTICE -> {
                val plain = stripXml(raw)
                Parsed(effectiveType, forcedSubType, plain.ifBlank { "[系统消息]" })
            }

            TYPE_REVOKE -> Parsed(effectiveType, forcedSubType, "[消息已撤回]")

            else -> Parsed(type, forcedSubType, stripXml(raw).ifBlank { "[消息 type=$type]" })
        }
    }

    fun inferType(raw: String): Int = when {
        raw.isBlank() -> TYPE_TEXT
        raw.contains("<appmsg", true) -> TYPE_APP
        raw.contains("<voicemsg", true) -> TYPE_VOICE
        raw.contains("<videomsg", true) -> TYPE_VIDEO
        raw.contains("<img", true) -> TYPE_IMAGE
        raw.contains("<emoji", true) -> TYPE_EMOJI
        raw.contains("<location", true) -> TYPE_LOCATION
        raw.contains("<msg", true) -> TYPE_APP
        else -> TYPE_TEXT
    }

    // ── 应用消息（type=49） ─────────────────────────────────────────

    private fun parseApp(raw: String, forcedSubType: Int): Parsed {
        val root = parseXml(raw) ?: return Parsed(
            TYPE_APP, forcedSubType, stripXml(raw).ifBlank { "[应用消息]" }
        )
        val appmsg = root.find("appmsg") ?: root
        val title = appmsg.childText("title")
        val des = appmsg.childText("des")
        val url = appmsg.childText("url")
        val appType = appmsg.childText("type").toIntOrNull() ?: forcedSubType
        val subType = appType.takeIf { it > 0 } ?: forcedSubType

        return when (subType) {
            APP_LINK -> Parsed(
                TYPE_APP, subType, text = "[链接] $title".trim(), title = title, desc = des,
                url = url, mediaKind = ForwardProtocol.MEDIA_LINK
            )

            APP_FILE -> {
                val attach = appmsg.find("appattach")
                val name = title.ifBlank { attach?.childText("attachid").orEmpty() }
                val size = attach?.childText("totallen")?.toLongOrNull() ?: 0
                Parsed(
                    TYPE_APP, subType,
                    text = "[文件] $name${if (size > 0) " (${formatSize(size)})" else ""}".trim(),
                    title = name, fileName = name, fileSize = size,
                    mediaKind = ForwardProtocol.MEDIA_FILE,
                    mediaHint = attach?.childText("cdnattachurl").orEmpty()
                )
            }

            APP_IMAGE -> Parsed(
                TYPE_APP, subType, "[图文消息] $title".trim(), title = title, desc = des,
                url = url, mediaKind = ForwardProtocol.MEDIA_LINK
            )

            APP_LOCATION -> Parsed(
                TYPE_APP, subType, "[实时位置] $title".trim(), title = title, desc = des
            )

            APP_COLLECTION -> Parsed(
                TYPE_APP, subType, "[收藏] $title".trim(), title = title, desc = des, url = url
            )

            APP_MERGE_FORWARD -> {
                val record = appmsg.childText("recorditem")
                val inner = stripHtml(record)
                val preview = inner.replace(Regex("\\s+"), " ").trim().take(500)
                Parsed(
                    TYPE_APP, subType,
                    text = "[合并转发] $title".let { if (preview.isNotBlank()) "$it\n$preview" else it },
                    title = title, desc = des, mediaKind = ForwardProtocol.MEDIA_LINK,
                    quoteText = preview
                )
            }

            APP_MINI_PROGRAM, APP_MINI_CARD -> Parsed(
                TYPE_APP, subType, "[小程序] $title".trim(), title = title, desc = des,
                url = url, mediaKind = ForwardProtocol.MEDIA_LINK
            )

            APP_MUSIC -> Parsed(
                TYPE_APP, subType, "[音乐] $title".trim(), title = title, desc = des,
                url = url, mediaKind = ForwardProtocol.MEDIA_LINK
            )

            APP_VIDEO_CHANNEL, APP_VIDEO_CHANNEL_LIVE -> Parsed(
                TYPE_APP, subType,
                (if (subType == APP_VIDEO_CHANNEL_LIVE) "[视频号直播] " else "[视频号] ") + title,
                title = title, desc = des, url = url,
                mediaKind = ForwardProtocol.MEDIA_LINK
            )

            APP_QUOTE -> {
                val refer = appmsg.find("refermsg")
                val quote = refer?.childText("content")?.let { stripXml(it).ifBlank { it } }.orEmpty()
                val quoteTitle = refer?.childText("displayname").orEmpty()
                val body = if (des.isNotBlank()) des else title
                Parsed(
                    TYPE_APP, subType,
                    text = buildString {
                        if (quote.isNotBlank()) append("[引用${if (quoteTitle.isNotBlank()) " $quoteTitle" else ""}] $quote")
                        if (body.isNotBlank()) {
                            if (isNotEmpty()) append('\n')
                            append(body)
                        }
                    }.ifBlank { "[引用消息]" },
                    title = quoteTitle, desc = body, quoteText = quote
                )
            }

            APP_TRANSFER -> {
                val pay = appmsg.childText("feedesc").ifBlank {
                    appmsg.find("wcpayinfo")?.childText("feedesc").orEmpty()
                }
                val amount = pay.replace("¥", "").replace("￥", "").trim()
                Parsed(
                    TYPE_APP, subType,
                    text = "[转账] ${if (amount.isNotBlank()) "¥$amount" else title}".trim(),
                    title = title, desc = pay
                )
            }

            APP_RED_PACKET -> Parsed(
                TYPE_APP, subType,
                text = "[红包] ${title.ifBlank { appmsg.childText("des") }}".trim(),
                title = title, desc = des
            )

            APP_GROUP_SOLITAIRE -> Parsed(
                TYPE_APP, subType, "[群接龙] $title".trim(), title = title, desc = des
            )

            else -> Parsed(
                TYPE_APP, subType,
                text = listOf("[应用消息] $title".trim(), des).filter { it.isNotBlank() }.joinToString("\n"),
                title = title, desc = des, url = url
            )
        }
    }

    // ── XML helpers ────────────────────────────────────────────────

    data class Node(
        val name: String,
        val attrs: Map<String, String>,
        val text: String,
        val children: List<Node>
    ) {
        fun find(tag: String): Node? = children.firstOrNull { it.name == tag }
        fun childText(tag: String): String = find(tag)?.text?.trim().orEmpty()
        fun attr(key: String): String = attrs[key].orEmpty()
    }

    fun parseXml(raw: String): Node? {
        val start = raw.indexOf('<')
        if (start < 0) return null
        return try {
            val p: XmlPullParser = XmlPullParserFactory.newInstance().newPullParser()
            p.setInput(StringReader(raw.substring(start)))
            var root: MutableNode? = null
            val stack = ArrayList<MutableNode>()
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val attrs = HashMap<String, String>()
                        for (i in 0 until p.attributeCount) attrs[p.getAttributeName(i)] = p.getAttributeValue(i).orEmpty()
                        val node = MutableNode(p.name, attrs)
                        if (stack.isEmpty()) root = node else stack.last().children.add(node)
                        stack.add(node)
                    }
                    XmlPullParser.TEXT -> {
                        stack.lastOrNull()?.text?.append(p.text.orEmpty())
                    }
                    XmlPullParser.END_TAG -> {
                        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    }
                }
                event = p.next()
            }
            root?.toNode()
        } catch (_: Throwable) {
            null
        }
    }

    private class MutableNode(val name: String, val attrs: Map<String, String>) {
        val text = StringBuilder()
        val children = ArrayList<MutableNode>()
        fun toNode(): Node = Node(name, attrs, text.toString().trim(), children.map { it.toNode() })
    }

    private fun findFirst(raw: String, tag: String): Node? = parseXml(raw)?.let { root ->
        fun dfs(n: Node): Node? {
            if (n.name == tag) return n
            n.children.forEach { c -> dfs(c)?.let { return it } }
            return null
        }
        dfs(root)
    }

    fun stripXml(raw: String): String = try {
        val sb = StringBuilder()
        var i = 0
        var inTag = false
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '<' -> inTag = true
                c == '>' -> inTag = false
                !inTag -> sb.append(c)
            }
            i++
        }
        sb.toString().trim()
    } catch (_: Throwable) { raw }

    fun stripHtml(html: String): String = html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .trim()

    fun formatDuration(ms: Long): String = when {
        ms <= 0 -> ""
        ms < 1000 -> "${ms}ms"
        else -> "%.1f″".format(ms / 1000.0)
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
        else -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
    }

    fun typeName(type: Int): String = when (type) {
        TYPE_TEXT -> "文本"
        TYPE_IMAGE -> "图片"
        TYPE_VOICE -> "语音"
        TYPE_CARD -> "名片"
        TYPE_VIDEO -> "视频"
        TYPE_EMOJI -> "表情"
        TYPE_LOCATION -> "位置"
        TYPE_APP -> "应用消息"
        TYPE_VOIP -> "语音通话"
        TYPE_MINI_PROGRAM -> "小程序"
        TYPE_SYSTEM -> "系统消息"
        TYPE_REVOKE -> "撤回消息"
        else -> "消息"
    }
}
