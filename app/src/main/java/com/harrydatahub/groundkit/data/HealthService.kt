package com.harrydatahub.groundkit.data

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
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Volume
import com.harrydatahub.groundkit.domain.HeatStrain
import com.harrydatahub.groundkit.domain.SleepSpan
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
    val heartRateMax: Long? = null,
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
            if (READ_HEART_RATE in granted) {
                add(HeartRateRecord.BPM_AVG)
                add(HeartRateRecord.BPM_MAX)
            }
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
            heartRateMax = totals[HeartRateRecord.BPM_MAX],
            waterMl = totals[HydrationRecord.VOLUME_TOTAL]?.inMilliliters,
        )
    }

    /**
     * Sleep sessions overlapping [from, now], less any time marked awake or out of bed
     * within them. Null when sleep is not allowed, so "no data" and "no sleep" differ.
     */
    suspend fun sleep(from: Instant): List<SleepSpan>? {
        val c = client ?: return null
        if (READ_SLEEP !in grantedPermissions()) return null
        // A session that began before `from` still counts for the part after it.
        val window = TimeRangeFilter.after(from.minusSeconds(SLEEP_LOOKBACK_S))
        val awake = setOf(
            SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        )
        return c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, window)).records.flatMap { r ->
            val start = r.startTime.toEpochMilli()
            val end = r.endTime.toEpochMilli()
            // Split the session around its awake stages.
            val gaps = r.stages.filter { it.stage in awake }.map { it.startTime.toEpochMilli() to it.endTime.toEpochMilli() }.sortedBy { it.first }
            var cursor = start
            buildList {
                for ((a, b) in gaps) {
                    if (a > cursor) add(SleepSpan(cursor, minOf(a, end)))
                    cursor = maxOf(cursor, b)
                }
                if (end > cursor) add(SleepSpan(cursor, end))
            }
        }.filter { it.end > from.toEpochMilli() }
    }

    /** Heart-rate samples since `from`, for the heat-strain check. Empty when not allowed. */
    suspend fun heartRateSamples(from: Instant): List<HeatStrain.Sample> {
        val c = client ?: return emptyList()
        if (READ_HEART_RATE !in grantedPermissions()) return emptyList()
        return c.readRecords(ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.after(from))).records
            .flatMap { r -> r.samples.map { HeatStrain.Sample(it.time.toEpochMilli(), it.beatsPerMinute) } }
            .filter { it.at >= from.toEpochMilli() }
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
        val READ_SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)

        /** Must match the health permissions declared in AndroidManifest.xml. */
        val PERMISSIONS = setOf(READ_STEPS, READ_DISTANCE, READ_ACTIVE_ENERGY, READ_HEART_RATE, READ_WATER, WRITE_WATER, READ_SLEEP)

        /** Longest sleep session looked back for, so one that started before the window counts. */
        private const val SLEEP_LOOKBACK_S = 16 * 3600L
    }
}
