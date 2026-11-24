package io.github.vitorhpsousa.dpfsentinel

import android.content.Context
import android.content.SharedPreferences
import io.github.vitorhpsousa.dpfsentinel.core.Units

// Settings and small persistent counters (the phone's equivalent of the firmware's NVS keys).
class Prefs(context: Context) {
    private val p: SharedPreferences = context.applicationContext.getSharedPreferences("dpf", Context.MODE_PRIVATE)

    var adapterAddress: String
        get() = p.getString("adapter", "") ?: ""
        set(v) = p.edit().putString("adapter", v).apply()

    var adapterName: String
        get() = p.getString("adapterName", "") ?: ""
        set(v) = p.edit().putString("adapterName", v).apply()

    /** A different token is a different bot: its updates are numbered separately, so the /status poll starts over. */
    var tgToken: String
        get() = p.getString("tgToken", "") ?: ""
        set(v) {
            val t = v.trim()
            val e = p.edit().putString("tgToken", t)
            if (t != tgToken) e.putLong("tgOffset", 0)
            e.apply()
        }

    var tgChatId: String
        get() = p.getString("tgChat", "") ?: ""
        set(v) = p.edit().putString("tgChat", v.trim()).apply()

    val telegramEnabled get() = tgToken.contains(':') && tgChatId.isNotEmpty()

    /** Daily report time (local), like TG_SEND_HOUR/TG_SEND_MIN. */
    var reportHour: Int
        get() = p.getInt("tgHour", 17)
        set(v) = p.edit().putInt("tgHour", v).apply()

    var reportMinute: Int
        get() = p.getInt("tgMin", 30)
        set(v) = p.edit().putInt("tgMin", v).apply()

    var tgLastReportDay: Long
        get() = p.getLong("tgDay", 0)
        set(v) = p.edit().putLong("tgDay", v).apply()

    var tgLastSentSession: Int
        get() = p.getInt("tgBoot", 0)
        set(v) = p.edit().putInt("tgBoot", v).apply()

    /** Send a Telegram message each time the app connects to the adapter and the car answers (on unless switched off). */
    var tgStartNotice: Boolean
        get() = p.getBoolean("tgStartMsg", true)
        set(v) = p.edit().putBoolean("tgStartMsg", v).apply()

    /** Wall-clock ms of the last "logging started" message, so a link that drops and returns doesn't repeat it. */
    var tgLastStartNotice: Long
        get() = p.getLong("tgStartMsgAt", 0)
        set(v) = p.edit().putLong("tgStartMsgAt", v).apply()

    /** Alerts not delivered yet (JSON array), so they survive the app being closed while offline. */
    var tgQueue: String
        get() = p.getString("tgQueue", "") ?: ""
        set(v) = p.edit().putString("tgQueue", v).apply()

    var tgUpdateOffset: Long
        get() = p.getLong("tgOffset", 0)
        set(v) = p.edit().putLong("tgOffset", v).apply()

    /** Dash minus ECU mileage, applied only to what is shown (docs/odometer-offset.md). */
    var odometerOffsetMi: Float
        get() = p.getFloat("odoOff", 0f)
        set(v) = p.edit().putFloat("odoOff", v).apply()

    /** How speed, distance and temperature are shown (never changes the logs). */
    var units: Units
        get() = Units.fromName(p.getString("units", null))
        set(v) = p.edit().putString("units", v.name).apply()

    /** Start logging when Android Auto opens the app. */
    var autoStartWithCar: Boolean
        get() = p.getBoolean("autoCar", true)
        set(v) = p.edit().putBoolean("autoCar", v).apply()

    /** Master switch for every automatic start (adapter in range, car Bluetooth, Android Auto). */
    var autoLog: Boolean
        get() = p.getBoolean("autoLog", true)
        set(v) = p.edit().putBoolean("autoLog", v).apply()

    /** Companion-device association with the OBD adapter (AutoSetup); -1 / "" when there is none. */
    var assocId: Int
        get() = p.getInt("assocId", -1)
        set(v) = p.edit().putInt("assocId", v).apply()

