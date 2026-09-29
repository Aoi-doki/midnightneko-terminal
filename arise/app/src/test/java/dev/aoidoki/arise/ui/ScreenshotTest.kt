package dev.aoidoki.arise.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.aoidoki.arise.Perms
import dev.aoidoki.arise.data.DayLogEntity
import dev.aoidoki.arise.data.EventEntity
import dev.aoidoki.arise.data.ObjectiveEntity
import dev.aoidoki.arise.data.PlayerEntity
import dev.aoidoki.arise.data.QuestEntity
import dev.aoidoki.arise.data.QuestWithObjectives
import dev.aoidoki.arise.data.WeightEntity
import dev.aoidoki.arise.engine.Baselines
import dev.aoidoki.arise.engine.Limitations
import dev.aoidoki.arise.engine.ObjectiveType
import dev.aoidoki.arise.engine.Progression
import dev.aoidoki.arise.engine.QuestKind
import dev.aoidoki.arise.engine.Rank
import dev.aoidoki.arise.engine.RankEngine
import dev.aoidoki.arise.engine.SystemEvent
import dev.aoidoki.arise.ui.components.StaticUi
import dev.aoidoki.arise.ui.components.SystemBackground
import dev.aoidoki.arise.engine.Item
import dev.aoidoki.arise.ui.screens.IntroStep
import dev.aoidoki.arise.ui.screens.InventoryScreen
import dev.aoidoki.arise.ui.screens.LogScreen
import dev.aoidoki.arise.ui.screens.ProfileForm
import dev.aoidoki.arise.ui.screens.QuestScreen
import dev.aoidoki.arise.ui.screens.RankScreen
import dev.aoidoki.arise.ui.screens.StatusScreen
import dev.aoidoki.arise.ui.screens.TrainScreen
import dev.aoidoki.arise.ui.screens.RegistrationStep
import dev.aoidoki.arise.ui.theme.AriseTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Renders every screen on the JVM (no emulator) to PNGs under src/test/screenshots — run
 * `./gradlew recordRoborazziDebug`. CI uploads them as an artifact so the look can be checked on
 * every change.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 9, 29).toEpochDay()
    private val now = LocalDate.ofEpochDay(today).atTime(19, 42).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val player = Progression.fullRestore(
        PlayerEntity(
            name = "Jinwoo", age = 24, heightCm = 180.0, weightKg = 88.4, startWeightKg = 92.0, goalWeightKg = 78.0,
            level = 7, xp = 410, freePoints = 3, str = 14, agi = 12, vit = 13, sen = 11, intel = 12,
            rank = Rank.D, title = "Wolf Slayer", gold = 1240, titles = listOf("Wolf Slayer", "The First Step"),
            streak = 9, bestStreak = 9, questsCompleted = 12, questsFailed = 2, fatigue = 35,
            baselines = Baselines(pushups = 18, squats = 34, situps = 20, plankSec = 55, avgSteps = 6500, assessedDay = today - 3),
            limitations = Limitations(kneeCare = true, noJumping = true, notes = listOf("Knee care: squat volume halved, no jumping.")),
            assessment = "Player Jinwoo, age 24. Body classification: overweight (BMI 27.3). Reported capability: 18 push-ups, 34 squats. The System has noted weakness in the left knee.",
            awakenedDay = today - 14,
        ),
    ).copy(hp = 260, mp = 90)

    private fun quest(kind: QuestKind, title: String, flavor: String, vararg objectives: Triple<ObjectiveType, Int, Int>, startsAt: Long = 0, deadline: Long = Long.MAX_VALUE) =
        QuestWithObjectives(
            QuestEntity(id = kind.ordinal + 1L, day = today, kind = kind, title = title, flavor = flavor, xpReward = 175, createdAt = 0, startsAt = startsAt, deadline = deadline),
            objectives.mapIndexed { i, (t, target, progress) -> ObjectiveEntity(id = i + 1L, questId = kind.ordinal + 1L, type = t, target = target, progress = progress) },
        )

    private val daily = quest(
        QuestKind.DAILY, "Daily Quest: Preparing to Become Stronger", "The Daily Quest has arrived. Complete every objective before midnight.",
        Triple(ObjectiveType.STEPS, 8000, 6120), Triple(ObjectiveType.PUSHUPS, 40, 40), Triple(ObjectiveType.SITUPS, 40, 25),
        Triple(ObjectiveType.SQUATS, 25, 0), Triple(ObjectiveType.BRISK_MIN, 25, 12),
    )

    private val state = UiState(
        loaded = true,
        player = player,
        quests = listOf(daily),
        todayLog = DayLogEntity(today, steps = 6120),
        days = (0 until 14).map { DayLogEntity(today - it, steps = 5000 + it * 300, completion = if (it % 5 == 3) 0.6f else 1f, result = if (it == 0) "OPEN" else if (it % 5 == 3) "FAILED" else "CLEARED") },
        weights = listOf(92.0, 91.6, 91.1, 90.4, 90.6, 89.7, 89.2, 88.4).mapIndexed { i, kg -> WeightEntity(time = now - (7 - i) * 2 * 86_400_000L, kg = kg) },
        log = listOf(
            EventEntity(time = now - 3_600_000, type = "QUEST_PROGRESS", title = "Quest Progress", message = "Daily Quest 75% complete."),
            EventEntity(time = now - 86_400_000, type = "LEVEL_UP", title = "Level Up!", message = "You have leveled up! Level 7. +3 ability points."),
            EventEntity(time = now - 2 * 86_400_000, type = "TITLE", title = "Title Acquired", message = "You have acquired the title \"Wolf Slayer\"."),
            EventEntity(time = now - 4 * 86_400_000, type = "PENALTY_STARTED", title = "Penalty Zone", message = "You have failed to complete the Daily Quest."),
        ),
        power = RankEngine.Power(body = 58, cardio = 46, strength = 41, discipline = 62),
        now = now,
    )

    private val perms = Perms {}.apply { activity = true; notifications = true; camera = false; health = true; healthAvailable = true }

    @Before
    fun still() {
        StaticUi.enabled = true
    }

    private fun shot(name: String, penalty: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            AriseTheme(penalty = penalty) {
                SystemBackground { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun intro() = shot("01_awakening") { IntroStep {} }

    @Test
    fun registration() = shot("02_registration") {
        RegistrationStep(ProfileForm(name = "Jinwoo", age = "24", height = "180", weight = "92", goal = "78")) {}
    }

    @Test
    fun registrationWords() = shot("03_own_words") {
        RegistrationStep(
            ProfileForm(
                name = "Jinwoo", age = "24", height = "180", weight = "92", goal = "78",
                about = "Desk job, used to play football. Left knee clicks when I squat deep. I can do about 15 push-ups.",
                stats = listOf("max push-ups" to "15", "plank" to "0:50", "left knee" to "old ACL tear"),
            ),
        ) {}
    }

    @Test
    fun inventory() = shot("16_inventory") {
        InventoryScreen(state.copy(inventory = mapOf("healing_potion" to 3, "mana_crystal" to 1, "streak_ward" to 1)), {}, {})
    }

    @Test
    fun shop() = shot("17_shop") { InventoryScreen(state, {}, {}, startInShop = true) }

    @Test
    fun itemCard() = shot("18_item_card") {
        InventoryScreen(state.copy(inventory = mapOf("elixir_of_life" to 1)), {}, {}, openItem = Item.ELIXIR_OF_LIFE)
    }

    @Test
    fun status() = shot("10_status") { StatusScreen(state, {}, {}, {}) }

    @Test
    fun questScreen() = shot("11_quest") { QuestScreen(state, {}, { _, _ -> }, {}, {}, {}, trackerGranted = true) }

    @Test
    fun penaltyZone() {
        val penalty = quest(
            QuestKind.PENALTY, "Penalty Quest: Survival",
            "You have failed to complete the Daily Quest. You will be transported to the Penalty Zone. Survive: walk the distance before the time limit expires.",
            Triple(ObjectiveType.STEPS, 4500, 1730), startsAt = now - 3_600_000, deadline = now + 5 * 3_600_000,
        )
        shot("12_penalty_zone", penalty = true) { QuestScreen(state.copy(quests = listOf(penalty, daily)), {}, { _, _ -> }, {}, {}, {}, trackerGranted = true) }
    }

    @Test
    fun train() = shot("13_train") { TrainScreen(state, perms, ObjectiveType.PUSHUPS, { _, _ -> }, {}, {}) }

    @Test
    fun rank() = shot("14_rank") { RankScreen(state.copy(trialRank = Rank.C), {}, {}) }

    @Test
    fun log() = shot("15_log") { LogScreen(state) {} }

    @Test
    fun levelUpPopup() = shot("20_popup_level_up") {
        EventPopup(SystemEvent(SystemEvent.Type.LEVEL_UP, "Level Up!", "You have leveled up! Level 8. +3 ability points."), onDismiss = {})
    }

    @Test
    fun penaltyPopup() = shot("21_popup_penalty", penalty = true) {
        EventPopup(
            SystemEvent(
                SystemEvent.Type.PENALTY_STARTED, "Penalty Zone",
                "You have failed to complete the Daily Quest. The Penalty Quest \"Survival\" opens at 07:00 and lasts 6 hours.",
            ),
            onDismiss = {},
        )
    }
}
