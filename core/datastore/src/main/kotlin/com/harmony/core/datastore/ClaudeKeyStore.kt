package com.harmony.core.datastore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The listener's own Anthropic API key, for the online mix's song guess with
 * Claude. Never stored in clear: it is encrypted with an AES-GCM key that
 * lives in Android Keystore (it can't be read out of the phone), and only the
 * ciphertext is written to app-private preferences. Not part of the settings
 * DataStore, so it is never in a settings export or a backup of them.
 */
@Singleton
class ClaudeKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val _state = MutableStateFlow(State())

    /** Whether a key is set (never the key), and what Anthropic said of it last. */
    val state: StateFlow<State> = _state.asStateFlow()

    data class State(val hasKey: Boolean = false, val loaded: Boolean = false, val problem: String? = null)

    /** Reads whether a key is set; call off the main thread (it touches the disk). */
    @Synchronized
    fun load() {
        if (_state.value.loaded) return
        _state.value = State(hasKey = prefs.contains(CIPHERTEXT), loaded = true, problem = prefs.getString(PROBLEM, null))
    }

    /** The key, or null when none is set or it can no longer be decrypted (the Keystore key was reset). */
    @Synchronized
    fun key(): String? {
        load()
        val stored = prefs.getString(CIPHERTEXT, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    /** Saves [key] (trimmed), or removes the key when it is null or blank. */
    @Synchronized
    fun setKey(key: String?) {
        val value = key?.trim().orEmpty()
        if (value.isEmpty()) {
            prefs.edit().remove(CIPHERTEXT).remove(PROBLEM).apply()
            _state.value = State(hasKey = false, loaded = true)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV length" }
        val sealed = iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(CIPHERTEXT, Base64.encodeToString(sealed, Base64.NO_WRAP)).remove(PROBLEM).apply()
        _state.value = State(hasKey = true, loaded = true)
    }

    /** What went wrong with the key the last time it was used ("rejected", "no credit"), or null when it worked. */
    @Synchronized
    fun reportProblem(problem: String?) {
        load()
        if (_state.value.problem == problem) return
        prefs.edit().apply { if (problem == null) remove(PROBLEM) else putString(PROBLEM, problem) }.apply()
        _state.value = _state.value.copy(problem = problem)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFS = "claude_key"
        const val CIPHERTEXT = "sealed"
        const val PROBLEM = "problem"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "harmony_claude_api_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
