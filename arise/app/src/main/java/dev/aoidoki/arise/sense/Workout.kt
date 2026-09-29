package dev.aoidoki.arise.sense

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import dev.aoidoki.arise.engine.ObjectiveType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class TrackMode(val label: String) { CAMERA("Camera"), SENSOR("Sensor") }

/** One landmark in normalised view space (0..1), for the skeleton overlay. */
data class Joint(val x: Float, val y: Float, val confidence: Float)

data class WorkoutState(
    val type: ObjectiveType = ObjectiveType.PUSHUPS,
    val mode: TrackMode = TrackMode.CAMERA,
    val running: Boolean = false,
    val count: Int = 0,
    /** Seconds held, for timed types. */
    val seconds: Int = 0,
    val depth: Float = 0f,
    val still: Boolean = false,
    val bodyVisible: Boolean = false,
    val hint: String = "",
    val joints: Map<Int, Joint> = emptyMap(),
)

/**
 * Drives one set: owns the counter for the chosen exercise and whichever front-end feeds it —
 * the camera (ML Kit pose → joint angles) or the phone's own sensors.
 */
class WorkoutController(private val context: Context) : SensorEventListener {
    private val _state = MutableStateFlow(WorkoutState())
    val state: StateFlow<WorkoutState> = _state

    private var counter: RepCounter? = null
    private val stillness = StillnessMonitor()
    private var timedStart = 0L
    private var timedAccum = 0L
    private var lastTick = 0L
    private var detector: PoseDetector? = null

    /** Called with each new rep so the UI can play a callout. */
    var onRep: ((Int) -> Unit)? = null

    fun configure(type: ObjectiveType, mode: TrackMode) {
        stop()
        _state.value = WorkoutState(type = type, mode = mode, hint = hintFor(type, mode))
    }

    fun hintFor(type: ObjectiveType, mode: TrackMode): String = when (type) {
        ObjectiveType.PUSHUPS -> if (mode == TrackMode.CAMERA) "Prop the phone on the floor 2 m to your side, camera facing you. Your whole body must be in frame."
        else "Place the phone face-up on the floor under your chest. Each time your chest nears the screen counts."
        ObjectiveType.SQUATS -> if (mode == TrackMode.CAMERA) "Stand side-on to the camera, 2–3 m away, full body in frame."
        else "Put the phone in your front trouser pocket. Squat to parallel; stand fully between reps."
        ObjectiveType.SITUPS -> if (mode == TrackMode.CAMERA) "Lie side-on to the camera, knees bent, whole body in frame."
        else "Hold the phone flat against your chest with both hands. Sit up past halfway each rep."
        ObjectiveType.PLANK_SEC -> "Hold a plank. The timer only runs while you hold still" + if (mode == TrackMode.CAMERA) " and your body is straight." else " (phone on your back or in a pocket)."
        ObjectiveType.MEDITATE_MIN -> "Sit comfortably with the phone in your lap. Breathe. The timer runs while you are still."
        else -> ""
    }

    fun start() {
        val s = _state.value
        counter = when (s.type) {
            ObjectiveType.PUSHUPS -> if (s.mode == TrackMode.CAMERA) Profiles.pushupPose() else ProximityRepCounter()
            ObjectiveType.SQUATS -> if (s.mode == TrackMode.CAMERA) Profiles.squatPose() else Profiles.squatAccel()
            ObjectiveType.SITUPS -> if (s.mode == TrackMode.CAMERA) Profiles.situpPose() else Profiles.situpTilt()
            else -> null
        }
        stillness.reset()
        timedAccum = 0
        timedStart = SystemClock.elapsedRealtime()
        lastTick = timedStart
        _state.value = s.copy(running = true, count = 0, seconds = 0, depth = 0f)
        registerSensors(s)
    }

    fun stop(): WorkoutState {
        unregisterSensors()
        val s = _state.value.copy(running = false)
        _state.value = s
        return s
    }

