package io.github.vitorhpsousa.dpfsentinel

import android.content.Context
import android.util.Log
import io.github.vitorhpsousa.dpfsentinel.core.Messages
import io.github.vitorhpsousa.dpfsentinel.core.TelegramLogic
import io.github.vitorhpsousa.dpfsentinel.core.TelegramLogic.ChatCandidate
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors

// The phone's version of src/report/telegram_report.cpp: queued alerts (delivered once the phone is
// online, oldest dropped past 10), a daily report after the set time that uploads the session logs
// not sent yet, and replies to "/status" from the configured chat. Off until a token and chat id are set.
// The parts that need no network (tidying ids, reading answers, wording errors) are core's TelegramLogic.
class Telegram(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = Prefs(ctx)
    private val exec = Executors.newSingleThreadExecutor()
    private val queue = ArrayDeque<String>()
    private var lastPollMs = 0L
    private var retryAfterMs = 0L        // next try of a failed daily report
    private var quietUntilMs = 0L        // Telegram said "too many requests": nothing automatic before this
    private var summarySentFor = NO_DAY  // report day whose summary message already went (not repeated on retries)
    // Chats seen by the /status poll, which confirms what it reads: kept so "Find my chat" still sees them.
    private val seenChats = ArrayList<ChatCandidate>()

    @Volatile var lastResult = "not run yet"
        private set

    val enabled get() = prefs.telegramEnabled

    /** What Telegram answered: the HTTP status (0 = no answer at all) and the body, error bodies included. */
    private class Reply(val code: Int, val body: String) {
        val ok get() = code == 200 && body.contains("\"ok\":true")
        /** Refused for good (bad token, unknown chat, malformed): sending the same thing again cannot work. */
        val permanent get() = code in 400..499 && code != 429
        fun error() = TelegramLogic.describeError(code, body)
    }

    /** Queue a one-off alert; sent as soon as possible. */
    fun event(text: String) {
        if (!enabled) return
        synchronized(queue) {
            if (queue.size >= MAX_QUEUE) queue.removeFirst()
            queue.addLast(text)
        }
        exec.execute { drain() }
    }

    /**
     * Called every ~15 s by the logger. statusText builds the "/status" reply; currentSession is
     * the live session, which is still growing and goes out with the next report.
     */
    fun tick(statusText: () -> String, latestLine: String, currentSession: Int) {
        if (!enabled) return
        exec.execute {
            drain()
            maybeDailyReport(latestLine, currentSession)
            val now = System.currentTimeMillis()
            if (now - lastPollMs >= POLL_MS && now >= quietUntilMs) { lastPollMs = now; pollCommands(statusText) }
        }
    }

    fun sendReportNow(latestLine: String, currentSession: Int, done: (String) -> Unit) {
        exec.execute {
            if (!enabled) lastResult = NOT_SET
            else runReport(force = true, latestLine = latestLine, currentSession = currentSession, day = LocalDate.now().toEpochDay())
            done(lastResult)
        }
    }

    /**
     * Sends a test message with the values given (what is typed in the settings, saved or not).
     * When it arrives both are saved, so there is no need to tap Save first.
     */
    fun test(token: String, chatId: String, done: (String) -> Unit) {
        exec.execute {
            val t = token.trim()
            val chat = TelegramLogic.normaliseChatId(chatId)
            lastResult = tokenProblem(t) ?: when {
                chat == null -> BAD_CHAT
                TelegramLogic.looksLikeBotIdFromToken(chat, t) -> BOT_ID
                else -> {
                    val r = sendMessage("DPF Sentinel (phone): test message. Alerts will arrive here.", t, chat)
                    if (r.ok) {
                        prefs.tgToken = t
                        prefs.tgChatId = chat
                        "Test message sent, settings saved"
                    } else r.error()
                }
            }
            done(lastResult)
        }
    }

    /**
     * The chats that wrote to the bot lately, newest first, for "Find my chat". Asks getUpdates
     * without an offset, so nothing is confirmed and a later /status still reaches the logger.
     * The second value is what to show: "Found: ...", what to do when nobody wrote, or the error.
     */
    fun findChats(token: String, done: (List<ChatCandidate>, String) -> Unit) {
        exec.execute {
            val t = token.trim()
            val problem = tokenProblem(t)
            if (problem != null) { done(emptyList(), problem); return@execute }
            val r = request(t, "getUpdates?timeout=0", null)
            if (!r.ok) { done(emptyList(), r.error()); return@execute }
            val remembered = if (t == prefs.tgToken) seenChats.toList() else emptyList()
            val found = TelegramLogic.latestPerChat(remembered + TelegramLogic.findChats(r.body)).reversed()
            done(
                found,
                when (found.size) {
                    0 -> "Send any message to your bot in Telegram, then tap Find my chat again"
                    1 -> "Found: ${label(found[0])}"
                    else -> "Several chats wrote to this bot. Pick yours:"
                },
            )
        }
    }

    fun shutdown() { exec.shutdown() }

    private fun drain() {
        while (true) {
            if (System.currentTimeMillis() < quietUntilMs) return
            val msg = synchronized(queue) { queue.peekFirst() } ?: return
            val r = sendMessage(msg)
            if (!r.ok) {
                lastResult = r.error()
                // Offline or rate-limited: stays queued, retried on the next tick. Refused for good:
                // dropped, or it would sit at the head of the queue and block every later alert.
                if (!r.permanent) return
                Log.w(TAG, "alert dropped: Telegram refused it (${r.code})")
            }
            synchronized(queue) { if (queue.peekFirst() === msg) queue.removeFirst() }
        }
    }

    private fun maybeDailyReport(latestLine: String, currentSession: Int) {
        // The latest report time that has passed: today's, or yesterday's while it is still before
        // today's. A report missed because the app was not logging at that time goes out on the
        // next logging session, once; today's still follows at its own time.
        val today = LocalDate.now().toEpochDay()
        val due = if (LocalTime.now().isBefore(LocalTime.of(prefs.reportHour, prefs.reportMinute))) today - 1 else today
        if (prefs.tgLastReportDay >= due) return
        val now = System.currentTimeMillis()
        if (now < retryAfterMs || now < quietUntilMs) return
        if (!runReport(force = false, latestLine = latestLine, currentSession = currentSession, day = due)) {
            retryAfterMs = System.currentTimeMillis() + 10 * 60_000L
        }
    }

    /** [day] is the report day (epoch day) this run settles when it succeeds. */
    private fun runReport(force: Boolean, latestLine: String, currentSession: Int, day: Long): Boolean {
        var sent = prefs.tgLastSentSession
        // First report (Telegram just switched on): start from now rather than upload every old log.
        if (sent == 0 && currentSession > 1) { sent = currentSession - 1; prefs.tgLastSentSession = sent }
        // Grouped by session (CSV first, then raw), so a session counts as sent only once all of its
        // files went: a failed CSV after a good raw log is retried instead of skipped for good.
        val sessions = SessionFiles.list(ctx).filter {
            val n = SessionFiles.numberOf(it)
            n > sent && n != currentSession && (force || it.length() >= MIN_BYTES)
        }.groupBy { SessionFiles.numberOf(it) }.toSortedMap()
        // Oldest first, a few per report; the rest stay unsent and go with the next report.
        val keep = sessions.keys.toList().take(MAX_SESSIONS)
        val left = sessions.size - keep.size
        val groups = keep.map { n -> n to sessions.getValue(n).sortedBy { if (it.name.startsWith("session_")) 0 else 1 } }
        val files = groups.flatMap { it.second }

        val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE dd MMM HH:mm", Locale.UK))
        if (files.isEmpty() && !force) {
            prefs.tgLastReportDay = day
            lastResult = "nothing new to send"
            return true
        }
        // The summary goes once per report: a retry after a failed upload carries on with the files.
        if (force || summarySentFor != day) {
            val r = sendMessage(Messages.dailyReport(date, files.size, files.sumOf { it.length() }, latestLine))
            if (!r.ok) {
                lastResult = r.error()
                return false
            }
            if (!force) summarySentFor = day
        }
        for ((n, group) in groups) {
            for (f in group) {
                val r = sendDocument(f, "${f.name} (${Messages.fmtBytes(f.length())})")
                if (!r.ok) {
                    lastResult = "${r.error()} (${f.name})"
                    return false   // tgLastSentSession still points before session n: all of it is retried
                }
            }
            prefs.tgLastSentSession = n
        }
        prefs.tgLastReportDay = day
        summarySentFor = NO_DAY
        lastResult = "sent ${files.size} file(s) at $date" + if (left > 0) ", $left older session(s) follow with the next report" else ""
        return true
    }

    private fun pollCommands(statusText: () -> String) {
        val token = prefs.tgToken
        val r = request(token, "getUpdates?offset=${prefs.tgUpdateOffset}&timeout=0&limit=5", null)
        if (!r.ok) return
        // Every update is confirmed, also those without a message (joins, edits of other kinds).
        val next = runCatching {
            val results = JSONObject(r.body).optJSONArray("result")
            var max = -1L
            if (results != null) for (i in 0 until results.length()) max = maxOf(max, results.getJSONObject(i).optLong("update_id", -1L))
            max + 1
        }.getOrElse { Log.w(TAG, "getUpdates parse failed", it); 0L }
        // Not if the token was changed meanwhile: the new bot starts from its own first update.
        if (next > 0 && prefs.tgToken == token) prefs.tgUpdateOffset = next

        val chats = TelegramLogic.findChats(r.body)
        seenChats.addAll(chats)
        while (seenChats.size > MAX_SEEN) seenChats.removeAt(0)
        // Only the configured chat is ever answered, and only for a command sent just now: one
        // queued while the phone was away would report readings nobody is waiting for any more.
        val mine = prefs.tgChatId.toLongOrNull() ?: return
        val nowS = System.currentTimeMillis() / 1000
        if (chats.any { it.id == mine && it.command == "/status" && !TelegramLogic.isStale(it.dateEpochS, nowS) }) {
            sendMessage(statusText())
        }
    }

    private fun sendMessage(text: String, token: String = prefs.tgToken, chatId: String = prefs.tgChatId): Reply {
        val form = "chat_id=" + URLEncoder.encode(chatId, "UTF-8") + "&text=" + URLEncoder.encode(text, "UTF-8")
        return request(token, "sendMessage", form)
    }

    /** One Bot API call. Never throws: no answer at all (offline, timeout) is code 0. */
    private fun request(token: String, method: String, form: String?): Reply = noteRateLimit(
        try {
            val c = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            if (form != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(form.toByteArray()) }
            }
            readReply(c)
        } catch (e: Exception) {
            Log.w(TAG, "${method.substringBefore('?')} failed: ${scrub(e, token)}")
            Reply(0, "")
        },
    )

    private fun readReply(c: HttpURLConnection): Reply {
        val code = c.responseCode
        val body = (if (code == 200) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return Reply(code, body)
    }

    // "Too many requests": hold the queue, the report and the poll for as long as Telegram asks.
    private fun noteRateLimit(r: Reply): Reply {
        if (r.code == 429) {
            val s = runCatching { JSONObject(r.body).optJSONObject("parameters")?.optLong("retry_after", 0L) ?: 0L }.getOrDefault(0L)
            quietUntilMs = System.currentTimeMillis() + s.coerceIn(5L, 3600L) * 1000L
        }
        return r
    }

    // Some network exceptions quote the request URL, which contains the bot token.
    private fun scrub(e: Exception, token: String): String {
        var msg = e.message ?: ""
        for (t in setOf(token, prefs.tgToken)) if (t.isNotEmpty()) msg = msg.replace(t, "<token>")
        return e.javaClass.simpleName + ": " + msg
    }

    private fun sendDocument(f: File, caption: String): Reply {
        val token = prefs.tgToken
        return noteRateLimit(
            try {
                val b = "----dpfsentinel7e3f9a"
                val c = URL("https://api.telegram.org/bot$token/sendDocument").openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 60_000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setChunkedStreamingMode(16 * 1024)
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
                DataOutputStream(c.outputStream).use { out ->
                    fun field(name: String, value: String) =
                        out.writeBytes("--$b\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                    field("chat_id", prefs.tgChatId)
                    out.write("--$b\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n$caption\r\n".toByteArray(Charsets.UTF_8))
                    out.writeBytes("--$b\r\nContent-Disposition: form-data; name=\"document\"; filename=\"${f.name}\"\r\n")
                    out.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
                    f.inputStream().use { it.copyTo(out) }
                    out.writeBytes("\r\n--$b--\r\n")
                }
                readReply(c)
            } catch (e: Exception) {
                Log.w(TAG, "sendDocument failed: ${scrub(e, token)}")
                Reply(0, "")
            },
        )
    }

    companion object {
        private const val TAG = "Telegram"
        private const val MAX_QUEUE = 10
        private const val MAX_SESSIONS = 5   // CSV + raw each, so up to 10 files as before
        private const val MAX_SEEN = 20
        private const val MIN_BYTES = 3000L   // skip blank / no-adapter sessions
        private const val POLL_MS = 60_000L
        private const val NO_DAY = Long.MIN_VALUE

        const val NOT_SET = "Set a bot token and a recipient ID first"
        const val BAD_CHAT = "That doesn't look like a recipient ID. Tap Find my chat, or type the number (groups start with a minus sign)"
        const val BOT_ID = "That is the bot's own ID (the number at the start of the token). Use your own user ID: tap Find my chat"

        /** Why a bot token as typed cannot be right, or null when its shape is fine. */
        fun tokenProblem(token: String): String? {
            val t = token.trim()
            return when {
                t.isEmpty() -> "Paste the bot token from @BotFather first"
                t.startsWith("bot", ignoreCase = true) -> "Paste the token without \"bot\" in front: it starts with digits"
                !t.contains(':') -> "The token must look like 12345678:AAbb... (bot id, colon, secret)"
                else -> null
            }
        }

        /** "Name (private)" for the settings screen; the id stands in for a missing name. */
        fun label(c: ChatCandidate): String = "${c.name.ifEmpty { c.id.toString() }} (${c.type.ifEmpty { "chat" }})"
    }
}
