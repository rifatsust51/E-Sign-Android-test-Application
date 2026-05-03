package com.rvl.myapplication

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SessionManager {

    private const val PREFS_NAME        = "tsp_secure_prefs"
    private const val KEY_USER_ID       = "user_id"
    private const val KEY_FULL_NAME     = "full_name"
    private const val KEY_EMAIL         = "email"
    private const val KEY_ACCESS_TOKEN  = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_TOKEN_EXPIRY  = "token_expiry"

    private fun getPrefs(context: Context) = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(context: Context, s: UserSession) {
        getPrefs(context).edit()
            .putString(KEY_USER_ID,       s.userId)
            .putString(KEY_FULL_NAME,     s.fullName)
            .putString(KEY_EMAIL,         s.email)
            .putString(KEY_ACCESS_TOKEN,  s.accessToken)
            .putString(KEY_REFRESH_TOKEN, s.refreshToken)
            .putLong(KEY_TOKEN_EXPIRY,    s.tokenExpiry)
            .apply()
    }

    fun load(context: Context): UserSession? {
        val p = getPrefs(context)
        val userId = p.getString(KEY_USER_ID, null) ?: return null
        return UserSession(
            userId,
            p.getString(KEY_FULL_NAME,     "Unknown")!!,
            p.getString(KEY_EMAIL,          "No email")!!,
            p.getString(KEY_ACCESS_TOKEN,   "")!!,
            p.getString(KEY_REFRESH_TOKEN,  "")!!,
            p.getLong(KEY_TOKEN_EXPIRY, 0L)
        )
    }

    fun clear(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    fun isExpired(session: UserSession, minValiditySecs: Long = 30L): Boolean =
        System.currentTimeMillis() / 1000L >= (session.tokenExpiry - minValiditySecs)
}