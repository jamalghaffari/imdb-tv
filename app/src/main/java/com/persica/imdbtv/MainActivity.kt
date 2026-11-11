package com.persica.imdbtv

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullscreenContainer: FrameLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF08090D.toInt())
        }

        fullscreenContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setBackgroundColor(0xFF000000.toInt())
        }

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF08090D.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                6
            )
            max = 100
            visibility = View.GONE
        }

        rootLayout.addView(webView)
        rootLayout.addView(progressBar)
        rootLayout.addView(fullscreenContainer)
        setContentView(rootLayout)

        configureWebView()

        val isTv = isAndroidTv()
        val useLive = intent.getBooleanExtra("use_live", false)

        val targetUrl = when {
            useLive && isTv -> "https://persicatheme.ir/imdb/?tv=1"
            useLive -> "https://persicatheme.ir/imdb/"
            isTv -> "file:///android_asset/web/index.html?tv=1"
            else -> "file:///android_asset/web/index.html"
        }

        webView.loadUrl(targetUrl)
    }

    private fun isAndroidTv(): Boolean {
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as? android.app.UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    class WebAppInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun playNative(title: String, url: String) {
            val intent = Intent(activity, PlayerActivity::class.java).apply {
                putExtra("title", title)
                putExtra("url", url)
            }
            activity.startActivity(intent)
        }

        @JavascriptInterface
        fun playExternal(url: String) {
            activity.launchExternalPlayer(url)
        }
    }

    fun launchExternalPlayer(rawUrl: String) {
        var cleanUrl = rawUrl.trim()
        if (cleanUrl.startsWith("vlc://https://")) {
            cleanUrl = "https://" + cleanUrl.removePrefix("vlc://https://")
        } else if (cleanUrl.startsWith("vlc://http://")) {
            cleanUrl = "http://" + cleanUrl.removePrefix("vlc://http://")
        } else if (cleanUrl.startsWith("vlc://")) {
            cleanUrl = cleanUrl.removePrefix("vlc://")
        }

        try {
            val vlcIntent = Intent(Intent.ACTION_VIEW).apply {
                setPackage("org.videolan.vlc")
                setDataAndType(Uri.parse(cleanUrl), "video/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(vlcIntent)
        } catch (e: Exception) {
            try {
                val chooserIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(cleanUrl), "video/*")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(Intent.createChooser(chooserIntent, "پخش فیلم با:"))
            } catch (err: Exception) {
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra("title", "پخش مستقیم فیلم")
                    putExtra("url", cleanUrl)
                }
                startActivity(intent)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                if (url.startsWith("vlc://")) {
                    launchExternalPlayer(url)
                    return true
                }

                if (url.startsWith("intent://")) {
                    try {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        return false
                    }
                }

                return false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                // If live server fails mid-way, smoothly fall back to local assets without showing chrome robot error
                if (request?.isForMainFrame == true) {
                    val fallback = if (isAndroidTv()) {
                        "file:///android_asset/web/index.html?tv=1"
                    } else {
                        "file:///android_asset/web/index.html"
                    }
                    view?.loadUrl(fallback)
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                webView.visibility = View.GONE
                fullscreenContainer.visibility = View.VISIBLE
                fullscreenContainer.addView(view)
            }

            override fun onHideCustomView() {
                if (customView == null) return
                webView.visibility = View.VISIBLE
                fullscreenContainer.visibility = View.GONE
                fullscreenContainer.removeView(customView)
                customViewCallback?.onCustomViewHidden()
                customView = null
                customViewCallback = null
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (customView != null && keyCode == KeyEvent.KEYCODE_BACK) {
            webView.webChromeClient?.onHideCustomView()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // اول از JavaScript بپرس آیا پنجره‌ای (مودال دانلود/پخش/پروفایل) باز است تا همان را ببندد و از اپ نپرد بیرون!
            webView.evaluateJavascript(
                "(function(){ return window.__closeAnyModal ? window.__closeAnyModal() : false; })()"
            ) { result ->
                if (result != "true") {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
