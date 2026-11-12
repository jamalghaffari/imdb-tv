package com.persica.imdbtv

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topOverlay: LinearLayout
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var errorOverlay: LinearLayout
    private lateinit var errorDetailText: TextView
    private lateinit var btnErrOnline: Button

    private var candidateUrls: List<String> = emptyList()
    private var currentUrlIndex = 0
    private var customReferer: String? = null
    private var movieTitle: String = "سینما"

    private var subtitleScale = 1.0f
    private var subtitleColor = Color.WHITE
    private var subtitleBgAlpha = 0x00
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var playbackSpeed = 1.0f

    private val prefsName = "imdb_player_prefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        subtitleScale = prefs.getFloat("sub_scale", 1.0f)
        subtitleColor = prefs.getInt("sub_color", Color.WHITE)
        subtitleBgAlpha = prefs.getInt("sub_bg_alpha", 0x00)
        resizeMode = prefs.getInt("resize_mode", AspectRatioFrameLayout.RESIZE_MODE_FIT)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        movieTitle = intent.getStringExtra("title") ?: "سینما"
        customReferer = intent.getStringExtra("referer")
        val urlsExtra = intent.getStringArrayListExtra("urls")
        val singleUrl = intent.getStringExtra("url") ?: ""
        candidateUrls = if (!urlsExtra.isNullOrEmpty()) {
            urlsExtra.filter { it.isNotBlank() }.distinct()
        } else if (singleUrl.isNotBlank()) {
            listOf(singleUrl)
        } else {
            emptyList()
        }

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        playerView = PlayerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = true
            controllerAutoShow = true
            controllerShowTimeoutMs = 4000
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShutterBackgroundColor(Color.BLACK)
            this.resizeMode = this@PlayerActivity.resizeMode
        }

        applySubtitleStyle()

        topOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 20, 32, 16)
            setBackgroundColor(0xCC090B10.toInt())
            visibility = View.GONE
        }

        val titleView = TextView(this).apply {
            text = movieTitle
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setShadowLayer(6f, 2f, 2f, Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnAudio = makeTopButton("🎙 صدا/دوبله", 0xFF27272A.toInt(), Color.WHITE) {
            showTrackPicker(C.TRACK_TYPE_AUDIO, "انتخاب زبان / صدای فیلم")
        }
        val btnSub = makeTopButton("💬 زیرنویس", 0xFF27272A.toInt(), Color.WHITE) {
            showTrackPicker(C.TRACK_TYPE_TEXT, "انتخاب زیرنویس")
        }
        val btnSettings = makeTopButton("⚙️ تنظیمات", 0xFFF5C518.toInt(), Color.BLACK) {
            showPlayerSettingsDialog()
        }
        val btnVlc = makeTopButton("▶ VLC", 0xFFEA580C.toInt(), Color.WHITE) {
            openCurrentInExternalPlayer()
        }
        val btnBack = makeTopButton("✕ خروج", 0x99222222.toInt(), Color.WHITE) {
            finish()
        }

        val topButtons = listOf(btnAudio, btnSub, btnSettings, btnVlc, btnBack)
        topButtons.forEach { it.id = View.generateViewId() }
        for (i in topButtons.indices) {
            if (i > 0) topButtons[i].nextFocusLeftId = topButtons[i - 1].id
            if (i < topButtons.lastIndex) topButtons[i].nextFocusRightId = topButtons[i + 1].id
            topButtons[i].setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    playerView.showController()
                    v.animate().scaleX(1.07f).scaleY(1.07f).setDuration(120).start()
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
            }
        }

        topOverlay.addView(titleView)
        topOverlay.addView(btnAudio)
        topOverlay.addView(btnSub)
        topOverlay.addView(btnSettings)
        topOverlay.addView(btnVlc)
        topOverlay.addView(btnBack)

        loadingSpinner = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }

        errorOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xEE08090D.toInt())
            setPadding(48, 48, 48, 48)
            visibility = View.GONE
        }

        errorDetailText = TextView(this).apply {
            text = "پخش این لینک با خطا مواجه شد."
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 28)
        }

        val errButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        btnErrOnline = makeTopButton("🌐 سرور پخش آنلاین", 0xFFF5C518.toInt(), Color.BLACK) {
            setResult(RESULT_FIRST_USER)
            finish()
        }
        val btnRetry = makeTopButton("🔄 تلاش مجدد", 0xFF27272A.toInt(), Color.WHITE) {
            errorOverlay.visibility = View.GONE
            currentUrlIndex = 0
            playCandidateAt(0)
        }
        val btnErrExternal = makeTopButton("▶ پخش با VLC", 0xFF27272A.toInt(), Color.WHITE) {
            openCurrentInExternalPlayer()
        }
        val btnErrClose = makeTopButton("✕ بستن", 0xFF27272A.toInt(), Color.WHITE) {
            finish()
        }

        val errBtns = listOf(btnErrOnline, btnRetry, btnErrExternal, btnErrClose)
        errBtns.forEach { it.id = View.generateViewId() }
        for (i in errBtns.indices) {
            if (i > 0) errBtns[i].nextFocusLeftId = errBtns[i - 1].id
            if (i < errBtns.lastIndex) errBtns[i].nextFocusRightId = errBtns[i + 1].id
        }

        errButtonsRow.addView(btnErrOnline)
        errButtonsRow.addView(btnRetry)
        errButtonsRow.addView(btnErrExternal)
        errButtonsRow.addView(btnErrClose)

        errorOverlay.addView(errorDetailText)
        errorOverlay.addView(errButtonsRow)

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            topOverlay.visibility = visibility
        })

        root.addView(playerView)
        root.addView(loadingSpinner, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        root.addView(topOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))
        root.addView(errorOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)

        initPlayer()
        if (candidateUrls.isNotEmpty()) {
            playCandidateAt(0)
        } else {
            showErrorState("لینک پخشی برای این فیلم یافت نشد.")
        }
    }

    private fun makeTopButton(label: String, bgColor: Int, fgColor: Int, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        val radius = 12f * density
        val normalBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(bgColor)
            setStroke((1 * density).toInt(), 0x33FFFFFF)
        }
        val focusedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(bgColor)
            setStroke((3 * density).toInt(), 0xFFF5C518.toInt())
        }
        val selector = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedBg)
            addState(intArrayOf(android.R.attr.state_selected), focusedBg)
            addState(intArrayOf(), normalBg)
        }

        return Button(this).apply {
            text = label
            setTextColor(fgColor)
            background = selector
            stateListAnimator = null
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            isFocusable = true
            isFocusableInTouchMode = false
            gravity = Gravity.CENTER
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins((8 * density).toInt(), 0, 0, 0)
            layoutParams = lp
            setOnClickListener {
                playerView.showController()
                onClick()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun initPlayer() {
        val browserUa = "Mozilla/5.0 (Linux; Android 11; SHIELD Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }
        val baseFactory = sslContext.socketFactory
        val compatFactory = object : SSLSocketFactory() {
            private fun patch(s: Socket): Socket {
                if (s is SSLSocket) {
                    val supported = s.supportedProtocols.toSet()
                    val desired = listOf("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1").filter { it in supported }
                    if (desired.isNotEmpty()) s.enabledProtocols = desired.toTypedArray()
                }
                return s
            }
            override fun getDefaultCipherSuites(): Array<String> = baseFactory.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = baseFactory.supportedCipherSuites
            override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean) = patch(baseFactory.createSocket(s, host, port, autoClose))
            override fun createSocket(host: String, port: Int) = patch(baseFactory.createSocket(host, port))
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) = patch(baseFactory.createSocket(host, port, localHost, localPort))
            override fun createSocket(host: InetAddress, port: Int) = patch(baseFactory.createSocket(host, port))
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int) = patch(baseFactory.createSocket(address, port, localAddress, localPort))
        }

        val okHttpClient = OkHttpClient.Builder()
            .dns(SmartDns)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionSpecs(listOf(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .sslSocketFactory(compatFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val reqBuilder = req.newBuilder()
                    .header("User-Agent", browserUa)
                    .header("Accept", "*/*")
                if (!customReferer.isNullOrBlank() && req.header("Referer") == null) {
                    reqBuilder.header("Referer", customReferer!!)
                }
                chain.proceed(reqBuilder.build())
            }
            .build()

        val httpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(browserUa)

        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 60000, 2500, 5000)
            .build()

        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguages("fa", "per", "fas", "en")
                    .setPreferredTextLanguages("fa", "per", "fas", "en")
            )
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(5))

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()

        playerView.player = player

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                loadingSpinner.visibility = if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                loadingSpinner.visibility = View.GONE
                val failedHost = try { Uri.parse(candidateUrls.getOrNull(currentUrlIndex) ?: "").host ?: "" } catch (_: Exception) { "" }
                var nextIdx = currentUrlIndex + 1
                while (nextIdx < candidateUrls.size) {
                    val nextHost = try { Uri.parse(candidateUrls[nextIdx]).host ?: "" } catch (_: Exception) { "" }
                    if (failedHost.isNotBlank() && nextHost == failedHost) {
                        nextIdx++
                    } else {
                        break
                    }
                }
                if (nextIdx < candidateUrls.size) {
                    currentUrlIndex = nextIdx
                    Toast.makeText(
                        this@PlayerActivity,
                        "تلاش با سرور جایگزین (${currentUrlIndex + 1}/${candidateUrls.size})...",
                        Toast.LENGTH_SHORT
                    ).show()
                    playCandidateAt(currentUrlIndex)
                } else {
                    val causeMsg = error.cause?.message?.take(60) ?: ""
                    showErrorState("سرور فایل مستقیم در دسترس نیست (${error.errorCodeName}${if (causeMsg.isNotBlank()) ": $causeMsg" else ""}).\nبرای تماشا، «سرور پخش آنلاین» را انتخاب کنید.")
                }
            }
        })
    }

    private fun playCandidateAt(index: Int) {
        val url = candidateUrls.getOrNull(index) ?: return
        loadingSpinner.visibility = View.VISIBLE
        errorOverlay.visibility = View.GONE
        val mediaItem = MediaItem.fromUri(url)
        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    private fun showErrorState(msg: String) {
        loadingSpinner.visibility = View.GONE
        errorDetailText.text = msg
        errorOverlay.visibility = View.VISIBLE
        btnErrOnline.post { btnErrOnline.requestFocus() }
    }

    private fun openCurrentInExternalPlayer() {
        val rawUrl = candidateUrls.getOrNull(currentUrlIndex) ?: candidateUrls.firstOrNull() ?: return
        try {
            val vlcIntent = Intent(Intent.ACTION_VIEW).apply {
                setPackage("org.videolan.vlc")
                setDataAndType(Uri.parse(rawUrl), "video/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(vlcIntent)
        } catch (e: Exception) {
            try {
                val chooser = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(rawUrl), "video/*")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(Intent.createChooser(chooser, "پخش با پلیر خارجی:"))
            } catch (_: Exception) {
                Toast.makeText(this, "پلیر خارجی (مثل VLC یا MX Player) نصب نیست", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun showTrackPicker(trackType: Int, title: String) {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == trackType }
        if (groups.isEmpty()) {
            Toast.makeText(this, "ترک دیگری در این فایل یافت نشد", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (trackType == C.TRACK_TYPE_TEXT) {
            labels.add("بدون زیرنویس (خاموش)")
            actions.add {
                p.trackSelectionParameters = p.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
        }

        var trackCounter = 1
        groups.forEach { group ->
            for (i in 0 until group.length) {
                val fmt = group.getTrackFormat(i)
                val lang = fmt.language?.uppercase() ?: "نامشخص"
                val lbl = fmt.label ?: "ترک $trackCounter"
                val selectedMark = if (group.isTrackSelected(i)) " ✓" else ""
                labels.add("$lbl ($lang)$selectedMark")
                val g = group.mediaTrackGroup
                val idx = i
                actions.add {
                    p.trackSelectionParameters = p.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(trackType, false)
                        .setOverrideForType(TrackSelectionOverride(g, idx))
                        .build()
                }
                trackCounter++
            }
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                actions[which].invoke()
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun applySubtitleStyle() {
        val subView = playerView.subtitleView ?: return
        val bgColor = Color.argb(subtitleBgAlpha, 0, 0, 0)
        val style = CaptionStyleCompat(
            subtitleColor,
            bgColor,
            Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            Color.BLACK,
            null
        )
        subView.setStyle(style)
        subView.setFractionalTextSize(0.0533f * subtitleScale)
    }

    private fun savePlayerPrefs() {
        getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putFloat("sub_scale", subtitleScale)
            .putInt("sub_color", subtitleColor)
            .putInt("sub_bg_alpha", subtitleBgAlpha)
            .putInt("resize_mode", resizeMode)
            .apply()
    }

    private fun showPlayerSettingsDialog() {
        val options = arrayOf(
            "📏 اندازه زیرنویس (کوچک / متوسط / بزرگ / خیلی بزرگ)",
            "🎨 رنگ زیرنویس (سفید / زرد طلایی / فیروزه‌ای)",
            "⬛ پس‌زمینه زیرنویس (شفاف تمیز / نیمه‌تیره)",
            "🖥️ نسبت تصویر (عادی Fit / پر کردن صفحه Zoom)",
            "⏩ سرعت پخش (1x / 1.25x / 1.5x / 2x)"
        )

        AlertDialog.Builder(this)
            .setTitle("تنظیمات حرفه‌ای پلیر")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSubtitleSizePicker()
                    1 -> showSubtitleColorPicker()
                    2 -> {
                        subtitleBgAlpha = if (subtitleBgAlpha == 0x00) 0x88 else 0x00
                        applySubtitleStyle()
                        savePlayerPrefs()
                        Toast.makeText(this, if (subtitleBgAlpha == 0x00) "پس‌زمینه زیرنویس حذف شد" else "پس‌زمینه نیمه‌تیره فعال شد", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        resizeMode = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        } else {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                        playerView.resizeMode = resizeMode
                        savePlayerPrefs()
                        Toast.makeText(this, if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) "حالت تمام‌صفحه بدون حاشیه" else "حالت استاندارد فیلم", Toast.LENGTH_SHORT).show()
                    }
                    4 -> showSpeedPicker()
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun showSubtitleSizePicker() {
        val labels = arrayOf("کوچک (0.8x)", "متوسط (استاندارد)", "بزرگ (1.3x)", "خیلی بزرگ مخصوص TV (1.6x)")
        val values = floatArrayOf(0.8f, 1.0f, 1.3f, 1.6f)
        AlertDialog.Builder(this)
            .setTitle("سایز زیرنویس")
            .setItems(labels) { _, i ->
                subtitleScale = values[i]
                applySubtitleStyle()
                savePlayerPrefs()
            }
            .show()
    }

    private fun showSubtitleColorPicker() {
        val labels = arrayOf("سفید خالص (پیشنهادی)", "زرد سینمایی", "فیروزه‌ای")
        val values = intArrayOf(Color.WHITE, Color.YELLOW, Color.CYAN)
        AlertDialog.Builder(this)
            .setTitle("رنگ زیرنویس")
            .setItems(labels) { _, i ->
                subtitleColor = values[i]
                applySubtitleStyle()
                savePlayerPrefs()
            }
            .show()
    }

    private fun showSpeedPicker() {
        val labels = arrayOf("عادی (1.0x)", "کمی سریع (1.25x)", "سریع (1.5x)", "خیلی سریع (2.0x)")
        val values = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f)
        AlertDialog.Builder(this)
            .setTitle("سرعت پخش")
            .setItems(labels) { _, i ->
                playbackSpeed = values[i]
                player?.playbackParameters = PlaybackParameters(playbackSpeed)
            }
            .show()
    }

    private fun isFocusInTopOverlay(): Boolean {
        val focused = currentFocus ?: return false
        var p = focused.parent
        while (p != null) {
            if (p === topOverlay) return true
            p = p.parent
        }
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && errorOverlay.visibility != View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && !isFocusInTopOverlay()) {
                playerView.showController()
                topOverlay.visibility = View.VISIBLE
                val target = (if (topOverlay.childCount > 2) topOverlay.getChildAt(2) else topOverlay.getChildAt(0))
                target?.requestFocus()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && isFocusInTopOverlay()) {
                playerView.showController()
                playerView.requestFocus()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            showPlayerSettingsDialog()
            return true
        }
        val p = player
        if (errorOverlay.visibility != View.VISIBLE && p != null && !playerView.isControllerFullyVisible) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration.coerceAtLeast(0L)))
                playerView.showController()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                p.seekTo((p.currentPosition - 10000).coerceAtLeast(0))
                playerView.showController()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (p.isPlaying) p.pause() else p.play()
                playerView.showController()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                playerView.showController()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
