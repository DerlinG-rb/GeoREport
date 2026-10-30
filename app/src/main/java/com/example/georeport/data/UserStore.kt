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

    // Los usuarios se guardan como JSON en preferencias privadas de esta app en el dispositivo.
    // Este almacenamiento no es SQLite ni conecta con un servidor de usuarios.
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // El correo normalizado es la clave con la que buscamos cada cuenta.
    fun isEmailRegistered(email: String): Boolean = readUsers().has(normalize(email))

    // Devuelve false si el correo ya existe o si no se pudo guardar la cuenta.
    fun register(fullName: String, email: String, phone: String, password: String): Boolean {
        val key = normalize(email)
        val users = readUsers()
        if (users.has(key)) return false

        // Cada cuenta recibe una sal aleatoria: contraseñas iguales producen hashes distintos.
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
        // commit() escribe de forma síncrona y permite saber si el guardado tuvo éxito.
        return prefs.edit().putString(KEY_USERS, users.toString()).commit()
    }

    // Comprueba las credenciales; guardar la sesión es un paso separado de esta validación.
    fun login(email: String, password: String): Boolean {
        val user = readUsers().optJSONObject(normalize(email)) ?: return false
        val salt = user.optString(KEY_SALT)
        val expectedHash = user.optString(KEY_HASH)
        if (salt.isEmpty() || expectedHash.isEmpty()) return false

        // Recalculamos el hash con la sal guardada y lo comparamos; no desciframos una contraseña.
        val actualHash = hashPassword(password, salt)
        return MessageDigest.isEqual(
            expectedHash.toByteArray(Charsets.UTF_8),
            actualHash.toByteArray(Charsets.UTF_8)
        )
    }

    // Recupera el nombre para mostrarlo en la interfaz; null indica que no hay nombre disponible.
    fun getUserName(email: String): String? =
        readUsers().optJSONObject(normalize(email))?.optString(KEY_NAME)?.takeIf { it.isNotBlank() }

    // Recordamos el correo para abrir directamente la pantalla principal la próxima vez.
    // Es una sesión local, no un token de autenticación emitido por un servidor.
    fun saveSession(email: String) {
        prefs.edit().putString(KEY_SESSION, normalize(email)).apply()
    }

    // null significa que no hay una sesión local guardada.
    fun getSessionEmail(): String? =
        prefs.getString(KEY_SESSION, null)?.takeIf { it.isNotBlank() }

    // Cerrar sesión elimina el correo recordado, pero conserva la cuenta registrada.
    fun clearSession() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    // Convierte el texto guardado en un objeto JSON. Si falta o no se puede leer,
    // devuelve una colección vacía en memoria sin sobrescribir aquí los datos almacenados.
    private fun readUsers(): JSONObject {
        val raw = prefs.getString(KEY_USERS, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    // Evita tratar como cuentas distintas los correos con espacios externos o mayúsculas.
    private fun normalize(email: String): String = email.trim().lowercase()

    // SecureRandom genera los 16 bytes aleatorios de la sal de cada cuenta.
    private fun generateSalt(): String {
        val bytes = ByteArray(SALT_SIZE)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    // Aplica SHA-256 a sal + contraseña y repite el hash hasta completar HASH_ITERATIONS.
    private fun hashPassword(password: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var result = digest.digest((salt + password).toByteArray(Charsets.UTF_8))
        repeat(HASH_ITERATIONS - 1) {
            result = digest.digest(result)
        }
        return result.toHex()
    }

    // Representa los bytes como texto hexadecimal para poder guardarlos en el JSON.
    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }

    // Nombre de las preferencias, claves del JSON y parámetros usados al generar el hash.
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
