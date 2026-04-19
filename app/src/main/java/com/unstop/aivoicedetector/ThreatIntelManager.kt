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

    fun reportConfirmedFake(mfcc: FloatArray) {
        val hash   = mfcc.sha256Hex()
        val values = ContentValues().apply {
            put("hash",       hash)
            put("mfcc_b64",   mfcc.toB64())
            put("created_ms", System.currentTimeMillis())
        }
        db.insertWithOnConflict("threats", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun isThreat(mfcc: FloatArray): Boolean {
        val cur = db.query("threats", arrayOf("mfcc_b64"), null, null, null, null, null)
        cur.use {
            while (it.moveToNext()) {
                val stored = it.getString(0).fromB64() ?: continue
                if (cosine(mfcc, stored) > 0.98f) return true
            }
        }
        return false
    }

    fun threatCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM threats", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val len = minOf(a.size, b.size)
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in 0 until len) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i] }
        val denom = sqrt(na.toDouble()) * sqrt(nb.toDouble())
        return if (denom < 1e-9) 0f else (dot / denom).toFloat()
    }

    private fun FloatArray.sha256Hex(): String {
        val bytes = ByteArray(size * 4)
        forEachIndexed { i, v ->
            val bits = java.lang.Float.floatToIntBits(v)
            bytes[i*4]   = (bits shr 24).toByte()
            bytes[i*4+1] = (bits shr 16).toByte()
            bytes[i*4+2] = (bits shr  8).toByte()
            bytes[i*4+3] =  bits.toByte()
        }
        return Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(bytes), Base64.NO_WRAP)
    }

    private fun FloatArray.toB64(): String {
        val bytes = ByteArray(size * 4)
        forEachIndexed { i, v ->
            val bits = java.lang.Float.floatToIntBits(v)
            bytes[i*4]   = (bits shr 24).toByte()
            bytes[i*4+1] = (bits shr 16).toByte()
            bytes[i*4+2] = (bits shr  8).toByte()
            bytes[i*4+3] =  bits.toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun String.fromB64(): FloatArray? = try {
        val b = Base64.decode(this, Base64.NO_WRAP)
        FloatArray(b.size / 4) { i ->
            java.lang.Float.intBitsToFloat(
                ((b[i*4].toInt() and 0xFF) shl 24) or
                ((b[i*4+1].toInt() and 0xFF) shl 16) or
                ((b[i*4+2].toInt() and 0xFF) shl  8) or
                 (b[i*4+3].toInt() and 0xFF)
            )
        }
    } catch (_: Exception) { null }
}

private class ThreatDb(ctx: Context) : SQLiteOpenHelper(ctx, "vs_threats.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS threats(
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                hash       TEXT UNIQUE NOT NULL,
                mfcc_b64   TEXT NOT NULL,
                created_ms INTEGER NOT NULL
            )""".trimIndent())
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        db.execSQL("DROP TABLE IF EXISTS threats"); onCreate(db)
    }
}