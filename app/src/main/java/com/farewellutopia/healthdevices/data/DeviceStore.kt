package com.farewellutopia.healthdevices.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "health-devices")

/**
 * The app's one persistence surface: the paired devices, the reception log
 * and the scan registration state, each a JSON value in a preferences
 * DataStore. Every write goes through a single `edit`, so the service, the
 * receivers and the activity never race on a device's watermark.
 */
class DeviceStore(context: Context) {

    private val store = context.applicationContext.dataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object K {
        val devices = stringPreferencesKey("devices")
        val receptions = stringPreferencesKey("receptions")
        val scan = stringPreferencesKey("scan")
    }

    val devices: Flow<List<PairedDevice>> = store.data.map { decodeDevices(it) }
    val receptions: Flow<List<Reception>> = store.data.map { decodeReceptions(it) }
    val scanState: Flow<ScanState> = store.data.map { decodeScan(it) }

    suspend fun devicesNow(): List<PairedDevice> = devices.first()
    suspend fun device(address: String): PairedDevice? = devicesNow().firstOrNull { it.address.equals(address, ignoreCase = true) }
    suspend fun scanStateNow(): ScanState = scanState.first()

    /** Adds or replaces the device with the same address. */
    suspend fun putDevice(device: PairedDevice) {
        store.edit { p ->
            val others = decodeDevices(p).filterNot { it.address.equals(device.address, ignoreCase = true) }
            p[K.devices] = json.encodeToString(ListSerializer(PairedDevice.serializer()), others + device)
        }
    }

    /** Applies [change] to the stored device, atomically; a no-op for an unknown address. */
    suspend fun updateDevice(address: String, change: (PairedDevice) -> PairedDevice) {
        store.edit { p ->
            val devices = decodeDevices(p)
            val index = devices.indexOfFirst { it.address.equals(address, ignoreCase = true) }
            if (index < 0) return@edit
            val updated = devices.toMutableList().also { it[index] = change(it[index]) }
            p[K.devices] = json.encodeToString(ListSerializer(PairedDevice.serializer()), updated)
        }
    }

    suspend fun forgetDevice(address: String) {
        store.edit { p ->
            val rest = decodeDevices(p).filterNot { it.address.equals(address, ignoreCase = true) }
            p[K.devices] = json.encodeToString(ListSerializer(PairedDevice.serializer()), rest)
        }
    }

    /** Prepends a reception; the log keeps the newest [RECEPTION_LIMIT]. */
    suspend fun addReception(reception: Reception) {
        store.edit { p ->
            val log = (listOf(reception) + decodeReceptions(p)).take(RECEPTION_LIMIT)
            p[K.receptions] = json.encodeToString(ListSerializer(Reception.serializer()), log)
        }
    }

    suspend fun setScanState(state: ScanState) {
        store.edit { p -> p[K.scan] = json.encodeToString(ScanState.serializer(), state) }
    }

    private fun decodeDevices(p: Preferences): List<PairedDevice> =
        p[K.devices]?.let { runCatching { json.decodeFromString(ListSerializer(PairedDevice.serializer()), it) }.getOrNull() } ?: emptyList()

    private fun decodeReceptions(p: Preferences): List<Reception> =
        p[K.receptions]?.let { runCatching { json.decodeFromString(ListSerializer(Reception.serializer()), it) }.getOrNull() } ?: emptyList()

    private fun decodeScan(p: Preferences): ScanState =
        p[K.scan]?.let { runCatching { json.decodeFromString(ScanState.serializer(), it) }.getOrNull() } ?: ScanState()

    companion object {
        const val RECEPTION_LIMIT = 20
    }
}
