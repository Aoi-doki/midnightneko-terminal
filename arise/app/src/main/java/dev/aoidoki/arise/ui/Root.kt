package dev.aoidoki.arise.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aoidoki.arise.Perms
import dev.aoidoki.arise.engine.SystemEvent
import dev.aoidoki.arise.ui.components.GlowButton
import dev.aoidoki.arise.ui.components.SystemBackground
import dev.aoidoki.arise.ui.components.SystemWindow
import dev.aoidoki.arise.ui.components.TypewriterText
import dev.aoidoki.arise.ui.screens.AwakeningScreen
import dev.aoidoki.arise.ui.screens.InventoryScreen
import dev.aoidoki.arise.ui.screens.LogScreen
import dev.aoidoki.arise.ui.screens.QuestScreen
import dev.aoidoki.arise.ui.screens.RankScreen
import dev.aoidoki.arise.ui.screens.SettingsScreen
import dev.aoidoki.arise.ui.screens.StatusScreen
import dev.aoidoki.arise.ui.screens.TrainScreen
import dev.aoidoki.arise.ui.theme.AriseTheme
import dev.aoidoki.arise.ui.theme.LocalSys
import dev.aoidoki.arise.ui.theme.Palette
import dev.aoidoki.arise.ui.theme.SysType
import dev.aoidoki.arise.engine.ObjectiveType
import kotlinx.coroutines.delay

enum class Tab(val label: String, val inBar: Boolean = true) {
    QUEST("Quest"), STATUS("Status"), TRAIN("Train"), ITEMS("Items"), RANK("Rank"),
    LOG("History", inBar = false), SETTINGS("Settings", inBar = false),
}

@Composable
fun AriseRoot(vm: MainViewModel, perms: Perms) {
    val state by vm.state.collectAsState()
    val popups by vm.popups.collectAsState()
    AriseTheme(penalty = state.inPenaltyZone) {
        SystemBackground {
            when {
                !state.loaded -> Unit
                state.player == null -> AwakeningScreen(vm, state, perms)
                else -> MainShell(vm, state, perms)
            }
            popups.firstOrNull()?.let { EventPopup(it, onDismiss = vm::dismissPopup, onShown = { vm.say(it.speech) }, stillSpeaking = vm::isSpeaking) }
            if (state.player != null && !state.settings.disclaimerAccepted) Disclaimer(vm::acceptDisclaimer)
        }
    }
}

@Composable
fun MainShell(vm: MainViewModel, state: UiState, perms: Perms) {
    // The quest is the reason to open the app, so it's home.
    var tab by rememberSaveable { mutableIntStateOf(Tab.QUEST.ordinal) }
    var trainType by rememberSaveable { mutableIntStateOf(ObjectiveType.PUSHUPS.ordinal) }
    // Ask for step tracking and notifications once, in context, instead of in a permissions wall.
    LaunchedEffect(Unit) {
        if (!perms.activity) perms.request(dev.aoidoki.arise.Perm.ACTIVITY)
        else if (!perms.notifications) perms.request(dev.aoidoki.arise.Perm.NOTIFICATIONS)
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopBar(state, current = Tab.entries[tab], onHistory = { tab = Tab.LOG.ordinal }, onSettings = { tab = Tab.SETTINGS.ordinal })
        Box(Modifier.weight(1f)) {
            AnimatedContent(targetState = tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                when (Tab.entries[t]) {
                    Tab.QUEST -> QuestScreen(
                        state,
                        onTrain = { type -> trainType = type.ordinal; tab = Tab.TRAIN.ordinal },
                        onManual = vm::manual,
                        onRecovery = vm::recovery,
                        onReroll = vm::reroll,
                        onStartTracker = { perms.request(dev.aoidoki.arise.Perm.ACTIVITY) },
                        trackerGranted = perms.activity,
                    )
                    Tab.STATUS -> StatusScreen(state, onAllocate = vm::allocate, onTitle = vm::equipTitle, onGoQuest = { tab = Tab.QUEST.ordinal })
                    Tab.TRAIN -> TrainScreen(
                        state, perms,
                        initialType = ObjectiveType.entries[trainType],
                        onSet = vm::addSet,
                        onAssessment = vm::assessment,
                        say = { text -> vm.say(text) },
                    )
                    Tab.ITEMS -> InventoryScreen(state, onBuy = vm::buy, onUse = vm::use)
                    Tab.RANK -> RankScreen(state, onAcceptTrial = vm::acceptTrial, onEvaluate = vm::evaluate)
                    Tab.LOG -> LogScreen(state, onAddWeight = vm::addWeight)
                    Tab.SETTINGS -> SettingsScreen(vm, state, perms)
                }
            }
        }
        BottomBar(tab) { tab = it }
    }
}

