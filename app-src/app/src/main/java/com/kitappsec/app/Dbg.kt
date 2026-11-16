package com.kitappsec.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log em Android/data/com.kitappsec.app/files/debug.log — não precisa de permissão de storage. */
object Dbg {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    fun file(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "debug.log")
    @Synchronized fun log(ctx: Context, msg: String) {
        Log.i("KitAppSec", msg)
        try {
            val f = file(ctx)
            if (f.length() > 256_000) f.writeText("")
            f.appendText("${fmt.format(Date())} $msg\n")
        } catch (e: Exception) { Log.e("KitAppSec", "falha ao gravar log", e) }
    }
    fun read(ctx: Context): String = try { file(ctx).readText().takeLast(20_000) } catch (e: Exception) { "(sem log: ${e.message})" }
}
