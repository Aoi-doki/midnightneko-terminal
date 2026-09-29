package dev.aoidoki.arise

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
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
import dev.aoidoki.arise.lock.PenaltyLock
import dev.aoidoki.arise.lock.PenaltyLockService
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
            if (compose.onAllNodesWithText("Confirm").fetchSemanticsNodes().isEmpty()) return
            compose.onAllNodesWithText("Confirm")[0].performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun theSystemRuns() {
        // 1. Awakening.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Accept").fetchSemanticsNodes().isNotEmpty() }
        screenshot("01_awakening")
        compose.onNodeWithText("Accept").performClick()
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

        // 2. The Quest window opens first, then the Status window.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("DAILY QUEST").fetchSemanticsNodes().isNotEmpty() }
        dismissPopups()
        screenshot("03_quest")
        compose.onNodeWithText("Status").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("LEVEL").fetchSemanticsNodes().isNotEmpty() }
        screenshot("04_status")
        val quest = runBlocking { graph.game.todayQuest() }
        assertNotNull(quest)
        assertTrue(quest!!.objectives.any { it.type == ObjectiveType.STEPS })
        assertTrue(runBlocking { graph.game.player() }!!.limitations.kneeCare)
        compose.onNodeWithText("Quest").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("DAILY QUEST").fetchSemanticsNodes().isNotEmpty() }

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

    /** The Penalty Lock covers another app for real, and the override code lifts it. */
    @Test
    fun penaltyLockCoversAppsUntilOverridden() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val component = "${app.packageName}/${PenaltyLockService::class.java.name}"
        fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).close()
        fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) {
                if (cond()) return true
                Thread.sleep(200)
            }
            return cond()
        }
        try {
            runBlocking {
                graph.lock.setCode("482913")
                assertEquals(PenaltyLock.Attempt.Accepted, graph.lock.setEnabled(true))
            }
            shell("settings put secure enabled_accessibility_services $component")
            shell("settings put secure accessibility_enabled 1")
            assertTrue("lock service never bound", waitFor(15_000) { PenaltyLockService.running.value })

            runBlocking { graph.lock.test() }
            app.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("the lock screen never covered Settings", waitFor(15_000) { PenaltyLockService.covering.value })
            screenshot("08_penalty_lock")

            assertTrue(runBlocking { graph.lock.override("000000") } is PenaltyLock.Attempt.Wrong)
            assertTrue(PenaltyLockService.covering.value)
            assertEquals(PenaltyLock.Attempt.Accepted, runBlocking { graph.lock.override("482913") })
            assertTrue("the override didn't lift the lock", waitFor(10_000) { !PenaltyLockService.covering.value })
            screenshot("09_lock_lifted")
        } finally {
            runBlocking { graph.lock.setEnabled(false, "482913") }
            shell("settings delete secure enabled_accessibility_services")
        }
    }
}
