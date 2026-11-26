package io.github.vitorhpsousa.dpfsentinel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import io.github.vitorhpsousa.dpfsentinel.core.DriveLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Optional back-up of the log files to the user's own Google Drive, off until they connect it in
// Settings. Finished sessions go to a "DPF Sentinel" folder; the session still being written waits
// until it is closed. The app asks Google only for the "drive.file" permission, which covers files
// the app itself created and nothing else in the Drive. Nothing is sent anywhere else.
class DriveUpload(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = Prefs(ctx)
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var lastTickMs = 0L

    /**
     * The session the logger is writing right now, -1 for none. Set by the logger before it opens
     * the files and cleared after it closes them, and read at upload time (not when a pass was
     * queued), so a file that is still growing is never sent as if it were complete.
     */
    @Volatile var activeSession: Int = -1

    private val _result = MutableStateFlow(if (prefs.driveOn) "waiting for the next finished drive" else "not connected")

    /** Shown under the settings card; changes as passes run in the background. */
    val result: StateFlow<String> = _result.asStateFlow()

    val enabled get() = prefs.driveOn

    /** [folderGone]: the folder, not the file transfer, was what Drive could not find. */
    private class Reply(val code: Int, val body: String, val location: String = "", val folderGone: Boolean = false) {
        val ok get() = code in 200..299
    }

    /** A line for the settings card from the screen itself ("asking Google…"). */
    fun note(text: String) { _result.value = text }

    /** Called every ~15 s by the logger; does real work at most every few minutes. */
    fun tick() {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now - lastTickMs < TICK_MS) return
        lastTickMs = now
        sync()
    }

    /** Uploads what is waiting, pass after pass until nothing is left or something stops it. */
    fun sync() {
        exec.execute {
            runCatching {
                var passes = 0
                while (enabled && pass() && ++passes < MAX_PASSES) { /* a long backlog: keep going */ }
            }.onFailure {
                Log.w(TAG, "Drive upload failed", it)
                _result.value = "upload failed: ${it.javaClass.simpleName}"
            }
        }
    }

    /** After the user allowed access in Settings. */
    fun connected() {
        prefs.driveOn = true
        _result.value = "connected"
    }

    /** Stops uploading. Files already in Drive stay there; the folder is looked up again next time. */
    fun disconnect() {
        prefs.driveOn = false
        prefs.driveFolderId = ""
        _result.value = "not connected"
    }

    /** One pass. True when it sent something and more sessions are still waiting. */
    private fun pass(): Boolean {
        val files = SessionFiles.list(ctx).map { DriveLogic.LogFile(it.name, SessionFiles.numberOf(it), it.length()) }
        val done = prefs.driveDoneNames.toMutableSet()
        val waiting = files.filter { it.session > prefs.driveLastSession && it.session != activeSession }.map { it.session }.distinct().size
        val plan = DriveLogic.plan(files, prefs.driveLastSession, done, activeSession)
        if (plan.isEmpty()) { if (!_result.value.startsWith("sent")) _result.value = "up to date"; return false }
        if (prefs.driveWifiOnly && !onUnmetered()) { _result.value = "waiting for Wi-Fi ($waiting drive(s) to upload)"; return false }

        var token = accessToken() ?: return false
        var folder = folderId(token) ?: return false
        var sent = 0
        for ((session, group) in plan) {
            for (f in group) {
                // Disconnected meanwhile, or the logger started writing this session again: stop here.
                if (!enabled) return false
                if (session == activeSession) return false
                val file = File(SessionFiles.folder(ctx), f.name)
                if (!file.exists()) continue
                // Already there (an earlier upload finished but its answer was lost): don't make a second copy.
                val there = exists(f.name, folder, token)
                var r = if (there) Reply(200, "") else upload(file, folder, token)
                if (r.code == 401) {
                    // The cached token went stale: drop it and ask once more.
                    clearToken(token)
                    token = accessToken() ?: return false
                    r = upload(file, folder, token)
                }
                if (r.folderGone) {
                    // The folder was deleted in Drive: make a new one and try this file again.
                    prefs.driveFolderId = ""
                    folder = folderId(token) ?: return false
                    r = upload(file, folder, token)
                }
                if (!r.ok) {
                    _result.value = (if (sent > 0) "sent $sent file(s), then: " else "") + DriveLogic.describe(r.code)
                    return false
                }
                if (!there) sent++
                done += f.name
                prefs.driveDoneNames = done
            }
            prefs.driveLastSession = session
            done.clear()
            prefs.driveDoneNames = done
        }
        val left = waiting - plan.size
        val at = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE dd MMM HH:mm", Locale.UK))
        _result.value = when {
            left > 0 -> "sent $sent file(s), $left more drive(s) to go…"
            sent == 0 -> "up to date"
            else -> "sent $sent file(s) to Drive, $at"
        }
        return left > 0
    }

    private fun onUnmetered(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** A fresh access token without bothering the user, or null (and a result text) when Google wants them to confirm again. */
    private fun accessToken(): String? {
        val result: AuthorizationResult = try {
            Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request()), 30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "authorize failed", e)
            _result.value = "couldn't reach Google sign-in, will try again"
            return null
        }
        val token = result.accessToken
        if (result.hasResolution() || token.isNullOrEmpty()) {
            _result.value = "Google wants you to confirm access again: tap Reconnect"
            return null
        }
        return token
    }

    private fun clearToken(token: String) {
        runCatching {
            Tasks.await(Identity.getAuthorizationClient(ctx).clearToken(ClearTokenRequest.builder().setToken(token).build()), 15, TimeUnit.SECONDS)
        }
    }

    /** The "DPF Sentinel" folder: the saved one if it is still there and not binned, else one this app made earlier, else a new one. */
    private fun folderId(token: String): String? {
        val saved = prefs.driveFolderId
        if (saved.isNotEmpty()) {
            val r = call("GET", "$API/files/$saved?fields=trashed", token)
            val binned = r.ok && runCatching { JSONObject(r.body).optBoolean("trashed", false) }.getOrDefault(false)
            if (r.ok && !binned) return saved
            if (!r.ok && r.code != 404) { _result.value = DriveLogic.describe(r.code); return null }
            // Deleted or in the bin: forget it and look for (or make) another.
        }
        val q = URLEncoder.encode(DriveLogic.folderQuery(), "UTF-8")
        val found = call("GET", "$API/files?q=$q&spaces=drive&fields=files(id)&pageSize=1", token)
        if (!found.ok) { _result.value = DriveLogic.describe(found.code); return null }
        var id = runCatching { JSONObject(found.body).optJSONArray("files")?.optJSONObject(0)?.optString("id", "") ?: "" }.getOrDefault("")
        if (id.isEmpty()) {
            val meta = JSONObject().put("name", DriveLogic.FOLDER).put("mimeType", "application/vnd.google-apps.folder")
            val made = call("POST", "$API/files?fields=id", token, meta.toString())
            if (!made.ok) { _result.value = DriveLogic.describe(made.code); return null }
            id = runCatching { JSONObject(made.body).optString("id", "") }.getOrDefault("")
            if (id.isEmpty()) { _result.value = "Google Drive made no folder"; return null }
        }
        if (!enabled) return null   // disconnected while this ran: don't bring the folder id back
        prefs.driveFolderId = id
        return id
    }

    /** Whether a file of this name is already in the folder. Any doubt counts as "no". */
    private fun exists(name: String, folder: String, token: String): Boolean {
        val q = URLEncoder.encode(DriveLogic.fileQuery(name, folder), "UTF-8")
        val r = call("GET", "$API/files?q=$q&spaces=drive&fields=files(id)&pageSize=1", token)
        if (!r.ok) return false
        return runCatching { (JSONObject(r.body).optJSONArray("files")?.length() ?: 0) > 0 }.getOrDefault(false)
    }

    /** One file, as a resumable upload (no size limit): the description first, then the bytes. */
    private fun upload(f: File, folder: String, token: String): Reply {
        val meta = JSONObject()
            .put("name", f.name)
            .put("parents", JSONArray().put(folder))
            .put("modifiedTime", Instant.ofEpochMilli(f.lastModified()).toString())
        val start = call(
            "POST", "$UPLOAD/files?uploadType=resumable", token, meta.toString(),
            extra = mapOf("X-Upload-Content-Type" to DriveLogic.mime(f.name), "X-Upload-Content-Length" to f.length().toString()),
        )
        // Only here does "not found" mean the folder: a 404 on the transfer below is a lost upload session.
        if (start.code == 404) return Reply(404, start.body, folderGone = true)
        if (!start.ok) return start
        if (start.location.isEmpty()) return Reply(0, "")
        return try {
            val c = URL(start.location).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.requestMethod = "PUT"
            c.doOutput = true
            c.setFixedLengthStreamingMode(f.length())
            c.setRequestProperty("Content-Type", DriveLogic.mime(f.name))
            f.inputStream().use { input -> c.outputStream.use { input.copyTo(it) } }
            val r = read(c)
            if (r.code == 404) Reply(0, "") else r
        } catch (e: Exception) {
            Log.w(TAG, "upload of ${f.name} failed: ${e.javaClass.simpleName}")
            Reply(0, "")
        }
    }

    /** One Drive API call. Never throws: no answer at all (offline, timeout) is code 0. */
    private fun call(method: String, url: String, token: String, json: String? = null, extra: Map<String, String> = emptyMap()): Reply =
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer $token")
            extra.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (json != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                c.outputStream.use { it.write(json.toByteArray()) }
            }
            read(c)
        } catch (e: Exception) {
            Log.w(TAG, "$method failed: ${e.javaClass.simpleName}")
            Reply(0, "")
        }

    private fun read(c: HttpURLConnection): Reply {
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        val location = c.getHeaderField("Location") ?: ""
        c.disconnect()
        if (code !in 200..299) Log.w(TAG, "Drive answered $code")
        return Reply(code, body, location)
    }

    companion object {
        private const val TAG = "DriveUpload"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val TICK_MS = 10 * 60_000L
        private const val MAX_PASSES = 20

        /** Only files this app created: it cannot list or read anything else in the Drive. */
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"

        fun request(): AuthorizationRequest =
            AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
    }
}
