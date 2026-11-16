package com.kitappsec.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    private lateinit var web: WebView
    private val prefs by lazy { getSharedPreferences("kit", MODE_PRIVATE) }
    private val reqCounter = AtomicInteger(1)
    private val uiFile by lazy { File(filesDir, "ui/index.html") }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Dbg.log(this, "==== onCreate v${BuildConfigCompat.versionName(this)} sdk=${Build.VERSION.SDK_INT}")

        web = WebView(this)
        setContentView(web)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // Substitui o termuxRun por arquivo (quebrado: fetch() não lê file:// no WebView)
                // pelo retorno direto do Termux via PendingIntent. Funciona com qualquer index.html do OTA.
                view.evaluateJavascript(POLYFILL, null)
            }
        }
        web.addJavascriptInterface(TermuxBridge(), "Termux")
        web.addJavascriptInterface(AppBridge(), "App")

        ResultBus.sink = { id, json ->
            runOnUiThread { web.evaluateJavascript("window.__termuxResult&&__termuxResult(${JSONObject.quote(id)},$json)", null) }
        }

        ensureRunCommandPermission()
        loadUi()
        checkOta(force = false)
    }

    override fun onDestroy() { ResultBus.sink = null; super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed() }

    private fun loadUi() {
        val url = if (uiFile.exists()) "file://${uiFile.absolutePath}" else "file:///android_asset/index.html"
        Dbg.log(this, "loadUi $url")
        web.loadUrl(url)
    }

    // ---------- Permissão RUN_COMMAND ----------
    private fun hasRunCommand() = checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    private fun ensureRunCommandPermission() {
        val granted = hasRunCommand()
        Dbg.log(this, "RUN_COMMAND granted=$granted termuxInstalled=${termuxInstalled()}")
        if (!granted && termuxInstalled()) requestPermissions(arrayOf(PERM), 42)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Dbg.log(this, "permResult ${permissions.joinToString()} -> ${grantResults.joinToString()}")
        if (!hasRunCommand()) toast("Sem permissão RUN_COMMAND: Config > Apps > Kit AppSec IA > Permissões > Termux")
    }

    private fun termuxInstalled(): Boolean = try {
        packageManager.getPackageInfo(TERMUX, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    // ---------- Execução no Termux ----------
    /** Retorna "" se despachou, ou a mensagem de erro. */
    private fun dispatch(cmd: String, id: String?): String {
        if (!termuxInstalled()) return "Termux não encontrado (com.termux)."
        if (!hasRunCommand()) {
            runOnUiThread { ensureRunCommandPermission() }
            return "Permissão RUN_COMMAND não concedida ao Kit AppSec IA."
        }
        val intent = Intent().apply {
            setClassName(TERMUX, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            if (id != null) {
                val back = Intent(this@MainActivity, TermuxResultReceiver::class.java)
                    .putExtra(TermuxResultReceiver.EXTRA_ID, id)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE // Termux precisa preencher os extras
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",
                    PendingIntent.getBroadcast(this@MainActivity, reqCounter.getAndIncrement(), back, flags))
            }
        }
        Dbg.log(this, "dispatch id=$id cmd=${cmd.take(160)}")
        return try {
            val cn = startService(intent)
            Dbg.log(this, "startService -> $cn")
            if (cn == null) "Termux recusou o serviço (RunCommandService não encontrado — atualize o Termux pelo F-Droid)." else ""
        } catch (e: IllegalStateException) {
            Dbg.log(this, "startService ISE ${e.message}; tentando startForegroundService")
            try { startForegroundService(intent); "" } catch (e2: Exception) { "Falha ao iniciar Termux: ${e2.javaClass.simpleName}: ${e2.message}" }
        } catch (e: SecurityException) {
            Dbg.log(this, "SecurityException ${e.message}")
            "SecurityException: ${e.message} — confira allow-external-apps=true em ~/.termux/termux.properties e rode termux-reload-settings."
        } catch (e: Exception) {
            Dbg.log(this, "erro ${e.javaClass.simpleName}: ${e.message}")
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    inner class TermuxBridge {
        /** Fire-and-forget (compatível com o index.html antigo). */
        @JavascriptInterface fun run(cmd: String) { val e = dispatch(cmd, null); if (e.isNotEmpty()) toast(e) }

        /** Assíncrono: resultado chega em window.__termuxResult(id, {out,err,code}). */
        @JavascriptInterface fun exec(id: String, cmd: String): String = dispatch(cmd, id)

        @JavascriptInterface fun diag(): String {
            val ver = try { packageManager.getPackageInfo(TERMUX, 0).versionName } catch (e: Exception) { null }
            return JSONObject()
                .put("termuxInstalado", ver != null).put("termuxVersao", ver ?: "-")
                .put("runCommand", hasRunCommand())
                .put("android", Build.VERSION.RELEASE).put("app", BuildConfigCompat.versionName(this@MainActivity))
                .put("logPath", Dbg.file(this@MainActivity).absolutePath).toString()
        }

        @JavascriptInterface fun log(): String = Dbg.read(this@MainActivity)
    }

    // ---------- OTA (UI em filesDir/ui — não depende de permissão de storage) ----------
    private fun updateBase(): String = prefs.getString("ota", DEFAULT_OTA)!!.trimEnd('/')

    private fun checkOta(force: Boolean) {
        Thread {
            try {
                val remote = httpGet("${updateBase()}/version.txt").trim().toIntOrNull()
                    ?: throw IllegalStateException("version.txt inválido")
                val local = prefs.getInt("otaVer", 0)
                Dbg.log(this, "OTA remote=$remote local=$local")
                if (remote > local || (force && !uiFile.exists())) {
                    val html = httpGet("${updateBase()}/index.html")
                    require(html.contains("<html", ignoreCase = true)) { "index.html remoto inválido" }
                    uiFile.parentFile?.mkdirs()
                    uiFile.writeText(html)
                    prefs.edit().putInt("otaVer", remote).apply()
                    runOnUiThread { toast("UI atualizada para v$remote"); loadUi() }
                } else if (force) runOnUiThread { toast("Já está na versão mais nova (v$local)") }
            } catch (e: Exception) {
                Dbg.log(this, "OTA falhou: ${e.javaClass.simpleName}: ${e.message}")
                if (force) runOnUiThread { toast("OTA falhou: ${e.message}") }
            }
        }.start()
    }

    private fun httpGet(u: String): String {
        val c = URL("$u?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000; c.useCaches = false
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode} em $u")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    inner class AppBridge {
        @JavascriptInterface fun checkUpdate() = checkOta(force = true)
        @JavascriptInterface fun getUpdateUrl(): String = updateBase()
        @JavascriptInterface fun setUpdateUrl(u: String) {
            if (!u.startsWith("https://")) { toast("Use uma URL https://"); return }
            prefs.edit().putString("ota", u.trim()).putInt("otaVer", 0).apply()
            checkOta(force = true)
        }
        @JavascriptInterface fun otaVersion(): Int = prefs.getInt("otaVer", 0)
        @JavascriptInterface fun uiPath(): String = if (uiFile.exists()) uiFile.absolutePath else "assets/index.html (embutido)"
        @JavascriptInterface fun hasStorage(): Boolean = true // UI fica em armazenamento interno
        @JavascriptInterface fun requestStorage() {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                runOnUiThread {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }
        @JavascriptInterface fun reload() = runOnUiThread { loadUi() }
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }

    companion object {
        const val TERMUX = "com.termux"
        const val PERM = "com.termux.permission.RUN_COMMAND"
        const val DEFAULT_OTA = "https://raw.githubusercontent.com/fcarvalho-dev/APPSEC/main"

        private val POLYFILL = """
(function(){
  if(!(window.Termux&&Termux.exec)||window.__kitPatched) return; window.__kitPatched=1;
  var cbs={};
  window.__termuxResult=function(id,r){var c=cbs[id];if(c){delete cbs[id];c(r);}};
  window.termuxRun=function(cmd,timeoutMs){
    timeoutMs=timeoutMs||180000;
    return new Promise(function(res){
      var id='k'+Date.now()+Math.random().toString(36).slice(2,7);
      var t=setTimeout(function(){delete cbs[id];res({out:'',err:'Tempo esgotado ('+Math.round(timeoutMs/1000)+'s). Toque em 🩺 pra ver o diagnóstico.',code:-1});},timeoutMs);
      cbs[id]=function(r){clearTimeout(t);res(r);};
      var e=Termux.exec(id,cmd);
      if(e){clearTimeout(t);delete cbs[id];res({out:'',err:e,code:-1});}
    });
  };
  var b=document.createElement('button');b.textContent='🩺';
  b.style.cssText='position:fixed;right:10px;bottom:10px;z-index:99999;width:42px;height:42px;border-radius:50%;border:0;background:#222;color:#fff;font-size:20px;opacity:.75';
  b.onclick=function(){
    var d=JSON.parse(Termux.diag());
    termuxRun('echo ok-termux; whoami',20000).then(function(r){
      alert('Termux: '+(d.termuxInstalado?d.termuxVersao:'NÃO instalado')+'\nRUN_COMMAND: '+(d.runCommand?'ok':'NEGADA')+
        '\nTeste: '+(r.code===0?'✅ '+r.out:'❌ '+(r.err||r.out))+'\n\nLog: '+d.logPath+'\n\n'+Termux.log().split('\n').slice(-15).join('\n'));
    });
  };
  document.body&&document.body.appendChild(b);
})();
"""
    }
}

object BuildConfigCompat {
    fun versionName(a: Activity): String =
        try { a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
}
