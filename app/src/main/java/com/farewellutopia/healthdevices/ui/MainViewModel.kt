package com.farewellutopia.healthdevices.ui

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.farewellutopia.healthdevices.ble.BackgroundScan
import com.farewellutopia.healthdevices.ble.GattSession
import com.farewellutopia.healthdevices.ble.ReadOutcome
import com.farewellutopia.healthdevices.data.DeviceStore
import com.farewellutopia.healthdevices.data.PairedDevice
import com.farewellutopia.healthdevices.data.Reception
import com.farewellutopia.healthdevices.data.ScanState
import com.farewellutopia.healthdevices.hc.HealthConnectAccess
import com.farewellutopia.healthdevices.hc.HealthConnectAvailability
import com.farewellutopia.healthdevices.importer.ImportResult
import com.farewellutopia.healthdevices.importer.ImportLogic
import com.farewellutopia.healthdevices.importer.Importer
import com.farewellutopia.healthdevices.profiles.DeviceProfile
import com.farewellutopia.healthdevices.profiles.Profiles
import com.farewellutopia.healthdevices.service.Notifications
import com.farewellutopia.healthdevices.time.DeviceClock

/** A device the pairing scan saw. */
data class FoundDevice(val address: String, val name: String?, val profile: DeviceProfile)

/** The pairing flow, step by step. */
sealed interface Pairing {
    data object Idle : Pairing
    data class Scanning(val found: List<FoundDevice>, val error: Int? = null) : Pairing
    data class Connecting(val device: FoundDevice) : Pairing

    /** Connected and read; the user now chooses the clock setting and whether to import what arrived. */
    data class Connected(val device: FoundDevice, val outcome: ReadOutcome, val bonded: Boolean) : Pairing
    data class Failed(val device: FoundDevice, val outcome: ReadOutcome) : Pairing

    /** The import of the readings on the device, if the user chose it. */
    data class Finished(val device: PairedDevice, val imported: ImportResult?) : Pairing
}

