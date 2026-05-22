package com.moligon.questtelegramuploader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.min

class QuestUploadService : Service() {
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Preparando monitoramento..."))
        if (!running) {
            running = true
            val runOnce = intent?.getBooleanExtra(EXTRA_RUN_ONCE, false) == true
            thread(name = "QuestUploadService") {
                try {
                    runLoop(runOnce)
                } catch (error: Exception) {
                    saveStatus(this, message = "Falha: ${error.message ?: "erro desconhecido"}")
                } finally {
                    running = false
                    if (runOnce || !isAutoEnabled(this)) {
                        markStopped(this)
                        stopForeground(false)
                        stopSelf(startId)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun runLoop(runOnce: Boolean) {
        do {
            scanAndUpload()
            if (runOnce || !isAutoEnabled(this)) break
            Thread.sleep(SCAN_INTERVAL_MS)
        } while (true)
    }

    private fun scanAndUpload() {
        val config = loadConfig(this)
        if (config.botToken.isBlank() || config.chatId.isBlank()) {
            saveStatus(this, message = "Configure token do bot e chat ID.")
            updateNotification("Configuração do Telegram pendente.")
            return
        }

        updateNotification("Procurando capturas do Quest...")
        val state = loadState(this)
        val files = discoverFiles(config.paths)
        val known = state.knownFingerprints
        for (file in files) {
            val fingerprint = file.fingerprint()
            if (!known.contains(fingerprint) && state.items.none { it.fingerprint == fingerprint }) {
                state.items.add(QueueItem(file.absolutePath, fingerprint, file.length(), file.lastModified()))
            }
        }
        state.lastScan = timestamp()
        saveState(this, state)
        saveStatus(this, pending = state.pendingCount(), uploaded = state.uploaded, failed = state.failed, skippedLarge = state.skippedLarge, lastScan = state.lastScan, message = "${state.items.size} item(ns) na fila.")

        val wakeLock = acquireWakeLock()
        try {
            for (item in state.items.toList()) {
                val file = File(item.path)
                if (!file.exists()) {
                    item.status = "failed"
                    item.error = "Arquivo não encontrado"
                    state.failed++
                    continue
                }
                if (file.length() > MAX_UPLOAD_BYTES) {
                    item.status = "skipped_large"
                    item.error = "Arquivo maior que 49 MB"
                    state.skippedLarge++
                    state.knownFingerprints.add(item.fingerprint)
                    continue
                }
                updateNotification("Enviando ${file.name}...")
                try {
                    uploadFile(config, file)
                    item.status = "sent"
                    item.error = null
                    state.uploaded++
                    state.knownFingerprints.add(item.fingerprint)
                } catch (error: Exception) {
                    item.attempts++
                    item.error = error.message
                    if (item.attempts >= MAX_ATTEMPTS) {
                        item.status = "failed"
                        state.failed++
                    }
                }
                state.items.removeAll { it.status == "sent" || it.status == "skipped_large" || it.status == "failed" }
                saveState(this, state)
                saveStatus(this, pending = state.pendingCount(), uploaded = state.uploaded, failed = state.failed, skippedLarge = state.skippedLarge, lastScan = state.lastScan, message = "Último arquivo: ${file.name}")
            }
            updateNotification("Monitorando capturas do Quest.")
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuestTelegramUploader:Upload").apply {
            acquire(10 * 60 * 1000L)
        }
    }

    private fun discoverFiles(paths: List<String>): List<File> {
        return paths.asSequence()
            .map { File(it) }
            .filter { it.exists() && it.isDirectory }
            .flatMap { directory -> directory.walkTopDown().maxDepth(3).filter { it.isFile } }
            .filter { it.extension.lowercase(Locale.US) in MEDIA_EXTENSIONS }
            .sortedBy { it.lastModified() }
            .toList()
    }

    private fun uploadFile(config: Config, file: File) {
        val lower = file.extension.lowercase(Locale.US)
        val method = when (lower) {
            "jpg", "jpeg", "png" -> "sendPhoto"
            "mp4" -> "sendVideo"
            else -> "sendDocument"
        }
        val field = when (method) {
            "sendPhoto" -> "photo"
            "sendVideo" -> "video"
            else -> "document"
        }
        val url = URL("https://api.telegram.org/bot${config.botToken}/$method")
        val boundary = "QuestBoundary${UUID.randomUUID()}"
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30000
            readTimeout = 120000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        BufferedOutputStream(connection.outputStream).use { output ->
            output.writeField(boundary, "chat_id", config.chatId)
            output.writeFile(boundary, field, file)
            output.write("--$boundary--\r\n".toByteArray())
        }
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (responseCode !in 200..299 || !response.contains("\"ok\":true")) {
            throw IllegalStateException("Telegram HTTP $responseCode: ${response.take(180)}")
        }
    }

    private fun OutputStream.writeField(boundary: String, name: String, value: String) {
        write("--$boundary\r\n".toByteArray())
        write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
        write(value.toByteArray())
        write("\r\n".toByteArray())
    }

    private fun OutputStream.writeFile(boundary: String, name: String, file: File) {
        val contentType = when (file.extension.lowercase(Locale.US)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
        write("--$boundary\r\n".toByteArray())
        write("Content-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"\r\n".toByteArray())
        write("Content-Type: $contentType\r\n\r\n".toByteArray())
        BufferedInputStream(file.inputStream()).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                write(buffer, 0, read)
            }
        }
        write("\r\n".toByteArray())
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Quest Telegram Uploader", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Quest Telegram Uploader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        saveStatus(this, message = text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    data class Config(val botToken: String, val chatId: String, val paths: List<String>)

    data class QueueItem(
        val path: String,
        val fingerprint: String,
        val size: Long,
        val modified: Long,
        var attempts: Int = 0,
        var status: String = "pending",
        var error: String? = null,
    )

    data class UploadState(
        val items: MutableList<QueueItem> = mutableListOf(),
        val knownFingerprints: MutableSet<String> = mutableSetOf(),
        var uploaded: Int = 0,
        var failed: Int = 0,
        var skippedLarge: Int = 0,
        var lastScan: String = "Nunca",
    ) {
        fun pendingCount(): Int = items.count { it.status == "pending" }
    }

    companion object {
        const val EXTRA_RUN_ONCE = "run_once"
        private const val CHANNEL_ID = "quest_telegram_uploader"
        private const val NOTIFICATION_ID = 9133
        private const val PREFS = "FlutterSharedPreferences"
        private const val NATIVE_CONFIG_PREFS = "quest_telegram_config"
        private const val AUTO_KEY = "auto_enabled"
        private const val STATUS_KEY = "native_status"
        private const val STATE_KEY = "upload_state"
        private const val SCAN_INTERVAL_MS = 60_000L
        private const val MAX_ATTEMPTS = 3
        private const val MAX_UPLOAD_BYTES = 49L * 1024L * 1024L
        private val MEDIA_EXTENSIONS = setOf("jpg", "jpeg", "png", "mp4")
        private val DEFAULT_PATHS = listOf("/sdcard/Oculus/Screenshots", "/sdcard/Oculus/VideoShots")

        fun setAutoEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTO_KEY, enabled).apply()
        }

        fun isAutoEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO_KEY, false)
        }

        fun statusMap(context: Context): Map<String, Any> {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getString(STATUS_KEY, null)?.let { JSONObject(it) } ?: JSONObject()
            return mapOf(
                "running" to saved.optBoolean("running", false),
                "autoEnabled" to isAutoEnabled(context),
                "pending" to saved.optInt("pending", 0),
                "uploaded" to saved.optInt("uploaded", 0),
                "failed" to saved.optInt("failed", 0),
                "skippedLarge" to saved.optInt("skippedLarge", 0),
                "lastScan" to saved.optString("lastScan", "Nunca"),
                "message" to saved.optString("message", "Aguardando início."),
            )
        }

        fun saveConfig(context: Context, botToken: String, chatId: String, paths: List<String>) {
            context.getSharedPreferences(NATIVE_CONFIG_PREFS, Context.MODE_PRIVATE).edit()
                .putString("botToken", botToken.trim())
                .putString("chatId", chatId.trim())
                .putString("paths", JSONArray(paths.map { it.trim() }.filter { it.isNotEmpty() }).toString())
                .apply()
        }

        fun testTelegram(context: Context) {
            val config = loadConfig(context)
            if (config.botToken.isBlank() || config.chatId.isBlank()) throw IllegalStateException("Token/chat ID não configurados")
            val text = URLEncoder.encode("Teste do Quest Telegram Uploader", "UTF-8")
            val chatId = URLEncoder.encode(config.chatId, "UTF-8")
            val url = URL("https://api.telegram.org/bot${config.botToken}/sendMessage?chat_id=$chatId&text=$text")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 30000
            }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299 || !response.contains("\"ok\":true")) {
                throw IllegalStateException("Telegram HTTP $responseCode: ${response.take(180)}")
            }
        }

