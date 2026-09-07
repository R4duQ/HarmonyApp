package com.harmony.feature.downloads

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Supplies SpotiFLAC's extension storage master key from Android Keystore.
 *
 * The non-exportable HMAC key never leaves Android Keystore. A domain-separated
 * 32-byte HMAC result is passed to the Go runtime on each process start, giving
 * it a stable master key without persisting raw key material in app storage.
 */
internal object SpotiFlacExtensionStorageKey {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "harmony_spotiflac_extension_storage_v1"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private val derivationContext =
        "Harmony SpotiFLAC extension storage master key v1"
            .toByteArray(Charsets.UTF_8)

    @Synchronized
    fun loadOrCreateEncoded(): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)
            ?: generateKey()

        // AndroidKeyStore owns the key, but HMAC operations can be exposed by
        // a device-specific crypto provider (for example
        // AndroidKeyStoreBCWorkaround). Let JCA select the compatible provider
        // instead of incorrectly requiring AndroidKeyStore to implement Mac.
        val derived = Mac.getInstance(HMAC_ALGORITHM).run {
            init(key)
            doFinal(derivationContext)
        }
        check(derived.size == MASTER_KEY_BYTES) {
            "Android Keystore returned an invalid SpotiFLAC master key length."
        }

        return try {
            Base64.encodeToString(derived, Base64.NO_WRAP)
        } finally {
            derived.fill(0)
        }
    }

    private fun generateKey(): SecretKey =
        KeyGenerator.getInstance(HMAC_ALGORITHM, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN,
                )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setKeySize(MASTER_KEY_BYTES * 8)
                    .build(),
            )
            generateKey()
        }

    private const val MASTER_KEY_BYTES = 32
}
