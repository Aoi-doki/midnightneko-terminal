package dev.aoidoki.arise.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class GhostFxTest {
    private val rate = 22050

    /** A vowel-ish test tone: 220 Hz with harmonics and a syllable envelope. */
    private fun voice(seconds: Float = 1.2f): FloatArray = FloatArray((seconds * rate).toInt()) { i ->
        val t = i.toDouble() / rate
        val env = (sin(PI * (t / seconds)) * (0.6 + 0.4 * sin(2 * PI * 4 * t))).coerceAtLeast(0.0)
        (env * (0.6 * sin(2 * PI * 220 * t) + 0.3 * sin(2 * PI * 440 * t) + 0.1 * sin(2 * PI * 660 * t))).toFloat()
    }

    @Test
    fun `output is clean, normalised and carries a tail`() {
        val input = voice()
        val out = GhostFx.process(input, rate)
        assertEquals(input.size + (1.6f * rate).toInt(), out.size)
        assertTrue(out.none { it.isNaN() || it.isInfinite() })
        val peak = out.maxOf { abs(it) }
        assertTrue("peak $peak", peak <= 0.9001f && peak > 0.5f)
        // The reverb/echo tail is audible after the dry signal ends.
        val tail = out.copyOfRange(input.size + rate / 10, input.size + rate / 2)
        assertTrue(tail.maxOf { abs(it) } > 0.01f)
    }

    @Test
    fun `dry settings leave little tail`() {
        val input = voice()
        val out = GhostFx.process(input, rate, GhostFx.Params(echo = 0f, reverb = 0f, ghost = 0f))
        val late = out.copyOfRange(input.size + rate / 2, out.size)
        assertTrue(late.maxOf { abs(it) } < 0.02f)
    }

    @Test
    fun `pitch shifter keeps length and lowers pitch`() {
        val tone = FloatArray(rate) { i -> sin(2 * PI * 440 * i / rate).toFloat() }
        val shifted = GhostFx.pitchShift(tone, rate, 0.5f)
        assertEquals(tone.size, shifted.size)
        fun crossings(x: FloatArray) = (rate / 4 until x.size - 1).count { x[it] <= 0 && x[it + 1] > 0 }
        val ratio = crossings(shifted).toDouble() / crossings(tone)
        assertTrue("ratio $ratio", ratio in 0.4..0.65)
    }

    @Test
    fun `silence stays silent`() {
        val out = GhostFx.process(FloatArray(rate), rate)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `chime is short and bounded`() {
        val c = Chime.synth(rate)
        assertTrue(c.isNotEmpty())
        assertTrue(c.maxOf { abs(it) } <= 0.5501f)
        assertTrue(c.none { it.isNaN() })
    }
}

class WavTest {
    @Test
    fun `round trip`() {
        val f = File.createTempFile("arise", ".wav")
        val samples = FloatArray(1000) { i -> sin(i / 10.0).toFloat() * 0.5f }
        Wav.write(f, samples, 24000)
        val back = Wav.read(f)
        assertNotNull(back)
        assertEquals(24000, back!!.sampleRate)
        assertEquals(1000, back.samples.size)
        for (i in samples.indices) assertEquals(samples[i], back.samples[i], 1e-3f)
        f.delete()
    }

    @Test
    fun `streaming headers with zero data size still read`() {
        val f = File.createTempFile("arise", ".wav")
        Wav.write(f, FloatArray(500) { 0.25f }, 16000)
        val bytes = f.readBytes()
        // Zero the data chunk size, as some TTS engines do while streaming.
        bytes[40] = 0; bytes[41] = 0; bytes[42] = 0; bytes[43] = 0
        val back = Wav.parse(bytes)
        assertEquals(500, back!!.samples.size)
        f.delete()
    }
}
