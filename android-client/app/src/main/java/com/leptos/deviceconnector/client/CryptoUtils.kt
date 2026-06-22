package com.leptos.deviceconnector.client

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE_BITS = 128

    fun deriveKey(secret: String): SecretKey {
        val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    fun encryptPayload(secret: String, payload: String): String? {
        val key = deriveKey(secret)
        val nonce = ByteArray(NONCE_SIZE)
        SecureRandom().nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        val nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP)
        val cipherB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return "ENCRYPTED|$nonceB64|$cipherB64\n"
    }

    fun decryptPayload(secret: String, line: String): String? {
        val fields = line.split("|", limit = 3)
        if (fields.size != 3 || fields[0] != "ENCRYPTED") {
            return null
        }
        val nonce = Base64.decode(fields[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(fields[2], Base64.NO_WRAP)
        val key = deriveKey(secret)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        return try {
            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun escapeField(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
