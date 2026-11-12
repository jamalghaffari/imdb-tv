package com.persica.imdbtv

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var downloadManager: AppDownloadManager
    private var lastBackPressedMs: Long = 0L
    private var lastSniffedUrl: String = ""

    private val imageCacheClient: OkHttpClient by lazy {
        val cacheDir = File(applicationContext.cacheDir, "poster_disk_cache")
        val cache = Cache(cacheDir, 120L * 1024L * 1024L) // 120 MB lazy disk cache
        OkHttpClient.Builder()
            .dns(SmartDns)
            .cache(cache)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val res = chain.proceed(chain.request())
                res.newBuilder()
                    .removeHeader("Pragma")
                    .removeHeader("Cache-Control")
                    .header("Cache-Control", "public, max-age=2592000") // 30 days
                    .build()
            }
            .build()
    }

    @Volatile var lastImdbId: String = ""

    private val playerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_FIRST_USER) {
            webView.post {
                webView.evaluateJavascript("window.__openOnlineEmbedFallback && window.__openOnlineEmbedFallback();", null)
            }
        }
    }

    private val adHostKeywords = listOf(
        "popads", "popcash", "adsterra", "doubleclick", "googlesyndication",
        "exoclick", "trafficjunky", "juicyads", "adnxs", "propellerads",
        "acscdn", "highperformanceformat", "effectivegatecpm", "onclickperformance",
        "pushance", "realsrv", "tsyndicate", "monetag"
    )

    private val allowedNavHosts = listOf(
        "persicatheme.ir", "vidsrc.", "2embed.", "imdb.com", "youtube.com", "youtu.be"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadManager = AppDownloadManager(this)

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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackOrExit()
            }
        })

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
        webView.requestFocus()
        webView.postDelayed({ checkForAppUpdate() }, 2500L)
    }

    private fun isAndroidTv(): Boolean {
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as? android.app.UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    inner class WebAppInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun setActiveImdbId(id: String) {
            activity.lastImdbId = id.trim()
        }

        @JavascriptInterface
        fun playNative(title: String, url: String) {
            val intent = Intent(activity, PlayerActivity::class.java).apply {
                putExtra("title", title)
                putExtra("url", url)
                if (activity.lastImdbId.isNotBlank()) putExtra("imdbId", activity.lastImdbId)
            }
            activity.runOnUiThread { activity.playerLauncher.launch(intent) }
        }

        @JavascriptInterface
        fun playNativeWithReferer(title: String, url: String, referer: String) {
            val intent = Intent(activity, PlayerActivity::class.java).apply {
                putExtra("title", title)
                putExtra("url", url)
                if (referer.isNotBlank()) putExtra("referer", referer)
                if (activity.lastImdbId.isNotBlank()) putExtra("imdbId", activity.lastImdbId)
            }
            activity.runOnUiThread { activity.playerLauncher.launch(intent) }
        }

        @JavascriptInterface
        fun playNativeList(title: String, urlsJson: String) {
            val list = ArrayList<String>()
            try {
                val arr = JSONArray(urlsJson)
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i)
                    if (u.isNotBlank()) list.add(u)
                }
            } catch (_: Exception) {
                if (urlsJson.startsWith("http")) list.add(urlsJson)
            }
            val intent = Intent(activity, PlayerActivity::class.java).apply {
                putExtra("title", title)
                putStringArrayListExtra("urls", list)
                if (activity.lastImdbId.isNotBlank()) putExtra("imdbId", activity.lastImdbId)
            }
            activity.runOnUiThread { activity.playerLauncher.launch(intent) }
        }

        @JavascriptInterface
        fun playExternal(url: String) {
            activity.launchExternalPlayer(url)
        }

        @JavascriptInterface
        fun dmEnqueue(id: String, title: String, url: String, quality: String) {
            activity.downloadManager.enqueue(id, title, url, quality)
        }

        @JavascriptInterface
        fun dmPause(id: String) {
            activity.downloadManager.pause(id)
        }

        @JavascriptInterface
        fun dmResume(id: String) {
            activity.downloadManager.resume(id)
        }

        @JavascriptInterface
        fun dmDelete(id: String) {
            activity.downloadManager.delete(id)
        }

        @JavascriptInterface
        fun dmGetAll(): String = activity.downloadManager.getAllJson()

        @JavascriptInterface
        fun dmGetConfig(): String = activity.downloadManager.getConfigJson()

        @JavascriptInterface
        fun dmSaveConfig(json: String) {
            activity.downloadManager.saveConfig(json)
        }

        @JavascriptInterface
        fun dmPlayLocal(filePath: String, title: String) {
            val fileUrl = if (filePath.startsWith("file://")) filePath else "file://$filePath"
            val intent = Intent(activity, PlayerActivity::class.java).apply {
                putExtra("title", title)
                putExtra("url", fileUrl)
            }
            activity.runOnUiThread { activity.playerLauncher.launch(intent) }
        }

        @JavascriptInterface
        fun checkAppUpdateManual() {
            activity.checkForAppUpdate(manual = true)
        }

        @JavascriptInterface
        fun getAppVersion(): String {
            return try {
                activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "1.7.9"
            } catch (_: Exception) { "1.7.9" }
        }

        @JavascriptInterface
        fun dmGetStorages(): String {
            return activity.downloadManager.getAvailableStoragesJson()
        }

        @JavascriptInterface
        fun dmHasStoragePermission(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        }

        @JavascriptInterface
        fun dmRequestStoragePermission() {
            activity.runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${activity.packageName}")
                        )
                        activity.startActivity(intent)
                    } catch (_: Exception) {
                        try {
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        } catch (_: Exception) {
                            Toast.makeText(activity, "لطفاً در تنظیمات سیستم مجوز دسترسی حافظه را فعال کنید", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        2026
                    )
                }
            }
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
                playerLauncher.launch(intent)
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
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 11; SHIELD Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val lower = url.lowercase()

                if (lower.startsWith("vlc://")) {
                    launchExternalPlayer(url)
                    return true
                }

                if (lower.startsWith("intent://")) {
                    try {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        return true
                    }
                }

                // مسدودسازی ریدایرکت‌های تبلیغاتی پاپ‌آپ هنگام پخش آنلاین
                if (adHostKeywords.any { lower.contains(it) }) {
                    return true
                }

                if (request?.isForMainFrame == true && (lower.startsWith("http://") || lower.startsWith("https://"))) {
                    val host = Uri.parse(url).host?.lowercase() ?: ""
                    if (allowedNavHosts.none { host.contains(it) }) {
                        // اجازه خروج ناخواسته به سایت‌های تبلیغاتی را نده
                        return true
                    }
                }

                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                val lower = url.lowercase()

                // مسدودسازی اسکریپت‌های پاپ‌آپ و تبلیغاتی داخل پلیرهای امبد
                if (lower.contains("/sbx.js") || lower.contains("disable-devtool") || adHostKeywords.any { lower.contains(it) }) {
                    return WebResourceResponse("application/javascript", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }

                // شکار هوشمند لینک استریم واقعی (.m3u8 یا .mp4) برای پخش بدون تبلیغ در پلیر نیتیو
                if ((lower.contains(".m3u8") || (lower.contains(".mp4") && !lower.contains("ads"))) &&
                    !lower.contains("persicatheme.ir") && url != lastSniffedUrl
                ) {
                    lastSniffedUrl = url
                    val ref = request.requestHeaders["Referer"] ?: request.requestHeaders["Origin"] ?: ""
                    val safeUrl = url.replace("'", "\\'")
                    val safeRef = ref.replace("'", "\\'")
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__onSniffedStream && window.__onSniffedStream('$safeUrl', '$safeRef');",
                            null
                        )
                    }
                }

                // کش تنبل (Lazy Disk Cache) تصاویر و پوسترها + downloads.json روی حافظه دیوایس (رفع کامل CORS در حالت آفلاین)
                if (request?.method == "GET" && (lower.startsWith("http://") || lower.startsWith("https://")) &&
                    (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".webp") || lower.contains("m.media-amazon.com/images/") || lower.contains("persicatheme.ir/imdb/downloads.json"))
                ) {
                    try {
                        val isJson = lower.contains("downloads.json")
                        val okReq = Request.Builder()
                            .url(url)
                            .cacheControl(CacheControl.Builder().maxStale(if (isJson) 1 else 30, TimeUnit.DAYS).build())
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                            .build()
                        val resp = imageCacheClient.newCall(okReq).execute()
                        if (resp.isSuccessful && resp.body != null) {
                            val mime = if (isJson) "application/json" else (
                                resp.header("Content-Type")?.substringBefore(";")?.trim()
                                    ?: if (lower.contains(".png")) "image/png" else if (lower.contains(".webp")) "image/webp" else "image/jpeg"
                            )
                            val headers = mapOf("Access-Control-Allow-Origin" to "*")
                            return WebResourceResponse(mime, if (isJson) "UTF-8" else null, 200, "OK", headers, resp.body!!.byteStream())
                        } else {
                            resp.close()
                        }
                    } catch (_: Exception) {}
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
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
                webView.requestFocus()
                if (url != null && url.startsWith("https://")) {
                    webView.postDelayed({
                        if (isFinishing || isDestroyed) return@postDelayed
                        webView.evaluateJavascript(
                            "(function(){var r=document.getElementById('root');return r?r.children.length:0;})()"
                        ) { res ->
                            if (res == "0" || res == "null") {
                                val fallback = if (isAndroidTv()) {
                                    "file:///android_asset/web/index.html?tv=1"
                                } else {
                                    "file:///android_asset/web/index.html"
                                }
                                webView.loadUrl(fallback)
                            }
                        }
                    }, 4000L)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                // مسدودسازی کامل پاپ‌آپ‌های تبلیغاتی
                return false
            }

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

    private fun handleBackOrExit() {
        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
            return
        }
        webView.evaluateJavascript(
            "(function(){ return window.__closeAnyModal ? window.__closeAnyModal() : false; })()"
        ) { result ->
            if (result != "true") {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressedMs < 2500L) {
                        finish()
                    } else {
                        lastBackPressedMs = now
                        val msg = "برای خروج از برنامه، دکمه Exit / بازگشت را دوباره بزنید"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        webView.evaluateJavascript(
                            "window.__showAppToast && window.__showAppToast('$msg');",
                            null
                        )
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            handleBackOrExit()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun checkForAppUpdate(manual: Boolean = false) {
        Thread {
            try {
                val req = Request.Builder()
                    .url("https://persicatheme.ir/imdb/version.json?t=" + System.currentTimeMillis())
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .build()
                val resp = imageCacheClient.newCall(req).execute()
                val body = resp.body?.string()
                resp.close()
                if (!resp.isSuccessful || body.isNullOrBlank()) {
                    if (manual) runOnUiThread {
                        Toast.makeText(this, "خطا در بررسی نسخه جدید", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                val json = JSONObject(body)
                val remoteCode = json.optLong("versionCode", 0L)
                val remoteName = json.optString("versionName", "")
                val apkUrl = json.optString("apkUrl", "https://persicatheme.ir/imdb/imdb-tv.apk")
                val notes = json.optString("notes", "نسخه جدید با بهبودهای عملکردی و باگ‌فیکس آماده نصب است.")

                val pInfo = packageManager.getPackageInfo(packageName, 0)
                val currentCode = PackageInfoCompat.getLongVersionCode(pInfo)

                if (remoteCode > currentCode && !isFinishing && !isDestroyed) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        AlertDialog.Builder(this)
                            .setTitle("نسخه جدید آماده است ($remoteName)")
                            .setMessage(notes)
                            .setPositiveButton("دانلود و نصب") { _, _ ->
                                downloadAndInstallApk(apkUrl, remoteName)
                            }
                            .setNegativeButton("بعداً", null)
                            .show()
                    }
                } else if (manual && !isFinishing && !isDestroyed) {
                    runOnUiThread {
                        Toast.makeText(this, "شما در حال استفاده از آخرین نسخه (${pInfo.versionName}) هستید.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) {
                if (manual) runOnUiThread {
                    Toast.makeText(this, "خطا در اتصال به سرور بروزرسانی", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun downloadAndInstallApk(apkUrl: String, versionName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "لطفاً اجازه نصب از این برنامه را فعال کنید و دوباره بزنید", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
            return
        }

        val progressDialog = AlertDialog.Builder(this)
            .setTitle("در حال دانلود نسخه $versionName...")
            .setMessage("لطفاً صبر کنید، فایل به‌روزرسانی در حال دریافت است.")
            .setCancelable(false)
            .create()
        progressDialog.show()

        Thread {
            try {
                val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updatesDir, "update.apk")
                val req = Request.Builder().url(apkUrl).cacheControl(CacheControl.FORCE_NETWORK).build()
                val resp = imageCacheClient.newCall(req).execute()
                val body = resp.body
                if (!resp.isSuccessful || body == null) {
                    resp.close()
                    runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(this, "خطا در دانلود فایل آپدیت", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output -> input.copyTo(output) }
                }
                resp.close()

                val contentUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runOnUiThread {
                    progressDialog.dismiss()
                    startActivity(installIntent)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "خطا در نصب نسخه جدید", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
