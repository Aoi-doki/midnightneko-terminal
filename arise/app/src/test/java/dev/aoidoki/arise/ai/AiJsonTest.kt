package dev.aoidoki.arise.ai

import dev.aoidoki.arise.data.PlayerEntity
import dev.aoidoki.arise.engine.Baselines
import dev.aoidoki.arise.engine.ObjectiveType
import dev.aoidoki.arise.engine.QuestKind
import dev.aoidoki.arise.engine.SafetyLimits
import dev.aoidoki.arise.engine.StatType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiJsonTest {
    @Test
    fun `extracts the object from chatty output`() {
        val raw = "Sure! Here is your quest:\n```json\n{\"title\":\"Daily Quest: {Iron}\",\"objectives\":[{\"type\":\"steps\",\"target\":8000}]}\n```\nGood luck!"
        assertEquals("{\"title\":\"Daily Quest: {Iron}\",\"objectives\":[{\"type\":\"steps\",\"target\":8000}]}", AiJson.extractObject(raw))
        assertNull(AiJson.extractObject("no json here"))
        assertNull(AiJson.extractObject("{\"unterminated\": true"))
    }

    @Test
    fun `parses a quest leniently`() {
        val raw = """
            {"title": "Daily Quest: Iron Will", "flavor": "Rise, Player.",
             "objectives": [
                {"type": "Push-ups", "target": "30 reps"},
                {"type": "walk", "target": 9000.0},
                {"type": "juggling", "target": 5},
                {"type": "plank", "target": 60},
             ]}
        """.trimIndent()
        val plan = AiJson.parseQuest(raw, QuestKind.DAILY, 150)
        assertNotNull(plan)
        plan!!
        assertEquals("AI", plan.source)
        assertEquals(listOf(ObjectiveType.PUSHUPS, ObjectiveType.STEPS, ObjectiveType.PLANK_SEC), plan.objectives.map { it.type })
        assertEquals(30, plan.objectives[0].target)
        assertEquals(9000, plan.objectives[1].target)
    }

    @Test
    fun `garbage is rejected, not guessed`() {
        assertNull(AiJson.parseQuest("{\"objectives\":[]}", QuestKind.DAILY, 100))
        assertNull(AiJson.parseQuest("{\"objectives\":[{\"type\":\"steps\",\"target\":-5}]}", QuestKind.DAILY, 100))
        assertNull(AiJson.parseQuest("I cannot do that.", QuestKind.DAILY, 100))
    }

    @Test
    fun `ai quest still goes through the rails`() {
        val p = PlayerEntity(baselines = Baselines(pushups = 8, avgSteps = 4000))
        val raw = "{\"title\":\"Starve the weakness\",\"flavor\":\"Skip dinner tonight.\",\"objectives\":[{\"type\":\"pushups\",\"target\":400}]}"
        val plan = AiJson.parseQuest(raw, QuestKind.DAILY, 150)!!
        val safe = SafetyLimits.sanitize(plan, p, emptyMap(), "The Daily Quest has arrived.")
        assertEquals("Daily Quest", safe.title)
        assertEquals("The Daily Quest has arrived.", safe.flavor)
        assertEquals(40, safe.objectives.single().target)
    }

    @Test
    fun `parses an assessment`() {
        val raw = """{"summary":"Player evaluated. Weak but willing.","stats":{"str":"12","agi":9,"vit":8,"sen":11,"int":14},
            "care":{"knee":true,"back":false,"upper_body":false,"cardio":false,"no_jumping":false},
            "notes":["Protect the left knee."],"baseline":{"pushups":15,"squats":0,"situps":0,"plank_sec":45,"steps":"5000"}}"""
        val a = AiJson.parseAssessment(raw)!!
        assertEquals(12, a.stats[StatType.STR])
        assertEquals(14, a.stats[StatType.INT])
        assertTrue(a.limitations.kneeCare)
        assertTrue(a.limitations.noJumping)
        assertEquals(15, a.baselines.pushups)
        assertEquals(5000, a.baselines.avgSteps)
        assertEquals(listOf("Protect the left knee."), a.limitations.notes)
    }

    @Test
    fun `objective type names are forgiving`() {
        assertEquals(ObjectiveType.SITUPS, AiJson.objectiveType("Sit-Ups"))
        assertEquals(ObjectiveType.BRISK_MIN, AiJson.objectiveType("brisk_min"))
        assertEquals(ObjectiveType.MEDITATE_MIN, AiJson.objectiveType("meditation"))
        assertEquals(ObjectiveType.DISTANCE_M, AiJson.objectiveType("distance_m"))
        assertNull(AiJson.objectiveType("juggling"))
    }

    @Test
    fun `prompt carries the player's own words verbatim`() {
        val p = PlayerEntity(about = "I hate running but love hiking")
        val prompt = Prompts.assessment(p, listOf(dev.aoidoki.arise.data.CustomStatEntity(name = "grip", value = "crushes cans")))
        assertTrue(prompt.contains("I hate running but love hiking"))
        assertTrue(prompt.contains("grip: crushes cans"))
        assertTrue(prompt.startsWith("<|im_start|>system"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }
}