    var assocMac: String
        get() = p.getString("assocMac", "") ?: ""
        set(v) = p.edit().putString("assocMac", v).apply()

    /** The car's own Bluetooth (head unit); connecting to it starts logging. "" = none. */
    var carBtAddress: String
        get() = p.getString("carBt", "") ?: ""
        set(v) = p.edit().putString("carBt", v).apply()

    var carBtName: String
        get() = p.getString("carBtName", "") ?: ""
        set(v) = p.edit().putString("carBtName", v).apply()

    /** The soot zone last alerted (0 none, 1 rising, 2 high), so each drive doesn't repeat it. */
    var sootZone: Int
        get() = p.getInt("sootZone", 0)
        set(v) = p.edit().putInt("sootZone", v).apply()

    /** Say regen alerts out loud (through the car's speakers when the phone plays through them). */
    var speakAlerts: Boolean
        get() = p.getBoolean("speak", true)
        set(v) = p.edit().putBoolean("speak", v).apply()

    /** Stop tapped by hand (wall-clock ms, 0 = not since the last start): no restart by itself for a while. */
    var stoppedAt: Long
        get() = p.getLong("stoppedAt", 0)
        set(v) = p.edit().putLong("stoppedAt", v).apply()

    /** Sessions in a row that never heard from the car, and when the last one ended (see TriggerBackoff). */
    var emptySessions: Int
        get() = p.getInt("emptySessions", 0)
        set(v) = p.edit().putInt("emptySessions", v).apply()

    var emptyEndedAt: Long
        get() = p.getLong("emptyEndedAt", 0)
        set(v) = p.edit().putLong("emptyEndedAt", v).apply()

    /** Last automatic start: wall-clock ms (0 = never) and "ok" or why it failed. */
    var autoLastTime: Long
        get() = p.getLong("autoLastTime", 0)
        set(v) = p.edit().putLong("autoLastTime", v).apply()

    var autoLastResult: String
        get() = p.getString("autoLastResult", "") ?: ""
        set(v) = p.edit().putString("autoLastResult", v).apply()

    /** The last row the car answered, and when (wall-clock ms): shown, marked as old, while not connected. */
    var lastRow: String
        get() = p.getString("lastRow", "") ?: ""
        set(v) = p.edit().putString("lastRow", v).apply()

    /** The next-regen text that went with the last reading ("~42 mi"), "" = none yet. */
    var lastEstimate: String
        get() = p.getString("lastEst", "") ?: ""
        set(v) = p.edit().putString("lastEst", v).apply()

    var lastRowAt: Long
        get() = p.getLong("lastRowAt", 0)
        set(v) = p.edit().putLong("lastRowAt", v).apply()

    /** Look for the car every day for 15 minutes from this time (off unless switched on). */
    var dailyOn: Boolean
        get() = p.getBoolean("dailyOn", false)
        set(v) = p.edit().putBoolean("dailyOn", v).apply()

    var dailyHour: Int
        get() = p.getInt("dailyHour", 8)
        set(v) = p.edit().putInt("dailyHour", v).apply()

    var dailyMinute: Int
        get() = p.getInt("dailyMin", 20)
        set(v) = p.edit().putInt("dailyMin", v).apply()

    /** Write latitude/longitude to the logs (speed, altitude and grade are logged regardless). Off unless chosen. */
    var logPosition: Boolean
        get() = p.getBoolean("logPos", false)
        set(v) = p.edit().putBoolean("logPos", v).apply()

    /**
     * Never reuses a number: also above any log file already on the phone, and saved with commit()
     * so a kill right after starting can't lose the increment (apply() writes later).
     */
    fun nextSessionNumber(highestOnDisk: Int = 0): Int {
        val n = maxOf(p.getInt("session", 0), highestOnDisk) + 1
        p.edit().putInt("session", n).commit()
        return n
    }

    val lastSessionNumber get() = p.getInt("session", 0)

    var health: String
        get() = p.getString("health", "") ?: ""
        set(v) = p.edit().putString("health", v).apply()
}
