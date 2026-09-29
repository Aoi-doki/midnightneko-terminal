package dev.aoidoki.arise.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The System's voice: a clean TTS line turned into something that sounds like it is coming from
 * everywhere and nowhere. Pure Kotlin, mono float PCM in and out, so it is unit-testable.
 *
 * Chain: dry → + shadow layer (pitched down, same timing) → chorus → multi-tap echo → Freeverb-style
 * reverb (low-passed wet) → normalise. The output is longer than the input by the reverb tail.
 */
object GhostFx {

    data class Params(
        /** Echo mix 0..1. */
        val echo: Float = 0.45f,
        /** Reverb wet 0..1. */
        val reverb: Float = 0.5f,
        /** Pitched-down shadow layer mix 0..1. */
        val ghost: Float = 0.35f,
        /** Shadow layer shift in semitones. */
        val ghostSemitones: Float = -4f,
        val tailSeconds: Float = 1.6f,
    )

    fun process(input: FloatArray, sampleRate: Int, params: Params = Params()): FloatArray {
        if (input.isEmpty()) return input
        val tail = (params.tailSeconds * sampleRate).roundToInt()
        val n = input.size + tail
        val dry = FloatArray(n).also { input.copyInto(it) }

        // 1. Shadow layer: same words, lower and a touch late, like a second voice under the first.
        val shadow = if (params.ghost > 0f) pitchShift(dry, sampleRate, 2.0.pow(params.ghostSemitones / 12.0).toFloat()) else null
        val layered = FloatArray(n) { i ->
            val s = shadow?.let { sh -> val j = i - (0.012 * sampleRate).toInt(); if (j >= 0) sh[j] * params.ghost else 0f } ?: 0f
            dry[i] * (1f - params.ghost * 0.35f) + s
        }

        // 2. Chorus: a slow-wobbling short delay gives the shimmer.
        val chorused = chorus(layered, sampleRate)

        // 3. Echo: 170 ms feedback delay with a quieter second tap at 340 ms.
        val echoed = echo(chorused, sampleRate, params.echo)

        // 4. Reverb, low-passed so the tail is dark and the words stay clear.
        val wet = lowPass(freeverb(echoed, sampleRate), sampleRate, 4500f)
        val out = FloatArray(n) { i -> echoed[i] * (1f - params.reverb * 0.4f) + wet[i] * params.reverb }

        return normalize(out, 0.9f)
    }

    /**
     * Delay-line pitch shifter: two taps sweep through a short buffer half a window apart and are
     * cross-faded with triangular windows. Keeps the timing of the input, which is what lets the
     * shadow layer sit under the dry voice.
     */
    fun pitchShift(x: FloatArray, sampleRate: Int, ratio: Float): FloatArray {
        val window = (0.05f * sampleRate).roundToInt().coerceAtLeast(64)
        val out = FloatArray(x.size)
        val rate = 1f - ratio
        var phase = 0f
        for (i in x.indices) {
            phase += rate
            if (phase >= window) phase -= window
            if (phase < 0) phase += window
            for (tap in 0..1) {
                val d = (phase + tap * window / 2f) % window
                val gain = 1f - abs(2f * d / window - 1f)
                out[i] += readFrac(x, i - d) * gain
            }
        }
        return out
    }

    fun chorus(x: FloatArray, sampleRate: Int, mix: Float = 0.35f): FloatArray {
        val base = 0.020f * sampleRate
        val depth = 0.005f * sampleRate
        val out = FloatArray(x.size)
        for (i in x.indices) {
            val lfo = sin(2 * PI * 0.3 * i / sampleRate).toFloat()
            out[i] = x[i] * (1f - mix) + readFrac(x, i - (base + depth * lfo)) * mix
        }
        return out
    }

