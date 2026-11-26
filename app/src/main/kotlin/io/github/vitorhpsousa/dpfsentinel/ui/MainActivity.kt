package io.github.vitorhpsousa.dpfsentinel.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import io.github.vitorhpsousa.dpfsentinel.LiveState
import io.github.vitorhpsousa.dpfsentinel.DpfApp
import io.github.vitorhpsousa.dpfsentinel.LoggerService
import io.github.vitorhpsousa.dpfsentinel.Prefs

class MainActivity : ComponentActivity() {
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (pendingStart) {
            pendingStart = false
            if (requiredPermissions().all { result[it] == true || granted(it) }) startLogger()
            else Toast.makeText(this, "Bluetooth permission is needed to talk to the OBD adapter", Toast.LENGTH_LONG).show()
        } else {
            startOnOpen()   // first run: the permissions were just allowed
        }
    }
    private var pendingStart = false

    private fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // Optional: GPS speed/altitude/grade in the logs. Logging works without it.
    private val optionalPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startLogger() {
        try {
            LoggerService.start(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't start logging: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Opening the app is asking it to log: look for the car straight away, and give up after a few
    // minutes if it isn't there (opened at home to read the logs). Not for a while after Stop.
    private fun startOnOpen() {
        if (LiveState.state.value.running || !Prefs(this).autoLog || LoggerService.stoppedRecently(this)) return
        if (!requiredPermissions().all { granted(it) }) return
        val stoppedAt = Prefs(this).stoppedAt
        runCatching { LoggerService.start(this, giveUpMs = LoggerService.AUTO_GIVE_UP_MS) }
        // start() clears the Stop time for a start by hand; this one is not by hand.
        if (stoppedAt > 0L) Prefs(this).stoppedAt = stoppedAt
    }

    private fun requestStart() {
        val wanted = (requiredPermissions() + optionalPermissions).distinct().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { !granted(it) }
        if (missing.isEmpty()) startLogger() else { pendingStart = true; askPermissions.launch(missing.toTypedArray()) }
    }

    // A session that started by itself logs the car only (Android gives GPS to a background start
    // only with "Allow all the time"). Now that the app is on screen, a second start adds GPS.
    override fun onStart() {
        super.onStart()
        val running = LiveState.state.value.running
        (application as DpfApp).telegram.retry()   // alerts that could not be sent last time
        (application as DpfApp).drive.tick(if (running) LiveState.state.value.session else -1)   // logs not yet in Drive
        if (running && !LoggerService.gpsOn && granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            runCatching { LoggerService.addGps(this) }
        } else {
            startOnOpen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ask up front, so logging can start later by itself without the phone in hand, and can
        // say so when it couldn't.
        val wanted = (requiredPermissions() + optionalPermissions).distinct().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { !granted(it) }
        if (missing.isNotEmpty()) askPermissions.launch(missing.toTypedArray())

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color(0xFF15181C))) {
                val snap by LiveState.state.collectAsState()
                var tab by remember { mutableIntStateOf(0) }
                Scaffold(
                    containerColor = Color.Black,
                    bottomBar = {
                        NavigationBar {
                            listOf("Live", "Logs", "Settings").forEachIndexed { i, label ->
                                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(label) })
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.padding(pad).fillMaxSize()) {
                        Column {
                            when (tab) {
                                0 -> LiveTab(snap, onStart = { requestStart() }, onStop = { LoggerService.stop(this@MainActivity) })
                                1 -> LogsTab()
                                else -> SettingsTab(snap)
                            }
                        }
                    }
                }
            }
        }
    }
}
