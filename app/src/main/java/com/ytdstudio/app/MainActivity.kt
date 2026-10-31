package com.ytdstudio.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView

    private val pickCookies = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { pickDone(""); return@registerForActivityResult }
        thread {
            val name = try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    Engine.cookieFile().outputStream().use { input.copyTo(it) }
                }
                displayName(uri)
            } catch (e: Exception) { "" }
            runOnUiThread { pickDone(name.ifEmpty { "cookies.txt" }.takeIf { Engine.cookieFile().isFile } ?: "") }
        }
    }

    private var lastClip = ""

    /** Copied a link? Fill the URL box when you come back to the app. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !::web.isInitialized) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        val link = Regex("""https?://[^\s<>"']+""").find(text)?.value ?: return
        if (link == lastClip) return
        lastClip = link
        web.evaluateJavascript(
            "(function(){var u=document.querySelector('#url'); if(u && !u.value){u.value=${JSONObject.quote(link)};}})()", null
        )
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(0xFF0D0D0D.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.textZoom = 100
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    // never navigate away from the app UI
                    return r.url.scheme != "file"
                }
                override fun onPageFinished(v: WebView, url: String) {
                    v.evaluateJavascript("document.documentElement.style.zoom='0.85'", null)
                    handleShare(intent)
                }
            }
            addJavascriptInterface(Bridge(), "Android")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })

        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** Link shared from the YouTube app -> filled into the URL box. */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        i.action = null
        web.evaluateJavascript(
            "(function(){var u=document.querySelector('#url'); if(u){u.value=${JSONObject.quote(text.trim())}; u.focus();}})()", null
        )
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        } ?: "cookies.txt"

    private fun pickDone(path: String) {
        web.evaluateJavascript("window.__pickDone(${JSONObject.quote(path)})", null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun call(route: String, body: String): String = Engine.call(route, body)

        @JavascriptInterface
        fun pick(kind: String) {
            runOnUiThread {
                if (kind == "file") pickCookies.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
                else pickDone("") // folder is fixed: Downloads/YTD Studio
            }
        }

        @JavascriptInterface
        fun clipboard(): String {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
        }
    }
}
