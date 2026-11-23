package com.nous.wxnotes.data

import android.content.Context
import android.os.Environment
import com.nous.wxshared.ForwardProtocol
import com.nous.wxshared.ForwardProtocol.ForwardBatch
import com.nous.wxshared.ForwardProtocol.ForwardMessage
import com.nous.wxshared.WxMessageParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotesRepository(private val context: Context) {

    private val db = NotesDatabase.get(context)
    private val noteDao = db.noteDao()
    private val messageDao = db.messageDao()
    private val tagDao = db.tagDao()

    private val prefs = context.getSharedPreferences("wxnotes", Context.MODE_PRIVATE)

    /**
     * 媒体放公共目录，用户可以直接在文件管理器里看到/导出。
     * 没有全文件访问权限时回退到私有目录。
     */
    /**
     * 媒体目录默认放一个 .nomedia，系统相册/媒体库就不会扫描这些转发的图片和视频。
     * 关掉开关时删掉 .nomedia。
     */
    fun applyMediaScanPolicy() {
        val skip = context.getSharedPreferences("wxnotes", Context.MODE_PRIVATE)
            .getBoolean(KEY_SKIP_MEDIA_SCAN, true)
        runCatching {
            val dir = mediaRoot()
            if (!dir.isDirectory) return@runCatching
            val marker = File(dir, ".nomedia")
            if (skip) {
                if (!marker.exists()) marker.writeText("")
            } else {
                marker.delete()
            }
        }
    }

    private fun mediaRoot(): File {
        val ext = Environment.getExternalStorageDirectory() ?: return File(context.filesDir, "media")
        val dir = File(ext, "Download/wxnotes/media")
        return if (dir.exists() || dir.mkdirs()) dir else File(context.filesDir, "media")
    }

    data class SaveResult(
        val noteId: Long,
        val noteTitle: String,
        val added: Int,
        val skipped: Int,
        val created: Boolean
    )

    /** 把一批转发消息落库；同会话窗口内自动追加到上一篇笔记。 */
    suspend fun saveBatch(batch: ForwardBatch): SaveResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val conv = batch.conversation
        val talker = conv.talker
        val talkerTitle = conv.title.ifBlank { talker.ifBlank { "微信消息" } }
        val firstText = batch.messages
            .firstOrNull { it.type == WxMessageParser.TYPE_TEXT && it.text.isNotBlank() }
            ?.text
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(28)

        // 1. 选出目标笔记
        // 单条消息的"更新"（例如自动补全原图后 publishUpdate）必须回到它原本所属的笔记；
        // 否则聚合窗口一过就会错误地新建一篇笔记，旧笔记里的缩略图永远不更新。
        val updateTarget = if (batch.update && batch.messages.size == 1) {
            val m0 = batch.messages[0]
            messageDao.getByKeyPrefix("$talker|${m0.effectiveDedupeKey()}")
        } else null
        // 每次保存单独成篇（不再按会话时间窗口合并）；
        // 只有"补全原图/大图"这类单条更新才回到消息原本所属的笔记。
        val existing = updateTarget?.let { noteDao.getById(it.noteId) }
        val noteId: Long
        var created = false
        if (existing != null) {
            noteId = existing.id
            // 之前只拿到 wxid，这次插件给了真实昵称就更新
            if (isRealName(talkerTitle) && existing.talkerTitle != talkerTitle) {
                noteDao.update(existing.copy(talkerTitle = talkerTitle, updatedAt = now))
            }
            // 标题还是默认的「会话 · 日期」时，用第一条文字消息替换
            if (firstText != null && existing.auto && isDefaultTitle(existing.title, existing.talkerTitle)) {
                noteDao.update(existing.copy(title = firstText, updatedAt = now))
            }
        } else {
            noteId = noteDao.insert(
                NoteEntity(
                    title = batch.noteTitle.ifBlank { firstText ?: defaultTitle(talkerTitle) },
                    createdAt = now,
                    updatedAt = now,
                    auto = true,
                    talker = talker,
                    talkerTitle = talkerTitle,
                    lastMessageTime = now
                )
            )
            created = true
        }

        // 2. 媒体导入 + 消息落库
        var added = 0
        var skipped = 0
        var lastText = ""
        var lastTime = 0L
        for (m in batch.messages) {
            // 去重作用域限定在"同一笔记"：同一条微信消息可以保存进不同笔记（例如不同批次），
            // 但同一笔记内重复保存不产生重复条目。
            val legacyKey = buildString {
                append(talker).append('|').append(m.effectiveDedupeKey())
            }
            val dedupe = "$legacyKey|n:$noteId"
            val parsed = if (m.text.isBlank()) {
                WxMessageParser.parse(m.type, m.rawXml, m.subType)
            } else null
            val text = m.text.ifBlank { parsed?.text.orEmpty() }
            val mediaKind = m.mediaKind.takeUnless { it.isBlank() || it == ForwardProtocol.MEDIA_NONE }
                ?: parsed?.mediaKind.orEmpty()
            // 已经存过：如果这次带了更完整的媒体（例如自动补全的原图），更新它
            val existing = messageDao.getByKey(dedupe)
                ?: messageDao.getByKey(legacyKey)?.takeIf { it.noteId == noteId }
            if (existing != null) {
                val newerMedia = m.mediaBase64.isNotBlank() ||
                    (m.localPath.isNotBlank() && !m.localPath.startsWith("lss://") && !m.localPath.startsWith("http"))
                val currentMissing = existing.mediaPath.isBlank() || !File(existing.mediaPath).exists()
                // 缩略图先落盘、原图后到的情况：文件存在但很小，也要用更大的原图覆盖
                val actualSize = if (existing.mediaPath.isNotBlank() && !currentMissing) {
                    runCatching { File(existing.mediaPath).length() }.getOrDefault(0L)
                } else 0L
                // provider 直传的文件一定是从微信侧拿到的原图/大文件，直接覆盖缩略图
                val fromInbox = m.localPath.startsWith("content://")
                val betterMedia = newerMedia && m.fileSize > 0 && m.fileSize > actualSize
                if (newerMedia && (currentMissing || betterMedia || fromInbox)) {
                    val newPath = finalizeVoice(importMedia(noteId, m, mediaKind), mediaKind)
                    if (newPath.isNotBlank()) {
                        messageDao.updateMedia(
                            existing.id,
                            newPath,
                            mediaKind,
                            m.fileName.ifBlank { existing.fileName },
                            m.fileSize.takeIf { it > 0 } ?: existing.fileSize
                        )
                        added++
                        lastTime = m.createTime * 1000
                        lastText = m.text.ifBlank { existing.text }
                    }
                }
                skipped++
                continue
            }

            val mediaPath = finalizeVoice(importMedia(noteId, m, mediaKind), mediaKind)

            val entity = MessageEntity(
                noteId = noteId,
                dedupeKey = dedupe,
                msgId = m.msgId,
                msgSvrId = m.msgSvrId,
                talker = talker,
                talkerTitle = talkerTitle,
                sender = m.sender,
                senderName = m.senderName.ifBlank {
                    when {
                        m.isSend -> "我"
                        // 群聊时不要把群名当发送人（导出时会误导）
                        talker.endsWith("@chatroom") -> ""
                        else -> talkerTitle
                    }
                },
                type = m.type,
                subType = if (m.subType != 0) m.subType else parsed?.subType ?: 0,
                createTime = m.createTime,
                isSend = m.isSend,
                text = text,
                rawXml = m.rawXml.take(if (prefs.getBoolean(KEY_KEEP_RAW, true)) Int.MAX_VALUE else 0),
                mediaKind = mediaKind,
                mediaUnavailable = m.mediaUnavailable,
                mediaPath = mediaPath,
                fileName = m.fileName.ifBlank { parsed?.fileName.orEmpty() },
                fileSize = m.fileSize.takeIf { it > 0 } ?: parsed?.fileSize ?: 0,
                durationMs = m.durationMs.takeIf { it > 0 } ?: parsed?.durationMs ?: 0
            )
            val row = messageDao.insertIgnore(entity)
            if (row == -1L) skipped++ else {
                added++
                if (m.createTime * 1000 >= lastTime) {
                    lastTime = m.createTime * 1000
                    lastText = text
                }
            }
        }

        // 3. 更新笔记元数据
        val note = noteDao.get(noteId) ?: return@withContext SaveResult(noteId, "", added, skipped, created)
        val count = messageDao.countByNote(noteId)
        val preview = lastText.replace(Regex("\\s+"), " ").trim().take(140)
        noteDao.update(
            note.copy(
                messageCount = count,
                preview = if (preview.isNotBlank()) preview else note.preview,
                updatedAt = now,
                lastMessageTime = maxOf(note.lastMessageTime, lastTime),
                talkerTitle = if (note.talkerTitle.isBlank()) talkerTitle else note.talkerTitle
            )
        )
        SaveResult(noteId, note.title, added, skipped, created)
    }

    /** 保存单条消息（第三方 App / 调试用）。 */
    suspend fun saveSingle(message: ForwardMessage, conversation: ForwardProtocol.Conversation): SaveResult =
        saveBatch(ForwardBatch(conversation = conversation, messages = listOf(message)))

    private suspend fun importMedia(noteId: Long, m: ForwardMessage, kind: String): String {
        if (m.mediaBase64.isNotBlank()) {
            return saveInlineMedia(noteId, m)
        }
        // 插件通过 MediaInboxProvider 直传的大文件：filesDir/inbox/<name>
        if (m.localPath.startsWith("content://")) {
            return importFromInbox(noteId, m)
        }
        if (m.localPath.isBlank()) return ""
        // 插件通过 127.0.0.1 的本地 HTTP 暴露微信私有目录里的媒体
        if (m.localPath.startsWith("lss://")) {
            return downloadViaLocalSocket(noteId, m)
        }
        if (m.localPath.startsWith("http://127.0.0.1:") || m.localPath.startsWith("http://localhost:")) {
            return downloadMedia(noteId, m)
        }
        val src = File(m.localPath)
        if (!src.exists() || !src.canRead() || !src.isFile) return m.localPath
        val fileName = src.name.ifBlank { "media_${System.currentTimeMillis()}" }
        val dir = File(mediaRoot(), noteId.toString())
        if (!dir.exists() && !dir.mkdirs()) return m.localPath
        val dst = uniqueFile(dir, fileName)
        return try {
            src.copyTo(dst, overwrite = true)
            dst.absolutePath
        } catch (_: Throwable) {
            m.localPath
        }
    }

    /**
     * 微信语音是 SILK：落盘后转成 WAV，MediaPlayer 才能播。
     */
    private fun finalizeVoice(path: String, kind: String): String {
        if (path.isBlank() || kind != ForwardProtocol.MEDIA_VOICE) return path
        val f = File(path)
        android.util.Log.i("WxNotesVT", "finalize path=$path exists=${f.isFile} silk=${if (f.isFile) VoiceTranscoder.isSilk(f) else false}")
        if (!f.isFile || !VoiceTranscoder.isSilk(f)) return path
        val wav = VoiceTranscoder.transcode(context, f) ?: return path
        runCatching { f.delete() }
        return wav.absolutePath
    }

    /** 读取插件通过 ContentProvider 写入的 inbox 文件，移动到笔记媒体目录。 */
    private fun importFromInbox(noteId: Long, m: ForwardMessage): String {
        val name = m.localPath.substringAfterLast('/')
        if (name.isBlank()) return ""
        val src = File(File(context.filesDir, "inbox"), name)
        if (!src.isFile || src.length() <= 0) return ""
        val dir = File(mediaRoot(), noteId.toString())
        if (!dir.exists() && !dir.mkdirs()) return ""
        val dst = uniqueFile(dir, m.fileName.ifBlank { src.name.substringAfter('_') })
        return try {
            src.copyTo(dst, overwrite = true)
            src.delete()
            dst.absolutePath
        } catch (_: Throwable) {
            ""
        }
    }

    /** 广播内嵌的小媒体：直接解码落盘（图片/语音/小文件走这条）。 */
    private fun saveInlineMedia(noteId: Long, m: ForwardMessage): String {
        val dir = File(mediaRoot(), noteId.toString())
        if (!dir.exists() && !dir.mkdirs()) return ""
        val name = m.fileName.ifBlank {
            val ext = when (m.mediaKind) {
                ForwardProtocol.MEDIA_IMAGE -> ".jpg"
                ForwardProtocol.MEDIA_VOICE -> ".amr"
                ForwardProtocol.MEDIA_VIDEO -> ".mp4"
                else -> ".bin"
            }
            "media_${System.currentTimeMillis()}$ext"
        }
        val dst = uniqueFile(dir, name)
        return try {
            val bytes = android.util.Base64.decode(m.mediaBase64, android.util.Base64.DEFAULT)
            dst.writeBytes(bytes)
            dst.absolutePath
        } catch (e: Throwable) {
            android.util.Log.e("wxnotes", "inline media failed", e)
            runCatching { dst.delete() }
            ""
        }
    }

    /** 通过抽象 LocalSocket 从微信进程取媒体（后台也能用，不受 loopback 网络限制）。 */
    private fun downloadViaLocalSocket(noteId: Long, m: ForwardMessage): String {
        val dir = File(mediaRoot(), noteId.toString())
        if (!dir.exists() && !dir.mkdirs()) return m.localPath
        val rest = m.localPath.removePrefix("lss://")
        val token = rest.substringBefore('/')
        val encoded = rest.substringAfter('/', "")
        if (token.isBlank() || encoded.isBlank()) return m.localPath
        val decodedName = runCatching {
            java.net.URLDecoder.decode(encoded.substringAfterLast('/'), "UTF-8")
        }.getOrDefault("")
        val name = m.fileName.ifBlank { decodedName.ifBlank { "media_${System.currentTimeMillis()}" } }
        val dst = uniqueFile(dir, name)
        var socket: android.net.LocalSocket? = null
        return try {
            socket = android.net.LocalSocket()
            socket.connect(
                android.net.LocalSocketAddress(
                    ForwardProtocol.MEDIA_SOCKET_NAME,
                    android.net.LocalSocketAddress.Namespace.ABSTRACT
                )
            )
            socket.soTimeout = 20_000
            socket.outputStream.write("GET $token/$encoded\n".toByteArray())
            socket.outputStream.flush()
            val input = java.io.BufferedInputStream(socket.inputStream)
            val header = readLine(input) ?: throw IllegalStateException("no header")
            if (!header.startsWith("OK ")) throw IllegalStateException(header)
            val len = header.substring(3).trim().toLong()
            dst.outputStream().use { out ->
                var remaining = len
                val buf = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
                if (remaining > 0) throw IllegalStateException("short read $remaining")
            }
            if (dst.length() > 0) dst.absolutePath else m.localPath
        } catch (e: Throwable) {
            android.util.Log.e("wxnotes", "localsocket media failed: ${m.localPath}", e)
            runCatching { dst.delete() }
            m.localPath
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun downloadMedia(noteId: Long, m: ForwardMessage): String {
        val dir = File(mediaRoot(), noteId.toString())
        if (!dir.exists() && !dir.mkdirs()) return m.localPath
        val urlName = m.localPath.substringAfterLast('/').substringBefore('?')
        val fallback = "media_${System.currentTimeMillis()}"
        val name = m.fileName.ifBlank { urlName.ifBlank { fallback } }
        val dst = uniqueFile(dir, name)
        return try {
            val conn = java.net.URL(m.localPath).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 120_000
            conn.requestMethod = "GET"
            conn.inputStream.use { input ->
                dst.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            }
            conn.disconnect()
            if (dst.length() > 0) dst.absolutePath else m.localPath
        } catch (e: Throwable) {
            android.util.Log.e("wxnotes", "download media failed: ${m.localPath}", e)
            runCatching { dst.delete() }
            m.localPath
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base-$i$ext")
            i++
        }
        return f
    }

    private fun isRealName(name: String): Boolean =
        name.isNotBlank() && !name.endsWith("@chatroom") && !name.startsWith("wxid_")

    private fun isDefaultTitle(title: String, talkerTitle: String): Boolean =
        talkerTitle.isNotBlank() && title.startsWith("$talkerTitle · ")

    private fun defaultTitle(talkerTitle: String): String {
        val day = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date())
        return "$talkerTitle · $day"
    }

    // ── 观察 ───────────────────────────────────────────────────────

    fun observeNotes(q: String) = noteDao.observeAll(q)
    fun observeNote(noteId: Long) = noteDao.observe(noteId)
    fun observeMessages(noteId: Long) = messageDao.observeByNote(noteId)
    fun observeTags(noteId: Long) = tagDao.observeTagsOf(noteId)
    fun observeAllTags() = tagDao.observeWithCount()
    fun observeNotesWithTag(tagId: Long) = tagDao.observeNotesWithTag(tagId)

    // ── 笔记编辑 ───────────────────────────────────────────────────

    suspend fun updateNote(note: NoteEntity) = withContext(Dispatchers.IO) {
        noteDao.update(note.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteNote(noteId: Long) = withContext(Dispatchers.IO) {
        deleteNoteInternal(noteId)
    }

    /** 批量删除：连同消息与媒体文件一起清掉。 */
    suspend fun deleteNotes(ids: List<Long>) = withContext(Dispatchers.IO) {
        ids.forEach { deleteNoteInternal(it) }
    }

    /** 修掉历史数据里"文本消息被误标为语音"的记录（旧版 findVoiceFile 按时间硬匹配导致）。 */
    suspend fun fixMisdetectedVoice() = withContext(Dispatchers.IO) {
        runCatching {
            messageDao.clearMisdetectedVoice(WxMessageParser.TYPE_TEXT, ForwardProtocol.MEDIA_VOICE)
            // 老版本保存的「等媒体」消息没有过期标记，界面会一直转圈：超过 5 分钟的直接标记为已过期
            val cutoff = System.currentTimeMillis() / 1000 - 300
            val n = messageDao.markStaleMediaUnavailable(cutoff)
            if (n > 0) android.util.Log.i("wxnotes", "marked $n stale media as unavailable")
        }
    }

    /** 删除单条消息（长按菜单用）：连带删掉它的媒体文件；笔记空了就把笔记也删掉。 */
    suspend fun deleteMessage(noteId: Long, messageId: Long) = withContext(Dispatchers.IO) {
        val msg = messageDao.getById(messageId)
        messageDao.deleteById(messageId)
        val path = msg?.mediaPath.orEmpty()
        if (path.isNotBlank()) {
            runCatching {
                val f = File(path)
                f.delete()
                val dir = f.parentFile
                if (dir != null && dir.name == noteId.toString() && dir.list()?.isEmpty() == true) {
                    dir.delete()
                }
            }
        }
        if (messageDao.countByNote(noteId) == 0) {
            deleteNoteInternal(noteId)
        } else {
            noteDao.get(noteId)?.let { noteDao.update(it.copy(updatedAt = System.currentTimeMillis())) }
        }
    }

    private suspend fun deleteNoteInternal(noteId: Long) {
        runCatching {
            File(mediaRoot(), noteId.toString()).deleteRecursively()
        }
        messageDao.deleteByNote(noteId)
        noteDao.delete(noteId)
    }

    suspend fun createManualNote(title: String): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        noteDao.insert(NoteEntity(title = title, createdAt = now, updatedAt = now, auto = false))
    }

    suspend fun renameNote(noteId: Long, title: String) = withContext(Dispatchers.IO) {
        noteDao.get(noteId)?.let { noteDao.update(it.copy(title = title, updatedAt = System.currentTimeMillis())) }
    }

    suspend fun setArchived(noteId: Long, archived: Boolean) = withContext(Dispatchers.IO) {
        noteDao.get(noteId)?.let { noteDao.update(it.copy(archived = archived, updatedAt = System.currentTimeMillis())) }
    }

    suspend fun setPinned(noteId: Long, pinned: Boolean) = withContext(Dispatchers.IO) {
        noteDao.get(noteId)?.let { noteDao.update(it.copy(pinned = pinned, updatedAt = System.currentTimeMillis())) }
    }

    // ── 标签 ───────────────────────────────────────────────────────

    suspend fun addTag(noteId: Long, name: String) = withContext(Dispatchers.IO) {
        val clean = name.trim().trimStart('#')
        if (clean.isBlank()) return@withContext
        val existing = tagDao.findByName(clean)
        val id = existing?.id ?: tagDao.insert(TagEntity(name = clean))
        if (id > 0) tagDao.link(NoteTagCrossRef(noteId, id))
    }

    suspend fun removeTag(noteId: Long, tagId: Long) = withContext(Dispatchers.IO) {
        tagDao.unlink(noteId, tagId)
    }

    suspend fun deleteTag(tagId: Long) = withContext(Dispatchers.IO) {
        tagDao.unlinkAll(tagId)
        tagDao.delete(tagId)
    }

    suspend fun tagsOf(noteId: Long): List<TagEntity> = tagDao.observeTagsOf(noteId).first()

    // ── 导出 ───────────────────────────────────────────────────────

    /**
     * 导出为 Obsidian 风格 Markdown：
     * - frontmatter（title/tags/source/created/updated/message_count）
     * - 用户正文
     * - 聊天记录时间线 + 媒体相对路径
     */
    suspend fun exportMarkdown(noteId: Long): File? = withContext(Dispatchers.IO) {
        val note = noteDao.get(noteId) ?: return@withContext null
        val messages = messageDao.getByNote(noteId)
        val tags = tagsOf(noteId)
        val now = Date()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(now)
        val slug = sanitize(note.title).ifBlank { "note" } + "_" + ts

        val baseDir = exportRoot() ?: return@withContext null
        val noteDir = File(baseDir, slug)
        val mediaDir = File(noteDir, "media")
        if (!mediaDir.exists()) mediaDir.mkdirs()

        val sb = StringBuilder()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        sb.appendLine("---")
        sb.appendLine("title: \"${note.title.replace("\"", "\\\"")}\"")
        sb.appendLine("created: ${iso.format(Date(note.createdAt))}")
        sb.appendLine("updated: ${iso.format(Date(note.updatedAt))}")
        sb.appendLine("source: 微信")
        if (note.talkerTitle.isNotBlank()) sb.appendLine("conversation: \"${note.talkerTitle}\"")
        if (tags.isNotEmpty()) sb.appendLine("tags: [${tags.joinToString(", ") { it.name }}]")
        sb.appendLine("message_count: ${messages.size}")
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("# ${note.title}")
        sb.appendLine()
        if (note.body.isNotBlank()) {
            sb.appendLine(note.body)
            sb.appendLine()
        }
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## 聊天记录")
        sb.appendLine()

        val dayFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        var lastDay = ""
        messages.forEach { m ->
            val timeMs = if (m.createTime > 0) m.createTime * 1000 else note.createdAt
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timeMs))
            if (day != lastDay) {
                sb.appendLine("### $day")
                sb.appendLine()
                lastDay = day
            }
            val who = when {
                m.isSend -> "我"
                m.senderName.isNotBlank() -> m.senderName
                note.talker.endsWith("@chatroom") -> m.sender.ifBlank { "群成员" }
                else -> note.talkerTitle
            }
            sb.appendLine("**${dayFmt.format(Date(timeMs))} · $who**")
            sb.appendLine()
            when {
                m.mediaKind == ForwardProtocol.MEDIA_IMAGE && m.mediaPath.isNotBlank() -> {
                    val rel = copyMediaToExport(m.mediaPath, mediaDir)
                    if (rel != null) sb.appendLine("![]($rel)") else sb.appendLine(m.text.ifBlank { "[图片]" })
                }
                m.mediaPath.isNotBlank() -> {
                    val rel = copyMediaToExport(m.mediaPath, mediaDir)
                    if (rel != null) {
                        val label = m.fileName.ifBlank { m.text.ifBlank { "附件" } }
                        sb.appendLine("[$label]($rel)")
                    } else {
                        sb.appendLine(m.text.ifBlank { "[附件]" })
                    }
                }
                else -> sb.appendLine(m.text.ifBlank { WxMessageParser.typeName(m.type) })
            }
            sb.appendLine()
        }

        val out = File(noteDir, "${sanitize(note.title).ifBlank { "note" }}.md")
        out.writeText(sb.toString())
        out
    }

    private fun copyMediaToExport(path: String, mediaDir: File): String? {
        val src = File(path)
        if (!src.exists() || !src.canRead()) return null
        return try {
            val dst = uniqueFile(mediaDir, src.name)
            src.copyTo(dst, overwrite = true)
            "media/${dst.name}"
        } catch (_: Throwable) {
            null
        }
    }

    private fun exportRoot(): File? {
        val public = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "wxnotes/exports")
        if (public.exists() || public.mkdirs()) return public
        val fallback = File(File(context.filesDir, "exports"), "").apply { mkdirs() }
        return if (fallback.exists()) fallback else null
    }

    private fun sanitize(name: String): String = name
        .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_")
        .trim()
        .take(60)

    companion object {
        const val KEY_SKIP_MEDIA_SCAN = "skip_media_scan"
        const val KEY_KEEP_RAW = "keep_raw_xml"
        const val KEY_AUTO_NOTIFY = "auto_notify"
    }
}
