package com.kitappsec.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/** Recebe o resultado que o Termux devolve via PendingIntent (RUN_COMMAND_PENDING_INTENT). */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: run { Dbg.log(ctx, "result sem id"); return }
        val b = intent.getBundleExtra("result")
        val json = JSONObject()
        if (b == null) {
            json.put("out", "").put("err", "Termux devolveu resultado vazio").put("code", -1)
        } else {
            val errCode = b.getInt("err", -1)          // -1 = RESULT_CODE_OK no Termux
            val errmsg = b.getString("errmsg") ?: ""
            val stdout = b.getString("stdout") ?: ""
            val stderr = b.getString("stderr") ?: ""
            val exit = if (b.containsKey("exitCode")) b.getInt("exitCode") else -1
            json.put("out", (stdout + if (stderr.isNotEmpty()) "\n$stderr" else "").trimEnd())
            json.put("err", if (errCode != -1 || errmsg.isNotEmpty()) "Termux err=$errCode $errmsg" else "")
            json.put("code", exit)
            Dbg.log(ctx, "result id=$id exit=$exit err=$errCode outLen=${stdout.length} errmsg=$errmsg")
        }
        ResultBus.deliver(id, json.toString())
    }
    companion object { const val EXTRA_ID = "kit_id" }
}

object ResultBus {
    @Volatile var sink: ((String, String) -> Unit)? = null
    fun deliver(id: String, json: String) { sink?.invoke(id, json) }
}
