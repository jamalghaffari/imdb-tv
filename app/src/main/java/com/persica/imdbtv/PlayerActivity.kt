package com.persica.imdbtv

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topOverlay: LinearLayout

    private var subtitleScale = 1.0f // 0.8, 1.0, 1.3, 1.6
    private var subtitleColor = Color.WHITE
    private var subtitleBgAlpha = 0x00 // Transparent by default (no dark box)
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
            controllerShowTimeoutMs = 3500
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShutterBackgroundColor(Color.BLACK)
            this.resizeMode = this@PlayerActivity.resizeMode
        }

        applySubtitleStyle()

        // Clean top bar (only visible when controller is visible — NO permanent dark box!)
        topOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 28, 40, 20)
            setBackgroundColor(0x00000000)
            visibility = View.GONE
        }

        val titleText = intent.getStringExtra("title") ?: "سینما IMDb"
        val videoUrl = intent.getStringExtra("url") ?: ""

        val titleView = TextView(this).apply {
            text = titleText
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setShadowLayer(6f, 2f, 2f, Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnSettings = Button(this).apply {
            text = "⚙️ تنظیمات پخش و زیرنویس"
            setTextColor(Color.BLACK)
            setBackgroundColor(0xFFF5C518.toInt())
            textSize = 12f
            isFocusable = true
            setPadding(24, 12, 24, 12)
            setOnClickListener { showPlayerSettingsDialog() }
        }

        val btnBack = Button(this).apply {
            text = "✕ خروج"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99222222.toInt())
            textSize = 12f
            isFocusable = true
            setPadding(22, 12, 22, 12)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(16, 0, 0, 0)
            layoutParams = lp
            setOnClickListener { finish() }
        }

        topOverlay.addView(titleView)
        topOverlay.addView(btnSettings)
        topOverlay.addView(btnBack)

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            topOverlay.visibility = visibility
        })

        root.addView(playerView)
        root.addView(topOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))
        setContentView(root)

        initPlayer(videoUrl)
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

    private fun initPlayer(url: String) {
        if (url.isEmpty()) return

        player = ExoPlayer.Builder(this).build().apply {
            playerView.player = this
            val mediaItem = MediaItem.fromUri(url)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true

            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    super.onPlayerError(error)
                    Toast.makeText(this@PlayerActivity, "خطا در پخش این لینک. لطفاً سرور یا کیفیت دیگری انتخاب کنید.", Toast.LENGTH_LONG).show()
                }
            })
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            showPlayerSettingsDialog()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            player?.let { it.seekTo(it.currentPosition + 10000) }
            playerView.showController()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            player?.let { it.seekTo((it.currentPosition - 10000).coerceAtLeast(0)) }
            playerView.showController()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (!playerView.isControllerFullyVisible) {
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
