package dev.aoidoki.arise.ai

import dev.aoidoki.arise.engine.Baselines
import dev.aoidoki.arise.engine.Limitations
import dev.aoidoki.arise.engine.ObjectivePlan
import dev.aoidoki.arise.engine.ObjectiveType
import dev.aoidoki.arise.engine.QuestKind
import dev.aoidoki.arise.engine.QuestPlan
import dev.aoidoki.arise.engine.StatType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Small models write almost-JSON: prose around it, trailing commas, numbers as strings, invented
 * keys. This digs out the first object, reads it leniently, and maps it onto the game's own types.
 * Anything it can't map is dropped; the caller then applies [dev.aoidoki.arise.engine.SafetyLimits].
 */
object AiJson {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        allowTrailingComma = true
    }

    /** The first balanced {...} in [text], respecting strings. Null if there isn't one. */
    fun extractObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    @Serializable
    data class ObjectiveDto(val type: String = "", val target: JsonElement? = null)

    @Serializable
    data class QuestDto(
        val title: String = "",
        val flavor: String = "",
        val objectives: List<ObjectiveDto> = emptyList(),
    )

    @Serializable
    data class StatsDto(
        val str: JsonElement? = null,
        val agi: JsonElement? = null,
        val vit: JsonElement? = null,
        val sen: JsonElement? = null,
        @SerialName("int") val intel: JsonElement? = null,
    )

    @Serializable
    data class CareDto(
        val knee: Boolean = false,
        val back: Boolean = false,
        @SerialName("upper_body") val upperBody: Boolean = false,
        val cardio: Boolean = false,
        @SerialName("no_jumping") val noJumping: Boolean = false,
    )

    @Serializable
    data class BaselineDto(
        val pushups: JsonElement? = null,
        val squats: JsonElement? = null,
        val situps: JsonElement? = null,
        @SerialName("plank_sec") val plankSec: JsonElement? = null,
        val steps: JsonElement? = null,
    )

    @Serializable
    data class AssessmentDto(
        val summary: String = "",
        val stats: StatsDto? = null,
        val care: CareDto? = null,
        val notes: List<String> = emptyList(),
        val baseline: BaselineDto? = null,
    )

    @Serializable
    data class TextDto(val text: String = "")

    fun number(e: JsonElement?): Int? {
        val p = (e as? JsonPrimitive) ?: return null
        p.intOrNull?.let { return it }
        p.doubleOrNull?.let { return it.toInt() }
        return Regex("-?\\d+").find(p.content)?.value?.toIntOrNull()
    }

    fun objectiveType(raw: String): ObjectiveType? {
        val s = raw.lowercase().replace(Regex("[^a-z]"), "")
        return when {
            s.startsWith("step") || s == "walk" || s == "walking" -> ObjectiveType.STEPS
            s.startsWith("push") || s.startsWith("press") -> ObjectiveType.PUSHUPS
            s.startsWith("squat") -> ObjectiveType.SQUATS
            s.startsWith("sit") || s.startsWith("crunch") -> ObjectiveType.SITUPS
            s.startsWith("plank") -> ObjectiveType.PLANK_SEC
            s.startsWith("brisk") || s.contains("cardio") || s.startsWith("jog") -> ObjectiveType.BRISK_MIN
            s.startsWith("sleep") -> ObjectiveType.SLEEP_MIN
            s.startsWith("medit") || s.startsWith("breath") -> ObjectiveType.MEDITATE_MIN
            s.startsWith("dist") || s.startsWith("run") -> ObjectiveType.DISTANCE_M
            else -> ObjectiveType.entries.firstOrNull { it.name.replace("_", "").lowercase() == s }
        }
    }

    fun parseQuest(text: String, kind: QuestKind, xp: Int): QuestPlan? {
        val obj = extractObject(text) ?: return null
        val dto = runCatching { json.decodeFromString<QuestDto>(obj) }.getOrNull() ?: return null
        val objectives = dto.objectives.mapNotNull { o ->
            val type = objectiveType(o.type) ?: return@mapNotNull null
            val target = number(o.target) ?: return@mapNotNull null
            if (target <= 0) null else ObjectivePlan(type, target)
        }
        if (objectives.isEmpty()) return null
        return QuestPlan(kind, dto.title.trim(), dto.flavor.trim(), objectives, xp, source = "AI")
    }

    data class Assessment(
        val summary: String,
        val stats: Map<StatType, Int>,
        val limitations: Limitations,
        val baselines: Baselines,
    )

    fun parseAssessment(text: String): Assessment? {
        val obj = extractObject(text) ?: return null
        val dto = runCatching { json.decodeFromString<AssessmentDto>(obj) }.getOrNull() ?: return null
        val s = dto.stats
        val stats = buildMap {
            number(s?.str)?.let { put(StatType.STR, it) }
            number(s?.agi)?.let { put(StatType.AGI, it) }
            number(s?.vit)?.let { put(StatType.VIT, it) }
            number(s?.sen)?.let { put(StatType.SEN, it) }
            number(s?.intel)?.let { put(StatType.INT, it) }
        }
        val c = dto.care ?: CareDto()
        val b = dto.baseline
        return Assessment(
            summary = dto.summary.trim(),
            stats = stats,
            limitations = Limitations(c.noJumping || c.knee, c.knee, c.back, c.upperBody, c.cardio, dto.notes.map { it.take(160) }.take(6)),
            baselines = Baselines(
                pushups = number(b?.pushups)?.coerceAtLeast(0) ?: 0,
                squats = number(b?.squats)?.coerceAtLeast(0) ?: 0,
                situps = number(b?.situps)?.coerceAtLeast(0) ?: 0,
                plankSec = number(b?.plankSec)?.coerceAtLeast(0) ?: 0,
                avgSteps = number(b?.steps)?.coerceAtLeast(0) ?: 0,
            ),
        )
    }

    fun parseText(text: String): String? {
        val obj = extractObject(text)
        val fromJson = obj?.let { runCatching { json.decodeFromString<TextDto>(it).text }.getOrNull() }
        return (fromJson ?: text.lines().firstOrNull { it.isNotBlank() })?.trim()?.trim('"')?.takeIf { it.length in 8..600 }
    }

    /** Used by tests to read raw values. */
    fun primitive(e: JsonElement): String = e.jsonPrimitive.content
}
