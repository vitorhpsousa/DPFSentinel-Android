package io.github.vitorhpsousa.dpfsentinel.core

// The parts of the Google Drive back-up that need no phone: which log files still have to go, in
// what order, and the texts shown for a refusal. The upload itself is in the app (DriveUpload.kt).
object DriveLogic {
    /** The folder made in the user's Drive. The app can only see what it put there itself. */
    const val FOLDER = "DPF Sentinel"

    /** Sessions sent per pass, so a long backlog can't hold the uploader for minutes. */
    const val MAX_SESSIONS = 6

    data class LogFile(val name: String, val session: Int, val bytes: Long)

    /**
     * What to upload now: sessions newer than [lastDoneSession], oldest first, the CSV before the
     * raw log. Left out: the session still being written ([currentSession], -1 for none), empty
     * files, and files of a half-sent session that already went ([doneNames]).
     * A session listed with no files left only needs marking as done.
     */
    fun plan(files: List<LogFile>, lastDoneSession: Int, doneNames: Set<String>, currentSession: Int): List<Pair<Int, List<LogFile>>> =
        files.filter { it.session > lastDoneSession && it.session != currentSession && it.session >= 0 }
            .groupBy { it.session }
            .toSortedMap()
            .entries.take(MAX_SESSIONS)
            .map { (n, group) ->
                n to group.filter { it.bytes > 0L && it.name !in doneNames }
                    .sortedWith(compareBy<LogFile> { if (it.name.startsWith("session_")) 0 else 1 }.thenBy { it.name })
            }

    /** Drive's search text for a folder of this name (quotes and backslashes escaped). */
    fun folderQuery(name: String = FOLDER): String {
        val safe = name.replace("\\", "\\\\").replace("'", "\\'")
        return "name = '$safe' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
    }

    /** Drive's search text for a file of this name inside one folder. */
    fun fileQuery(name: String, folderId: String): String {
        val safe = name.replace("\\", "\\\\").replace("'", "\\'")
        return "name = '$safe' and '$folderId' in parents and trashed = false"
    }

    fun mime(name: String): String = if (name.endsWith(".csv")) "text/csv" else "text/plain"

    /** What to tell the user for an HTTP status from Drive (0 = no answer at all). */
    fun describe(code: Int): String = when (code) {
        0 -> "no connection to Google Drive, will try again"
        401 -> "Google sign-in has expired: tap Reconnect"
        403 -> "Google Drive refused the upload (storage full, access removed, or too many requests)"
        404 -> "the Drive folder is gone, it will be made again"
        429 -> "Google Drive is busy, will try again"
        in 500..599 -> "Google Drive had a problem ($code), will try again"
        else -> "Google Drive answered $code"
    }
}
