package dev.aoidoki.arise.sense

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Health Connect: where Samsung Health, a Galaxy Watch or a smart scale put their data. Every call
 * is best-effort — no Health Connect, no permission or an error all read as "no data".
 */
class HealthRepo(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
    )

    fun available(): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun needsUpdate(): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED

    private val client: HealthConnectClient? by lazy { if (available()) runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() else null }

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun granted(): Set<String> = runCatching { client?.permissionController?.getGrantedPermissions() }.getOrNull() ?: emptySet()

    suspend fun hasCore(): Boolean = granted().contains(HealthPermission.getReadPermission(StepsRecord::class))

    suspend fun backgroundReadSupported(): Boolean = runCatching {
        client?.features?.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    data class Day(val steps: Int?, val distanceM: Int?)

    suspend fun day(start: Instant, end: Instant): Day {
        val c = client ?: return Day(null, null)
        val granted = granted()
        return runCatching {
            val metrics = buildSet {
                if (HealthPermission.getReadPermission(StepsRecord::class) in granted) add(StepsRecord.COUNT_TOTAL)
                if (HealthPermission.getReadPermission(DistanceRecord::class) in granted) add(DistanceRecord.DISTANCE_TOTAL)
            }
            if (metrics.isEmpty()) return Day(null, null)
            val r = c.aggregate(AggregateRequest(metrics = metrics, timeRangeFilter = TimeRangeFilter.between(start, end)))
            Day(r[StepsRecord.COUNT_TOTAL]?.toInt(), r[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.toInt())
        }.onFailure { Log.w(TAG, "aggregate failed", it) }.getOrDefault(Day(null, null))
    }

    /** Minutes asleep in sessions that ended this morning (18:00 yesterday → 14:00 today). */
    suspend fun sleepLastNight(dayStart: Instant): Int? {
        val c = client ?: return null
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted()) return null
        return runCatching {
            val from = dayStart.minus(Duration.ofHours(6))
            val to = dayStart.plus(Duration.ofHours(14))
            val r = c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, to)))
            r.records.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt().takeIf { it > 0 }
        }.getOrNull()
    }

    data class Weigh(val time: Instant, val kg: Double)

    suspend fun weightsSince(since: Instant): List<Weigh> {
        val c = client ?: return emptyList()
        if (HealthPermission.getReadPermission(WeightRecord::class) !in granted()) return emptyList()
        return runCatching {
            c.readRecords(ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.after(since))).records
                .filter { it.metadata.dataOrigin.packageName != context.packageName }
                .map { Weigh(it.time, it.weight.inKilograms) }
        }.getOrDefault(emptyList())
    }

    suspend fun writeWeight(kg: Double, at: Instant) {
        val c = client ?: return
        if (HealthPermission.getWritePermission(WeightRecord::class) !in granted()) return
        runCatching {
            c.insertRecords(
                listOf(
                    WeightRecord(
                        time = at,
                        zoneOffset = ZoneId.systemDefault().rules.getOffset(at),
                        weight = Mass.kilograms(kg),
                        metadata = Metadata.manualEntry(),
                    ),
                ),
            )
        }.onFailure { Log.w(TAG, "weight write failed", it) }
    }

    companion object {
        private const val TAG = "HealthRepo"
    }
}
