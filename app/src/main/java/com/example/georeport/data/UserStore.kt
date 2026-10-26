package com.example.georeport.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Almacenamiento local de usuarios y sesión.
 * Las contraseñas nunca se guardan en texto plano: se usa una sal aleatoria
 * y un hash SHA-256 iterado.
 */
class UserStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEmailRegistered(email: String): Boolean = readUsers().has(normalize(email))

    fun register(fullName: String, email: String, phone: String, password: String): Boolean {
        val key = normalize(email)
        val users = readUsers()
        if (users.has(key)) return false

        val salt = generateSalt()
        users.put(
            key,
            JSONObject().apply {
                put(KEY_NAME, fullName)
                put(KEY_PHONE, phone)
                put(KEY_SALT, salt)
                put(KEY_HASH, hashPassword(password, salt))
            }
        )
        return prefs.edit().putString(KEY_USERS, users.toString()).commit()
    }

    fun login(email: String, password: String): Boolean {
        val user = readUsers().optJSONObject(normalize(email)) ?: return false
        val salt = user.optString(KEY_SALT)
        val expectedHash = user.optString(KEY_HASH)
        if (salt.isEmpty() || expectedHash.isEmpty()) return false

        val actualHash = hashPassword(password, salt)
        return MessageDigest.isEqual(
            expectedHash.toByteArray(Charsets.UTF_8),
            actualHash.toByteArray(Charsets.UTF_8)
        )
    }

    fun getUserName(email: String): String? =
        readUsers().optJSONObject(normalize(email))?.optString(KEY_NAME)?.takeIf { it.isNotBlank() }

    fun saveSession(email: String) {
        prefs.edit().putString(KEY_SESSION, normalize(email)).apply()
    }

    fun getSessionEmail(): String? =
        prefs.getString(KEY_SESSION, null)?.takeIf { it.isNotBlank() }

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    private fun readUsers(): JSONObject {
        val raw = prefs.getString(KEY_USERS, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun normalize(email: String): String = email.trim().lowercase()

    private fun generateSalt(): String {
        val bytes = ByteArray(SALT_SIZE)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    private fun hashPassword(password: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var result = digest.digest((salt + password).toByteArray(Charsets.UTF_8))
        repeat(HASH_ITERATIONS - 1) {
            result = digest.digest(result)
        }
        return result.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }

    private companion object {
        const val PREFS_NAME = "georeport_users"
        const val KEY_USERS = "users"
        const val KEY_SESSION = "session_email"
        const val KEY_NAME = "name"
        const val KEY_PHONE = "phone"
        const val KEY_SALT = "salt"
        const val KEY_HASH = "hash"
        const val SALT_SIZE = 16
        const val HASH_ITERATIONS = 10_000
    }
}
