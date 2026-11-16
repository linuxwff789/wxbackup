package com.nous.wxnotes.ui

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 全局只允许一条语音在播。
 * playingId 是 Compose state，任何 VoiceContent 都能读到并正确高亮，
 * 避免 LazyColumn 复用导致"播放状态串到别的消息"。
 */
object VoicePlayback {

    var playingId by mutableStateOf<Long?>(null)
        private set

    private var player: MediaPlayer? = null

    fun toggle(context: Context, id: Long, path: String) {
        if (playingId == id) {
            stop()
            return
        }
        stop()
        runCatching {
            val p = MediaPlayer()
            p.setDataSource(path)
            p.prepare()
            p.setOnCompletionListener { stop() }
            p.start()
            player = p
            playingId = id
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingId = null
    }
}
