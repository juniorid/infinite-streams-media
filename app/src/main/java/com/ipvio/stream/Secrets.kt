package com.ipvio.stream

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Verschlüsselt kleine Texte (z. B. Playlist-Adressen mit Zugangsdaten) mit einem Schlüssel aus dem
 * Android-Keystore. Der Schlüssel verlässt das Gerät nie. Ohne Keystore (manche Boxen) liefert encrypt() null,
 * die App speichert dann wie bisher im Klartext – es geht also nie etwas verloren.
 */
object Secrets {
    private const val ALIAS = "ipvio_secrets_v1"
    private const val PREFIX = "v1:"

    private fun key(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    } catch (e: Exception) {
        null
    }

    fun encrypt(plain: String): String? = try {
        val k = key() ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k)
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    fun decrypt(token: String): String? = try {
        if (!token.startsWith(PREFIX)) null
        else {
            val parts = token.removePrefix(PREFIX).split(":")
            val k = key() ?: return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }
}
