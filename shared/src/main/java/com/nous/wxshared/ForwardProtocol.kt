package com.nous.wxshared

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 微信 Xposed 插件 → 笔记 App / 第三方 App 的转发协议。
 *
 * 传输方式有两种，内容完全一致：
 * 1. 显式广播（setPackage 指向接收方），完整 JSON 放在 [EXTRA_BATCH_JSON]
 * 2. outbox 落盘：<OUTBOX_DIR>/<batchId>.json（供第三方 App / 失败重试读取）
 *
 * 第三方 App 只要解析同一份 JSON 即可接入，字段保持向后兼容：
 * 新增字段只追加，不改变已有字段语义。
 */
object ForwardProtocol {

    const val VERSION = 1

    /** 显式广播 action（接收方必须静态注册 exported=true） */
    const val ACTION_RECEIVE_BATCH = "com.nous.wxnotes.action.RECEIVE_BATCH"

    const val EXTRA_BATCH_JSON = "batch_json"
    const val EXTRA_JSON_PATH = "batch_json_path"
    const val EXTRA_BATCH_ID = "batch_id"
    const val EXTRA_SOURCE = "source_package"

    /** 默认公共目录（插件进程写入，接收 App 读取） */
    const val DEFAULT_OUTBOX_DIR = "/sdcard/Download/wxfwd/outbox"
    const val DEFAULT_MEDIA_DIR = "/sdcard/Download/wxfwd/media"

    /** 插件在微信进程内监听的抽象 LocalSocket 名（媒体传输） */
    const val MEDIA_SOCKET_NAME = "wxfwd_media"

    /** 单个 Binder 事务上限约 1MB；单批最多 100 条，避免超限 */
    const val MAX_MESSAGES_PER_BATCH = 100

    /** 大媒体分片广播：每片 base64 后控制在 ~500KB，避免超过 Binder 1MB */
    const val ACTION_RECEIVE_CHUNK = "com.nous.wxnotes.action.RECEIVE_CHUNK"
    const val EXTRA_CHUNK_JSON = "chunk_json"
    const val CHUNK_SIZE = 100 * 1024

    /** 广播发送方包名（微信） */
    const val SOURCE_WECHAT = "com.tencent.mm"

    const val MEDIA_NONE = "none"
    const val MEDIA_IMAGE = "image"
    const val MEDIA_VOICE = "voice"
    const val MEDIA_VIDEO = "video"
    const val MEDIA_FILE = "file"
    const val MEDIA_EMOJI = "emoji"
    const val MEDIA_LINK = "link"

    /**
     * 大媒体分片。接收方按 transferId 落盘，收齐后合并，
     * 再按 dedupeKey 更新对应消息的媒体。
     */
    data class MediaChunk(
        val transferId: String,
        val dedupeKey: String,
        val msgSvrId: Long = 0,
        val msgId: Long = 0,
        val talker: String = "",
        val talkerTitle: String = "",
        val isGroup: Boolean = false,
        val type: Int = 0,
        val subType: Int = 0,
        val createTime: Long = 0,
        val isSend: Boolean = false,
        val sender: String = "",
        val senderName: String = "",
        val text: String = "",
        val rawXml: String = "",
        val mediaKind: String = "",
        val fileName: String = "",
        val fileSize: Long = 0,
        val durationMs: Long = 0,
        val index: Int = 0,
        val total: Int = 0,
        val data: String = ""
    ) {
        fun encode(): String = JSONObject()
            .put("transferId", transferId)
            .put("dedupeKey", dedupeKey)
            .put("msgSvrId", msgSvrId)
            .put("msgId", msgId)
            .put("talker", talker)
            .put("talkerTitle", talkerTitle)
            .put("isGroup", isGroup)
            .put("type", type)
            .put("subType", subType)
            .put("createTime", createTime)
            .put("isSend", isSend)
            .put("sender", sender)
            .put("senderName", senderName)
            .put("text", text)
            .put("rawXml", rawXml)
            .put("mediaKind", mediaKind)
            .put("fileName", fileName)
            .put("fileSize", fileSize)
            .put("durationMs", durationMs)
            .put("index", index)
            .put("total", total)
            .put("data", data)
            .toString()

        companion object {
            fun decode(s: String): MediaChunk? = try {
                val o = JSONObject(s)
                MediaChunk(
                    transferId = o.optString("transferId"),
                    dedupeKey = o.optString("dedupeKey"),
                    msgSvrId = o.optLong("msgSvrId", 0),
                    msgId = o.optLong("msgId", 0),
                    talker = o.optString("talker"),
                    talkerTitle = o.optString("talkerTitle"),
                    isGroup = o.optBoolean("isGroup", false),
                    type = o.optInt("type", 0),
                    subType = o.optInt("subType", 0),
                    createTime = o.optLong("createTime", 0),
                    isSend = o.optBoolean("isSend", false),
                    sender = o.optString("sender"),
                    senderName = o.optString("senderName"),
                    text = o.optString("text"),
                    rawXml = o.optString("rawXml"),
                    mediaKind = o.optString("mediaKind"),
                    fileName = o.optString("fileName"),
                    fileSize = o.optLong("fileSize", 0),
                    durationMs = o.optLong("durationMs", 0),
                    index = o.optInt("index", 0),
                    total = o.optInt("total", 0),
                    data = o.optString("data")
                )
            } catch (_: Throwable) {
                null
            }
        }
    }

