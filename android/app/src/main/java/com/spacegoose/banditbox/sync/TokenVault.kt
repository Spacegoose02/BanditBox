package com.spacegoose.banditbox.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the server's device token encrypted with a key that never leaves the Android Keystore.
 * If the key is lost (for example after a factory reset of the keystore) the token can't be
 * read and the TV shows "not synced" until it is paired again.
 */
class TokenVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sync_token", Context.MODE_PRIVATE)

    fun token(): String? {
        val saved = prefs.getString(KEY, null) ?: return null
        return try {
            val (iv, data) = saved.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("BanditBox", "Couldn't read the sync token", e)
            null
        }
    }

    /** Saves [token], or forgets it when null. Returns false if it couldn't be saved. */
    fun set(token: String?): Boolean {
        if (token == null) return prefs.edit().remove(KEY).commit()
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val data = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val text = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
            prefs.edit().putString(KEY, text).commit()
        } catch (e: Exception) {
            Log.w("BanditBox", "Couldn't save the sync token", e)
            false
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "banditbox_sync_token"
        const val KEY = "token"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
