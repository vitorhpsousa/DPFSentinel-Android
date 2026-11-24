package io.github.vitorhpsousa.dpfsentinel.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.vitorhpsousa.dpfsentinel.DpfApp
import io.github.vitorhpsousa.dpfsentinel.Prefs
import io.github.vitorhpsousa.dpfsentinel.core.RegenSpeech
import kotlinx.coroutines.delay

// Spoken regen alerts: the switch and a test button. Placed inside SettingsTab's column.
@Composable
fun VoiceCard() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val speaker = (ctx.applicationContext as DpfApp).speaker
    var on by remember { mutableStateOf(prefs.speakAlerts) }
    var problem by remember { mutableStateOf("") }
    var tested by remember { mutableStateOf(0) }
    // The voice engine reports a problem a moment after the first try.
    LaunchedEffect(tested) { if (tested > 0) { delay(2500); problem = speaker.problem } }

    Text("Spoken alerts", fontWeight = FontWeight.Bold, fontSize = 20.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Say filter cleaning alerts out loud", Modifier.weight(1f))
        Switch(on, { on = it; prefs.speakAlerts = it })
    }
    Text(
        "Started, halfway, nearly done and finished, in a voice like the sat-nav's. It plays where the phone's sound goes: " +
            "through the car's speakers when the phone is on Android Auto, or on Bluetooth with the car set to Bluetooth audio. Music pauses while it talks. " +
            "The volume is the phone's media or navigation volume.",
        color = Muted, fontSize = 12.sp,
    )
    OutlinedButton(onClick = { problem = ""; speaker.say(RegenSpeech.TEST); tested++ }) { Text("Test voice") }
    if (problem.isNotEmpty()) Text(problem, color = Amber, fontSize = 12.sp)
}
