package io.github.vitorhpsousa.dpfsentinel.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.identity.Identity
import io.github.vitorhpsousa.dpfsentinel.DpfApp
import io.github.vitorhpsousa.dpfsentinel.DriveUpload
import io.github.vitorhpsousa.dpfsentinel.Prefs

// The Google Drive part of the settings: connect once, then every finished drive is copied to a
// "DPF Sentinel" folder in the user's own Drive. Placed inside SettingsTab's column.
@Composable
fun DriveCard() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val drive = (ctx.applicationContext as DpfApp).drive
    var on by remember { mutableStateOf(prefs.driveOn) }
    var wifiOnly by remember { mutableStateOf(prefs.driveWifiOnly) }
    val result by drive.result.collectAsState()

    fun connected() {
        drive.connected()
        on = true
        drive.sync()
    }

    // Google's own screen, where the user picks the account and allows access.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) { drive.note(if (on) "not confirmed (cancelled)" else "not connected (cancelled)"); return@rememberLauncherForActivityResult }
        runCatching { Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(r.data) }
            .onSuccess { if (it.accessToken.isNullOrEmpty()) drive.note("Google gave no access") else connected() }
            .onFailure { drive.note("Google sign-in failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    // Asks Google for access: straight through when it was already given, else via Google's screen.
    fun connect() {
        drive.note("asking Google…")
        Identity.getAuthorizationClient(ctx).authorize(DriveUpload.request())
            .addOnSuccessListener { a ->
                val pending = a.pendingIntent
                if (a.hasResolution() && pending != null) {
                    runCatching { consent.launch(IntentSenderRequest.Builder(pending.intentSender).build()) }
                        .onFailure { drive.note("couldn't open Google sign-in") }
                } else if (!a.accessToken.isNullOrEmpty()) connected()
                else drive.note("Google gave no access")
            }
            .addOnFailureListener { drive.note("Google sign-in failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    Text("Google Drive", fontWeight = FontWeight.Bold, fontSize = 20.sp)
    Text(
        "Copies each finished drive's log files to a \"DPF Sentinel\" folder in your own Google Drive. " +
            "The app can only see the files it puts there, nothing else in your Drive, and nothing is sent to the developer.",
        color = Muted, fontSize = 12.sp,
    )
    if (!on) {
        Button(onClick = { connect() }) { Text("Connect Google Drive") }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Only upload on Wi-Fi", Modifier.weight(1f))
            Switch(wifiOnly, { wifiOnly = it; prefs.driveWifiOnly = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { drive.note("uploading…"); drive.sync() }) { Text("Upload now") }
            OutlinedButton(onClick = { drive.disconnect(); on = false }) { Text("Disconnect") }
            // For when Google asks to confirm access again (it was removed in the Google Account, or lapsed).
            TextButton(onClick = { connect() }) { Text("Reconnect") }
        }
        Text(
            "Disconnect stops the uploads; files already in Drive stay there. To withdraw the permission itself, " +
                "remove DPF Sentinel under \"Third-party apps\" in your Google Account.",
            color = Muted, fontSize = 12.sp,
        )
    }
    Text("Last result: $result", color = Muted, fontSize = 12.sp)
}
