package dev.aoidoki.arise.ui.screens

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.pose.PoseLandmark
import dev.aoidoki.arise.Perm
import dev.aoidoki.arise.Perms
import dev.aoidoki.arise.engine.Game
import dev.aoidoki.arise.engine.ObjectiveType
import dev.aoidoki.arise.sense.TrackMode
import dev.aoidoki.arise.sense.WorkoutController
import dev.aoidoki.arise.sense.WorkoutState
import dev.aoidoki.arise.ui.UiState
import dev.aoidoki.arise.ui.components.Chip
import dev.aoidoki.arise.ui.components.GlowButton
import dev.aoidoki.arise.ui.components.StatBar
import dev.aoidoki.arise.ui.components.SystemWindow
import dev.aoidoki.arise.ui.components.staticMode
import dev.aoidoki.arise.ui.theme.LocalSys
import dev.aoidoki.arise.ui.theme.Palette
import dev.aoidoki.arise.ui.theme.SysType
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

private val trainable = listOf(ObjectiveType.PUSHUPS, ObjectiveType.SQUATS, ObjectiveType.SITUPS, ObjectiveType.PLANK_SEC, ObjectiveType.MEDITATE_MIN)

/** Assessment measurements: which exercise, and its fixed duration (null = until you stop). */
private val assessmentPlan = listOf(
    ObjectiveType.PUSHUPS to null,
    ObjectiveType.SQUATS to 120,
    ObjectiveType.SITUPS to 60,
    ObjectiveType.PLANK_SEC to null,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrainScreen(
    state: UiState,
    perms: Perms,
    initialType: ObjectiveType,
    onSet: (ObjectiveType, Int) -> Unit,
    onAssessment: (Game.AssessmentResult) -> Unit,
    say: (String) -> Unit,
) {
    val sys = LocalSys.current
    val context = LocalContext.current
    val still = staticMode()
    val controller = remember { WorkoutController(context) }
    val ws by controller.state.collectAsState()
    var type by remember { mutableStateOf(if (initialType in trainable) initialType else ObjectiveType.PUSHUPS) }
    var mode by remember { mutableStateOf(if (perms.camera) TrackMode.CAMERA else TrackMode.SENSOR) }
    var front by remember { mutableStateOf(true) }
    var assessing by remember { mutableStateOf<Int?>(null) }
    var limit by remember { mutableStateOf<Int?>(null) }
    var elapsed by remember { mutableStateOf(0) }
    val results = remember { mutableStateMapOf<ObjectiveType, Int>() }

    fun effectiveMode(t: ObjectiveType, m: TrackMode) = if (t == ObjectiveType.MEDITATE_MIN) TrackMode.SENSOR else m
    LaunchedEffect(type, mode) { controller.configure(type, effectiveMode(type, mode)) }
    DisposableEffect(Unit) {
        controller.onRep = { n -> if (n % 10 == 0) say("$n") }
        onDispose { controller.release() }
    }

    fun finish() {
        val s = controller.stop()
        val amount = when (s.type) {
            ObjectiveType.PLANK_SEC -> s.seconds
            ObjectiveType.MEDITATE_MIN -> s.seconds / 60
            else -> s.count
        }
        val a = assessing
        if (a != null) {
            results[s.type] = amount
            assessing = null
        }
        limit = null
        if (amount > 0) onSet(s.type, amount)
    }

    // Timed assessment steps stop themselves.
    LaunchedEffect(ws.running, limit) {
        elapsed = 0
        if (!ws.running) return@LaunchedEffect
        while (true) {
            delay(1000)
            elapsed++
            val l = limit
            if (l != null && elapsed >= l) {
                say("Time.")
                finish()
                break
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        SystemWindow(title = if (assessing != null) "Assessment" else "Training") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                trainable.forEach { t -> Chip(t.label, t == type, { if (!ws.running) type = t }) }
            }
            Spacer(Modifier.height(10.dp))
            if (type != ObjectiveType.MEDITATE_MIN) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("TRACK BY", style = SysType.Small.copy(color = sys.muted, letterSpacing = 2.sp))
                    TrackMode.entries.forEach { m ->
                        Chip(m.label, m == mode, {
                            if (!ws.running) {
                                if (m == TrackMode.CAMERA && !perms.camera) perms.request(Perm.CAMERA)
                                mode = m
                            }
                        })
                    }
                    if (mode == TrackMode.CAMERA) Chip(if (front) "Front" else "Back", false, { if (!ws.running) front = !front })
                }
                Spacer(Modifier.height(10.dp))
            }
            Text(ws.hint, style = SysType.Small.copy(color = sys.muted))
        }

        if (effectiveMode(type, mode) == TrackMode.CAMERA) {
            SystemWindow(animate = false) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, sys.accent.copy(alpha = 0.4f), RoundedCornerShape(4.dp)),
                ) {
                    if (perms.camera && !still) CameraFeed(controller, front)
                    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (perms.camera) "" else "Camera permission needed", style = SysType.Small.copy(color = sys.muted))
                    }
                    Skeleton(ws)
                    if (!ws.bodyVisible && perms.camera) {
                        Text(
                            "BODY NOT DETECTED", style = SysType.Label.copy(color = Palette.Gold),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(8.dp),
                        )
                    }
                }
            }
        }

        SystemWindow(accent = if (ws.running) Palette.Cyan else sys.accent) {
            val big = when (type) {
                ObjectiveType.PLANK_SEC -> "%d:%02d".format(ws.seconds / 60, ws.seconds % 60)
                ObjectiveType.MEDITATE_MIN -> "%d:%02d".format(ws.seconds / 60, ws.seconds % 60)
                else -> ws.count.toString()
            }
            Text(big, style = SysType.Huge.copy(color = sys.text, fontSize = 88.sp), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(
                when {
                    !ws.running -> "READY"
                    type.isTimed -> if (ws.still) "HOLD" else "PAUSED — HOLD STILL"
                    else -> "REPS"
                },
                style = SysType.Label.copy(color = if (type.isTimed && ws.running && !ws.still) Palette.Gold else sys.muted, letterSpacing = 6.sp),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            limit?.let {
                Spacer(Modifier.height(6.dp))
                Text("${(it - elapsed).coerceAtLeast(0)}s left", style = SysType.Mono.copy(color = Palette.Gold), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            if (type.isRep && ws.running) {
                Spacer(Modifier.height(10.dp))
                StatBar("Depth", (ws.depth * 100).toInt(), 100, Palette.Cyan, height = 6.dp, showNumbers = false)
            }
            Spacer(Modifier.height(14.dp))
            if (!ws.running) {
                GlowButton("Start", { controller.start(); say("Begin.") }, Modifier.fillMaxWidth(), filled = true)
            } else {
                GlowButton("Finish set", { finish(); say("Set recorded.") }, Modifier.fillMaxWidth(), filled = true, accent = Palette.Good)
            }
            val target = state.daily?.objectives?.firstOrNull { it.type == type }
            if (target != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Daily Quest: ${type.format(target.progress)} / ${type.format(target.target)} ${type.unit}",
                    style = SysType.Small.copy(color = sys.muted), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        AssessmentWindow(
            state = state,
            results = results,
            running = ws.running,
            onMeasure = { i ->
                val (t, secs) = assessmentPlan[i]
                type = t
                assessing = i
                limit = secs
                controller.configure(t, effectiveMode(t, mode))
                controller.start()
                say(if (secs != null) "Assessment. ${t.label}. ${secs} seconds. Begin." else "Assessment. ${t.label}. As many as you can. Begin.")
            },
            onSubmit = {
                onAssessment(
                    Game.AssessmentResult(
                        pushups = results[ObjectiveType.PUSHUPS],
                        squats = results[ObjectiveType.SQUATS],
                        situps = results[ObjectiveType.SITUPS],
                        plankSec = results[ObjectiveType.PLANK_SEC],
                    ),
                )
                results.clear()
            },
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AssessmentWindow(state: UiState, results: Map<ObjectiveType, Int>, running: Boolean, onMeasure: (Int) -> Unit, onSubmit: () -> Unit) {
    val sys = LocalSys.current
    val p = state.player ?: return
    val due = p.baselines.assessedDay < 0 || state.today - p.baselines.assessedDay >= 7
    SystemWindow(title = if (p.baselines.assessedDay < 0) "Assessment Required" else "Re-evaluation", accent = if (due) Palette.Gold else sys.accent, icon = "?") {
        Text(
            if (p.baselines.assessedDay < 0) "Measure your real limits so the System can calibrate your quests. Each test is counted by the tracker."
            else if (due) "A week has passed. Measure again — real improvement becomes stat growth."
            else "Last measured ${state.today - p.baselines.assessedDay} day(s) ago. Re-evaluation opens weekly.",
            style = SysType.Small.copy(color = sys.muted),
        )
        Spacer(Modifier.height(10.dp))
        assessmentPlan.forEachIndexed { i, (t, secs) ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.label.uppercase() + if (secs != null) " · ${secs}s" else " · max", style = SysType.Label.copy(color = sys.text))
                    val prev = when (t) {
                        ObjectiveType.PUSHUPS -> p.baselines.pushups
                        ObjectiveType.SQUATS -> p.baselines.squats
                        ObjectiveType.SITUPS -> p.baselines.situps
                        else -> p.baselines.plankSec
                    }
                    Text(
                        results[t]?.let { "Measured: ${t.format(it)}" } ?: "Previous: ${if (prev > 0) t.format(prev) else "—"}",
                        style = SysType.Small.copy(color = if (results[t] != null) Palette.Good else sys.muted),
                    )
                }
                GlowButton(if (results[t] != null) "Redo" else "Measure", { onMeasure(i) }, enabled = !running && due)
            }
        }
        if (results.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            GlowButton("Submit to the System", onSubmit, Modifier.fillMaxWidth(), filled = true, accent = Palette.Gold, enabled = !running)
        }
    }
}

@Composable
private fun CameraFeed(controller: WorkoutController, front: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    DisposableEffect(front) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, controller.poseAnalyzer(front)) }
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            runCatching {
                provider?.unbindAll()
                provider?.bindToLifecycle(owner, selector, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView({ previewView }, Modifier.fillMaxSize())
}

private val bones = listOf(
    PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
    PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
    PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
    PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
    PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
    PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
    PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE,
)

/** The body as the System sees it, drawn in System blue over the camera. */
@Composable
private fun Skeleton(ws: WorkoutState) {
    val accent = LocalSys.current.accentSoft
    Canvas(Modifier.fillMaxSize()) {
        // PreviewView FIT_CENTER letterboxes a 3:4 frame into this 3:4 box, so normalised coords map directly.
        fun pt(i: Int): Offset? = ws.joints[i]?.takeIf { it.confidence > 0.5f }?.let { Offset(it.x * size.width, it.y * size.height) }
        for ((a, b) in bones) {
            val pa = pt(a) ?: continue
            val pb = pt(b) ?: continue
            drawLine(accent.copy(alpha = 0.25f), pa, pb, 10.dp.toPx())
            drawLine(accent, pa, pb, 3.dp.toPx())
        }
        ws.joints.values.filter { it.confidence > 0.5f }.forEach { j ->
            val o = Offset(j.x * size.width, j.y * size.height)
            drawCircle(Color.White, 3.5.dp.toPx(), o)
            drawCircle(accent.copy(alpha = 0.3f), 8.dp.toPx(), o)
        }
    }
}
