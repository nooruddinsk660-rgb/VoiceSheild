package com.unstop.aivoicedetector

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import java.security.MessageDigest
import kotlin.math.sqrt

class ThreatIntelManager(context: Context) {

    private val db = ThreatDb(context).writableDatabase

    // LSH: 8 random projection hyperplanes for 39-dim space
    // Generated deterministically from seed so consistent across sessions
    private val N_PLANES     = 8
    private val FEATURE_DIM  = 39
    private val lshPlanes: Array<FloatArray> = generateLSHPlanes()
    private fun generateLSHPlanes(): Array<FloatArray> {
        val rng = java.util.Random(0xDEADBEEFL)
        return Array(N_PLANES) { FloatArray(FEATURE_DIM) { rng.nextGaussian().toFloat() } }
    }

    // Compute LSH bucket hash — 8-bit integer (256 buckets)
    private fun lshHash(features: FloatArray): Int {
        var hash = 0
        val len = minOf(features.size, FEATURE_DIM)
        for (p in 0 until N_PLANES) {
            var dot = 0f
            for (i in 0 until len) dot += lshPlanes[p][i] * features[i]
            if (dot > 0f) hash = hash or (1 shl p)
        }
        return hash
    }


    fun reportConfirmedFake(mfcc39: FloatArray) {
        val hash    = mfcc39.sha256Hex()
        val lsh     = lshHash(mfcc39)
        val values  = ContentValues().apply {
            put("hash",       hash)
            put("lsh_bucket", lsh)
            put("mfcc_b64",   mfcc39.toB64())
            put("created_ms", System.currentTimeMillis())
        }
        db.insertWithOnConflict("threats", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun isThreat(mfcc39: FloatArray): Boolean {
        val queryHash = lshHash(mfcc39)

        // Check same bucket + Hamming distance 1 neighbours (bit-flip each plane)
        val bucketsToCheck = mutableSetOf(queryHash)
        for (p in 0 until N_PLANES) bucketsToCheck.add(queryHash xor (1 shl p))

        val placeholders = bucketsToCheck.joinToString(",") { "?" }
        val args = bucketsToCheck.map { it.toString() }.toTypedArray()
        val cur  = db.rawQuery(
            "SELECT mfcc_b64 FROM threats WHERE lsh_bucket IN ($placeholders)", args)

        cur.use {
            while (it.moveToNext()) {
                val stored = it.getString(0).fromB64() ?: continue
                if (cosine(mfcc39, stored) > 0.95f) return true
            }
        }
        return false
    }

    fun threatCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM threats", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun verifySignature(sessionJson: String, signatureB64: String, publicKeyB64: String): Boolean {
        return try {
            val pubKeyBytes = Base64.decode(publicKeyB64, Base64.NO_WRAP)
            val pubKey = java.security.KeyFactory
                .getInstance("RSA")
                .generatePublic(java.security.spec.X509EncodedKeySpec(pubKeyBytes))
            val sigBytes = Base64.decode(signatureB64, Base64.NO_WRAP)
            java.security.Signature.getInstance("SHA256withRSA").run {
                initVerify(pubKey)
                update(sessionJson.toByteArray(Charsets.UTF_8))
                verify(sigBytes)
            }
        } catch (e: Exception) { false }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val len = minOf(a.size, b.size)
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in 0 until len) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i] }
        val d = sqrt(na.toDouble()) * sqrt(nb.toDouble())
        return if (d < 1e-9) 0f else (dot / d).toFloat()
    }

    private fun FloatArray.sha256Hex(): String {
        val bytes = ByteArray(size * 4)
        forEachIndexed { i, v ->
            val bits = java.lang.Float.floatToIntBits(v)
            bytes[i*4] = (bits shr 24).toByte(); bytes[i*4+1] = (bits shr 16).toByte()
            bytes[i*4+2] = (bits shr 8).toByte(); bytes[i*4+3] = bits.toByte()
        }
        return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes), Base64.NO_WRAP)
    }

    private fun FloatArray.toB64(): String {
        val bytes = ByteArray(size * 4)
        forEachIndexed { i, v ->
            val bits = java.lang.Float.floatToIntBits(v)
            bytes[i*4] = (bits shr 24).toByte(); bytes[i*4+1] = (bits shr 16).toByte()
            bytes[i*4+2] = (bits shr 8).toByte(); bytes[i*4+3] = bits.toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun String.fromB64(): FloatArray? = try {
        val b = Base64.decode(this, Base64.NO_WRAP)
        FloatArray(b.size / 4) { i ->
            java.lang.Float.intBitsToFloat(
                ((b[i*4].toInt() and 0xFF) shl 24) or ((b[i*4+1].toInt() and 0xFF) shl 16) or
                ((b[i*4+2].toInt() and 0xFF) shl  8) or  (b[i*4+3].toInt() and 0xFF))
        }
    } catch (_: Exception) { null }
}

private class ThreatDb(ctx: Context) : SQLiteOpenHelper(ctx, "vs_threats.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS threats(
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                hash       TEXT UNIQUE NOT NULL,
                lsh_bucket INTEGER NOT NULL DEFAULT 0,
                mfcc_b64   TEXT NOT NULL,
                created_ms INTEGER NOT NULL
            )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lsh ON threats(lsh_bucket)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        db.execSQL("DROP TABLE IF EXISTS threats"); onCreate(db)
    }
}