package dev.aoidoki.arise

import android.Manifest
import android.app.ActivityManager
import android.graphics.Bitmap
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.aoidoki.arise.data.CustomStatEntity
import dev.aoidoki.arise.engine.Game
import dev.aoidoki.arise.engine.ObjectiveType
import dev.aoidoki.arise.engine.QuestKind
import dev.aoidoki.arise.engine.QuestStatus
import dev.aoidoki.arise.sense.StepTrackerService
import dev.aoidoki.arise.ui.components.StaticUi
import dev.aoidoki.arise.voice.Chime
import dev.aoidoki.arise.voice.GhostFx
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end on a real (emulated) device: the app launches, a Player is registered, the Daily
 * Quest arrives, a forced two-day jump opens the Penalty Zone, the penalty is survived, the voice
 * pipeline runs, and the step tracker holds a foreground service. Screenshots of each stage are
 * saved for the CI artifact.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AriseApp
    private val graph get() = app.graph

    @Before
    fun setUp() {
        StaticUi.enabled = true
        runBlocking {
            graph.db.maintenance().wipe()
            graph.settings.setDisclaimer(true)
            graph.settings.setVoice(graph.settings.current().voice.copy(enabled = false))
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(app.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    private fun dismissPopups() {
        repeat(20) {
            if (compose.onAllNodesWithText("CONFIRM").fetchSemanticsNodes().isEmpty()) return
            compose.onAllNodesWithText("CONFIRM")[0].performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun theSystemRuns() {
        // 1. Awakening.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("ACCEPT").fetchSemanticsNodes().isNotEmpty() }
        screenshot("01_awakening")
        compose.onNodeWithText("ACCEPT").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("PLAYER REGISTRATION")).fetchSemanticsNodes().isNotEmpty() }
        screenshot("02_registration")

        runBlocking {
            graph.game.awaken(
                Game.Profile(
                    "Emulator", 28, 176.0, 90.0, 78.0, "Desk job. Bad left knee.",
                    listOf(CustomStatEntity(name = "max push-ups", value = "12")),
                ),
            )
        }

        // 2. The Status window and a Daily Quest.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("LEVEL").fetchSemanticsNodes().isNotEmpty() }
        dismissPopups()
        screenshot("03_status")
        val quest = runBlocking { graph.game.todayQuest() }
        assertNotNull(quest)
        assertTrue(quest!!.objectives.any { it.type == ObjectiveType.STEPS })
        assertTrue(runBlocking { graph.game.player() }!!.limitations.kneeCare)
        compose.onNodeWithText("QUEST").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("GOAL").fetchSemanticsNodes().isNotEmpty() }
        screenshot("04_quest")

        // 3. Two days pass with nothing done: the Penalty Zone.
        runBlocking {
            graph.game.shiftTime(2 * 86_400_000L)
            graph.game.settle()
        }
        val penalty = runBlocking { graph.game.activeQuests() }.single { it.quest.kind == QuestKind.PENALTY }
        runBlocking {
            // Jump into the window, then walk it off.
            val into = penalty.quest.startsAt + 60_000 - graph.time.now()
            if (into > 0) graph.game.shiftTime(into)
            graph.game.recordActivity(100, null, null)
            graph.game.recordActivity(100, null, null)
        }
        compose.waitForIdle()
        dismissPopups()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("PENALTY ZONE").fetchSemanticsNodes().isNotEmpty() }
        screenshot("05_penalty_zone")
        runBlocking {
            val target = penalty.objectives.single().target
            graph.game.recordActivity(100 + target + 10, null, null)
        }
        val cleared = runBlocking { graph.db.quests().byId(penalty.quest.id) }!!
        assertEquals(QuestStatus.COMPLETED, cleared.quest.status)
        dismissPopups()
        screenshot("06_survived")

        // 4. The voice DSP runs on-device (the TTS engine itself may not exist on the image).
        val processed = GhostFx.process(Chime.synth(22050), 22050)
        assertTrue(processed.none { it.isNaN() })
        graph.voice.say("Test.", graph.settings.let { runBlocking { it.current().voice.copy(enabled = true) } })
        Thread.sleep(3000)

        // 5. The System is watching: the step tracker runs as a foreground service.
        StepTrackerService.start(app)
        Thread.sleep(3000)
        val am = app.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        val service = am.getRunningServices(50).firstOrNull { it.service.className == StepTrackerService::class.java.name }
        assertNotNull("step tracker not running", service)
        assertTrue("step tracker not in foreground", service!!.foreground)
        screenshot("07_final")
    }
}
