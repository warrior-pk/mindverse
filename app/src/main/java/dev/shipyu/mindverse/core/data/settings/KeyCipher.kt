package dev.shipyu.mindverse.core.data.settings

/**
 * Symmetric codec for provider keys and other secrets persisted by the app
 * (spec "Key storage: Android Keystore-encrypted persistence"). Implementations frame
 * their own output (e.g. iv || ciphertext) and must be reversible by [decrypt].
 *
 * The interface exists so the encrypted-store logic is unit-testable without the Android
 * Keystore (JVM tests inject a fake cipher); the production binding is [KeystoreKeyCipher].
 */
interface KeyCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(ciphertext: ByteArray): ByteArray
}
