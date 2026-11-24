package io.github.vitorhpsousa.dpfsentinel.core

/**
 * The parts of the Telegram set-up that need no network or Android: tidying what the user typed,
 * reading Telegram's answers, and turning its errors into advice. The app's Telegram.kt does the
 * HTTP and hands the status code and body here.
 */
object TelegramLogic {

    /** One update that names a chat the bot could report to. See [findChats]. */
    data class ChatCandidate(
        /** Chat id as Telegram gives it: positive for a person, negative for groups, -100... for supergroups and channels. */
        val id: Long,
        /** "private", "group", "supergroup" or "channel"; "" if absent. */
        val type: String,
        /** Group/channel title, else the person's first (and last) name, else @username, else "". */
        val name: String,
        val updateId: Long,
        /** When the message was sent, seconds since 1970; 0 if absent. */
        val dateEpochS: Long,
        /** Message text, "" for photos, joins and the like. */
        val text: String,
    ) {
        /** The bot command in [text], lower-cased and without "@BotName": "/status@MyBot now" -> "/status". "" if the text is not a command. */
        val command: String get() = commandOf(text)
    }

    /**
     * A bot command at the start of a message: "/status", "/status@MyBot" and "/Status extra words"
     * all give "/status". Groups append "@BotName" when several bots are present. "" when the text
     * does not start with "/" followed by a letter, digit or underscore.
     */
    fun commandOf(text: String): String {
        val first = text.trimStart().takeWhile { !it.isWhitespace() }
        if (first.length < 2 || first[0] != '/') return ""
        val name = first.substring(1).substringBefore('@')
        if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' }) return ""
        return "/" + name.lowercase()
    }

    // Spaces of every kind, zero-width and direction marks: what copy-and-paste from a chat app drags in.
    private fun invisible(c: Char) = c.isWhitespace() || c == ' ' || c == ' ' || c == ' ' ||
        c in '​'..'‏' || c in '‪'..'‮' || c in '⁠'..'⁤' || c == '﻿'

    /**
     * Cleans a chat id as typed or pasted in the settings screen.
     *
     * Removes spaces (including non-breaking), zero-width and direction characters, thousands
     * separators (, . ' _) between digits and one leading '+'; a typographic minus becomes '-'.
     * Returns the id as Telegram wants it: digits with an optional '-' (no leading zero), or
     * "@channelname" (5-32 letters, digits, underscores, starting with a letter). Returns null
     * when what is left is neither, so the caller can say "that doesn't look like a chat id".
     */
    fun normaliseChatId(input: String?): String? {
        if (input == null) return null
        var s = input.filterNot { invisible(it) }.replace('−', '-').replace('–', '-').replace('—', '-')
        if (s.startsWith("@")) return if (Regex("@[A-Za-z][A-Za-z0-9_]{4,31}").matches(s)) s else null
        if (s.startsWith("+")) s = s.substring(1)
        s = s.filterNot { it == ',' || it == '.' || it == '\'' || it == '_' }
        return if (Regex("-?[1-9][0-9]{0,18}").matches(s) && s.toLongOrNull() != null) s else null
    }

    /**
     * True when the chat id is the bot's own id: the digits before ':' in the token. A common
     * slip (the bot's id is the first number people see); Telegram answers it with "chat not
     * found". Both arguments may be raw user input.
     */
    fun looksLikeBotIdFromToken(chatId: String?, token: String?): Boolean {
        val id = normaliseChatId(chatId) ?: return false
        val botId = token.orEmpty().filterNot { invisible(it) }.substringBefore(':', "")
        return botId.isNotEmpty() && botId.all { it in '0'..'9' } && botId == id
    }

    /**
     * Plain advice for a failed Bot API call.
     *
     * @param httpCode the HTTP status; 0 or negative when the request never got an answer
     *   (no network, timeout, DNS).
     * @param body the response body, normally `{"ok":false,"error_code":..,"description":"..",
     *   "parameters":{"retry_after":N}}`; may be null, empty or not JSON.
     * @return one sentence to show the user. Known causes get an instruction; anything else gets
     *   Telegram's own description, or "Telegram error <code>" when there is none.
     */
    const val SHARED_BOT = "Another device is using this bot token (the DPF board?). Make a second bot in @BotFather and give the phone its own token"

    fun describeError(httpCode: Int, body: String?): String {
        if (httpCode <= 0) return "No internet connection"
        val root = MiniJson.parse(body) as? Map<*, *>
        val desc = (root?.get("description") as? String).orEmpty().trim()
        val d = desc.lowercase()
        return when {
            // A token of the wrong shape gets 404 "Not Found"; a well-formed wrong one gets 401.
            httpCode == 401 || (httpCode == 404 && (d.isEmpty() || d == "not found")) ->
                "That bot token isn't valid — copy it again from @BotFather"
            httpCode == 403 && "blocked" in d -> "You blocked the bot — unblock it in Telegram"
            (httpCode == 400 && "chat not found" in d) || (httpCode == 403 && "initiate conversation" in d) ->
                "Open your bot in Telegram and press Start, then try again"
            httpCode == 409 -> SHARED_BOT
            httpCode == 429 -> {
                val wait = ((root?.get("parameters") as? Map<*, *>)?.get("retry_after") as? MiniJson.Num)?.long
                    ?: Regex("retry after (\\d+)").find(d)?.groupValues?.get(1)?.toLongOrNull()
                if (wait != null) "Telegram is rate-limiting, try again in $wait s" else "Telegram is rate-limiting, try again in a minute"
            }
            desc.isNotEmpty() -> desc
            else -> "Telegram error $httpCode"
        }
    }

