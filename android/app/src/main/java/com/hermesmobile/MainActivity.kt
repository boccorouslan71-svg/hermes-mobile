package com.hermesmobile

import android.content.Intent
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(
            this,
            Intent(this, com.hermesmobile.runtime.HermesForegroundService::class.java)
        )

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webViewClient = WebViewClient()
        }
        setContentView(webView)
        // The bundled page is available immediately. It polls localhost until
        // HermesLocalServer is ready, avoiding ERR_CONNECTION_REFUSED on launch.
        webView.loadUrl("file:///android_asset/web/index.html")
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
