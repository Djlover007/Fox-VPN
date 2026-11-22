package com.djlover007.foxvpn

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayInputStream

/** Privater Browser: kein Cache, keine Drittanbieter-Cookies, Werbe-/Tracker-Blocker. */
@SuppressLint("SetJavaScriptEnabled")
class FoxBrowser(context: Context) {
    var url by mutableStateOf("")
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var progress by mutableIntStateOf(100)
    var blocked by mutableIntStateOf(0)

    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val host = request?.url?.host ?: return null
                if (AdBlock.isBlocked(host)) {
                    view?.post { blocked += 1 }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme ?: return false
                // Nur Webseiten im Browser öffnen, Sonderlinks (intent:, market: …) ignorieren
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                url = u.orEmpty()
                updateNav(view)
            }

            override fun doUpdateVisitedHistory(view: WebView?, u: String?, isReload: Boolean) {
                if (!u.isNullOrBlank()) url = u
                updateNav(view)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress
            }
        }
    }

    private fun updateNav(view: WebView?) {
        canBack = view?.canGoBack() == true
        canForward = view?.canGoForward() == true
    }

    fun open(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val target = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            !text.contains(' ') && text.contains('.') -> "https://$text"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(text)
        }
        webView.loadUrl(target)
    }

    fun home() = webView.loadUrl("https://duckduckgo.com/")

    fun clearTraces() {
        webView.stopLoading()
        webView.clearHistory()
        webView.clearCache(true)
        webView.clearFormData()
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        blocked = 0
        home()
        webView.post { webView.clearHistory(); updateNav(webView) }
    }

    fun destroy() {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        webView.destroy()
    }
}

object AdBlock {
    private val hosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.com", "adservice.google.de",
        "pagead2.googlesyndication.com", "connect.facebook.net", "an.facebook.com",
        "scorecardresearch.com", "taboola.com", "outbrain.com", "criteo.com", "criteo.net",
        "amazon-adsystem.com", "adnxs.com", "pubmatic.com", "rubiconproject.com", "openx.net",
        "hotjar.com", "moatads.com", "quantserve.com", "quantcount.com", "popads.net", "popcash.net",
        "propellerads.com", "adsterra.com", "exoclick.com", "juicyads.com", "trafficjunky.net",
        "yieldmo.com", "smartadserver.com", "adform.net", "adition.com", "teads.tv", "media.net",
        "advertising.com", "zemanta.com", "mgid.com", "revcontent.com", "bidswitch.net",
        "casalemedia.com", "3lift.com", "sharethrough.com", "indexww.com", "everesttech.net",
        "demdex.net", "omtrdc.net", "chartbeat.com", "chartbeat.net", "newrelic.com", "nr-data.net",
        "mixpanel.com", "segment.io", "segment.com", "branch.io", "app-measurement.com",
        "adcolony.com", "applovin.com", "unityads.unity3d.com", "inmobi.com", "ioam.de",
        "xiti.com", "etracker.com", "yandex.ru", "mc.yandex.ru", "ads.yahoo.com", "analytics.yahoo.com",
        "ads-twitter.com", "static.ads-twitter.com", "analytics.tiktok.com", "ads.tiktok.com",
        "bat.bing.com", "clarity.ms", "onesignal.com", "pushwoosh.com", "adskeeper.com",
        "hilltopads.net", "clickadu.com", "a-ads.com", "coinzilla.com",
    )

    fun isBlocked(host: String): Boolean {
        var h = host.lowercase()
        while (true) {
            if (h in hosts) return true
            val i = h.indexOf('.')
            if (i < 0 || i == h.length - 1) return false
            h = h.substring(i + 1)
        }
    }
}
