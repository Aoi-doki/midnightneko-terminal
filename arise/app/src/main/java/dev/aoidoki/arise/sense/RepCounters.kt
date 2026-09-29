package dev.aoidoki.arise.sense

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Rep counting as small state machines over one signal. Pure — the tests feed them synthetic
 * traces; the camera and sensor front-ends feed them live values.
 */
interface RepCounter {
    val count: Int
    /** Feed one sample. Returns true when this sample completed a rep. */
    fun feed(value: Float, tMillis: Long): Boolean
    fun reset()
}

/**
 * Two-threshold hysteresis on an angle (or any scalar). A rep is: leave the rest zone, reach the
 * flex zone, come back to rest. [restHigh] says whether rest is the high end (push-up: arms
 * straight ≈ 170°) or the low end. Smoothed with an EMA; reps faster than [minRepMs] are ignored.
 */
class HysteresisRepCounter(
    private val rest: Float,
    private val flex: Float,
    private val restHigh: Boolean = true,
    private val minRepMs: Long = 450,
    private val smoothing: Float = 0.45f,
    /** Accelerometer signals have no "rest" reading to wait for; the first flex already counts. */
    private val requireRestStart: Boolean = true,
) : RepCounter {
    private var ema = Float.NaN
    private var flexed = false
    private var lastRepAt = Long.MIN_VALUE / 2
    private var armed = false
    override var count = 0
        private set

    /** 0 at rest, 1 fully flexed; for the UI's depth gauge. */
    var depth = 0f
        private set

    private fun atRest(v: Float) = if (restHigh) v >= rest else v <= rest
    private fun atFlex(v: Float) = if (restHigh) v <= flex else v >= flex

    override fun feed(value: Float, tMillis: Long): Boolean {
        if (value.isNaN()) return false
        ema = if (ema.isNaN()) value else ema + smoothing * (value - ema)
        val v = ema
        depth = ((if (restHigh) rest - v else v - rest) / abs(rest - flex)).coerceIn(0f, 1f)
        // Must start from rest so a set that begins mid-motion doesn't count half a rep.
        if (!armed) {
            if (!requireRestStart || atRest(v)) armed = true
            if (requireRestStart) return false
        }
        if (!flexed && atFlex(v)) flexed = true
        if (flexed && atRest(v)) {
            flexed = false
            if (tMillis - lastRepAt >= minRepMs) {
                lastRepAt = tMillis
                count++
                return true
            }
        }
        return false
    }

    override fun reset() {
        ema = Float.NaN; flexed = false; armed = false; count = 0; depth = 0f
    }
}

/**
 * Push-ups with the phone flat on the floor under the chest: the proximity sensor reads "near" at
 * the bottom of each rep. Values are distance in cm; near is anything below [nearCm].
 */
class ProximityRepCounter(private val nearCm: Float = 3f, private val minRepMs: Long = 500) : RepCounter {
    private var near = false
    private var lastRepAt = Long.MIN_VALUE / 2
    override var count = 0
        private set

    override fun feed(value: Float, tMillis: Long): Boolean {
        val isNear = value < nearCm
        val rep = isNear && !near && tMillis - lastRepAt >= minRepMs
        near = isNear
        if (rep) {
            lastRepAt = tMillis
            count++
        }
        return rep
    }

    override fun reset() {
        near = false; count = 0
    }
}

object Motion {
    fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    /** Angle in degrees between the gravity vector and the phone's screen normal (0 = lying flat). */
    fun tiltDegrees(x: Float, y: Float, z: Float): Float {
        val m = magnitude(x, y, z)
        if (m < 1e-3f) return Float.NaN
        return Math.toDegrees(acos((z / m).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    /** Angle at b (degrees) between segments b→a and b→c. For elbows, knees and hips. */
    fun jointAngle(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float {
        val a1 = atan2((ay - by).toDouble(), (ax - bx).toDouble())
        val a2 = atan2((cy - by).toDouble(), (cx - bx).toDouble())
        var deg = Math.toDegrees(abs(a1 - a2)).toFloat()
        if (deg > 180f) deg = 360f - deg
        return deg
    }
}

/** Keeps a short window of accelerometer magnitudes and says whether the body is holding still. */
class StillnessMonitor(private val windowMs: Long = 1200, private val maxStd: Float = 0.55f) {
    private val samples = ArrayDeque<Pair<Long, Float>>()

    fun feed(magnitude: Float, tMillis: Long): Boolean {
        samples.addLast(tMillis to magnitude)
        while (samples.isNotEmpty() && tMillis - samples.first().first > windowMs) samples.removeFirst()
        return still()
    }

    fun still(): Boolean {
        if (samples.size < 5) return false
        val mean = samples.sumOf { it.second.toDouble() } / samples.size
        val variance = samples.sumOf { (it.second - mean) * (it.second - mean) } / samples.size
        return sqrt(variance) <= maxStd
    }

    fun reset() = samples.clear()
}

/** Exercise profiles: which signal, which thresholds. Kept in one place so tests use the real numbers. */
object Profiles {
    /** Camera: elbow angle. Straight arms ≈ 160–180°, bottom ≈ 70–90°. */
    fun pushupPose() = HysteresisRepCounter(rest = 150f, flex = 100f, restHigh = true, minRepMs = 500)
    /** Camera: knee angle. Standing ≈ 170°, parallel ≈ 90°. */
    fun squatPose() = HysteresisRepCounter(rest = 160f, flex = 110f, restHigh = true, minRepMs = 700)
    /** Camera: hip angle (shoulder–hip–knee). Lying ≈ 130–160°, sitting up ≈ 50–70°. */
    fun situpPose() = HysteresisRepCounter(rest = 120f, flex = 75f, restHigh = true, minRepMs = 700)

    /** Sensor: accelerometer magnitude with the phone in a pocket. Squats dip below g then push above it. */
    fun squatAccel() = HysteresisRepCounter(rest = 10.9f, flex = 8.6f, restHigh = true, minRepMs = 900, smoothing = 0.3f, requireRestStart = false)
    /** Sensor: phone held flat on the chest; tilt from the floor plane. Lying ≈ 0–20°, up ≈ 60°+. */
    fun situpTilt() = HysteresisRepCounter(rest = 25f, flex = 55f, restHigh = false, minRepMs = 800)
}
