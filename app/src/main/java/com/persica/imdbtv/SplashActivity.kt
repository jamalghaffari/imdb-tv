package com.persica.imdbtv

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AlphaAnimation
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var splashLogo: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        statusText = findViewById(R.id.splashStatus)
        splashLogo = findViewById(R.id.splashLogo)

        // Smooth fade-in animation for logo
        val fadeIn = AlphaAnimation(0.2f, 1.0f).apply {
            duration = 1000
            fillAfter = true
        }
        splashLogo.startAnimation(fadeIn)

        checkOnlineAndProceed()
    }

    private fun checkOnlineAndProceed() {
        val startTime = System.currentTimeMillis()

        thread {
            var isLiveServerAvailable = false
            try {
                // Ping live host with 3.5s timeout
                val connection = (URL("https://persicatheme.ir/imdb/").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                }
                val code = connection.responseCode
                if (code in 200..399) {
                    isLiveServerAvailable = true
                }
                connection.disconnect()
            } catch (e: Exception) {
                isLiveServerAvailable = false
            }

            // Ensure splash stays for at least 1.5 seconds for nice intro experience
            val elapsed = System.currentTimeMillis() - startTime
            val remaining = (1500 - elapsed).coerceAtLeast(0)

            Handler(Looper.getMainLooper()).postDelayed({
                if (isLiveServerAvailable) {
                    statusText.text = "دریافت آخرین تغییرات..."
                } else {
                    statusText.text = "بارگذاری نسخه آفلاین..."
                }

                // Proceed to MainActivity
                Handler(Looper.getMainLooper()).postDelayed({
                    val intent = Intent(this@SplashActivity, MainActivity::class.java).apply {
                        putExtra("use_live", isLiveServerAvailable)
                    }
                    startActivity(intent)
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    finish()
                }, 300)
            }, remaining)
        }
    }
}
