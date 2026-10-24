package com.ytdstudio.app

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.min

/** Job queue, settings and the API routes used by the WebView UI. */
object Engine {
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val LOCK = Any()
    private val jobs = mutableListOf<Job>()

    @Volatile private var initDone = false
    @Volatile private var initError = ""
    @Volatile private var ytdlpVersion = "loading…"
    private const val APP_VERSION = "1.1.0"
    private const val DIR_LABEL = "Downloads/YTD Studio"
    private const val MIN_FREE = 300L * 1024 * 1024
    private val ACTIVE = setOf("starting", "downloading", "processing")
    private val MEDIA = setOf(
        "mp4", "mp3", "m4a", "webm", "mkv", "opus", "ogg", "wav", "flac", "aac",
        "3gp", "mov", "m4v", "ts", "flv", "avi", "wmv", "mpg"
    )

    val FORMATS = linkedMapOf(
        "best" to "Best quality",
        "compat" to "Most compatible (H.264 MP4)",
        "1080" to "1080p", "720" to "720p", "480" to "480p", "360" to "360p",
        "audio" to "Audio only (MP3)",
        "audio_m4a" to "Audio only (M4A, no re-encode)"
    )

    /** Fallback chain: if YouTube refuses one way of asking, try the next automatically. */
    private val CLIENT_ARGS: List<String?> = listOf(
        null,
        "youtube:player_client=tv,web_safari",
        "youtube:player_client=android_vr",
        "youtube:player_client=mweb"
    )

    private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private val RE_SPEED = Regex("""at\s+~?\s*([\d.]+)\s*([KMGT]?)i?B/s""")
    private val RE_TOTAL = Regex("""of\s+~?\s*([\d.]+)\s*([KMGT]?)i?B""")
    private val RE_ITEM = Regex("""Downloading (?:item|video) (\d+) of (\d+)""")
    private val RE_DEST = Regex("""\[download]\s+Destination:\s*(.+)""")
    private val RE_VID = Regex("""(?:v=|youtu\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})""")
    private val RE_PARTIAL = Regex("""^.*\.f\d+\.[A-Za-z0-9]+$""")

