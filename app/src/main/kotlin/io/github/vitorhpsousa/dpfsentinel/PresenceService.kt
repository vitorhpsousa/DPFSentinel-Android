package io.github.vitorhpsousa.dpfsentinel

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import androidx.annotation.RequiresApi

// Android binds this when the associated OBD adapter comes into Bluetooth range (set up in
// AutoSetup), with the phone locked and the app not running. "Disappeared" is ignored on purpose:
// a BLE adapter stops advertising once connected, and the logger stops by itself when the car is off.
@RequiresApi(Build.VERSION_CODES.S)
class PresenceService : CompanionDeviceService() {
    // Android 12
    @Deprecated("Replaced on Android 13")
    override fun onDeviceAppeared(address: String) {
        AutoStart.request(this, "adapter in range")
    }

    @Deprecated("Replaced on Android 13")
    override fun onDeviceDisappeared(address: String) {}

    // Android 13 to 15
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Deprecated("Replaced on Android 16")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        AutoStart.request(this, "adapter in range")
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Deprecated("Replaced on Android 16")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {}

    // Android 16+
    @RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED -> AutoStart.request(this, "adapter in range")
            DevicePresenceEvent.EVENT_BT_CONNECTED -> AutoStart.request(this, "adapter connected")
            else -> {}
        }
    }
}