    fun echo(x: FloatArray, sampleRate: Int, mix: Float, feedback: Float = 0.35f): FloatArray {
        if (mix <= 0f) return x.copyOf()
        val d1 = (0.170f * sampleRate).roundToInt()
        val d2 = (0.340f * sampleRate).roundToInt()
        val line = FloatArray(x.size)
        for (i in x.indices) {
            val fb = if (i >= d1) line[i - d1] * feedback else 0f
            line[i] = x[i] + fb
        }
        return FloatArray(x.size) { i ->
            val e1 = if (i >= d1) line[i - d1] else 0f
            val e2 = if (i >= d2) x[i - d2] * 0.5f else 0f
            x[i] + (e1 + e2) * mix * 0.6f
        }
    }

    /** Mono Freeverb: 4 damped combs in parallel into 2 all-passes in series. */
    fun freeverb(x: FloatArray, sampleRate: Int, room: Float = 0.84f, damp: Float = 0.3f): FloatArray {
        val scale = sampleRate / 44100f
        val combs = intArrayOf(1116, 1188, 1277, 1356).map { (it * scale).roundToInt().coerceAtLeast(8) }
        val allpasses = intArrayOf(556, 441).map { (it * scale).roundToInt().coerceAtLeast(8) }
        val sum = FloatArray(x.size)
        for (len in combs) {
            val buf = FloatArray(len)
            var idx = 0
            var store = 0f
            for (i in x.indices) {
                val y = buf[idx]
                store = y * (1 - damp) + store * damp
                buf[idx] = x[i] * 0.015f + store * room
                idx = (idx + 1) % len
                sum[i] += y
            }
        }
        var signal = sum
        for (len in allpasses) {
            val buf = FloatArray(len)
            var idx = 0
            val out = FloatArray(signal.size)
            for (i in signal.indices) {
                val b = buf[idx]
                out[i] = -signal[i] + b
                buf[idx] = signal[i] + b * 0.5f
                idx = (idx + 1) % len
            }
            signal = out
        }
        return signal
    }

    fun lowPass(x: FloatArray, sampleRate: Int, cutoffHz: Float): FloatArray {
        val a = exp(-2.0 * PI * cutoffHz / sampleRate).toFloat()
        val out = FloatArray(x.size)
        var y = 0f
        for (i in x.indices) {
            y = (1 - a) * x[i] + a * y
            out[i] = y
        }
        return out
    }

    fun normalize(x: FloatArray, peak: Float): FloatArray {
        var max = 0f
        for (v in x) if (!v.isNaN()) max = maxOf(max, abs(v))
        if (max <= 1e-6f) return FloatArray(x.size)
        val g = peak / max
        return FloatArray(x.size) { i -> if (x[i].isNaN()) 0f else x[i] * g }
    }

    private fun readFrac(x: FloatArray, pos: Float): Float {
        if (pos < 0) return 0f
        val i = pos.toInt()
        if (i + 1 >= x.size) return if (i < x.size) x[i] else 0f
        val f = pos - i
        return x[i] * (1 - f) + x[i + 1] * f
    }
}

/** The "ding" before the System speaks: a glassy bell, synthesized, with its own small reverb. */
object Chime {
    fun synth(sampleRate: Int, seconds: Float = 1.1f): FloatArray {
        val n = (seconds * sampleRate).roundToInt()
        val partials = listOf(1318.5 to 1.0, 1975.5 to 0.6, 2637.0 to 0.25, 3951.1 to 0.12)
        val raw = FloatArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val attack = (t / 0.004).coerceAtMost(1.0)
            var s = 0.0
            for ((f, a) in partials) s += a * sin(2 * PI * f * t) * exp(-t * (3.0 + f / 1500.0))
            // A second, quieter strike a fifth higher — the classic two-note System notification.
            val t2 = t - 0.12
            if (t2 > 0) s += 0.7 * sin(2 * PI * 1760.0 * t2) * exp(-t2 * 4.0) + 0.3 * sin(2 * PI * 2637.0 * t2) * exp(-t2 * 5.0)
            (s * attack).toFloat()
        }
        val wet = GhostFx.freeverb(raw, sampleRate, room = 0.8f, damp = 0.2f)
        return GhostFx.normalize(FloatArray(n) { raw[it] + wet[it] * 0.6f }, 0.55f)
    }
}
