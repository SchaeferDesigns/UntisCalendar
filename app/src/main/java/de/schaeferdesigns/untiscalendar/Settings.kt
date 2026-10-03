package de.schaeferdesigns.untiscalendar

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App settings. The password is encrypted with a key that never leaves the Android Keystore. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var server: String
        get() = prefs.getString("server", DEFAULT_SERVER)!!
        set(value) = prefs.edit().putString("server", value).apply()

    var school: String
        get() = prefs.getString("school", DEFAULT_SCHOOL)!!
        set(value) = prefs.edit().putString("school", value).apply()

    var username: String
        get() = prefs.getString("username", "")!!
        set(value) = prefs.edit().putString("username", value).apply()

    var password: String
        get() = prefs.getString("password", null)?.let { decrypt(it) } ?: ""
        set(value) = prefs.edit().putString("password", encrypt(value)).apply()

    val hasPassword: Boolean get() = prefs.contains("password")

    var calendarId: Long
        get() = prefs.getLong("calendarId", -1)
        set(value) = prefs.edit().putLong("calendarId", value).apply()

    var autoSync: Boolean
        get() = prefs.getBoolean("autoSync", true)
        set(value) = prefs.edit().putBoolean("autoSync", value).apply()

    var lastStatus: String
        get() = prefs.getString("lastStatus", "Noch nicht synchronisiert.")!!
        set(value) = prefs.edit().putString("lastStatus", value).apply()

    val isComplete: Boolean
        get() = server.isNotBlank() && school.isNotBlank() && username.isNotBlank() &&
            hasPassword && calendarId >= 0

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = try {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_LENGTH))
        String(cipher.doFinal(data, IV_LENGTH, data.size - IV_LENGTH), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    companion object {
        const val DEFAULT_SERVER = "hbg-schwaebisch-gmuend.webuntis.com"
        const val DEFAULT_SCHOOL = "hbg-schwaebisch-gmuend"
        private const val KEY_ALIAS = "untis_credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
    }
}
