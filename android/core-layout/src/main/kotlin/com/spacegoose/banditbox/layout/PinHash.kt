package com.spacegoose.banditbox.layout

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hashes profile PINs so the layout document (which is synced and backed up) never holds
 * the PIN itself. Stored as "pbkdf2-sha256$<iterations>$<salt>$<hash>".
 *
 * A 4-digit PIN can't resist a determined attacker with the file in hand, whatever the hash;
 * this keeps it out of plain sight. The kids lock is a soft lock by design (see handoff).
 */
object PinHash {
    const val PIN_LENGTH = 4
    private const val PREFIX = "pbkdf2-sha256"
    private const val SEPARATOR = '$'
    private const val ITERATIONS = 50_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    fun isValidPin(pin: String) = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

    fun hash(pin: String): String {
        require(isValidPin(pin)) { "PIN must be $PIN_LENGTH digits" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        return encode(ITERATIONS, salt, derive(pin, salt, ITERATIONS))
    }

    /** True when [pin] matches [stored]. A null or malformed stored value never matches. */
    fun verify(pin: String, stored: String?): Boolean {
        val parts = stored?.split(SEPARATOR) ?: return false
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        return try {
            val salt = Base64.getDecoder().decode(parts[2])
            val expected = Base64.getDecoder().decode(parts[3])
            MessageDigest.isEqual(expected, derive(pin, salt, iterations))
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(iterations: Int, salt: ByteArray, hash: ByteArray): String {
        val b64 = Base64.getEncoder().withoutPadding()
        return listOf(PREFIX, iterations.toString(), b64.encodeToString(salt), b64.encodeToString(hash))
            .joinToString(SEPARATOR.toString())
    }
}
