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

class SecretStore(context: Context) {
    private val preferences = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val alias = "openrouter_api_key"

    fun saveApiKey(value: String) = saveApiKey(ApiKeyProvider.OPENROUTER, value)

    fun saveApiKey(provider: ApiKeyProvider, value: String) {
        val keyName = provider.storageName
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
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateKey(): SecretKey {
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
