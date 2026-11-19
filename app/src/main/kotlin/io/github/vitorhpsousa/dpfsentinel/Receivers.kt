package io.github.vitorhpsousa.dpfsentinel

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

// The phone connected to some Bluetooth device. Starts logging when it is the car's own Bluetooth
// (the head unit chosen in Settings), which happens on every drive even when the OBD adapter was in
// range all night and so never "appeared" again. Links to anything else, the adapter included, are ignored.
class CarBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val car = Prefs(context).carBtAddress
        if (car.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        if (device.address.equals(car, ignoreCase = true)) AutoStart.request(context, "car Bluetooth")
    }
}

// After a reboot or an app update Android may have forgotten that the app watches for the adapter:
// ask again. Never starts logging by itself.
class RearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // The daily alarm is set for a wall-clock time: set it again when the clock or zone changes.
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> { DailyWindow.schedule(context); return }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {}
            else -> return
        }
        AutoSetup.observe(context)
        DailyWindow.schedule(context)   // alarms do not survive a reboot
    }
}
