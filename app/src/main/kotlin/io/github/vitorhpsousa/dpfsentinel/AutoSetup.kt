package io.github.vitorhpsousa.dpfsentinel

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcelable
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.IntentCompat
import io.github.vitorhpsousa.dpfsentinel.obd.BleElm
import java.util.regex.Pattern

// One-time pairing of the app with the OBD adapter through Android's companion-device manager.
// The association is what lets Android wake the app when the adapter comes into range
// (PresenceService) and lets it start the logger from the background.
object AutoSetup {
    private const val TAG = "AutoSetup"

    fun supported(context: Context) =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    /** Android 12+ can watch for the adapter; older versions only keep the association. */
    val canObserve get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private fun manager(context: Context): CompanionDeviceManager? =
        if (supported(context)) context.getSystemService(CompanionDeviceManager::class.java) else null

    /**
     * Asks Android to look for an adapter. [onChooser] gets the system's "pick a device" dialog to
     * launch; its result goes to [onChooserResult]. Call with an activity context.
     */
    fun associate(context: Context, onChooser: (IntentSender) -> Unit, onError: (String) -> Unit) {
        val cdm = manager(context) ?: return onError("This phone has no companion-device support")
        val names = BleElm.NAME_HINTS.joinToString("|") { Pattern.quote(it) }
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile(".*($names).*", Pattern.CASE_INSENSITIVE))
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
        val callback = object : CompanionDeviceManager.Callback() {
            @Deprecated("Android 12 and older")
            override fun onDeviceFound(chooser: IntentSender) = onChooser(chooser)
            override fun onAssociationPending(chooser: IntentSender) = onChooser(chooser)
            override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "No adapter found")
        }
        try {
            cdm.associate(request, callback, null)
        } catch (e: RuntimeException) {
            onError(e.message ?: "Couldn't search for the adapter")
        }
    }

    /** Stores what the chooser returned and starts watching. Returns "" or an error to show. */
    fun onChooserResult(context: Context, data: Intent?): String {
        if (data == null) return "No adapter chosen"
        var id = -1
        var mac = ""
        var name = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            IntentCompat.getParcelableExtra(data, CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)?.let {
                id = it.id
                mac = it.deviceMacAddress?.toString().orEmpty()
                name = it.displayName?.toString().orEmpty()
            }
        }
        if (mac.isEmpty()) {
            @Suppress("DEPRECATION")
            when (val d = IntentCompat.getParcelableExtra(data, CompanionDeviceManager.EXTRA_DEVICE, Parcelable::class.java)) {
                is ScanResult -> { mac = d.device.address; name = d.scanRecord?.deviceName ?: deviceName(d.device) }
                is BluetoothDevice -> { mac = d.address; name = deviceName(d) }
                else -> {}
            }
        }
        if (mac.isEmpty()) return "Android didn't say which adapter was chosen"
        mac = mac.uppercase()   // BluetoothAdapter.getRemoteDevice only takes upper case
        if (id < 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) id = associationId(context, mac)
        val prefs = Prefs(context)
        prefs.assocId = id
        prefs.assocMac = mac
        prefs.adapterAddress = mac   // the logger connects to this one, without scanning
        prefs.adapterName = name
        prefs.autoLog = true
        return observe(context)
    }

    private fun deviceName(d: BluetoothDevice): String =
        try { d.name.orEmpty() } catch (e: SecurityException) { "" }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun associationId(context: Context, mac: String): Int = try {
        manager(context)?.myAssociations?.firstOrNull { it.deviceMacAddress?.toString().equals(mac, ignoreCase = true) }?.id ?: -1
    } catch (e: RuntimeException) {
        -1
    }

    /** (Re)starts watching for the adapter; also called after a reboot or an app update. */
    fun observe(context: Context): String {
        val prefs = Prefs(context)
        val mac = prefs.assocMac
        if (mac.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return ""
        val cdm = manager(context) ?: return ""
        return try {
            if (Build.VERSION.SDK_INT >= 36 && prefs.assocId >= 0) {
                cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(prefs.assocId).build())
            } else {
                @Suppress("DEPRECATION")
                cdm.startObservingDevicePresence(mac)
            }
            ""
        } catch (e: RuntimeException) {
            Log.w(TAG, "startObservingDevicePresence failed", e)
            "Paired, but Android wouldn't watch for the adapter: ${e.message}"
        }
    }

    /** Stops watching and removes the association. The logger keeps its saved adapter. */
    fun remove(context: Context) {
        val prefs = Prefs(context)
        val mac = prefs.assocMac
        val id = prefs.assocId
        val cdm = manager(context)
        if (cdm != null && mac.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 36 && id >= 0) {
                        cdm.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
                    } else {
                        @Suppress("DEPRECATION")
                        cdm.stopObservingDevicePresence(mac)
                    }
                }
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && id >= 0) {
                    cdm.disassociate(id)
                } else {
                    @Suppress("DEPRECATION")
                    cdm.disassociate(mac)
                }
            }
        }
        prefs.assocId = -1
        prefs.assocMac = ""
    }
}
