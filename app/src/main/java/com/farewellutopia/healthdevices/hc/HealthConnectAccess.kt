package com.farewellutopia.healthdevices.hc

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import com.farewellutopia.healthdevices.profiles.RecordKeys

/** Whether Health Connect can be used on this phone. */
enum class HealthConnectAvailability {
    AVAILABLE,

    /** Android 12/13 without the Health Connect app installed. */
    NOT_INSTALLED,

    /** The Health Connect app is there but too old for the client. */
    UPDATE_REQUIRED,
}

/**
 * The app's door to Health Connect. The client is created only after
 * [HealthConnectClient.getSdkStatus] said it can be (creating it otherwise
 * throws), and only once per process. No Google Play services are involved:
 * the client binds to the platform's Health Connect (Android 14+) or to the
 * Health Connect app.
 */
object HealthConnectAccess {

    val WRITE_PERMISSIONS: Set<String> = setOf(
        HealthPermission.getWritePermission(BloodPressureRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
    )

    @Volatile
    private var client: HealthConnectClient? = null

    fun availability(context: Context): HealthConnectAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UPDATE_REQUIRED
        else -> HealthConnectAvailability.NOT_INSTALLED
    }

    /** The client, or null when Health Connect is not available. */
    fun client(context: Context): HealthConnectClient? {
        client?.let { return it }
        if (availability(context) != HealthConnectAvailability.AVAILABLE) return null
        synchronized(this) {
            return client ?: HealthConnectClient.getOrCreate(context.applicationContext).also { client = it }
        }
    }

    /** The write permissions Health Connect granted; empty when it is not available. */
    suspend fun granted(context: Context): Set<String> {
        val c = client(context) ?: return emptySet()
        return c.permissionController.getGrantedPermissions().intersect(WRITE_PERMISSIONS)
    }

    suspend fun allGranted(context: Context): Boolean = granted(context) == WRITE_PERMISSIONS

    suspend fun insert(context: Context, records: List<Record>) {
        val c = client(context) ?: throw IllegalStateException("Health Connect is not available")
        if (records.isNotEmpty()) c.insertRecords(records)
    }

    /** The metadata every record carries: recorded automatically by the device, with an id the import can repeat. */
    fun metadata(manufacturer: String?, model: String?): (String) -> Metadata {
        val device = Device(type = Device.TYPE_UNKNOWN, manufacturer = manufacturer, model = model)
        return { clientRecordId -> Metadata.autoRecorded(device, clientRecordId, RecordKeys.VERSION) }
    }
}
