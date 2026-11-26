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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
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
    @Volatile private var lastRunMs = 0L

    /** Shown under the settings card. */
    @Volatile var lastResult: String = if (prefs.driveOn) "waiting for the next finished drive" else "not connected"
        private set

    val enabled get() = prefs.driveOn

    private class Reply(val code: Int, val body: String, val location: String = "") {
        val ok get() = code in 200..299
    }

    /** Called every ~15 s by the logger and when the app opens; does real work at most every few minutes. */
    fun tick(currentSession: Int) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now - lastRunMs < TICK_MS) return
        sync(currentSession)
    }

    /** Uploads what is waiting. [done] gets the result text (on the uploader's thread). */
    fun sync(currentSession: Int, done: (String) -> Unit = {}) {
        lastRunMs = System.currentTimeMillis()
        exec.execute {
            if (enabled) runCatching { run(currentSession) }.onFailure {
                Log.w(TAG, "Drive upload failed", it)
                lastResult = "upload failed: ${it.javaClass.simpleName}"
            }
            done(lastResult)
        }
    }

    /** After the user allowed access in Settings. */
    fun connected() {
        prefs.driveOn = true
        lastResult = "connected"
    }

    /** Stops uploading. Files already in Drive stay there; the folder is looked up again next time. */
    fun disconnect() {
        prefs.driveOn = false
        prefs.driveFolderId = ""
        lastResult = "not connected"
    }

    private fun run(currentSession: Int) {
        val files = SessionFiles.list(ctx).map { DriveLogic.LogFile(it.name, SessionFiles.numberOf(it), it.length()) }
        val done = prefs.driveDoneNames.toMutableSet()
        val plan = DriveLogic.plan(files, prefs.driveLastSession, done, currentSession)
        if (plan.isEmpty()) { if (!lastResult.startsWith("sent")) lastResult = "up to date"; return }
        if (prefs.driveWifiOnly && !onUnmetered()) { lastResult = "waiting for Wi-Fi"; return }

        var token = accessToken() ?: return
        var folder = folderId(token) ?: return
        var sent = 0
        for ((session, group) in plan) {
            for (f in group) {
                val file = File(SessionFiles.folder(ctx), f.name)
                if (!file.exists()) continue
                var r = upload(file, folder, token)
                if (r.code == 401) {
                    // The cached token went stale: drop it and ask once more.
                    clearToken(token)
                    token = accessToken() ?: return
                    r = upload(file, folder, token)
                }
                if (r.code == 404) {
                    // The folder was deleted in Drive: make a new one and try this file again.
                    prefs.driveFolderId = ""
                    folder = folderId(token) ?: return
                    r = upload(file, folder, token)
                }
                if (!r.ok) {
                    lastResult = (if (sent > 0) "sent $sent file(s), then: " else "") + DriveLogic.describe(r.code)
                    return
                }
                sent++
                done += f.name
                prefs.driveDoneNames = done
            }
            prefs.driveLastSession = session
            done.clear()
            prefs.driveDoneNames = done
        }
        lastResult = if (sent == 0) "up to date" else "sent $sent file(s) to Drive, " +
            java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEE dd MMM HH:mm", java.util.Locale.UK))
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
            lastResult = "couldn't reach Google sign-in, will try again"
            return null
        }
        val token = result.accessToken
        if (result.hasResolution() || token.isNullOrEmpty()) {
            lastResult = "Google wants you to confirm access again: tap Connect Google Drive"
            return null
        }
        return token
    }

    private fun clearToken(token: String) {
        runCatching {
            Tasks.await(Identity.getAuthorizationClient(ctx).clearToken(ClearTokenRequest.builder().setToken(token).build()), 15, TimeUnit.SECONDS)
        }
    }

    /** The "DPF Sentinel" folder: the saved one, else one this app made earlier, else a new one. */
    private fun folderId(token: String): String? {
        prefs.driveFolderId.takeIf { it.isNotEmpty() }?.let { return it }
        val q = URLEncoder.encode(DriveLogic.folderQuery(), "UTF-8")
        val found = call("GET", "$API/files?q=$q&spaces=drive&fields=files(id)&pageSize=1", token)
        if (!found.ok) { lastResult = DriveLogic.describe(found.code); return null }
        var id = runCatching { JSONObject(found.body).optJSONArray("files")?.optJSONObject(0)?.optString("id", "") ?: "" }.getOrDefault("")
        if (id.isEmpty()) {
            val meta = JSONObject().put("name", DriveLogic.FOLDER).put("mimeType", "application/vnd.google-apps.folder")
            val made = call("POST", "$API/files?fields=id", token, meta.toString())
            if (!made.ok) { lastResult = DriveLogic.describe(made.code); return null }
            id = runCatching { JSONObject(made.body).optString("id", "") }.getOrDefault("")
            if (id.isEmpty()) { lastResult = "Google Drive made no folder"; return null }
        }
        prefs.driveFolderId = id
        return id
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
            read(c)
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
        if (code !in 200..299) Log.w(TAG, "Drive answered $code: ${body.take(300)}")
        return Reply(code, body, location)
    }

    companion object {
        private const val TAG = "DriveUpload"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val TICK_MS = 10 * 60_000L

        /** Only files this app created: it cannot list or read anything else in the Drive. */
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"

        fun request(): AuthorizationRequest =
            AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
    }
}
