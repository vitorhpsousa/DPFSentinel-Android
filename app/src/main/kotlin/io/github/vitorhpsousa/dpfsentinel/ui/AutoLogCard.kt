package io.github.vitorhpsousa.dpfsentinel.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.vitorhpsousa.dpfsentinel.AutoSetup
import io.github.vitorhpsousa.dpfsentinel.DailyWindow
import io.github.vitorhpsousa.dpfsentinel.core.DailyWindowLogic
import io.github.vitorhpsousa.dpfsentinel.Prefs
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

// What the "Automatic logging" card shows, re-read once a second so it follows the system settings.
private class AutoLogState(context: Context, prefs: Prefs) {
    private fun granted(context: Context, p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    val nearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        granted(context, Manifest.permission.BLUETOOTH_CONNECT) && granted(context, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val paired = prefs.assocMac.isNotEmpty()
    val adapter = prefs.adapterName.ifEmpty { prefs.assocMac }
    val car = prefs.carBtName.ifEmpty { prefs.carBtAddress }
    val unrestricted = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    val enabled = prefs.autoLog
    val last = if (prefs.autoLastTime == 0L) "" else
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(prefs.autoLastTime)) + " — " + prefs.autoLastResult
}

/** Paired Bluetooth devices as (address, name): the car's head unit is one of them. */
@SuppressLint("MissingPermission")   // a missing permission just gives an empty list
private fun bondedDevices(context: Context, except: String): List<Pair<String, String>> = try {
    context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
        .filter { !it.address.equals(except, ignoreCase = true) }
        .map { Pair(it.address, it.name ?: it.address) }
        .sortedBy { it.second.lowercase() }
} catch (e: SecurityException) {
    emptyList()
}

@Composable
private fun Check(ok: Boolean, text: String) {
    Row {
        Text(if (ok) "✓ " else "✗ ", color = if (ok) Green else Amber, fontWeight = FontWeight.Bold)
        Text(text)
    }
}

@Composable
fun AutoLogCard() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val st = remember(tick) { AutoLogState(ctx, prefs) }
    var message by remember { mutableStateOf("") }
    var pickCar by remember { mutableStateOf(false) }
    var dailyOn by remember { mutableStateOf(prefs.dailyOn) }
    var dailyTime by remember { mutableStateOf("%02d:%02d".format(prefs.dailyHour, prefs.dailyMinute)) }

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        message = if (result.resultCode == Activity.RESULT_OK) AutoSetup.onChooserResult(ctx, result.data) else "No adapter chosen"
        tick++
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Automatic logging", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Switch(st.enabled, { prefs.autoLog = it; DailyWindow.schedule(ctx); tick++ })
            }
            Check(st.nearby, "Nearby devices allowed")
            Check(st.notifications, "Notifications allowed")
            Check(st.paired, if (st.paired) "Adapter linked to Android (${st.adapter})" else "Adapter not linked to Android yet")
            if (!st.paired && AutoSetup.supported(ctx)) {
                Text(
                    "The app already finds the adapter while it is open. Linking it is one extra step, done once, so Android wakes the app " +
                        "when the adapter is near and the phone is in your pocket: sit in the car with the ignition on, tap the button below, " +
                        "and pick the adapter in the list Android shows.",
                    color = Muted, fontSize = 12.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Check(st.car.isNotEmpty(), "Car Bluetooth: ${st.car.ifEmpty { "None" }}") }
                TextButton(onClick = { pickCar = true }) { Text("Choose") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Check(st.unrestricted, "Battery: " + if (st.unrestricted) "Unrestricted" else "restricted") }
                TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }) { Text("Battery settings") }
            }
            if (!st.unrestricted) {
                Text("In the list, find DPF Sentinel and choose Unrestricted (\"Don't optimise\").", color = Muted, fontSize = 12.sp)
            }

            if (!AutoSetup.supported(ctx)) {
                Text("This phone can't pair the adapter with Android. Choose the car's Bluetooth instead.", color = Muted, fontSize = 12.sp)
            } else if (!st.paired) {
                Button(onClick = {
                    message = "Looking for the adapter: plug it in and turn the ignition on…"
                    AutoSetup.associate(
                        ctx,
                        onChooser = { sender ->
                            message = ""
                            runCatching { chooser.launch(IntentSenderRequest.Builder(sender).build()) }
                                .onFailure { message = "Couldn't open Android's device list" }
                        },
                        onError = { message = it },
                    )
                }) { Text("Link the adapter now") }
            } else {
                OutlinedButton(onClick = { AutoSetup.remove(ctx); message = "Removed"; tick++ }) { Text("Remove") }
            }
            if (!AutoSetup.canObserve) {
                Text(
                    "On Android 11 and older the adapter is paired but Android can't watch for it: logging starts with the car's Bluetooth or Android Auto.",
                    color = Muted, fontSize = 12.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Also look for the car every day at", Modifier.weight(1f))
                OutlinedTextField(
                    dailyTime,
                    { t ->
                        dailyTime = t
                        DailyWindowLogic.parse(t)?.let { (h, m) -> prefs.dailyHour = h; prefs.dailyMinute = m; DailyWindow.schedule(ctx) }
                    },
                    singleLine = true, isError = DailyWindowLogic.parse(dailyTime) == null,
                    modifier = Modifier.width(92.dp),
                )
                Switch(dailyOn, { dailyOn = it; prefs.dailyOn = it; DailyWindow.schedule(ctx) })
            }
            Text(
                "For 15 minutes from that time (24-hour clock, e.g. 08:20) the app tries to reach the adapter, for a drive that starts at " +
                    "the same time every day. Needs Battery: Unrestricted, or the adapter linked.",
                color = Muted, fontSize = 12.sp,
            )
            if (message.isNotEmpty()) Text(message, color = Amber, fontSize = 12.sp)
            if (st.last.isNotEmpty()) Text("Last automatic start: ${st.last}", color = Muted, fontSize = 12.sp)
        }
    }
    Text(
        "Logging starts when you open the app, when the car's Bluetooth connects, when the linked adapter comes near, and at the daily time if set. " +
            "GPS is only added when you open the app.",
        color = Muted, fontSize = 12.sp,
    )

    if (pickCar) {
        val devices = remember { bondedDevices(ctx, prefs.assocMac) }
        fun choose(address: String, name: String) {
            prefs.carBtAddress = address
            prefs.carBtName = name
            pickCar = false
            tick++
        }
        AlertDialog(
            onDismissRequest = { pickCar = false },
            title = { Text("Which one is the car?") },
            text = {
                Column {
                    if (devices.isEmpty()) Text("No paired Bluetooth devices found. Pair the phone with the car first.", color = Muted, fontSize = 12.sp)
                    (listOf(Pair("", "None")) + devices).forEach { (address, name) ->
                        Text(name, Modifier.fillMaxWidth().clickable { choose(address, if (address.isEmpty()) "" else name) }.padding(vertical = 10.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickCar = false }) { Text("Cancel") } },
        )
    }
}
