package com.example.transcription.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys at rest, encrypted with a hardware-backed AES key.
 *
 * Both caches below exist because reading a key is not the rare operation the
 * shape of this class suggests. `buildModelOptions` asks for all three every
 * time the keyboard opens, and every one of those asks used to mean opening
 * AndroidKeyStore — a binder round trip to the keystore daemon — and then a
 * fresh GCM decrypt. Neither result can change without going through
 * [saveApiKey], and the ciphertext a plaintext is filed under changes on every
 * save, so a stale hit is not reachable: a re-encrypted key is a different
 * cache key, and one written by another process misses and decrypts normally.
 */
class SecretStore(context: Context) {
    private val preferences = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val alias = "openrouter_api_key"

    @Volatile private var cachedKey: SecretKey? = null

    /** Decrypted keys by the exact ciphertext they came from. */
    private val decrypted = HashMap<String, String>()

    fun saveApiKey(value: String) = saveApiKey(ApiKeyProvider.OPENROUTER, value)

    fun saveApiKey(provider: ApiKeyProvider, value: String) {
        val keyName = provider.storageName
        // The superseded ciphertext can never be asked for again, and a cleared
        // key has no ciphertext at all.
        synchronized(decrypted) { decrypted.clear() }
        if (value.isBlank()) {
            preferences.edit().remove(keyName).remove("${keyName}_iv").apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(keyName, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("${keyName}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun readApiKey(): String = readApiKey(ApiKeyProvider.OPENROUTER)

    fun readApiKey(provider: ApiKeyProvider): String {
        val keyName = provider.storageName
        val encrypted = preferences.getString(keyName, null) ?: return ""
        val iv = preferences.getString("${keyName}_iv", null) ?: return ""
        synchronized(decrypted) { decrypted["$encrypted|$iv"] }?.let { return it }
        val plaintext = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
        // A failed decrypt is not cached: it is the one outcome that can turn
        // into a success later, when whatever broke the keystore is over.
        if (plaintext.isNotEmpty()) {
            synchronized(decrypted) { decrypted["$encrypted|$iv"] = plaintext }
        }
        return plaintext
    }

    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        return loadOrGenerateKey().also { cachedKey = it }
    }

    private fun loadOrGenerateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }
}

enum class ApiKeyProvider(internal val storageName: String, val label: String, val placeholder: String) {
    OPENROUTER("key", "OpenRouter", "sk-or-v1-…"),
    ELEVENLABS("elevenlabs_key", "ElevenLabs", "sk_…"),
    ASSEMBLYAI("assemblyai_key", "AssemblyAI", "API key")
}
