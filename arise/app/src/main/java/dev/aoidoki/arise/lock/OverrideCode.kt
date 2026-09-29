package dev.aoidoki.arise.lock

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The override code and the one-time recovery code: only salted PBKDF2 hashes are ever stored. */
object OverrideCode {
    const val MIN_LENGTH = 6
    const val FREE_TRIES = 5
    private const val ITERATIONS = 20_000
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O, 1/I
    private val random = SecureRandom()

    fun valid(code: String): Boolean = code.length >= MIN_LENGTH && code.all { it.isDigit() }

    fun newSalt(): String = Base64.getEncoder().encodeToString(ByteArray(16).also { random.nextBytes(it) })

    fun hash(secret: String, salt: String): String {
        val spec = PBEKeySpec(normalize(secret).toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(key)
    }

    fun matches(secret: String, salt: String, expected: String): Boolean {
        if (salt.isEmpty() || expected.isEmpty() || secret.isBlank()) return false
        return MessageDigest.isEqual(hash(secret, salt).toByteArray(), expected.toByteArray())
    }

    /** 12 characters in groups of four, e.g. "K7QM-2XRA-PW9D". */
    fun newRecoveryCode(): String =
        (0 until 12).map { ALPHABET[random.nextInt(ALPHABET.length)] }.chunked(4).joinToString("-") { it.joinToString("") }

    /** Recovery codes are typed however: lower case, spaces, missing dashes. */
    fun normalize(s: String): String = s.trim().uppercase().filter { it.isLetterOrDigit() }

    /** Five free tries, then 60 s, doubling per miss, capped at an hour. */
    fun backoffMillis(fails: Int): Long =
        if (fails < FREE_TRIES) 0 else (60_000L shl (fails - FREE_TRIES).coerceAtMost(6)).coerceAtMost(3_600_000L)
}
