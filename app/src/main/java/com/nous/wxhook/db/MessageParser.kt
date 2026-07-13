package com.nous.wxhook.db

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * WeChat message XML parser.
 * Parses message content from the content column in the message table.
 */
object MessageParser {

    private const val TAG = "wxhook:Parser"

    // Message types
    const val TYPE_TEXT = 1
    const val TYPE_IMAGE = 3
    const val TYPE_VOICE = 34
    const val TYPE_CARD = 42
    const val TYPE_VIDEO = 43
    const val TYPE_EMOJI_DYNAMIC = 47
    const val TYPE_LOCATION = 48
    const val TYPE_APP = 49
    const val TYPE_SYSTEM = 10000
    const val TYPE_REVOKE = 10002

    // App message subtypes (type=49)
    const val APP_LINK = 5
    const val APP_FILE = 6
    const val APP_IMAGE_MSG = 8
    const val APP_LOCATION = 13
    const val APP_COLLECTION = 14
    const val APP_MERGE_FORWARD = 19
    const val APP_MINI_PROGRAM = 20
    const val APP_MUSIC = 24
    const val APP_MINI_CARD = 33
    const val APP_TRANSFER = 2000   // 微信转账
    const val APP_RED_PACKET = 2001 // 微信红包

    data class ParsedMessage(
        val type: Int,
        val typeDesc: String,
        val content: String?,
        val subType: Int = 0,
        val subTypeDesc: String = "",
        val mediaPath: String? = null,
        val fileName: String? = null,
        val fileSize: Long = 0,
        val title: String? = null,
        val url: String? = null
    )

    /**
     * Parse message content based on type.
     */
    fun parse(type: Int, content: String?, subType: Int = 0): ParsedMessage {
        return when (type) {
            TYPE_TEXT -> ParsedMessage(
                type = type,
                typeDesc = "文本",
                content = content
            )

            TYPE_IMAGE -> parseImage(content)
            TYPE_VOICE -> parseVoice(content)
            TYPE_VIDEO -> parseVideo(content)
            TYPE_APP -> parseApp(content, subType)
            TYPE_SYSTEM -> ParsedMessage(
                type = type,
                typeDesc = "系统消息",
                content = content
            )
            TYPE_REVOKE -> ParsedMessage(
                type = type,
                typeDesc = "撤回消息",
                content = content
            )
            TYPE_CARD -> parseCard(content)
            TYPE_LOCATION -> parseLocation(content)
            TYPE_EMOJI_DYNAMIC -> ParsedMessage(
                type = type,
                typeDesc = "动态表情",
                content = content
            )
            else -> ParsedMessage(
                type = type,
                typeDesc = "未知($type)",
                content = content
            )
        }
    }