/** What the status screen shows about the phone: permissions and services, refreshed on every start. */
data class PhoneState(
    val bluetoothPermissions: Boolean = false,
    val notificationsPermission: Boolean = true,
    val bluetoothOn: Boolean = false,
    val healthConnect: HealthConnectAvailability = HealthConnectAvailability.NOT_INSTALLED,
    val healthConnectGranted: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application get() = getApplication()
    private val store = DeviceStore(app)

    val devices: StateFlow<List<PairedDevice>> = store.devices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val receptions: StateFlow<List<Reception>> = store.receptions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val scanState: StateFlow<ScanState> = store.scanState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanState())

    private val _phone = MutableStateFlow(PhoneState())
    val phone: StateFlow<PhoneState> = _phone.asStateFlow()

    private val _pairing = MutableStateFlow<Pairing>(Pairing.Idle)
    val pairing: StateFlow<Pairing> = _pairing.asStateFlow()

    private var scanCallback: ScanCallback? = null
    private var connectJob: Job? = null

    /** Re-reads what the phone allows; cheap, so it runs on every activity start and after every permission dialog. */
    fun refresh() {
        viewModelScope.launch {
            val hc = HealthConnectAccess.availability(app)
            val granted = if (hc == HealthConnectAvailability.AVAILABLE) withContext(Dispatchers.IO) { HealthConnectAccess.allGranted(app) } else false
            _phone.value = PhoneState(
                bluetoothPermissions = BackgroundScan.hasPermissions(app),
                notificationsPermission = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                bluetoothOn = BackgroundScan.isBluetoothOn(app),
                healthConnect = hc,
                healthConnectGranted = granted,
                ignoringBatteryOptimizations = app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true,
            )
        }
    }

    /** Registers the background scan if it is due (see [BackgroundScan.ensure]); no-op while pairing. */
    fun ensureScan() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_pairing.value == Pairing.Idle) BackgroundScan.ensure(app, store)
        }
    }

    /** The status screen's button: registers again, at most once per [com.farewellutopia.healthdevices.ble.ScanPlan.MANUAL_INTERVAL_MS]. */
    fun reregisterScan() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_pairing.value == Pairing.Idle) BackgroundScan.register(app, store, manual = true)
        }
    }

    /** Forgets what was imported so far: the next reception writes everything the device holds (Health Connect upserts). */
    fun resetWatermark(address: String) {
        viewModelScope.launch(Dispatchers.IO) { store.updateDevice(address) { it.copy(watermark = null) } }
    }

    fun setClock(address: String, clock: DeviceClock) {
        viewModelScope.launch(Dispatchers.IO) { store.updateDevice(address) { it.copy(clock = clock) } }
    }

    /** Forgets the device in the app; the bond stays until the user removes it in the system's Bluetooth settings. */
    fun forget(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.forgetDevice(address)
            if (store.devicesNow().isEmpty()) BackgroundScan.unregister(app, store) else BackgroundScan.register(app, store)
        }
    }

    // --- pairing ---

    fun startPairing() {
        stopScan()
        // The background scan is paused while pairing: the device accepts one
        // connection, and the service must not take it from under the pairing.
        viewModelScope.launch(Dispatchers.IO) { BackgroundScan.pause(app, store) }
        if (!BackgroundScan.hasPermissions(app)) {
            _pairing.value = Pairing.Scanning(emptyList(), error = ERROR_NO_PERMISSION)
            return
        }
        val scanner = app.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            _pairing.value = Pairing.Scanning(emptyList(), error = ERROR_BLUETOOTH_OFF)
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onFound(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onFound(it) }
            override fun onScanFailed(errorCode: Int) {
                _pairing.value = Pairing.Scanning((_pairing.value as? Pairing.Scanning)?.found.orEmpty(), error = errorCode)
            }
        }
        val filters = Profiles.SERVICE_UUIDS.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        _pairing.value = Pairing.Scanning(emptyList())
        try {
            scanner.startScan(filters, settings, callback)
            scanCallback = callback
        } catch (e: SecurityException) {
            Log.w(TAG, "scan refused", e)
            _pairing.value = Pairing.Scanning(emptyList(), error = ERROR_NO_PERMISSION)
        }
    }

    private fun onFound(result: ScanResult) {
        val current = _pairing.value as? Pairing.Scanning ?: return
        val services = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
        val profile = Profiles.byService(services) ?: return
        val address = result.device.address.uppercase()
        if (current.found.any { it.address == address }) return
        // Already paired devices are not offered again; "Forget device" first.
        if (devices.value.any { it.address.equals(address, ignoreCase = true) }) return
        _pairing.value = current.copy(found = current.found + FoundDevice(address, result.scanRecord?.deviceName, profile))
    }

    fun stopScan() {
        val callback = scanCallback ?: return
        scanCallback = null
        try {
            app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan failed", e)
        }
    }

    /** Connects for the first time: service discovery, Device Information, and the subscription that triggers the system's pairing dialog. */
    fun connect(found: FoundDevice) {
        stopScan()
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val remote: BluetoothDevice = adapter.getRemoteDevice(found.address)
        _pairing.value = Pairing.Connecting(found)
        connectJob?.cancel()
        connectJob = viewModelScope.launch(Dispatchers.Default) {
            val outcome = try {
                GattSession(app, remote, found.profile, GattSession.PAIRING_TIMEOUT_MS).run()
            } catch (e: SecurityException) {
                Log.w(TAG, "connect refused", e)
                ReadOutcome(emptyList(), failure = com.farewellutopia.healthdevices.ble.ReadFailure.CONNECT_FAILED)
            }
            val bonded = try {
                remote.bondState == BluetoothDevice.BOND_BONDED
            } catch (e: SecurityException) {
                false
            }
            _pairing.value = if (outcome.failure == null) Pairing.Connected(found, outcome, bonded) else Pairing.Failed(found, outcome)
        }
    }

    /**
     * Stores the device and either imports what the first connection brought
     * (watermark from the data) or leaves it alone (watermark = the newest
     * stamp the device just sent, so exactly what it holds now counts as old
     * — independent of the phone's clock and of the zone setting). Then the
     * background scan covers it.
     */
    fun finishPairing(clock: DeviceClock, importExisting: Boolean) {
        val state = _pairing.value as? Pairing.Connected ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val now = Instant.now()
            val device = PairedDevice(
                address = state.device.address,
                name = state.device.name,
                manufacturer = state.outcome.manufacturer,
                model = state.outcome.model,
                profileId = state.device.profile.id,
                hasCurrentTimeService = state.outcome.hasCurrentTimeService,
                clock = clock,
                watermark = if (importExisting) null else ImportLogic.newestStamp(state.outcome.frames, state.device.profile, clock, ZoneId.systemDefault(), now),
                bonded = state.bonded,
                pairedAt = now.toEpochMilli(),
            )
            store.putDevice(device)
            val imported = if (importExisting) {
                Importer(app, store).import(device, state.outcome, now) { Notifications.format(app, it) }
            } else {
                null
            }
            BackgroundScan.register(app, store)
            _pairing.value = Pairing.Finished(store.device(device.address) ?: device, imported)
        }
    }

    fun cancelPairing() {
        stopScan()
        connectJob?.cancel()
        connectJob = null
        _pairing.value = Pairing.Idle
        // The pause in startPairing ends here (or in finishPairing).
        viewModelScope.launch(Dispatchers.IO) { if (store.devicesNow().isNotEmpty()) BackgroundScan.register(app, store) }
    }

    override fun onCleared() {
        stopScan()
        super.onCleared()
    }

    companion object {
        private const val TAG = "MainViewModel"

        /** Pairing-scan errors of the app's own, beside the scanner's codes (which are all positive). */
        const val ERROR_NO_PERMISSION = -1
        const val ERROR_BLUETOOTH_OFF = -2
    }
}