@Composable
private fun TopBar(state: UiState, current: Tab, onHistory: () -> Unit, onSettings: () -> Unit) {
    val sys = LocalSys.current
    val p = state.player ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (sys.penalty) "PENALTY ZONE" else "SYSTEM · ${current.label.uppercase()}",
                style = SysType.Label.copy(color = sys.accent),
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(p.name, style = SysType.Header.copy(color = sys.text))
                Text("  Lv ${p.level} · ${p.rank.label}-Rank", style = SysType.Num.copy(color = sys.muted, fontSize = 12.sp))
            }
        }
        Text("G ", style = SysType.Num.copy(color = Palette.Gold, fontSize = 12.sp))
        Text("%,d".format(p.gold), style = SysType.Num.copy(color = sys.text, fontSize = 12.sp))
        Spacer(Modifier.width(6.dp))
        IconSlot(Icons.Outlined.History, "History", current == Tab.LOG, onHistory)
        IconSlot(Icons.Outlined.Settings, "Settings", current == Tab.SETTINGS, onSettings)
    }
}

@Composable
private fun IconSlot(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val sys = LocalSys.current
    Box(
        Modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = if (on) sys.accent else sys.muted, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val sys = LocalSys.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(sys.background)
            .drawBehind { drawLine(sys.hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(top = 4.dp, bottom = 6.dp),
    ) {
        Tab.entries.filter { it.inBar }.forEach { t ->
            val on = t.ordinal == selected
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(t.ordinal) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .width(18.dp)
                        .height(2.dp)
                        .background(if (on) sys.accent else Color.Transparent),
                )
                Spacer(Modifier.height(8.dp))
                Text(t.label, style = SysType.Small.copy(color = if (on) sys.text else Palette.Dim, fontWeight = FontWeight.Medium))
            }
        }
    }
}

@Composable
fun EventPopup(e: SystemEvent, onDismiss: () -> Unit, onShown: () -> Unit = {}, stillSpeaking: () -> Boolean = { false }) {
    val penalty = e.type in setOf(SystemEvent.Type.PENALTY_STARTED, SystemEvent.Type.PENALTY_FAILED, SystemEvent.Type.DEATH, SystemEvent.Type.WARNING)
    val accent = when {
        penalty -> Palette.Red
        e.type == SystemEvent.Type.LEVEL_UP || e.type == SystemEvent.Type.RANK_UP -> Palette.Gold
        e.type == SystemEvent.Type.TITLE -> Palette.Violet
        else -> LocalSys.current.accent
    }
    LaunchedEffect(e) {
        // Speak the instant the window opens, then auto-close, but never mid-sentence.
        onShown()
        delay(if (penalty) 12_000 else 7_000)
        var waited = 0
        while (stillSpeaking() && waited < 10_000) {
            delay(250)
            waited += 250
        }
        onDismiss()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        SystemWindow(Modifier.padding(24.dp), title = e.title, accent = accent) {
            TypewriterText(e.message, SysType.Body.copy(color = LocalSys.current.text), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            GlowButton("Confirm", onDismiss, Modifier.fillMaxWidth(), accent = accent)
        }
    }
}

@Composable
private fun Disclaimer(onAccept: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        SystemWindow(Modifier.padding(20.dp), title = "Notice") {
            Text(
                "The System is a game, not a doctor. It sets movement goals from your own numbers and keeps them inside safe limits, " +
                    "but it cannot see your health. Stop if anything hurts, and talk to a doctor before starting if you have a heart, " +
                    "joint or other medical condition, are pregnant, or have a history of eating disorders.\n\n" +
                    "The System will never ask you to eat less, skip meals or fast. Penalties are always extra movement.",
                style = SysType.Body.copy(color = LocalSys.current.text),
            )
            Spacer(Modifier.height(16.dp))
            GlowButton("I understand", onAccept, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun FadeIn(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) { content() }
}