        fun markStopped(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = prefs.getString(STATUS_KEY, null)?.let { JSONObject(it) } ?: JSONObject()
            current.put("running", false)
            prefs.edit().putString(STATUS_KEY, current.toString()).apply()
        }

        private fun loadConfig(context: Context): Config {
            val prefs = context.getSharedPreferences(NATIVE_CONFIG_PREFS, Context.MODE_PRIVATE)
            val token = prefs.getString("botToken", "").orEmpty()
            val chatId = prefs.getString("chatId", "").orEmpty()
            val paths = prefs.getString("paths", null)?.let { raw -> parseStringList(raw) } ?: DEFAULT_PATHS
            return Config(token, chatId, paths)
        }

        private fun parseStringList(raw: String): List<String> {
            return try {
                val json = JSONArray(raw)
                List(json.length()) { json.getString(it) }
            } catch (_: Exception) {
                raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
        }

        private fun loadState(context: Context): UploadState {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(STATE_KEY, null) ?: return UploadState()
            return try {
                val json = JSONObject(raw)
                val itemsJson = json.optJSONArray("items") ?: JSONArray()
                val items = mutableListOf<QueueItem>()
                for (index in 0 until itemsJson.length()) {
                    val item = itemsJson.getJSONObject(index)
                    items += QueueItem(
                        path = item.getString("path"),
                        fingerprint = item.getString("fingerprint"),
                        size = item.optLong("size"),
                        modified = item.optLong("modified"),
                        attempts = item.optInt("attempts"),
                        status = item.optString("status", "pending"),
                        error = item.optString("error", null),
                    )
                }
                val knownJson = json.optJSONArray("known") ?: JSONArray()
                val known = mutableSetOf<String>()
                for (index in 0 until min(knownJson.length(), 2000)) known += knownJson.getString(index)
                UploadState(items, known, json.optInt("uploaded"), json.optInt("failed"), json.optInt("skippedLarge"), json.optString("lastScan", "Nunca"))
            } catch (_: Exception) {
                UploadState()
            }
        }

        private fun saveState(context: Context, state: UploadState) {
            val json = JSONObject()
            val items = JSONArray()
            for (item in state.items) {
                items.put(JSONObject().apply {
                    put("path", item.path)
                    put("fingerprint", item.fingerprint)
                    put("size", item.size)
                    put("modified", item.modified)
                    put("attempts", item.attempts)
                    put("status", item.status)
                    put("error", item.error)
                })
            }
            json.put("items", items)
            json.put("known", JSONArray(state.knownFingerprints.takeLastCompat(2000)))
            json.put("uploaded", state.uploaded)
            json.put("failed", state.failed)
            json.put("skippedLarge", state.skippedLarge)
            json.put("lastScan", state.lastScan)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(STATE_KEY, json.toString()).apply()
        }

        private fun saveStatus(
            context: Context,
            pending: Int? = null,
            uploaded: Int? = null,
            failed: Int? = null,
            skippedLarge: Int? = null,
            lastScan: String? = null,
            message: String? = null,
        ) {
            val current = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(STATUS_KEY, null)?.let { JSONObject(it) } ?: JSONObject()
            current.put("running", isAutoEnabled(context))
            pending?.let { current.put("pending", it) }
            uploaded?.let { current.put("uploaded", it) }
            failed?.let { current.put("failed", it) }
            skippedLarge?.let { current.put("skippedLarge", it) }
            lastScan?.let { current.put("lastScan", it) }
            message?.let { current.put("message", it) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(STATUS_KEY, current.toString()).apply()
        }

        private fun File.fingerprint(): String = "$absolutePath|${length()}|${lastModified()}"

        private fun timestamp(): String = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(Date())

        private fun <T> Set<T>.takeLastCompat(limit: Int): List<T> {
            val list = toList()
            return if (list.size <= limit) list else list.subList(list.size - limit, list.size)
        }
    }
}
