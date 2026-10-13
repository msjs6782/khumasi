package com.khumasi.game.ai

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
 * Stores the player's Gemini key encrypted with an AES key that lives in the Android Keystore
 * (it never leaves the device's secure hardware). The encrypted value is excluded from backups,
 * so the key never travels to another device or to the cloud.
 */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val hasKey: Boolean get() = prefs.contains(VALUE)

    fun save(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val enc = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(VALUE, Base64.encodeToString(enc, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun load(): String? = try {
        val v = prefs.getString(VALUE, null) ?: return null
        val iv = prefs.getString(IV, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(v, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (t: Throwable) {
        Log.w("KhumasiAI", "Stored key unreadable; clearing it", t)
        clear(); null
    }

    fun clear() = prefs.edit().clear().apply()

    /** Last 4 characters only, for showing which key is saved. */
    fun masked(): String? = load()?.let { if (it.length > 4) "••••" + it.takeLast(4) else "••••" }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
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

    companion object {
        const val PREFS = "khumasi_secure"
        private const val ALIAS = "khumasi_gemini"
        private const val VALUE = "gemini_key"
        private const val IV = "gemini_iv"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
