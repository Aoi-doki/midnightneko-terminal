package dev.aoidoki.arise.lock

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.aoidoki.arise.ui.components.ButtonKind
import dev.aoidoki.arise.ui.components.Pane
import dev.aoidoki.arise.ui.components.SysButton
import dev.aoidoki.arise.ui.components.SysField
import dev.aoidoki.arise.ui.components.SystemBackground
import dev.aoidoki.arise.ui.components.staticMode
import dev.aoidoki.arise.ui.screens.PenaltyPane
import dev.aoidoki.arise.ui.theme.AriseTheme
import dev.aoidoki.arise.ui.theme.Fonts
import dev.aoidoki.arise.ui.theme.LocalSys
import dev.aoidoki.arise.ui.theme.Palette
import dev.aoidoki.arise.ui.theme.SysType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** An app the player may still open while locked. */
data class LockApp(val pkg: String, val label: String)

/** Callbacks from the lock screen back to the service. */
interface LockActions {
    fun phone()
    fun messages()
    fun openSystem()
    fun open(pkg: String)
    suspend fun override(input: String): PenaltyLock.Attempt
}

/** The PENALTY ZONE lock screen drawn over blocked apps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LockScreen(state: LockState, apps: List<LockApp>, actions: LockActions, clock: () -> Long = System::currentTimeMillis, startOverride: Boolean = false) {
    AriseTheme(penalty = !state.night) {
        val sys = LocalSys.current
        var now by remember { mutableLongStateOf(clock()) }
        val still = staticMode()
        LaunchedEffect(Unit) {
            while (!still) {
                now = clock()
                delay(1000)
            }
        }
        SystemBackground {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (state.night) "LIGHTS OUT" else "PENALTY ZONE", style = SysType.Label.copy(color = sys.accent))
                Text(
                    when {
                        state.test -> "Test lock"
                        state.night -> "Rest is training."
                        else -> "This phone is locked."
                    },
                    style = SysType.Title.copy(color = sys.text),
                )
                Text(
                    when {
                        state.test -> "This is what a Penalty Zone looks like. It lifts in 30 seconds, or with your override code."
                        state.night -> "The Night Lock lifts in the morning. Put the phone down, Player."
                        else -> "Walk. The System is watching. The lock lifts the moment the Penalty Quest is complete."
                    },
                    style = SysType.Body.copy(color = sys.muted),
                )
                state.quest?.let { PenaltyPane(it, now) }
                if (state.night) NightPane(state.until, now)
                Pane(label = "Still available") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SysButton("Phone", actions::phone, Modifier.weight(1f), kind = ButtonKind.SECONDARY, accent = Palette.White)
                        SysButton("Messages", actions::messages, Modifier.weight(1f), kind = ButtonKind.SECONDARY, accent = Palette.White)
                        SysButton("SYSTEM", actions::openSystem, Modifier.weight(1f), kind = ButtonKind.SECONDARY, accent = Palette.White)
                    }
                    if (apps.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            apps.forEach { a -> SysButton(a.label, { actions.open(a.pkg) }, kind = ButtonKind.SECONDARY) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Emergency calls always work, from here and from the lock screen.", style = SysType.Small.copy(color = sys.muted))
                }
                OverrideBox(actions, startOverride, now)
            }
        }
    }
}

@Composable
private fun NightPane(until: Long, now: Long) {
    val secs = ((until - now).coerceAtLeast(0)) / 1000
    Pane(label = "Night Lock", accent = Palette.Violet, emphasis = true) {
        Text("Lifts in", style = SysType.Label.copy(color = Palette.Silver))
        Text(
            "%02d:%02d:%02d".format(secs / 3600, (secs / 60) % 60, secs % 60),
            style = SysType.Huge.copy(color = Palette.Violet, fontSize = 44.sp, fontFamily = Fonts.Mono),
        )
        Spacer(Modifier.height(4.dp))
        Text("Alarms, calls and messages still work.", style = SysType.Small.copy(color = Palette.Silver))
    }
}

@Composable
private fun OverrideBox(actions: LockActions, startOpen: Boolean, now: Long) {
    val sys = LocalSys.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(startOpen) }
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var waitUntil by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    if (!open) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(
                "Override",
                style = SysType.Label.copy(color = sys.muted),
                modifier = Modifier.clickable { open = true }.padding(vertical = 8.dp),
            )
        }
        return
    }
    Pane(label = "Override", accent = Palette.Silver) {
        Text(
            "For emergencies. The lock lifts (tonight only, for the Night Lock), any Penalty Quest stays, and the System records it.",
            style = SysType.Small.copy(color = sys.muted),
        )
        Spacer(Modifier.height(10.dp))
        SysField("Override or recovery code", code, { code = it.take(24); message = null }, secret = true)
        val waiting = now < waitUntil
        message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = SysType.Small.copy(color = sys.accent))
        }
        if (waiting) Text("Try again in ${(waitUntil - now + 999) / 1000} s.", style = SysType.Small.copy(color = sys.accent))
        Spacer(Modifier.height(10.dp))
        SysButton(
            "Unlock",
            {
                busy = true
                scope.launch {
                    when (val a = actions.override(code)) {
                        PenaltyLock.Attempt.Accepted -> message = null
                        is PenaltyLock.Attempt.Wrong -> message = "Wrong code. ${a.triesBeforeWait} more before a wait."
                        is PenaltyLock.Attempt.Wait -> { message = "Too many wrong codes."; waitUntil = a.until }
                    }
                    code = ""
                    busy = false
                }
            },
            Modifier.fillMaxWidth(),
            enabled = code.isNotBlank() && !busy && !waiting,
        )
    }
}

/** A window root that can host Compose outside an Activity (the accessibility overlay). */
class LockOverlayView(context: Context) : FrameLayout(context), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
    }

    fun setContent(content: @Composable () -> Unit) {
        addView(ComposeView(context).apply { setContent(content) })
    }

    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
