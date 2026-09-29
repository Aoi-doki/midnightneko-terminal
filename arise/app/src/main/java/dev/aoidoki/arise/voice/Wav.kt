package dev.aoidoki.arise.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal RIFF/WAVE reader for what TextToSpeech.synthesizeToFile writes: PCM 8/16-bit, mono or stereo. */
object Wav {
    data class Pcm(val samples: FloatArray, val sampleRate: Int)

    fun read(file: File): Pcm? = runCatching { parse(file.readBytes()) }.getOrNull()

    fun parse(bytes: ByteArray): Pcm? {
        if (bytes.size < 44) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        var pos = 12
        var channels = 1
        var rate = 22050
        var bits = 16
        var format = 1
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = bb.getShort(body).toInt()
                    channels = bb.getShort(body + 2).toInt()
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                }
                "data" -> {
                    if (format != 1) return null
                    // Some TTS engines write a streaming header with size 0 or -1: take what's there.
                    val len = if (size <= 0 || body + size > bytes.size) bytes.size - body else size
                    return Pcm(decode(bb, body, len, channels, bits), rate)
                }
            }
            pos = body + size + (size and 1)
            if (size < 0) break
        }
        return null
    }

    private fun decode(bb: ByteBuffer, start: Int, len: Int, channels: Int, bits: Int): FloatArray {
        val bytesPer = bits / 8
        val frame = bytesPer * channels
        val frames = len / frame
        return FloatArray(frames) { f ->
            var acc = 0f
            for (c in 0 until channels) {
                val o = start + f * frame + c * bytesPer
                acc += when (bits) {
                    8 -> ((bb.get(o).toInt() and 0xFF) - 128) / 128f
                    16 -> bb.getShort(o) / 32768f
                    else -> 0f
                }
            }
            acc / channels
        }
    }

    /** Write mono 16-bit PCM — used for the processed-phrase cache. */
    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        val data = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(samples.size * 2)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        file.writeBytes(data.array())
    }
}
