package com.nous.wxhook.root.libsu

import android.os.IInterface
import android.os.Parcel
import com.topjohnwu.superuser.Shell

class WxRootBinder : android.os.Binder(), IInterface {

    override fun asBinder(): android.os.IBinder = this

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            TRANSACTION_EXEC -> {
                val cmd = data.readString()
                val result = Shell.cmd(cmd ?: "").exec()
                reply?.writeInt(result.code)
                reply?.writeString(result.out.joinToString("\n"))
                reply?.writeString(result.err.joinToString("\n"))
                reply?.writeNoException()
                true
            }
            TRANSACTION_CHECK_ROOT -> {
                val isRoot = Shell.getShell().isRoot
                reply?.writeInt(if (isRoot) 1 else 0)
                reply?.writeNoException()
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    companion object {
        const val TRANSACTION_EXEC = android.os.IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_CHECK_ROOT = android.os.IBinder.FIRST_CALL_TRANSACTION + 2

        // App 端代理
        fun exec(shell: android.os.IBinder, command: String): Shell.Result {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(command)
                shell.transact(TRANSACTION_EXEC, data, reply, 0)
                reply.readException()
                val code = reply.readInt()
                val out = reply.createStringArrayList() ?: emptyList()
                val err = reply.createStringArrayList() ?: emptyList()
                return Shell.Result(code, out, err)
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

        private const val DESCRIPTOR = "com.nous.wxhook.root.libsu.WxRootBinder"
    }
}