    // ------------------------------------------------------------ lifecycle
    fun init(ctx: Context) {
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        loadJobs()
        Thread {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
            } catch (e: Exception) {
                initError = "Could not start the download engine: ${e.message}"
            }
            ytdlpVersion = runCatching { YoutubeDL.getInstance().version(app) }.getOrNull() ?: "unknown"
            if (initError.isEmpty() && System.currentTimeMillis() - prefs.getLong("last_update", 0) > 12 * 3600_000L) doUpdate()
            initDone = true
        }.start()
        Thread(::scheduler).apply { isDaemon = true }.start()
    }

    /** yt-dlp breaks often; this fetches the newest release. */
    @Synchronized
    private fun doUpdate(): Boolean = try {
        YoutubeDL.getInstance().updateYoutubeDL(app)
        prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
        ytdlpVersion = runCatching { YoutubeDL.getInstance().version(app) }.getOrNull() ?: ytdlpVersion
        true
    } catch (_: Exception) { false }

    @Synchronized
    private fun refreshIfStale() {
        if (System.currentTimeMillis() - prefs.getLong("last_update", 0) > 3600_000L) doUpdate()
    }

    // ----------------------------------------------------------- persistence
    private var savedSig = ""

    private fun loadJobs() {
        val raw = prefs.getString("jobs", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val j = Job.fromStore(arr.getJSONObject(i))
                if (j.status in ACTIVE || j.status == "queued") {
                    j.status = "error"; j.error = "Interrupted when the app closed. Press Retry."
                }
                jobs.add(j)
            }
        } catch (_: Exception) { }
    }

    private fun saveJobs() {
        val (sig, arr) = synchronized(LOCK) {
            val sg = jobs.joinToString("|") { it.id + it.status }
            val a = JSONArray()
            if (sg != savedSig) jobs.takeLast(100).forEach { a.put(it.toStore()) }
            Pair(sg, a)
        }
        if (sig == savedSig) return
        savedSig = sig
        prefs.edit().putString("jobs", arr.toString()).apply()
    }

    // -------------------------------------------------------------- settings
    private fun settings(): JSONObject = JSONObject().apply {
        put("download_dir", DIR_LABEL)
        put("cookies", prefs.getString("cookies", "none"))
        put("cookie_file", prefs.getString("cookie_file", ""))
        put("max_concurrent", prefs.getInt("max", 3))
        put("default_format", prefs.getString("fmt", "best"))
        put("wifi_only", prefs.getBoolean("wifi_only", false))
    }

    private fun updateSettings(p: JSONObject): JSONObject {
        val e = prefs.edit()
        if (p.optString("cookies") in listOf("none", "file")) e.putString("cookies", p.getString("cookies"))
        if (p.has("cookie_file")) e.putString("cookie_file", p.optString("cookie_file").trim())
        if (p.has("max_concurrent")) e.putInt("max", p.optInt("max_concurrent", 3).coerceIn(1, 5))
        if (p.optString("default_format") in FORMATS) e.putString("fmt", p.getString("default_format"))
        if (p.has("wifi_only")) e.putBoolean("wifi_only", p.optBoolean("wifi_only"))
        e.apply()
        return settings()
    }

    fun cookieFile(): File = File(app.filesDir, "cookies.txt")

    private fun onUnmetered(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // ----------------------------------------------------------------- state
    private fun state(): JSONObject {
        val arr = JSONArray()
        synchronized(LOCK) { jobs.asReversed().forEach { arr.put(it.toJson()) } }
        val fm = JSONObject().also { o -> FORMATS.forEach { (k, v) -> o.put(k, v) } }
        return JSONObject().apply {
            put("settings", settings()); put("jobs", arr); put("formats", fm)
            put("info", JSONObject().apply {
                put("version", APP_VERSION); put("ytdlp", ytdlpVersion)
                put("deno", initError.isEmpty()); put("ffmpeg", initError.isEmpty())
            })
        }
    }

    // ---------------------------------------------------------------- routes
    fun call(route: String, bodyStr: String): String = try {
        val body = if (bodyStr.isBlank()) JSONObject() else JSONObject(bodyStr)
        when (route) {
            "/api/state" -> state()
            "/api/add" -> add(body)
            "/api/cancel" -> { cancelJob(body.optString("id")); ok() }
            "/api/retry" -> { retryJob(body.optString("id")); startService(); ok() }
            "/api/remove" -> { removeJob(body.optString("id")); ok() }
            "/api/clear" -> { clearDone(); ok() }
            "/api/settings" -> JSONObject().put("settings", updateSettings(body))
            "/api/update" -> { Thread { doUpdate() }.start(); ok() }
            "/api/open" -> open(body)
            "/api/quit" -> ok()
            else -> JSONObject().put("error", "Not found")
        }.toString()
    } catch (e: Exception) {
        JSONObject().put("error", e.message ?: e.toString()).toString()
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun find(id: String) = synchronized(LOCK) { jobs.firstOrNull { it.id == id } }

    private fun cancelJob(id: String) {
        val j = find(id) ?: return
        synchronized(LOCK) {
            if (j.status == "queued") {
                j.status = "cancelled"; j.error = ""
            } else if (j.status in ACTIVE) {
                j.cancel = true
                runCatching { YoutubeDL.getInstance().destroyProcessById(j.id) }
            }
        }
    }

    private fun retryJob(id: String) {
        val j = find(id) ?: return
        synchronized(LOCK) {
            if (j.status == "error" || j.status == "cancelled" || (j.status == "done" && j.itemsFailed > 0)) j.reset()
        }
    }

    private fun removeJob(id: String) {
        val j = find(id) ?: return
        synchronized(LOCK) {
            if (j.status in listOf("done", "error", "cancelled", "queued")) {
                jobs.remove(j)
                File(archiveDir(), "${j.id}.txt").delete()
            }
        }
    }

    private fun clearDone() {
        synchronized(LOCK) { jobs.removeAll { it.status == "done" || it.status == "cancelled" } }
    }

    private fun archiveDir() = File(app.filesDir, "archives").apply { mkdirs() }

    private fun add(body: JSONObject): JSONObject {
        val found = Regex("""https?://[^\s<>"']+""").findAll(body.optString("url")).map { it.value }.distinct().toList()
        if (found.isEmpty()) return JSONObject().put("error", "Paste a link that starts with http:// or https://")
        val fmt = body.optString("format").takeIf { it in FORMATS } ?: prefs.getString("fmt", "best")!!
        var added = 0
        synchronized(LOCK) {
            for (u in found) {
                if (jobs.any { it.url == u && (it.status in ACTIVE || it.status == "queued") }) continue
                val pl = body.optBoolean("playlist") || ("list=" in u && "watch?v=" !in u && "&v=" !in u && "youtu.be/" !in u)
                val j = Job(UUID.randomUUID().toString().replace("-", "").take(10), u, fmt, pl)
                RE_VID.find(u)?.let { j.thumb = "https://i.ytimg.com/vi/${it.groupValues[1]}/mqdefault.jpg" }
                jobs.add(j); added++
            }
        }
        if (added == 0) return JSONObject().put("error", "That link is already downloading.")
        startService()
        return JSONObject().put("added", added)
    }

    private fun open(body: JSONObject): JSONObject {
        val j = body.optString("id").takeIf { it.isNotEmpty() }?.let { find(it) }
        val what = body.optString("what", "folder")
        return try {
            if (what == "file" && j != null && j.fileUri.isNotEmpty()) {
                val mime = app.contentResolver.getType(Uri.parse(j.fileUri)) ?: "*/*"
                app.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(j.fileUri), mime)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } else {
                app.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            ok()
        } catch (e: ActivityNotFoundException) {
            JSONObject().put("error", "No app found to open this. Look in Downloads/YTD Studio.")
        } catch (e: SecurityException) {
            JSONObject().put("error", "The file was moved or deleted.")
        }
    }

    // ------------------------------------------------------------- scheduler
    private fun startService() {
        if (!DownloadService.running) {
            runCatching { ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java)) }
        }
    }

    private fun scheduler() {
        var tick = 0
        while (true) {
            Thread.sleep(500)
            tick++
            var activeCount = 0
            var speed = 0.0
            synchronized(LOCK) {
                var running = jobs.count { it.status in ACTIVE }
                val limit = prefs.getInt("max", 3)
                val wifiOnly = prefs.getBoolean("wifi_only", false)
                val netOk = !wifiOnly || onUnmetered()
                for (j in jobs) {
                    if (j.status != "queued") continue
                    if (!initDone) { j.error = "Starting engine…"; continue }
                    if (!netOk) { j.error = "Waiting for Wi-Fi…"; continue }
                    if (running >= limit) { j.error = ""; continue }
                    j.error = ""; j.status = "starting"; running++
                    Thread { runJob(j) }.start()
                }
                activeCount = jobs.count { it.status in ACTIVE || it.status == "queued" }
                speed = jobs.filter { it.status == "downloading" }.sumOf { it.speed ?: 0.0 }
            }
            if (tick % 4 == 0) saveJobs()
            if (activeCount == 0 && DownloadService.running) {
                app.stopService(Intent(app, DownloadService::class.java))
            } else if (activeCount > 0 && tick % 4 == 0) {
                val s = if (speed > 0) " · %.1f MB/s".format(speed / 1048576.0) else ""
                DownloadService.update(app, "$activeCount download(s)$s")
            }
        }
    }

    // ------------------------------------------------------------------ jobs
    private fun clean(t: String) = ANSI.replace(t, "").trim()

    private fun friendly(msg: String): String {
        val m = clean(msg).replace(Regex("^ERROR:\\s*"), "")
        val low = m.lowercase()
        return when {
            "not a bot" in low || "sign in to confirm" in low ->
                "YouTube wants proof you are not a bot. Open Settings and load a cookies.txt file."
            "requested format is not available" in low || "no video formats" in low ->
                "YouTube returned no downloadable formats even after trying every method. Use Settings > Update yt-dlp now, then Retry."
            "private video" in low || "video unavailable" in low -> "This video is private or unavailable."
            "unable to resolve host" in low || "timed out" in low || "network is unreachable" in low ->
                "No internet connection. Check your network and press Retry."
            "no space left" in low -> "Your phone is out of storage."
            else -> m.take(400)
        }
    }

    private fun shouldFallback(low: String) = listOf(
        "requested format is not available", "not a bot", "sign in to confirm", "n challenge", "nsig",
        "javascript runtime", "js runtime", "403", "no video formats", "unable to extract", "only images are available"
    ).any { it in low }

    private const val COOKIE_HELP = "YouTube no longer accepts your saved sign-in (the cookies were rotated). " +
        "Export a fresh cookies.txt from a private window, load it in Settings, then press Retry."

    private fun toBytes(n: String, u: String): Double = n.toDouble() * when (u) {
        "K" -> 1024.0; "M" -> 1048576.0; "G" -> 1073741824.0; "T" -> 1099511627776.0; else -> 1.0
    }

    private fun formatSelector(fmt: String) = when (fmt) {
        "audio" -> "bestaudio/best"
        "audio_m4a" -> "bestaudio[ext=m4a]/bestaudio/best"
        "compat" -> "bv*[vcodec^=avc1]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b"
        "1080", "720", "480", "360" -> "bv*[height<=$fmt]+ba/b[height<=$fmt]/b"
        else -> "bv*+ba/b"
    }

    private fun buildRequest(job: Job, work: File, clientArgs: String?): YoutubeDLRequest {
        val req = YoutubeDLRequest(job.url)
        req.addOption("-f", formatSelector(job.format))
        val tmpl = if (job.playlist)
            "${work.absolutePath}/%(playlist_title|Playlist)s/%(playlist_index&{} - |)s%(title)s.%(ext)s"
        else "${work.absolutePath}/%(title)s.%(ext)s"
        req.addOption("-o", tmpl)
        req.addOption(if (job.playlist) "--yes-playlist" else "--no-playlist")
        if (job.playlist) {
            req.addOption("--no-abort-on-error")
            req.addOption("--sleep-interval", "3"); req.addOption("--max-sleep-interval", "8")
            // on Retry, already-finished playlist items are skipped
            req.addOption("--download-archive", File(archiveDir(), "${job.id}.txt").absolutePath)
        }
        req.addOption("--windows-filenames")
        req.addOption("--retries", "5"); req.addOption("--fragment-retries", "5")
        req.addOption("--socket-timeout", "30")
        req.addOption("-N", "4"); req.addOption("--sleep-requests", "1")
        req.addOption("--cache-dir", File(app.cacheDir, "ytdlp").absolutePath)
        when (job.format) {
            "audio" -> { req.addOption("-x"); req.addOption("--audio-format", "mp3"); req.addOption("--audio-quality", "192K") }
            "audio_m4a" -> { req.addOption("-x"); req.addOption("--audio-format", "m4a") }
            "compat" -> req.addOption("--merge-output-format", "mp4")
            else -> { req.addOption("--merge-output-format", "mp4"); req.addOption("-S", "res,ext:mp4:m4a") }
        }
        if (prefs.getString("cookies", "none") == "file" && cookieFile().isFile) {
            req.addOption("--cookies", cookieFile().absolutePath)
        }
        if (ytdlpVersion >= "2025.11") req.addOption("--remote-components", "ejs:github")
        if (clientArgs != null) req.addOption("--extractor-args", clientArgs)
        return req
    }

    private fun onLine(job: Job, p: Float, eta: Long, line: String) {
        if (job.cancel) return
        val l = clean(line)
        RE_ITEM.find(l)?.let { job.index = it.groupValues[1].toInt(); job.count = it.groupValues[2].toInt() }
        RE_DEST.find(l)?.let {
            job.title = File(it.groupValues[1]).nameWithoutExtension.replace(Regex("""\.f\d+$"""), "")
        }
        if (l.startsWith("[Merger]") || l.startsWith("[ExtractAudio]") || l.startsWith("[VideoConvertor]") || l.startsWith("[Fixup")) {
            job.status = "processing"; job.speed = null; job.eta = null
        } else if (p >= 0) {
            job.status = "downloading"
            job.progress = min(99.9, p.toDouble())
            job.eta = if (eta >= 0) eta else null
            RE_SPEED.find(l)?.let { job.speed = toBytes(it.groupValues[1], it.groupValues[2]) }
            RE_TOTAL.find(l)?.let {
                val tot = toBytes(it.groupValues[1], it.groupValues[2]).toLong()
                job.total = tot; job.downloaded = (tot * p / 100.0).toLong()
            }
        }
    }

    private fun runJob(job: Job) {
        if (initError.isNotEmpty()) { job.status = "error"; job.error = initError; notifyDone(job); return }
        val base = app.getExternalFilesDir(null) ?: app.filesDir
        if (base.usableSpace < MIN_FREE) {
            job.status = "error"; job.error = "Not enough free storage (need at least 300 MB)."; notifyDone(job); return
        }
        val work = File(base, "work/${job.id}")
        var attempt = 0
        try {
            while (true) {
                work.deleteRecursively(); work.mkdirs()
                job.resetProgress()
                try {
                    val req = buildRequest(job, work, CLIENT_ARGS[attempt])
                    YoutubeDL.getInstance().execute(req, job.id) { p, eta, line -> onLine(job, p, eta, line) }
                    if (job.cancel) { job.status = "cancelled"; return }
                    val n = publish(job, work)
                    if (n == 0 && !job.playlist) throw IllegalStateException("Nothing was downloaded.")
                    job.itemsDone = n; job.status = "done"; job.progress = 100.0; job.error = ""
                    notifyDone(job)
                    return
                } catch (e: Exception) {
                    if (job.cancel) { job.status = "cancelled"; return }
                    val msg = clean(e.message ?: e.toString())
                    val low = msg.lowercase()
                    if ("no longer valid" in low && "cookie" in low) job.cookieWarn = true
                    val hasFiles = work.walkTopDown().any { it.isFile && it.extension.lowercase() in MEDIA }
                    if (!hasFiles && attempt < CLIENT_ARGS.lastIndex && shouldFallback(low)) {
                        if (attempt == 0) refreshIfStale()
                        attempt++
                        job.detail = "Retrying with another method (${attempt + 1}/${CLIENT_ARGS.size})"
                        continue
                    }
                    val got = if (hasFiles) runCatching { publish(job, work) }.getOrDefault(0) else 0
                    job.itemsDone = got; job.itemsFailed = 1; job.detail = msg
                    if (got > 0) {
                        job.status = "done"; job.progress = 100.0
                        job.error = "Some items could not be downloaded. Press Retry to try again. " +
                            (if (job.cookieWarn) COOKIE_HELP else "")
                    } else {
                        job.status = "error"
                        job.error = if (job.cookieWarn) COOKIE_HELP else friendly(msg)
                    }
                    notifyDone(job)
                    return
                }
            }
        } finally {
            job.speed = null; job.eta = null
            if (job.cancel) job.status = "cancelled"
            work.deleteRecursively()
        }
    }

    private fun notifyDone(job: Job) {
        try {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel("done") == null) {
                nm.createNotificationChannel(NotificationChannel("done", "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val pi = PendingIntent.getActivity(
                app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val good = job.status == "done"
            nm.notify(
                job.id.hashCode(),
                Notification.Builder(app, "done")
                    .setContentTitle(if (good) "Download finished" else "Download failed")
                    .setContentText(job.title)
                    .setSmallIcon(if (good) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                    .setAutoCancel(true).setContentIntent(pi).build()
            )
        } catch (_: Exception) { }
    }

    /** Copy finished files from the private work dir into the public Downloads folder. */
    private fun publish(job: Job, work: File): Int {
        var n = 0
        val files = work.walkTopDown().filter { it.isFile && it.extension.lowercase() in MEDIA && !RE_PARTIAL.matches(it.name) }.toList()
        for (f in files) {
            val rel = f.parentFile!!.relativeTo(work).path
            val sub = if (rel.isEmpty() || rel == ".") "" else "/$rel"
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/YTD Studio$sub")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val r = app.contentResolver
            val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: continue
            r.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
            r.update(uri, cv, null, null)
            job.file = "$DIR_LABEL$sub/${f.name}"; job.fileUri = uri.toString()
            n++
        }
        return n
    }
}
