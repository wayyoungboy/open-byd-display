package org.openbyddisplay.vehicle

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder

/** Same external-provider read path as `adb shell content query`, under the real shell UID. */
internal class ShellNativeModeReader(context: Context) : AutoCloseable {
    private val managerType = Class.forName("android.app.IActivityManager")
    private val manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
    private val token = Binder()
    private val provider: Any
    private val query = Class.forName("android.content.IContentProvider").methods.single {
        it.name == "query" && it.parameterTypes.size == 5 && it.parameterTypes[0] == String::class.java
    }
    init {
        check(android.os.Process.myUid() == 2000 &&
            context.packageManager.getApplicationInfo("com.android.shell", 0).uid == android.os.Process.myUid()) {
            "读取原生设置需要真实 shell 身份"
        }
        val holder = managerType.getMethod("getContentProviderExternal", String::class.java,
            Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(manager, "carsettings", 0, token, "openbyddisplay-headlight-follow")
            ?: error("原生设置服务不可用")
        provider = holder.javaClass.getField("provider").get(holder) ?: error("原生设置服务未返回接口")
    }

    fun read(): Int {
        val args = Bundle().apply {
            putString("android:query-arg-sql-selection", "key = ?")
            putStringArray("android:query-arg-sql-selection-args", arrayOf("sys_screen_mode"))
        }
        val cursor = query.invoke(provider, "com.android.shell", URI, arrayOf("value"), args, null) as Cursor?
        return cursor?.use {
            val column = it.getColumnIndex("value")
            if (it.count == 1 && it.moveToFirst() && column >= 0) it.getString(column)?.toIntOrNull() ?: -1 else -1
        } ?: -1
    }

    override fun close() {
        managerType.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java)
            .invoke(manager, "carsettings", token)
    }
    companion object { private val URI = Uri.parse("content://carsettings/global") }
}
