package com.nous.wxhook.root

import android.content.Context
import android.util.Log
import com.nous.wxhook.core.command.CommandResult
import com.nous.wxhook.core.command.ShellEscaper
import com.nous.wxhook.rootbridge.RootCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootGatewayImpl(private val context: Context? = null) : RootGateway {

    private var useLibsu = false

    suspend fun ensureRootService(): Boolean {
        val appContext = context ?: return false
        return try {
            com.nous.wxhook.root.libsu.RootManager.ensureConnected(appContext).also { connected ->
                useLibsu = connected && com.nous.wxhook.root.libsu.RootManager.currentBinder() != null
                Log.i("wxhook:Root", "RootService ensureConnected=$connected binder=${useLibsu}")
            }
        } catch (e: Exception) {
            useLibsu = false
            Log.e("wxhook:Root", "RootService ensureConnected failed", e)
            false
        }
    }

    suspend fun initialize(): RootStatus {
        val status = check()
        if (status.available) ensureRootService()
        return status
    }

    override suspend fun check(): RootStatus = withContext(Dispatchers.IO) {
        if (useLibsu) {
            val result = com.nous.wxhook.root.libsu.RootManager.exec("id -u", 5_000)
            if (result.isSuccess) {
                RootStatus(true, result.stdout.trim().toIntOrNull() ?: -1)
            } else {
                RootStatus(false, message = result.stderr)
            }
        } else {
            val r = RootCommandRunner.runSu("id -u", 5_000)
            if (r.isSuccess) {
                RootStatus(true, r.stdout.trim().toIntOrNull() ?: -1)
            } else {
                RootStatus(false, message = r.stderr)
            }
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        if (useLibsu) {
            com.nous.wxhook.root.libsu.RootManager.fileExists(path)
        } else {
            run("test -e ${ShellEscaper.quote(path)} && echo 1 || echo 0").let {
                it.isSuccess && it.stdout.trim() == "1"
            }
        }
    }

    override suspend fun stat(path: String): FileMetadata? = withContext(Dispatchers.IO) {
        val r = run("stat -c '%d %g %s %f' ${ShellEscaper.quote(path)} 2>/dev/null")
        if (!r.isSuccess) return@withContext null
        val parts = r.stdout.trim().split(" ")
        if (parts.size < 4) return@withContext null
        FileMetadata(
            path = path,
            exists = true,
            isDirectory = parts[3].first() == '4',
            uid = parts[0].toIntOrNull() ?: 0,
            gid = parts[1].toIntOrNull() ?: 0,
            size = parts[2].toLongOrNull() ?: 0,
            mode = parts[3],
        )
    }

    override suspend fun mkdirs(path: String): Boolean = withContext(Dispatchers.IO) {
        if (useLibsu) {
            com.nous.wxhook.root.libsu.RootManager.mkdirs(path)
        } else {
            run("mkdir -p ${ShellEscaper.quote(path)}").isSuccess
        }
    }

    override suspend fun copy(src: String, dst: String): Boolean = withContext(Dispatchers.IO) {
        if (useLibsu) {
            com.nous.wxhook.root.libsu.RootManager.copy(src, dst)
        } else {
            run("cp ${ShellEscaper.quote(src)} ${ShellEscaper.quote(dst)}").isSuccess
        }
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        if (useLibsu) {
            com.nous.wxhook.root.libsu.RootManager.delete(path)
        } else {
            run("rm -f ${ShellEscaper.quote(path)}").isSuccess
        }
    }

    override suspend fun writeTarZstd(outputPath: String, pairsPath: String, useZstd: Boolean): Int =
        withContext(Dispatchers.IO) {
            val binder = com.nous.wxhook.root.libsu.RootManager.currentBinder()
                ?: return@withContext -1
            com.nous.wxhook.root.libsu.WxRootBinder.writeTarZstd(binder, outputPath, pairsPath, useZstd)
        }

    override suspend fun webdavUpload(url: String, user: String, pass: String, filePath: String): Boolean =
        withContext(Dispatchers.IO) {
            val binder = com.nous.wxhook.root.libsu.RootManager.currentBinder()
                ?: return@withContext false
            com.nous.wxhook.root.libsu.WxRootBinder.webdavUpload(binder, url, user, pass, filePath) == 0
        }

    override suspend fun verifyTarZstd(archivePath: String): Int = withContext(Dispatchers.IO) {
        val binder = com.nous.wxhook.root.libsu.RootManager.currentBinder() ?: return@withContext -1
        com.nous.wxhook.root.libsu.WxRootBinder.verifyTarZstd(binder, archivePath)
    }

    override suspend fun readFileFromTar(archivePath: String, filePath: String): String = withContext(Dispatchers.IO) {
        val binder = com.nous.wxhook.root.libsu.RootManager.currentBinder() ?: return@withContext ""
        com.nous.wxhook.root.libsu.WxRootBinder.readFileFromTar(binder, archivePath, filePath)
    }

    override suspend fun listTar(archivePath: String): String = withContext(Dispatchers.IO) {
        val binder = com.nous.wxhook.root.libsu.RootManager.currentBinder() ?: return@withContext ""
        com.nous.wxhook.root.libsu.WxRootBinder.listTar(binder, archivePath)
    }

    override suspend fun getTarSqlMaxRowId(archivePath: String, filePath: String): Long = withContext(Dispatchers.IO) {
        val binder = com.nous.wxhook.root.libsu.RootManager.currentBinder() ?: return@withContext 0L
        com.nous.wxhook.root.libsu.WxRootBinder.getTarSqlMaxRowId(binder, archivePath, filePath)
    }

    override suspend fun getFullArchiveRowId(archivePath: String, hash: String): Long = withContext(Dispatchers.IO) {
        val binder = com.nous.wxhook.root.libsu.RootManager.currentBinder() ?: return@withContext 0L
        com.nous.wxhook.root.libsu.WxRootBinder.getFullArchiveRowId(binder, archivePath, hash)
    }

    override suspend fun pollFullArchiveRowId(hash: String): Long = withContext(Dispatchers.IO) {
        val binder = com.nous.wxhook.root.libsu.RootManager.currentBinder() ?: return@withContext -2L
        com.nous.wxhook.root.libsu.WxRootBinder.pollFullArchiveRowId(binder, hash)
    }

    override suspend fun run(command: String, timeoutMs: Long): CommandResult = exec(command, timeoutMs)

    override suspend fun exec(command: String, timeoutMs: Long): CommandResult = withContext(Dispatchers.IO) {
        withContext(Dispatchers.IO) {
            if (useLibsu) {
                com.nous.wxhook.root.libsu.RootManager.exec(command, timeoutMs)
            } else {
                RootCommandRunner.runSu(command, timeoutMs)
            }
        }
    }

    override suspend fun runQuiet(command: String, timeoutMs: Long): String =
        withContext(Dispatchers.IO) {
            if (useLibsu) {
                com.nous.wxhook.root.libsu.RootManager.execQuiet(command, timeoutMs)
            } else {
                RootCommandRunner.runSuQuiet(command, timeoutMs)
            }
        }

    // 文件操作 - 在 root 进程执行
    override suspend fun writeFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        if (useLibsu) {
            com.nous.wxhook.root.libsu.RootManager.writeFile(path, content)
        } else {
            // fallback: 写临时文件再 su 复制
            val tmp = java.io.File.createTempFile("root_", ".tmp")
            tmp.writeText(content)
            try {
                copy(tmp.absolutePath, path)
            } finally {
                tmp.delete()
            }
        }
    }

    override suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        if (useLibsu) {
            com.nous.wxhook.root.libsu.RootManager.readFile(path)
        } else {
            runQuiet("cat ${ShellEscaper.quote(path)} 2>/dev/null")
        }
    }

    override suspend fun fileSize(path: String): Long = withContext(Dispatchers.IO) {
        if (useLibsu) {
            com.nous.wxhook.root.libsu.RootManager.fileSize(path)
        } else {
            runQuiet("stat -c %s ${ShellEscaper.quote(path)} 2>/dev/null").trim().toLongOrNull() ?: 0L
        }
    }
}
