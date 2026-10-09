package com.harrydatahub.groundkit.data.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The account session and synced settings on this device. The session (tokens) is
 * encrypted with a key kept in the Android Keystore, which never leaves the device; the
 * file is also left out of backups (res/xml), so a restore starts signed out.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    var session: AuthSession?
        get() = prefs.getString(SESSION, null)?.let(::decrypt)
            ?.let { runCatching { SupabaseClient.parseSession(JSONObject(it)) }.getOrNull() }
        set(value) {
            prefs.edit().apply { if (value == null) remove(SESSION) else putString(SESSION, encrypt(value.toJson().toString())) }.apply()
        }

    /** The PKCE verifier of a provider sign-in in progress (the app may be killed meanwhile). */
    var verifier: String?
        get() = prefs.getString(VERIFIER, null)?.let(::decrypt)
        set(value) {
            prefs.edit().apply { if (value == null) remove(VERIFIER) else putString(VERIFIER, encrypt(value)) }.apply()
        }

    var settings: SyncedSettings
        get() = SyncedSettings.fromJson(prefs.getString(SETTINGS, null)?.let { runCatching { JSONObject(it) }.getOrNull() })
        set(value) {
            prefs.edit().putString(SETTINGS, value.toJson().toString()).apply()
        }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** Null when the data cannot be read (key lost after a restore or reset): signed out. */
    private fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
    }.getOrNull()

    private companion object {
        const val ALIAS = "groundkit_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val SESSION = "session"
        const val VERIFIER = "verifier"
        const val SETTINGS = "synced"
    }
}
