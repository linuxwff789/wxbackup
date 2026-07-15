package com.nous.wxhook.root.libsu

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.util.Log
import com.nous.wxhook.backup.NativeArchive
import com.topjohnwu.superuser.Shell
import java.io.File

class WxRootBinder : android.os.Binder(), IInterface {

    override fun asBinder(): android.os.IBinder = this

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code in TRANSACTION_EXEC..TRANSACTION_VERIFY_TAR_ZSTD) {
            data.enforceInterface(DESCRIPTOR)
        }
        return when (code) {
            TRANSACTION_EXEC -> {
                val cmd = data.readString()
                val result = Shell.cmd(cmd ?: "").exec()
                reply?.writeNoException()
                reply?.writeInt(result.code)
                reply?.writeString(result.out.joinToString("\n"))
                reply?.writeString(result.err.joinToString("\n"))
                true
            }
            TRANSACTION_CHECK_ROOT -> {
                val isRoot = Shell.getShell().isRoot
                reply?.writeNoException()
                reply?.writeInt(if (isRoot) 1 else 0)
                true
            }
            TRANSACTION_WRITE_FILE -> {
                val path = data.readString()
                val content = data.readString()
                val result = try {
                    val file = File(path)
                    file.parentFile?.mkdirs()
                    file.writeText(content ?: "")
                    0 // success
                } catch (e: Exception) {
                    -1 // failure
                }
                reply?.writeNoException()
                reply?.writeInt(result)
                true
            }
            TRANSACTION_READ_FILE -> {
                val path = data.readString()
                val content = try {
                    File(path).readText()
                } catch (e: Exception) {
                    ""
                }
                reply?.writeNoException()
                reply?.writeString(content)
                true
            }
            TRANSACTION_MKDIRS -> {
                val path = data.readString()
                val result = try {
                    File(path).mkdirs()
                    0
                } catch (e: Exception) {
                    -1
                }
                reply?.writeNoException()
                reply?.writeInt(result)
                true
            }
            TRANSACTION_FILE_EXISTS -> {
                val path = data.readString()
                val exists = File(path).exists()
                reply?.writeNoException()
                reply?.writeInt(if (exists) 1 else 0)
                true
            }
            TRANSACTION_FILE_SIZE -> {
                val path = data.readString()
                val size = try {
                    File(path).length()
                } catch (e: Exception) {
                    0L
                }
                reply?.writeNoException()
                reply?.writeLong(size)
                true
            }
            TRANSACTION_COPY -> {
                val src = data.readString()
                val dst = data.readString()
                val result = try {
                    File(src).copyTo(File(dst!!), overwrite = true)
                    0
                } catch (e: Exception) {
                    -1
                }
                reply?.writeNoException()
                reply?.writeInt(result)
                true
            }
            TRANSACTION_DELETE -> {
                val path = data.readString()
                val result = try {
                    File(path).delete()
                    0
                } catch (e: Exception) {
                    -1
                }
                reply?.writeNoException()
                reply?.writeInt(result)
                true
            }
            TRANSACTION_WRITE_TAR_ZSTD -> {
                val outputPath = data.readString()
                val pairs = data.createStringArray()
                val result = try {
                    NativeArchive.writeTarZstd(outputPath ?: "", pairs ?: emptyArray())
                } catch (e: Throwable) {
                    Log.e("wxhook:archive", "write JNI failed", e)
                    -1
                }
                reply?.writeNoException()
                reply?.writeInt(result)
                true
            }
            TRANSACTION_VERIFY_TAR_ZSTD -> {
                val archivePath = data.readString()
                val result = try {
                    NativeArchive.verifyTarZstd(archivePath ?: "")
                } catch (e: Throwable) {
                    Log.e("wxhook:archive", "verify JNI failed", e)
                    -1
                }
                reply?.writeNoException()
                reply?.writeInt(result)
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    companion object {
        const val TRANSACTION_EXEC = android.os.IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_CHECK_ROOT = android.os.IBinder.FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_WRITE_FILE = android.os.IBinder.FIRST_CALL_TRANSACTION + 3
        const val TRANSACTION_READ_FILE = android.os.IBinder.FIRST_CALL_TRANSACTION + 4
        const val TRANSACTION_MKDIRS = android.os.IBinder.FIRST_CALL_TRANSACTION + 5
        const val TRANSACTION_FILE_EXISTS = android.os.IBinder.FIRST_CALL_TRANSACTION + 6
        const val TRANSACTION_FILE_SIZE = android.os.IBinder.FIRST_CALL_TRANSACTION + 7
        const val TRANSACTION_COPY = android.os.IBinder.FIRST_CALL_TRANSACTION + 8
        const val TRANSACTION_DELETE = android.os.IBinder.FIRST_CALL_TRANSACTION + 9
        const val TRANSACTION_WRITE_TAR_ZSTD = android.os.IBinder.FIRST_CALL_TRANSACTION + 10
        const val TRANSACTION_VERIFY_TAR_ZSTD = android.os.IBinder.FIRST_CALL_TRANSACTION + 11
        private const val DESCRIPTOR = "com.nous.wxhook.root.libsu.WxRootBinder"

        fun exec(shell: android.os.IBinder, command: String): ExecResult {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(command)
                shell.transact(TRANSACTION_EXEC, data, reply, 0)
                reply.readException()
                val code = reply.readInt()
                val out = reply.readString()?.lineSequence()?.filter { it.isNotEmpty() }?.toList() ?: emptyList()
                val err = reply.readString()?.lineSequence()?.filter { it.isNotEmpty() }?.toList() ?: emptyList()
                return ExecResult(code, out, err)
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun writeFile(shell: android.os.IBinder, path: String, content: String): Int {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(path)
                data.writeString(content)
                shell.transact(TRANSACTION_WRITE_FILE, data, reply, 0)
                reply.readException()
                return reply.readInt()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun readFile(shell: android.os.IBinder, path: String): String {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(path)
                shell.transact(TRANSACTION_READ_FILE, data, reply, 0)
                reply.readException()
                return reply.readString() ?: ""
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun mkdirs(shell: android.os.IBinder, path: String): Int {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(path)
                shell.transact(TRANSACTION_MKDIRS, data, reply, 0)
                reply.readException()
                return reply.readInt()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun fileExists(shell: android.os.IBinder, path: String): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(path)
                shell.transact(TRANSACTION_FILE_EXISTS, data, reply, 0)
                reply.readException()
                return reply.readInt() == 1
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun fileSize(shell: android.os.IBinder, path: String): Long {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(path)
                shell.transact(TRANSACTION_FILE_SIZE, data, reply, 0)
                reply.readException()
                return reply.readLong()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun copy(shell: android.os.IBinder, src: String, dst: String): Int {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(src)
                data.writeString(dst)
                shell.transact(TRANSACTION_COPY, data, reply, 0)
                reply.readException()
                return reply.readInt()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun delete(shell: android.os.IBinder, path: String): Int {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(path)
                shell.transact(TRANSACTION_DELETE, data, reply, 0)
                reply.readException()
                return reply.readInt()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun writeTarZstd(shell: android.os.IBinder, outputPath: String, pairs: Array<String>): Int = transactInt(
            shell,
            TRANSACTION_WRITE_TAR_ZSTD,
        ) { data ->
            data.writeString(outputPath)
            data.writeStringArray(pairs)
        }

        fun verifyTarZstd(shell: android.os.IBinder, archivePath: String): Int = transactInt(
            shell,
            TRANSACTION_VERIFY_TAR_ZSTD,
        ) { data -> data.writeString(archivePath) }

        private fun transactInt(
            shell: android.os.IBinder,
            transaction: Int,
            write: (Parcel) -> Unit,
        ): Int {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                write(data)
                shell.transact(transaction, data, reply, 0)
                reply.readException()
                return reply.readInt()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun checkRoot(shell: android.os.IBinder): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                shell.transact(TRANSACTION_CHECK_ROOT, data, reply, 0)
                reply.readException()
                return reply.readInt() == 1
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    data class ExecResult(val code: Int, val out: List<String>, val err: List<String>)
}
