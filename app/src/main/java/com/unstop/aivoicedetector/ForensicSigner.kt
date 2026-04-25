package com.unstop.aivoicedetector

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class ForensicSigner(private val ctx: Context) {

    private val KEY_ALIAS = "voiceshield_forensic_key_v2"

    data class VerificationResult(
        val valid:  Boolean,
        val reason: String,
    )

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
            val ks  = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = ks.getKey(KEY_ALIAS, null) as? PrivateKey ?: return ""
            Signature.getInstance("SHA256withRSA").run {
                initSign(key)
                update(sessionJson.toByteArray(Charsets.UTF_8))
                Base64.encodeToString(sign(), Base64.NO_WRAP)
            }
        } catch (e: Exception) { e.printStackTrace(); "" }
    }

    fun publicKeyBase64(): String {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            Base64.encodeToString(
                ks.getCertificate(KEY_ALIAS)?.publicKey?.encoded ?: return "", Base64.NO_WRAP)
        } catch (e: Exception) { "" }
    }

    fun verify(
        sessionJson:  String,
        signatureB64: String,
        publicKeyB64: String = "",
    ): VerificationResult {
        if (signatureB64.isEmpty()) return VerificationResult(false, "No signature present")
        if (sessionJson.isEmpty()) return VerificationResult(false, "No session data to verify")

        return try {
            val sigBytes = Base64.decode(signatureB64, Base64.NO_WRAP)

            val publicKey = if (publicKeyB64.isNotEmpty()) {
                // External key path — from PDF or shared report
                val pubBytes = Base64.decode(publicKeyB64, Base64.NO_WRAP)
                KeyFactory.getInstance("RSA")
                    .generatePublic(X509EncodedKeySpec(pubBytes))
            } else {
                // Device key path — from Android Keystore
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                ks.getCertificate(KEY_ALIAS)?.publicKey
                    ?: return VerificationResult(false, "Device key not found")
            }

            val valid = Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey)
                update(sessionJson.toByteArray(Charsets.UTF_8))
                verify(sigBytes)
            }

            if (valid) VerificationResult(true,  "VALID — SHA256withRSA, Android Keystore TEE")
            else       VerificationResult(false, "INVALID — signature does not match session data")
        } catch (e: Exception) {
            VerificationResult(false, "VERIFY ERROR: ${e.message?.take(60)}")
        }
    }
}