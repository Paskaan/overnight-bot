package com.pascalh.overnightbot.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.pascalh.overnightbot.core.Mode
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Alpaca API keys, encrypted with a key that lives in the Android Keystore.
 * The keys never leave the phone except in requests to Alpaca. Paper and live keys are kept apart.
 */
class Creds(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("creds", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val parts = stored.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    fun set(mode: Mode, keyId: String, secret: String) {
        prefs.edit()
            .putString(mode.name + "_id", encrypt(keyId.trim()))
            .putString(mode.name + "_secret", encrypt(secret.trim()))
            .apply()
    }

    fun has(mode: Mode): Boolean = prefs.contains(mode.name + "_id") && prefs.contains(mode.name + "_secret")

    /** Key ID and secret, or null when none are saved or they cannot be read any more. */
    fun get(mode: Mode): Pair<String, String>? {
        val id = prefs.getString(mode.name + "_id", null) ?: return null
        val secret = prefs.getString(mode.name + "_secret", null) ?: return null
        return try {
            decrypt(id) to decrypt(secret)
        } catch (e: Exception) {
            null
        }
    }

    fun clear(mode: Mode) {
        prefs.edit().remove(mode.name + "_id").remove(mode.name + "_secret").apply()
    }

    private companion object {
        const val ALIAS = "overnightbot_keys"
    }
}
