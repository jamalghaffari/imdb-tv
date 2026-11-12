package com.persica.imdbtv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.storage.StorageManager
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AppDownloadManager(private val context: Context) {

    data class DownloadTask(
        val id: String,
        val title: String,
        val url: String,
        val quality: String,
        var status: String, // queued | scheduled | downloading | paused | completed | error
        var downloadedBytes: Long = 0L,
        var totalBytes: Long = 0L,
        var speedBps: Long = 0L,
        var filePath: String = "",
        var errorMsg: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("url", url)
            put("quality", quality)
            put("status", status)
            put("downloadedBytes", downloadedBytes)
            put("totalBytes", totalBytes)
            put("speedBps", speedBps)
            put("filePath", filePath)
            put("errorMsg", errorMsg)
        }
    }

    private val tasksFile = File(context.filesDir, "dm_tasks.json")
    private val configFile = File(context.filesDir, "dm_config.json")
    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    private val activeFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val handler = Handler(Looper.getMainLooper())

    var scheduleEnabled: Boolean = false
    var startHour: Int = 2
    var endHour: Int = 7
    var wifiOnly: Boolean = true
    @Volatile var storageMode: String = "external" // "external" or "internal" or "usb"
    @Volatile var customSavePath: String = ""

    private val client: OkHttpClient = run {
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
        OkHttpClient.Builder()
            .dns(SmartDns)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionSpecs(listOf(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .sslSocketFactory(compatFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    init {
        loadConfig()
        loadTasks()
        startSchedulerLoop()
    }

    private fun getSaveDir(): File {
        if (customSavePath.isNotBlank()) {
            try {
                val custom = File(customSavePath)
                if ((custom.exists() || custom.mkdirs()) && custom.canWrite()) {
                    return custom
                }
            } catch (_: Exception) {}
        }
        val dir = if (storageMode == "external") {
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "movies")
        } else {
            File(context.filesDir, "movies")
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getAvailableStoragesJson(): String {
        val arr = JSONArray()
        val seen = HashSet<String>()

        fun addOption(id: String, label: String, dir: File?, isUsb: Boolean) {
            if (dir == null) return
            try {
                if (!dir.exists()) dir.mkdirs()
                val probe = File(dir, ".write_test")
                val writable = try {
                    probe.writeText("1")
                    probe.delete()
                    true
                } catch (_: Exception) {
                    dir.canWrite()
                }
                if (!writable) return
                val abs = dir.absolutePath
                if (!seen.add(abs)) return
                val stat = StatFs(abs)
                val freeGb = String.format(java.util.Locale.US, "%.1f", stat.availableBytes / (1024.0 * 1024.0 * 1024.0))
                arr.put(JSONObject().apply {
                    put("id", id)
                    put("label", label)
                    put("path", abs)
                    put("freeGb", freeGb)
                    put("isUsb", isUsb)
                })
            } catch (_: Exception) {}
        }

        // 1. Direct USB / Removable drives (e.g. /storage/XXXX-XXXX/IMDbCinema) when full storage permission is granted
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && sm != null) {
                sm.storageVolumes.forEachIndexed { idx, vol ->
                    if (vol.isRemovable) {
                        val volDir = vol.directory
                        if (volDir != null) {
                            val name = vol.getDescription(context) ?: "فلش USB"
                            addOption("usb_root_$idx", "💾 $name (پوشه مستقیم IMDbCinema)", File(volDir, "IMDbCinema"), true)
                        }
                    }
                }
            }
            File("/storage").listFiles()?.forEachIndexed { idx, f ->
                if (f.isDirectory && f.name !in setOf("emulated", "self")) {
                    addOption("usb_mnt_$idx", "💾 فلش USB (${f.name})", File(f, "IMDbCinema"), true)
                }
            }
        } catch (_: Exception) {}

        // 2. App-scoped external & removable dirs (always writable on Android TV without special permissions)
        try {
            val extDirs = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
            extDirs.forEachIndexed { idx, d ->
                if (d != null) {
                    val isRemovable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        try { Environment.isExternalStorageRemovable(d) } catch (_: Exception) { idx > 0 }
                    } else idx > 0
                    if (isRemovable || idx > 0) {
                        addOption("usb_app_$idx", "🔌 فلش USB / کارت حافظه (مسیر امن اندروید)", d, true)
                    } else {
                        addOption("external", "📱 حافظه داخلی دستگاه", d, false)
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Public Movies/IMDbCinema on primary storage (when granted)
        try {
            val pubMovies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (pubMovies != null) {
                addOption("public_movies", "🎬 پوشه عمومی Movies/IMDbCinema", File(pubMovies, "IMDbCinema"), false)
            }
        } catch (_: Exception) {}

        // 4. Private internal fallback
        addOption("internal", "🔒 حافظه خصوصی برنامه", File(context.filesDir, "movies"), false)

        return arr.toString()
    }

    @Synchronized
    private fun loadConfig() {
        try {
            if (!configFile.exists()) return
            val obj = JSONObject(configFile.readText())
            scheduleEnabled = obj.optBoolean("scheduleEnabled", false)
            startHour = obj.optInt("startHour", 2)
            endHour = obj.optInt("endHour", 7)
            wifiOnly = obj.optBoolean("wifiOnly", true)
            storageMode = obj.optString("storageMode", "external")
            customSavePath = obj.optString("customSavePath", "")
        } catch (_: Exception) {}
    }

    @Synchronized
    fun saveConfig(jsonStr: String) {
        try {
            val obj = JSONObject(jsonStr)
            scheduleEnabled = obj.optBoolean("scheduleEnabled", false)
            startHour = obj.optInt("startHour", 2).coerceIn(0, 23)
            endHour = obj.optInt("endHour", 7).coerceIn(0, 23)
            wifiOnly = obj.optBoolean("wifiOnly", true)
            storageMode = obj.optString("storageMode", "external")
            customSavePath = obj.optString("customSavePath", "")
            configFile.writeText(getConfigJson())
            evaluateQueue()
        } catch (_: Exception) {}
    }

    fun getConfigJson(): String = JSONObject().apply {
        put("scheduleEnabled", scheduleEnabled)
        put("startHour", startHour)
        put("endHour", endHour)
        put("wifiOnly", wifiOnly)
        put("storageMode", storageMode)
        put("customSavePath", customSavePath)
        put("savePath", getSaveDir().absolutePath)
    }.toString()

    @Synchronized
    private fun loadTasks() {
        try {
            if (!tasksFile.exists()) return
            val arr = JSONArray(tasksFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                var st = o.optString("status", "paused")
                if (st == "downloading") st = "paused"
                val t = DownloadTask(
                    id = o.getString("id"),
                    title = o.optString("title", "فیلم"),
                    url = o.getString("url"),
                    quality = o.optString("quality", ""),
                    status = st,
                    downloadedBytes = o.optLong("downloadedBytes", 0L),
                    totalBytes = o.optLong("totalBytes", 0L),
                    speedBps = 0L,
                    filePath = o.optString("filePath", ""),
                    errorMsg = o.optString("errorMsg", "")
                )
                tasks[t.id] = t
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun persistTasks() {
        try {
            val arr = JSONArray()
            tasks.values.sortedBy { it.title }.forEach { arr.put(it.toJson()) }
            tasksFile.writeText(arr.toString())
        } catch (_: Exception) {}
    }

    private fun isWithinScheduleWindow(): Boolean {
        if (!scheduleEnabled) return true
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (startHour <= endHour) {
            hour in startHour until endHour
        } else {
            hour >= startHour || hour < endHour
        }
    }

    @Suppress("DEPRECATION")
    private fun isWifiOrEthernet(): Boolean {
        if (!wifiOnly) return true
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val net = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(net) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } else {
                val info = cm.activeNetworkInfo ?: return false
                info.isConnected && (info.type == ConnectivityManager.TYPE_WIFI || info.type == ConnectivityManager.TYPE_ETHERNET)
            }
        } catch (_: Exception) { true }
    }

    private fun canDownloadNow(): Boolean = isWithinScheduleWindow() && isWifiOrEthernet()

    private fun startSchedulerLoop() {
        val runnable = object : Runnable {
            override fun run() {
                evaluateQueue()
                handler.postDelayed(this, 20000L)
            }
        }
        handler.postDelayed(runnable, 5000L)
    }

    fun enqueue(id: String, title: String, url: String, quality: String) {
        val safeName = (id + "_" + title).replace(Regex("[^a-zA-Z0-9._-]"), "_").take(60) + ".mp4"
        val destFile = File(getSaveDir(), safeName)
        val existing = tasks[id]
        if (existing != null && existing.status == "completed" && File(existing.filePath).exists()) {
            return
        }
        val task = existing ?: DownloadTask(
            id = id,
            title = title,
            url = url,
            quality = quality,
            status = if (canDownloadNow()) "queued" else "scheduled",
            filePath = destFile.absolutePath
        )
        task.status = if (canDownloadNow()) "queued" else "scheduled"
        task.errorMsg = ""
        tasks[id] = task
        persistTasks()
        evaluateQueue()
    }

    fun pause(id: String) {
        activeFlags[id]?.set(false)
        tasks[id]?.let {
            if (it.status == "downloading" || it.status == "queued" || it.status == "scheduled") {
                it.status = "paused"
                it.speedBps = 0L
                persistTasks()
            }
        }
    }

    fun resume(id: String) {
        tasks[id]?.let {
            if (it.status != "completed") {
                it.status = if (canDownloadNow()) "queued" else "scheduled"
                it.errorMsg = ""
                persistTasks()
                evaluateQueue()
            }
        }
    }

    fun delete(id: String) {
        activeFlags[id]?.set(false)
        tasks.remove(id)?.let {
            try { File(it.filePath).delete() } catch (_: Exception) {}
            persistTasks()
        }
    }

    fun getAllJson(): String {
        val arr = JSONArray()
        tasks.values.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    @Synchronized
    private fun evaluateQueue() {
        val allowed = canDownloadNow()
        if (!allowed) {
            tasks.values.filter { it.status == "downloading" || it.status == "queued" }.forEach {
                activeFlags[it.id]?.set(false)
                it.status = "scheduled"
                it.speedBps = 0L
            }
            persistTasks()
            return
        }

        val downloadingCount = tasks.values.count { it.status == "downloading" }
        if (downloadingCount >= 2) return

        val next = tasks.values.firstOrNull { it.status == "queued" || it.status == "scheduled" } ?: return
        startDownloadWorker(next)
    }

    private fun startDownloadWorker(task: DownloadTask) {
        val flag = AtomicBoolean(true)
        activeFlags[task.id] = flag
        task.status = "downloading"
        persistTasks()

        Thread {
            try {
                val file = File(task.filePath)
                file.parentFile?.mkdirs()
                val existingLen = if (file.exists()) file.length() else 0L
                task.downloadedBytes = existingLen

                val reqBuilder = Request.Builder()
                    .url(task.url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                if (existingLen > 0L) {
                    reqBuilder.header("Range", "bytes=$existingLen-")
                }

                val resp = client.newCall(reqBuilder.build()).execute()
                if (!resp.isSuccessful && resp.code != 206) {
                    resp.close()
                    task.status = "error"
                    task.errorMsg = "HTTP ${resp.code}"
                    task.speedBps = 0L
                    persistTasks()
                    evaluateQueue()
                    return@Thread
                }

                val body = resp.body
                if (body == null) {
                    resp.close()
                    task.status = "error"
                    task.errorMsg = "پاسخ خالی از سرور"
                    persistTasks()
                    evaluateQueue()
                    return@Thread
                }

                val isResume = (resp.code == 206 && existingLen > 0L)
                val contentLen = body.contentLength()
                if (contentLen > 0L) {
                    task.totalBytes = if (isResume) existingLen + contentLen else contentLen
                }

                val raf = RandomAccessFile(file, "rw")
                if (isResume) {
                    raf.seek(existingLen)
                } else {
                    raf.setLength(0L)
                    task.downloadedBytes = 0L
                }

                val buf = ByteArray(32 * 1024)
                var lastTickMs = System.currentTimeMillis()
                var bytesSinceTick = 0L

                body.byteStream().use { input ->
                    raf.use { out ->
                        while (flag.get()) {
                            val read = input.read(buf)
                            if (read == -1) break
                            out.write(buf, 0, read)
                            task.downloadedBytes += read
                            bytesSinceTick += read

                            val now = System.currentTimeMillis()
                            val dt = now - lastTickMs
                            if (dt >= 1000L) {
                                task.speedBps = (bytesSinceTick * 1000L) / dt
                                bytesSinceTick = 0L
                                lastTickMs = now
                                persistTasks()
                            }
                        }
                    }
                }
                resp.close()

                if (!flag.get()) {
                    task.speedBps = 0L
                    if (task.status == "downloading") task.status = "paused"
                } else {
                    task.status = "completed"
                    task.speedBps = 0L
                    if (task.totalBytes <= 0L) task.totalBytes = task.downloadedBytes
                }
                persistTasks()
                evaluateQueue()
            } catch (e: Exception) {
                task.status = "error"
                task.speedBps = 0L
                task.errorMsg = e.message?.take(60) ?: "خطای شبکه"
                persistTasks()
                evaluateQueue()
            }
        }.start()
    }
}