    /**
     * The chats seen in a `getUpdates` response, one entry per update that carries a message,
     * oldest first (newest last), so `lastOrNull()` is the chat that wrote most recently.
     *
     * Reads `message`, `edited_message`, `channel_post` and `edited_channel_post`. Ids are taken as
     * whole numbers of any size Telegram uses (up to 52 bits, negative for groups). The body is
     * parsed as JSON, so text or names that contain `"id":` cannot be mistaken for fields.
     * Returns an empty list for `{"ok":false,..}`, an empty result or anything unreadable.
     *
     * To offer the user "is this your chat?", take [latestPerChat] of the result and ignore
     * entries for which [isStale] is true.
     */
    fun findChats(getUpdatesBody: String?): List<ChatCandidate> {
        val root = MiniJson.parse(getUpdatesBody) as? Map<*, *> ?: return emptyList()
        val result = root["result"] as? List<*> ?: return emptyList()
        val out = ArrayList<ChatCandidate>()
        for (u in result) {
            val upd = u as? Map<*, *> ?: continue
            val msg = listOf("message", "edited_message", "channel_post", "edited_channel_post")
                .firstNotNullOfOrNull { upd[it] as? Map<*, *> } ?: continue
            val chat = msg["chat"] as? Map<*, *> ?: continue
            val id = (chat["id"] as? MiniJson.Num)?.long ?: continue
            fun str(k: String) = (chat[k] as? String).orEmpty().trim()
            val person = listOf(str("first_name"), str("last_name")).filter { it.isNotEmpty() }.joinToString(" ")
            val name = str("title").ifEmpty { person }.ifEmpty { str("username").let { if (it.isEmpty()) "" else "@$it" } }
            out.add(
                ChatCandidate(
                    id, str("type"), name,
                    (upd["update_id"] as? MiniJson.Num)?.long ?: 0L,
                    (msg["date"] as? MiniJson.Num)?.long ?: 0L,
                    (msg["text"] as? String) ?: (msg["caption"] as? String).orEmpty(),
                ),
            )
        }
        return out.sortedBy { it.updateId }
    }

    /** One entry per chat id, each chat's most recent update, still newest last. */
    fun latestPerChat(chats: List<ChatCandidate>): List<ChatCandidate> =
        chats.groupBy { it.id }.values.map { it.last() }.sortedBy { it.updateId }

    /**
     * True when a message is too old to act on: sent more than [maxAgeS] seconds before now.
     * Use it to ignore commands queued while the phone was offline and, in the "find my chat"
     * helper, to offer only a chat that wrote in the last couple of minutes. A date of 0
     * (missing) is stale; a date slightly in the future (clock skew) is not.
     */
    fun isStale(dateEpochS: Long, nowEpochS: Long, maxAgeS: Long = 120): Boolean = nowEpochS - dateEpochS > maxAgeS
}

// Just enough JSON to read Bot API answers: objects -> Map, arrays -> List, strings -> String,
// numbers -> Num (text kept, so 52-bit ids are exact), true/false -> Boolean, null -> null.
// parse() returns null for anything that is not one complete JSON value.
internal object MiniJson {
    class Num(val raw: String) {
        val long: Long? get() = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 && kotlin.math.abs(it) < 9.0e15 }?.toLong()
    }

    private class Bad : RuntimeException()

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(depth: Int): Any? {
            if (depth > 64) throw Bad()
            ws()
            if (i >= s.length) throw Bad()
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw Bad()
            }
        }
        fun lit(word: String, v: Any?): Any? { if (!s.startsWith(word, i)) throw Bad(); i += word.length; return v }
        fun num(): Num {
            val a = i
            while (i < s.length && (s[i] in '0'..'9' || s[i] in "+-.eE")) i++
            return Num(s.substring(a, i))
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw Bad()
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') { sb.append(c); continue }
                if (i >= s.length) throw Bad()
                when (val e = s[i++]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'u' -> {
                        if (i + 4 > s.length) throw Bad()
                        sb.append((s.substring(i, i + 4).toIntOrNull(16) ?: throw Bad()).toChar()); i += 4
                    }
                    else -> sb.append(e)   // \" \\ \/
                }
            }
        }
        fun obj(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw Bad()
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw Bad()
                i++
                m[k] = value(depth + 1)
                ws()
                if (i >= s.length) throw Bad()
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> throw Bad() }
            }
        }
        fun arr(depth: Int): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l.add(value(depth + 1))
                ws()
                if (i >= s.length) throw Bad()
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> throw Bad() }
            }
        }
    }

    fun parse(text: String?): Any? {
        if (text.isNullOrBlank()) return null
        return try {
            val p = P(text)
            val v = p.value(0)
            p.ws()
            if (p.i != text.length) null else v
        } catch (e: Bad) {
            null
        }
    }
}