    data class Conversation(
        val talker: String,
        val title: String = "",
        val isGroup: Boolean = false,
        val memberCount: Int = 0
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("talker", talker)
            .put("title", title)
            .put("isGroup", isGroup)
            .put("memberCount", memberCount)

        companion object {
            fun fromJson(o: JSONObject?): Conversation = Conversation(
                talker = o?.optString("talker").orEmpty(),
                title = o?.optString("title").orEmpty(),
                isGroup = o?.optBoolean("isGroup", false) ?: false,
                memberCount = o?.optInt("memberCount", 0) ?: 0
            )
        }
    }

    data class ForwardMessage(
        val msgId: Long = 0,
        val msgSvrId: Long = 0,
        val type: Int = 0,
        val subType: Int = 0,
        /** 微信时间戳（秒） */
        val createTime: Long = 0,
        val isSend: Boolean = false,
        /** 发送者 wxid（群聊时是群成员；单聊时等于 talker） */
        val sender: String = "",
        val senderName: String = "",
        /** 已解析的展示文本，用于列表预览、搜索、笔记正文 */
        val text: String = "",
        /** 原始 content（XML/纯文本），保留用于重新解析 */
        val rawXml: String = "",
        val mediaKind: String = MEDIA_NONE,
        /** 插件复制到公共目录后的绝对路径；拿不到媒体时为空 */
        val localPath: String = "",
        val fileName: String = "",
        val fileSize: Long = 0,
        val durationMs: Long = 0,
        val thumbPath: String = "",
        /** 小媒体（≤500KB）直接内嵌，避免跨进程文件传输限制 */
        val mediaBase64: String = "",
        /** 去重键：优先 msgSvrId，其次 talker+createTime+type+消息指纹 */
        val dedupeKey: String = ""
    ) {
        fun effectiveDedupeKey(): String =
            dedupeKey.ifBlank {
                when {
                    msgSvrId != 0L -> "svr:$msgSvrId"
                    msgId != 0L -> "id:${msgId}"
                    else -> "fp:$createTime:$type:$subType:${text.hashCode()}"
                }
            }

        fun toJson(): JSONObject = JSONObject()
            .put("msgId", msgId)
            .put("msgSvrId", msgSvrId)
            .put("type", type)
            .put("subType", subType)
            .put("createTime", createTime)
            .put("isSend", isSend)
            .put("sender", sender)
            .put("senderName", senderName)
            .put("text", text)
            .put("rawXml", rawXml)
            .put("mediaKind", mediaKind)
            .put("localPath", localPath)
            .put("fileName", fileName)
            .put("fileSize", fileSize)
            .put("durationMs", durationMs)
            .put("thumbPath", thumbPath)
            .put("mediaBase64", mediaBase64)
            .put("dedupeKey", effectiveDedupeKey())

        companion object {
            fun fromJson(o: JSONObject): ForwardMessage = ForwardMessage(
                msgId = o.optLong("msgId", 0),
                msgSvrId = o.optLong("msgSvrId", 0),
                type = o.optInt("type", 0),
                subType = o.optInt("subType", 0),
                createTime = o.optLong("createTime", 0),
                isSend = o.optBoolean("isSend", false),
                sender = o.optString("sender").orEmpty(),
                senderName = o.optString("senderName").orEmpty(),
                text = o.optString("text").orEmpty(),
                rawXml = o.optString("rawXml").orEmpty(),
                mediaKind = o.optString("mediaKind", MEDIA_NONE).orEmpty(),
                localPath = o.optString("localPath").orEmpty(),
                fileName = o.optString("fileName").orEmpty(),
                fileSize = o.optLong("fileSize", 0),
                durationMs = o.optLong("durationMs", 0),
                thumbPath = o.optString("thumbPath").orEmpty(),
                mediaBase64 = o.optString("mediaBase64").orEmpty(),
                dedupeKey = o.optString("dedupeKey").orEmpty()
            )
        }
    }

    data class ForwardBatch(
        val batchId: String = UUID.randomUUID().toString(),
        val sentAt: Long = System.currentTimeMillis(),
        val pluginVersion: String = "",
        val noteTitle: String = "",
        val conversation: Conversation = Conversation(""),
        val messages: List<ForwardMessage> = emptyList(),
        /** true 表示这是"原图/附件补全"的更新，应回到消息原本所属的笔记；
         *  false 表示用户主动保存，即使只有一条也要新建笔记。 */
        val update: Boolean = false
    ) {
        fun toJson(): JSONObject {
            val arr = JSONArray()
            messages.forEach { arr.put(it.toJson()) }
            return JSONObject()
                .put("v", VERSION)
                .put("batchId", batchId)
                .put("sentAt", sentAt)
                .put("pluginVersion", pluginVersion)
                .put("noteTitle", noteTitle)
                .put("conversation", conversation.toJson())
                .put("messages", arr)
                .put("update", update)
        }

        fun encode(): String = toJson().toString()

        companion object {
            fun fromJson(s: String): ForwardBatch? = try {
                fromJson(JSONObject(s))
            } catch (_: Throwable) {
                null
            }

            fun fromJson(o: JSONObject): ForwardBatch {
                val arr = o.optJSONArray("messages") ?: JSONArray()
                val list = ArrayList<ForwardMessage>(arr.length())
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(ForwardMessage.fromJson(it)) }
                }
                return ForwardBatch(
                    batchId = o.optString("batchId").ifBlank { UUID.randomUUID().toString() },
                    sentAt = o.optLong("sentAt", System.currentTimeMillis()),
                    pluginVersion = o.optString("pluginVersion").orEmpty(),
                    noteTitle = o.optString("noteTitle").orEmpty(),
                    conversation = Conversation.fromJson(o.optJSONObject("conversation")),
                    messages = list,
                    update = o.optBoolean("update", false)
                )
            }
        }
    }
}
