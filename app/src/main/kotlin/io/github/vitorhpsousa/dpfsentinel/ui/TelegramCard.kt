package io.github.vitorhpsousa.dpfsentinel.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.vitorhpsousa.dpfsentinel.DpfApp
import io.github.vitorhpsousa.dpfsentinel.Prefs
import io.github.vitorhpsousa.dpfsentinel.Snapshot
import io.github.vitorhpsousa.dpfsentinel.Telegram
import io.github.vitorhpsousa.dpfsentinel.core.Messages
import io.github.vitorhpsousa.dpfsentinel.core.TelegramLogic
import io.github.vitorhpsousa.dpfsentinel.core.Units

// The Telegram part of the settings: token, recipient, report time. "Find my chat" and "Send test"
// work on what is typed, so the set-up can be finished without leaving the screen or saving first.
// Placed inside SettingsTab's column.
@Composable
fun TelegramCard(s: Snapshot, units: Units) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val telegram = (ctx.applicationContext as DpfApp).telegram
    var token by remember { mutableStateOf(prefs.tgToken) }
    var chat by remember { mutableStateOf(prefs.tgChatId) }
    var reportTime by remember { mutableStateOf("%02d:%02d".format(prefs.reportHour, prefs.reportMinute)) }
    var tgResult by remember { mutableStateOf(telegram.lastResult) }
    var startMsg by remember { mutableStateOf(prefs.tgStartNotice) }
    var found by remember { mutableStateOf("") }
    var choices by remember { mutableStateOf<List<TelegramLogic.ChatCandidate>>(emptyList()) }

    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_LONG).show()
    fun onMain(f: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(f)

    fun save() {
        val hm = Regex("""^(\d{1,2}):(\d{2})$""").find(reportTime.trim())
        val hour = hm?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val minute = hm?.groupValues?.get(2)?.toIntOrNull() ?: -1
        if (hour !in 0..23 || minute !in 0..59) { toast("The report time must look like 17:30 (24-hour clock)"); return }
        val t = token.trim()
        var id = ""
        // Both empty switches Telegram off; anything else has to be a usable pair.
        if (t.isNotEmpty() || chat.isNotBlank()) {
            Telegram.tokenProblem(t)?.let { toast(it); return }
            id = TelegramLogic.normaliseChatId(chat) ?: run { toast(Telegram.BAD_CHAT); return }
            if (TelegramLogic.looksLikeBotIdFromToken(id, t)) { toast(Telegram.BOT_ID); return }
        }
        prefs.tgToken = t
        prefs.tgChatId = id
        prefs.reportHour = hour
        prefs.reportMinute = minute
        token = t
        chat = id
        toast("Saved")
    }

    Text("Telegram", fontWeight = FontWeight.Bold, fontSize = 20.sp)
    Text(
        "A message each time the app connects to the car, regen start/finish and health alerts, a daily report with the new logs, and /status replies. " +
            "Create a bot with @BotFather in Telegram, paste its token here, send your bot any message, then tap Find my chat.",
        color = Muted, fontSize = 12.sp,
    )
    OutlinedTextField(token, { token = it }, label = { Text("Bot token (12345678:AA...)") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(chat, { chat = it; found = ""; choices = emptyList() },
        label = { Text("Recipient ID (your Telegram user ID)") },
        supportingText = { Text("For a private chat this is your own user ID. Groups start with a minus sign.") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
    OutlinedButton(onClick = {
        found = "Looking…"
        choices = emptyList()
        telegram.findChats(token, chat) { list, msg ->
            onMain {
                found = msg
                if (list.size == 1) chat = list[0].id.toString() else choices = list
            }
        }
    }) { Text("Find my chat") }
    if (found.isNotEmpty()) Text(found, fontSize = 12.sp)
    choices.forEach { c ->
        TextButton(onClick = { chat = c.id.toString(); found = "Found: ${Telegram.label(c)}"; choices = emptyList() }) {
            Text("${Telegram.label(c)} · ${c.id}")
        }
    }
    OutlinedTextField(reportTime, { reportTime = it }, label = { Text("Daily report time (HH:MM)") }, singleLine = true,
        modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Message me when logging starts", Modifier.weight(1f))
        Switch(startMsg, { startMsg = it; prefs.tgStartNotice = it })
    }
    Text("A message with the first readings each time the app connects to the adapter and the car answers, so you can see it is working without opening the app.", color = Muted, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { save() }) { Text("Save") }
        OutlinedButton(onClick = {
            tgResult = "sending…"
            telegram.test(token, chat) { r ->
                onMain {
                    tgResult = r
                    // Sent means saved: show the recipient as it was stored (spaces and the like removed).
                    if (prefs.tgToken == token.trim()) TelegramLogic.normaliseChatId(chat)?.let { if (it == prefs.tgChatId) chat = it }
                }
            }
        }) { Text("Send test") }
        OutlinedButton(onClick = {
            telegram.sendReportNow(Messages.latestLine(s.row, units), if (s.running) s.session else -1) { r -> onMain { tgResult = r } }
        }) { Text("Report now") }
    }
    Text("Last result: $tgResult", color = Muted, fontSize = 12.sp)
}
