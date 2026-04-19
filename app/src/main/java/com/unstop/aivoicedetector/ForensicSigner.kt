package com.unstop.aivoicedetector

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature

class ForensicSigner(private val ctx: Context) {

    private val KEY_ALIAS = "voiceshield_forensic_key_v2"

    private fun ensureKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) return
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setUserAuthenticationRequired(false)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            .apply { initialize(spec); generateKeyPair() }
    }

    fun signSession(sessionJson: String): String {
        return try {
            ensureKey()
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = ks.getKey(KEY_ALIAS, null) as? PrivateKey ?: return ""
            Signature.getInstance("SHA256withRSA").run {
                initSign(key)
                update(sessionJson.toByteArray(Charsets.UTF_8))
                Base64.encodeToString(sign(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun publicKeyBase64(): String {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            Base64.encodeToString(ks.getCertificate(KEY_ALIAS)?.publicKey?.encoded ?: return "", Base64.NO_WRAP)
        } catch (e: Exception) { "" }
    }
}