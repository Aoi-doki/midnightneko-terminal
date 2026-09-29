package dev.aoidoki.arise.sense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class RepCounterTest {
    /** A joint angle swinging between [hi] and [lo], [reps] times, at [hz] samples/s, [secPerRep] each, with noise. */
    private fun angleTrace(reps: Int, hi: Float, lo: Float, secPerRep: Double = 1.6, hz: Int = 20, noise: Float = 4f, seed: Int = 1): List<Pair<Float, Long>> {
        val r = Random(seed)
        val n = (reps * secPerRep * hz).toInt() + hz
        return (0 until n).map { i ->
            val t = i.toDouble() / hz
            val phase = (t / secPerRep).coerceAtMost(reps.toDouble())
            val mid = (hi + lo) / 2
            val amp = (hi - lo) / 2
            val v = mid + amp * cos(2 * PI * phase).toFloat() + (r.nextFloat() - 0.5f) * noise
            v to (t * 1000).toLong()
        }
    }

    @Test
    fun `push-up pose counts clean reps`() {
        val c = Profiles.pushupPose()
        angleTrace(12, hi = 172f, lo = 78f).forEach { (v, t) -> c.feed(v, t) }
        assertEquals(12, c.count)
    }

    @Test
    fun `half reps do not count`() {
        val c = Profiles.pushupPose()
        angleTrace(10, hi = 172f, lo = 125f).forEach { (v, t) -> c.feed(v, t) }
        assertEquals(0, c.count)
    }

    @Test
    fun `squat pose counts with jitter`() {
        val c = Profiles.squatPose()
        angleTrace(15, hi = 175f, lo = 85f, secPerRep = 2.0, noise = 8f, seed = 7).forEach { (v, t) -> c.feed(v, t) }
        assertEquals(15, c.count)
    }

    @Test
    fun `a set that starts mid-rep is not counted as a rep`() {
        val c = Profiles.pushupPose()
        // Starts at the bottom, rises once, then one full rep.
        listOf(80f, 90f, 120f, 165f, 170f, 120f, 80f, 120f, 170f).flatMap { v -> List(6) { v } }.forEachIndexed { i, v -> c.feed(v, i * 70L) }
        assertEquals(1, c.count)
    }

    @Test
    fun `sit-up tilt counts from the chest-held phone`() {
        val c = Profiles.situpTilt()
        angleTrace(8, hi = 75f, lo = 8f, secPerRep = 2.0).map { (v, t) -> (83f - v) to t }.forEach { (v, t) -> c.feed(v, t) }
        assertEquals(8, c.count)
    }

    @Test
    fun `accelerometer squats in a pocket`() {
        val c = Profiles.squatAccel()
        val hz = 50
        val per = 2.0
        val reps = 10
        val r = Random(3)
        for (i in 0 until (reps * per * hz).toInt()) {
            val t = i.toDouble() / hz
            val ph = (t % per) / per
            // Going down reads light (below g), the bottom and the drive up read heavy (above g).
            val a = 9.81f - 3.2f * cos(2 * PI * ph).toFloat()
            c.feed(a + (r.nextFloat() - 0.5f) * 0.4f, (t * 1000).toLong())
        }
        assertEquals(reps, c.count)
    }

    @Test
    fun `proximity counts chest touches with debounce`() {
        val c = ProximityRepCounter()
        var t = 0L
        repeat(20) {
            c.feed(0f, t); t += 30
            c.feed(5f, t); t += 30 // bounce inside the debounce window
            c.feed(0f, t); t += 600
            c.feed(5f, t); t += 600
        }
        assertEquals(20, c.count)
    }

    @Test
    fun `stillness detects holding vs moving`() {
        val s = StillnessMonitor()
        val r = Random(5)
        var still = false
        for (i in 0 until 100) still = s.feed(9.81f + (r.nextFloat() - 0.5f) * 0.3f, i * 20L)
        assertTrue(still)
        for (i in 100 until 200) still = s.feed(9.81f + sin(i * 0.5f) * 4f, i * 20L)
        assertFalse(still)
    }

    @Test
    fun `joint angle geometry`() {
        assertEquals(90f, Motion.jointAngle(0f, 1f, 0f, 0f, 1f, 0f), 0.01f)
        assertEquals(180f, Motion.jointAngle(-1f, 0f, 0f, 0f, 1f, 0f), 0.01f)
        assertEquals(0f, Motion.tiltDegrees(0f, 0f, 9.81f), 0.01f)
        assertEquals(90f, Motion.tiltDegrees(0f, 9.81f, 0f), 0.01f)
    }
}

class StepMathTest {
    @Test
    fun `counts steps across a day`() {
        var s = StepState()
        s = StepMath.onReading(s, 10_000, day = 1, minuteKey = 0)
        s = StepMath.onReading(s, 10_500, day = 1, minuteKey = 1)
        assertEquals(500, s.today)
    }

    @Test
    fun `reboot resets the counter but not today's steps`() {
        var s = StepState()
        s = StepMath.onReading(s, 10_000, 1, 0)
        s = StepMath.onReading(s, 12_000, 1, 1)
        s = StepMath.onReading(s, 50, 1, 2) // rebooted
        s = StepMath.onReading(s, 350, 1, 3)
        assertEquals(2300, s.today)
    }

    @Test
    fun `midnight starts from zero`() {
        var s = StepState()
        s = StepMath.onReading(s, 1000, 1, 0)
        s = StepMath.onReading(s, 9000, 1, 1)
        s = StepMath.onReading(s, 9100, 2, 2)
        assertEquals(0, s.today)
        s = StepMath.onReading(s, 9400, 2, 3)
        assertEquals(300, s.today)
    }

    @Test
    fun `brisk minutes need cadence in consecutive minutes`() {
        var s = StepState()
        var counter = 0L
        s = StepMath.onReading(s, counter, 1, 0)
        for (m in 1..10) {
            counter += 115
            s = StepMath.onReading(s, counter, 1, m.toLong())
        }
        assertEquals(10, s.briskMinutes)
        // A slow stroll adds none.
        for (m in 11..20) {
            counter += 60
            s = StepMath.onReading(s, counter, 1, m.toLong())
        }
        assertEquals(10, s.briskMinutes)
    }
}