    private fun registerSensors(s: WorkoutState) {
        val sm = context.getSystemService(SensorManager::class.java) ?: return
        val needsAccel = s.mode == TrackMode.SENSOR || s.type.isTimed
        if (s.mode == TrackMode.SENSOR && s.type == ObjectiveType.PUSHUPS) {
            sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        if (needsAccel) {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    private fun unregisterSensors() {
        context.getSystemService(SensorManager::class.java)?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val s = _state.value
        if (!s.running) return
        val t = SystemClock.elapsedRealtime()
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val c = counter ?: return
                if (c.feed(event.values[0].coerceAtMost(event.sensor.maximumRange), t)) rep(c.count)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                val mag = Motion.magnitude(x, y, z)
                val still = stillness.feed(mag, t)
                if (s.type.isTimed) {
                    tickTimed(still && (s.mode == TrackMode.SENSOR || s.bodyVisible), t)
                    return
                }
                val c = counter ?: return
                val signal = if (s.type == ObjectiveType.SITUPS) Motion.tiltDegrees(x, y, z) else mag
                val hit = c.feed(signal, t)
                val depth = (c as? HysteresisRepCounter)?.depth ?: 0f
                if (hit) rep(c.count) else if (kotlin.math.abs(depth - s.depth) > 0.05f) _state.value = s.copy(depth = depth)
            }
        }
    }

    private fun tickTimed(holding: Boolean, t: Long) {
        val s = _state.value
        if (holding) timedAccum += (t - lastTick).coerceAtMost(500)
        lastTick = t
        val seconds = if (s.type == ObjectiveType.MEDITATE_MIN) (timedAccum / 1000).toInt() else (timedAccum / 1000).toInt()
        if (seconds != s.seconds || holding != s.still) _state.value = s.copy(seconds = seconds, still = holding)
    }

    private fun rep(n: Int) {
        _state.value = _state.value.copy(count = n, depth = 0f)
        onRep?.invoke(n)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---- Camera --------------------------------------------------------------------------------

    fun poseAnalyzer(frontCamera: Boolean): ImageAnalysis.Analyzer {
        val d = detector ?: PoseDetection.getClient(
            PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build(),
        ).also { detector = it }
        return PoseAnalyzer(d, frontCamera) { pose, w, h -> onPose(pose, w, h, frontCamera) }
    }

    private fun onPose(pose: Pose, width: Int, height: Int, mirror: Boolean) {
        val s = _state.value
        val joints = pose.allPoseLandmarks.associate { lm ->
            val x = lm.position.x / width
            lm.landmarkType to Joint(if (mirror) 1f - x else x, lm.position.y / height, lm.inFrameLikelihood)
        }
        val angle = angleFor(s.type, pose)
        val visible = angle != null
        if (!s.running) {
            _state.value = s.copy(joints = joints, bodyVisible = visible)
            return
        }
        val t = SystemClock.elapsedRealtime()
        if (s.type.isTimed) {
            // Plank in camera mode: body straight (shoulder–hip–ankle) and holding still.
            val straight = bodyLine(pose)?.let { it >= 155f } ?: false
            _state.value = s.copy(joints = joints, bodyVisible = visible || straight)
            tickTimed(straight, t)
            return
        }
        val c = counter ?: return
        var next = s.copy(joints = joints, bodyVisible = visible)
        if (angle != null && c.feed(angle, t)) {
            next = next.copy(count = c.count, depth = 0f)
            _state.value = next
            onRep?.invoke(c.count)
            return
        }
        next = next.copy(depth = (c as? HysteresisRepCounter)?.depth ?: 0f)
        _state.value = next
    }

    /** The joint angle that defines this exercise, from whichever body side the camera sees better. */
    private fun angleFor(type: ObjectiveType, pose: Pose): Float? {
        val (a, b, c) = when (type) {
            ObjectiveType.PUSHUPS -> Triple(PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER, PoseLandmark.LEFT_ELBOW to PoseLandmark.RIGHT_ELBOW, PoseLandmark.LEFT_WRIST to PoseLandmark.RIGHT_WRIST)
            ObjectiveType.SQUATS -> Triple(PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP, PoseLandmark.LEFT_KNEE to PoseLandmark.RIGHT_KNEE, PoseLandmark.LEFT_ANKLE to PoseLandmark.RIGHT_ANKLE)
            ObjectiveType.SITUPS -> Triple(PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER, PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP, PoseLandmark.LEFT_KNEE to PoseLandmark.RIGHT_KNEE)
            else -> return null
        }
        return bestSideAngle(pose, a, b, c)
    }

    private fun bodyLine(pose: Pose): Float? = bestSideAngle(
        pose,
        PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_ANKLE to PoseLandmark.RIGHT_ANKLE,
    )

    private fun bestSideAngle(pose: Pose, a: Pair<Int, Int>, b: Pair<Int, Int>, c: Pair<Int, Int>): Float? {
        fun side(i: Int): Pair<Float, Float>? {
            val pa = pose.getPoseLandmark(if (i == 0) a.first else a.second) ?: return null
            val pb = pose.getPoseLandmark(if (i == 0) b.first else b.second) ?: return null
            val pc = pose.getPoseLandmark(if (i == 0) c.first else c.second) ?: return null
            val conf = minOf(pa.inFrameLikelihood, pb.inFrameLikelihood, pc.inFrameLikelihood)
            return Motion.jointAngle(pa.position.x, pa.position.y, pb.position.x, pb.position.y, pc.position.x, pc.position.y) to conf
        }
        val best = listOfNotNull(side(0), side(1)).maxByOrNull { it.second } ?: return null
        return if (best.second >= 0.5f) best.first else null
    }

    fun release() {
        stop()
        detector?.close()
        detector = null
    }
}

private class PoseAnalyzer(
    private val detector: PoseDetector,
    private val front: Boolean,
    private val onPose: (Pose, Int, Int) -> Unit,
) : ImageAnalysis.Analyzer {
    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (media == null) {
            image.close()
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(media, rotation)
        val (w, h) = if (rotation % 180 == 0) image.width to image.height else image.height to image.width
        detector.process(input)
            .addOnSuccessListener { onPose(it, w, h) }
            .addOnCompleteListener { image.close() }
    }
}
