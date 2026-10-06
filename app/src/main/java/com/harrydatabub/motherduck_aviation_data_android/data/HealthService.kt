package com.harrydatabub.motherduck_aviation_data_android.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Volume
import java.time.Instant
import java.time.ZoneId

/**
 * What Health Connect knows about the current shift. Each value is null when there is no
 * data or that type was not allowed. Health Connect has no noise-exposure type, so unlike
 * the iOS app there are no sound levels.
 */
data class ShiftStats(
    val steps: Long? = null,
    val distanceKm: Double? = null,
    val activeKcal: Double? = null,
    val heartRateLatest: Long? = null,
    val heartRateAverage: Long? = null,
    val waterMl: Double? = null,
)

enum class HealthAvailability { AVAILABLE, NEEDS_PROVIDER, UNAVAILABLE }

/**
 * Health Connect, the Android counterpart of the iOS app's HealthKit service: reads the
 * shift's activity and heart rate, and saves the water logged in the app.
 */
class HealthService(private val context: Context) {
    val availability: HealthAvailability
        get() = when (HealthConnectClient.getSdkStatus(context, PROVIDER)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.NEEDS_PROVIDER
            else -> HealthAvailability.UNAVAILABLE
        }

    private val client: HealthConnectClient?
        get() = if (availability == HealthAvailability.AVAILABLE) HealthConnectClient.getOrCreate(context) else null

    /** The permission sheet; launched from the Shift screen. */
    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    /** Where to install or update the Health Connect app (Android 13 and older). */
    fun providerIntent() = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding"),
    ).setPackage("com.android.vending").putExtra("overlay", true).putExtra("callerId", context.packageName)

    suspend fun grantedPermissions(): Set<String> =
        runCatching { client?.permissionController?.getGrantedPermissions() }.getOrNull().orEmpty()

    /** Totals since `start`, for whichever types the user allowed. */
    suspend fun read(start: Instant): ShiftStats {
        val c = client ?: return ShiftStats()
        val granted = grantedPermissions()
        val window = TimeRangeFilter.after(start)
        // Asking for a type without its permission fails the whole aggregate, so ask only
        // for the allowed ones.
        val metrics = buildSet {
            if (READ_STEPS in granted) add(StepsRecord.COUNT_TOTAL)
            if (READ_DISTANCE in granted) add(DistanceRecord.DISTANCE_TOTAL)
            if (READ_ACTIVE_ENERGY in granted) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (READ_HEART_RATE in granted) add(HeartRateRecord.BPM_AVG)
            if (READ_WATER in granted) add(HydrationRecord.VOLUME_TOTAL)
        }
        if (metrics.isEmpty()) return ShiftStats()
        val totals = c.aggregate(AggregateRequest(metrics, window))
        val latestBpm = if (READ_HEART_RATE in granted) {
            c.readRecords(ReadRecordsRequest(HeartRateRecord::class, window, ascendingOrder = false, pageSize = 1))
                .records.firstOrNull()?.samples?.maxByOrNull { it.time }?.beatsPerMinute
        } else null
        return ShiftStats(
            steps = totals[StepsRecord.COUNT_TOTAL],
            distanceKm = totals[DistanceRecord.DISTANCE_TOTAL]?.inKilometers,
            activeKcal = totals[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
            heartRateLatest = latestBpm,
            heartRateAverage = totals[HeartRateRecord.BPM_AVG],
            waterMl = totals[HydrationRecord.VOLUME_TOTAL]?.inMilliliters,
        )
    }

    /** Saves a drink to Health Connect. False when unavailable or not allowed. */
    suspend fun logWater(ml: Double, at: Instant = Instant.now()): Boolean {
        val c = client ?: return false
        if (WRITE_WATER !in grantedPermissions()) return false
        // Hydration is an interval record and its end must follow its start.
        val offset = ZoneId.systemDefault().rules.getOffset(at)
        c.insertRecords(
            listOf(
                HydrationRecord(
                    startTime = at.minusSeconds(60), startZoneOffset = offset,
                    endTime = at, endZoneOffset = offset,
                    volume = Volume.milliliters(ml),
                    metadata = Metadata.manualEntry(),
                ),
            ),
        )
        return true
    }

    companion object {
        const val PROVIDER = "com.google.android.apps.healthdata"

        val READ_STEPS = HealthPermission.getReadPermission(StepsRecord::class)
        val READ_DISTANCE = HealthPermission.getReadPermission(DistanceRecord::class)
        val READ_ACTIVE_ENERGY = HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
        val READ_HEART_RATE = HealthPermission.getReadPermission(HeartRateRecord::class)
        val READ_WATER = HealthPermission.getReadPermission(HydrationRecord::class)
        val WRITE_WATER = HealthPermission.getWritePermission(HydrationRecord::class)

        /** Must match the health permissions declared in AndroidManifest.xml. */
        val PERMISSIONS = setOf(READ_STEPS, READ_DISTANCE, READ_ACTIVE_ENERGY, READ_HEART_RATE, READ_WATER, WRITE_WATER)
    }
}
