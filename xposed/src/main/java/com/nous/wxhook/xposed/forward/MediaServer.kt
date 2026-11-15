package com.nous.wxhook.xposed.forward

import android.net.LocalServerSocket
import android.net.LocalSocket
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * 微信进程内的本地媒体服务器（仅 127.0.0.1）。
 *
 * 插件能直接读微信私有目录里的图片/语音/视频，但笔记 App 没有权限。
 * 因此由插件把文件通过回环 HTTP 暴露出去，笔记 App 收到批次后按 URL 下载。
 * URL 带一次性随机 token，只有拿到广播内容的接收方才能访问。
 */
object MediaServer {

    private const val TAG = "[wxhook:Media]"
    private const val PREFIX = "/m/"

    private var server: ServerSocket? = null
    private var localServer: LocalServerSocket? = null

    /** notes App 通过抽象 Unix socket 取文件（不受后台网络限制） */
    const val SOCKET_NAME = com.nous.wxshared.ForwardProtocol.MEDIA_SOCKET_NAME

    @Volatile
    var port: Int = 0
        private set

    val token: String by lazy {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun ensureStarted() {
        if (server != null) return
        try {
            val s = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            server = s
            port = s.localPort
            Thread({
                while (!s.isClosed) {
                    val socket = try {
                        s.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    Thread({ handle(socket) }, "wxfwd-media-conn").apply {
                        isDaemon = true
                        start()
                    }
                }
            }, "wxfwd-media").apply {
                isDaemon = true
                start()
            }
            XposedBridge.log("$TAG listening on 127.0.0.1:$port")

            val ls = LocalServerSocket(SOCKET_NAME)
            localServer = ls
            Thread({
                while (true) {
                    val socket = try {
                        ls.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    Thread({ handleLocal(socket) }, "wxfwd-lss-conn").apply {
                        isDaemon = true
                        start()
                    }
                }
            }, "wxfwd-lss").apply {
                isDaemon = true
                start()
            }
            XposedBridge.log("$TAG local socket @$SOCKET_NAME ready token=$token")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG start failed: ${e.message}")
        }
    }

    fun urlFor(path: String): String? = uriFor(path)

    /** 生成给接收端的媒体 URI（抽象 LocalSocket，优先级高于 HTTP）。 */
    fun uriFor(path: String): String? {
        if (path.isBlank()) return null
        ensureStarted()
        val encoded = try {
            URLEncoder.encode(path, "UTF-8")
        } catch (_: Throwable) {
            return null
        }
        return "lss://$token/$encoded"
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val line = readLine(input) ?: return
            // 读掉请求头
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
            }
            val parts = line.split(' ')
            if (parts.size < 2 || parts[0] != "GET") {
                writeStatus(socket.getOutputStream(), "405 Method Not Allowed")
                return
            }
            val path = parts[1]
            val expected = "$PREFIX$token/"
            if (!path.startsWith(expected)) {
                writeStatus(socket.getOutputStream(), "403 Forbidden")
                return
            }
            val realPath = URLDecoder.decode(path.removePrefix(expected), "UTF-8")
            val file = File(realPath)
            if (!file.isFile || !file.canRead()) {
                writeStatus(socket.getOutputStream(), "404 Not Found")
                return
            }
            val out = socket.getOutputStream()
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/octet-stream\r\n")
                append("Content-Length: ${file.length()}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            out.write(header.toByteArray())
            file.inputStream().use { it.copyTo(out, 64 * 1024) }
            out.flush()
        } catch (_: Throwable) {
            // 连接中断无所谓
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    /** 自定义协议：一行 "GET <token>/<urlencoded-path>\n"，回 "OK <len>\n"+bytes 或 "ERR xxx\n" */
    private fun handleLocal(socket: LocalSocket) {
        try {
            socket.soTimeout = 20_000
            val input = socket.inputStream
            val line = readLine(input) ?: return
            val path = line.trim().removePrefix("GET ").trim()
            val expected = "$token/"
            val out = socket.outputStream
            if (!path.startsWith(expected)) {
                out.write("ERR forbidden\n".toByteArray())
                out.flush()
                return
            }
            val realPath = URLDecoder.decode(path.removePrefix(expected), "UTF-8")
            val file = File(realPath)
            if (!file.isFile || !file.canRead()) {
                out.write("ERR not found\n".toByteArray())
                out.flush()
                return
            }
            out.write("OK ${file.length()}\n".toByteArray())
            file.inputStream().use { it.copyTo(out, 64 * 1024) }
            out.flush()
        } catch (_: Throwable) {
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun writeStatus(out: OutputStream, status: String) {
        try {
            out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        } catch (_: Throwable) {
        }
    }
}