    private fun parseImage(content: String?): ParsedMessage {
        if (content == null) return ParsedMessage(TYPE_IMAGE, "图片", null)
        try {
            val xmlStart = content.indexOf("<?xml")
            val clean = if (xmlStart >= 0) content.substring(xmlStart) else content
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(clean))
            var aesKey: String? = null
            var cdnUrl: String? = null
            var length: Long = 0

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "img" -> {
                                aesKey = parser.getAttributeValue(null, "aeskey")
                                cdnUrl = parser.getAttributeValue(null, "cdnbigimgurl")
                                length = parser.getAttributeValue(null, "length")?.toLongOrNull() ?: 0
                            }
                        }
                    }
                }
                parser.next()
            }

            return ParsedMessage(
                type = TYPE_IMAGE,
                typeDesc = "图片",
                content = content,
                mediaPath = cdnUrl,
                fileSize = length
            )
        } catch (e: Exception) {
            return ParsedMessage(TYPE_IMAGE, "图片", content)
        }
    }

    private fun parseVoice(content: String?): ParsedMessage {
        if (content == null) return ParsedMessage(TYPE_VOICE, "语音", null)
        try {
            val xmlStart = content.indexOf("<?xml")
            val clean = if (xmlStart >= 0) content.substring(xmlStart) else content
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(clean))
            var voiceLength: String? = null
            var voiceFormat: String? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "voicemsg" -> {
                                voiceLength = parser.getAttributeValue(null, "voicelength")
                                voiceFormat = parser.getAttributeValue(null, "voiceformat")
                            }
                        }
                    }
                }
                parser.next()
            }

            return ParsedMessage(
                type = TYPE_VOICE,
                typeDesc = "语音(${voiceLength}ms)",
                content = content,
                mediaPath = voiceFormat
            )
        } catch (e: Exception) {
            return ParsedMessage(TYPE_VOICE, "语音", content)
        }
    }

    private fun parseVideo(content: String?): ParsedMessage {
        if (content == null) return ParsedMessage(TYPE_VIDEO, "视频", null)
        return ParsedMessage(TYPE_VIDEO, "视频", content)
    }

    private fun parseCard(content: String?): ParsedMessage {
        if (content == null) return ParsedMessage(TYPE_CARD, "名片", null)
        return ParsedMessage(TYPE_CARD, "名片", content)
    }

    private fun parseLocation(content: String?): ParsedMessage {
        if (content == null) return ParsedMessage(TYPE_LOCATION, "位置", null)
        return ParsedMessage(TYPE_LOCATION, "位置", content)
    }

    private fun parseApp(content: String?, subType: Int): ParsedMessage {
        val typeDesc = when (subType) {
            APP_LINK -> "链接"
            APP_FILE -> "文件"
            APP_IMAGE_MSG -> "图文消息"
            APP_LOCATION -> "实时位置"
            APP_COLLECTION -> "收藏"
            APP_MERGE_FORWARD -> "合并转发"
            APP_MINI_PROGRAM -> "小程序"
            APP_MUSIC -> "音乐"
            APP_MINI_CARD -> "小程序卡片"
            APP_TRANSFER -> "转账"
            APP_RED_PACKET -> "红包"
            else -> "应用消息($subType)"
        }

        if (content == null) return ParsedMessage(TYPE_APP, typeDesc, null, subType)

        var title = ""
        var url = ""
        var fileName = ""
        var appMsgType = 0
        var payAmount = ""
        val sb = StringBuilder()

        try {
            // Strip sender prefix (wxid_xxx: ) before XML if present
            val xmlStart = content.indexOf("<?xml")
            if (xmlStart < 0) return ParsedMessage(TYPE_APP, typeDesc, content, subType)
            val cleanContent = content.substring(xmlStart)

            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(cleanContent))
            var currentTag = ""
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (parser.name == "appmsg") {
                            title = parser.getAttributeValue(null, "title") ?: ""
                            url = parser.getAttributeValue(null, "url") ?: ""
                        }
                        if (parser.name == "appattach") {
                            fileName = parser.getAttributeValue(null, "cdnattachurl") ?: ""
                        }
                        if (parser.name == "wcpayinfo") {
                            // Inside payment info
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        when (currentTag) {
                            "title" -> if (title.isEmpty()) title = text
                            "url" -> if (url.isEmpty()) url = text
                            "type" -> appMsgType = text.toIntOrNull() ?: 0
                            "feedesc" -> payAmount = text           // 转账金额: ￥30.00
                            "des" -> if (text.contains("转账") || text.contains("￥") || text.contains("¥")) payAmount = text
                            "attachid" -> fileName = text
                            "cdnattachurl" -> fileName = text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "title" || parser.name == "url" || parser.name == "type" || parser.name == "feedesc" || parser.name == "des") {
                            currentTag = ""
                        }
                        currentTag = ""
                    }
                }
                parser.next()
            }
        } catch (_: Exception) {}

        // Determine subtype from XML <type> element
        val detectedSubType = when {
            appMsgType == 2000 || title.contains("转账") || payAmount.contains("￥") || payAmount.contains("¥") -> APP_TRANSFER
            title.contains("红包") || title.contains("Red Packet") -> APP_RED_PACKET
            appMsgType in listOf(5, 6, 8, 13, 14, 19, 20, 24, 33) -> appMsgType  // map XML type to our constants
            else -> subType
        }

        // Extract amount from payAmount
        val amount = when {
            payAmount.contains("￥") || payAmount.contains("¥") -> payAmount.filter { it.isDigit() || it == '.' }
            else -> ""
        }

        val finalTypeDesc = when (detectedSubType) {
            APP_TRANSFER -> if (amount.isNotEmpty()) "转账 ¥$amount" else "转账"
            APP_RED_PACKET -> "红包"
            APP_LINK -> "链接"; APP_FILE -> "文件"; APP_IMAGE_MSG -> "图文消息"
            APP_LOCATION -> "实时位置"; APP_COLLECTION -> "收藏"; APP_MERGE_FORWARD -> "合并转发"
            APP_MINI_PROGRAM -> "小程序"; APP_MUSIC -> "音乐"; APP_MINI_CARD -> "小程序卡片"
            else -> typeDesc
        }

        return ParsedMessage(
            type = TYPE_APP,
            typeDesc = finalTypeDesc,
            content = content,
            subType = detectedSubType,
            subTypeDesc = finalTypeDesc,
            title = title,
            url = url,
            fileName = fileName
        ).also { Log.i("wxhook:Parser","subType=$detectedSubType title=$title file=$fileName") }
    }

    /**
     * Get human-readable type description.
     */
    fun getTypeDesc(type: Int): String = when (type) {
        TYPE_TEXT -> "文本"
        TYPE_IMAGE -> "图片"
        TYPE_VOICE -> "语音"
        TYPE_CARD -> "名片"
        TYPE_VIDEO -> "视频"
        TYPE_EMOJI_DYNAMIC -> "动态表情"
        TYPE_LOCATION -> "位置"
        TYPE_APP -> "应用消息"
        TYPE_SYSTEM -> "系统消息"
        TYPE_REVOKE -> "撤回消息"
        else -> "未知($type)"
    }
}
