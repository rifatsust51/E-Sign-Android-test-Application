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

    // ── ROOT CAUSE FIX ─────────────────────────────────────────────────────────
    //
    // THE BUG (why it fails on every attempt after the first):
    //
    //   EncryptedSharedPreferences is NOT safe to instantiate multiple times
    //   against the same file from different call sites in rapid succession.
    //   Each call to EncryptedSharedPreferences.create() opens the underlying
    //   XML file and builds a new in-memory key→value map. When two instances
    //   exist simultaneously (e.g. the instance used by clear() during logout
    //   and the instance used by save() during the next login), their in-memory
    //   caches are independent. An async apply() from the clear() instance can
    //   flush AFTER the save() instance has already written the new session,
    //   wiping it. Even with commit() this is still a problem because two
    //   separate Java objects both think they "own" the file.
    //
    //   On the FIRST login the process is brand-new so there is only ever one
    //   instance in memory — it works fine. On subsequent logins, the object
    //   created for clear() during logout is still in the JVM heap (GC hasn't
    //   collected it yet) when a new object is created for save() — they race.
    //
    // THE FIX:
    //   Cache a single EncryptedSharedPreferences instance for the lifetime of
    //   the process. All calls to save(), load(), and clear() share the SAME
    //   object, so there is no multi-instance cache inconsistency.
    //   If the instance is ever corrupted (KeyStore reset, OS bug), we wipe the
    //   raw file and rebuild — same recovery path as before, but only once.
    //
    @Volatile
    private var prefsInstance: android.content.SharedPreferences? = null

    private fun getPrefs(context: Context): android.content.SharedPreferences {
        // Double-checked locking — safe because prefsInstance is @Volatile
        prefsInstance?.let { return it }
        return synchronized(this) {
            prefsInstance ?: buildPrefs(context.applicationContext).also { prefsInstance = it }
        }
    }

    private fun buildPrefs(context: Context): android.content.SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context, PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Prefs file is corrupted (e.g. KeyStore wiped on factory reset).
            // Wipe the raw file and try once more.
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context, PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    fun save(context: Context, s: UserSession) {
        getPrefs(context).edit()
            .putString(KEY_USER_ID,       s.userId)
            .putString(KEY_FULL_NAME,     s.fullName)
            .putString(KEY_EMAIL,         s.email)
            .putString(KEY_ACCESS_TOKEN,  s.accessToken)
            .putString(KEY_REFRESH_TOKEN, s.refreshToken)
            .putLong(KEY_TOKEN_EXPIRY,    s.tokenExpiry)
            .commit() // synchronous — caller (AuthManager) proceeds only after data is on disk
    }

    fun load(context: Context): UserSession? {
        val p = getPrefs(context)
        val userId = p.getString(KEY_USER_ID, null) ?: return null
        return UserSession(
            userId,
            p.getString(KEY_FULL_NAME,    "Unknown")!!,
            p.getString(KEY_EMAIL,         "No email")!!,
            p.getString(KEY_ACCESS_TOKEN,  "")!!,
            p.getString(KEY_REFRESH_TOKEN, "")!!,
            p.getLong(KEY_TOKEN_EXPIRY, 0L)
        )
    }

    fun clear(context: Context) {
        // Clears data but keeps the same cached instance — no new object created,
        // no risk of a stale second instance racing with the next save().
        getPrefs(context).edit().clear().commit()
    }

    fun isExpired(session: UserSession, minValiditySecs: Long = 30L): Boolean =
        System.currentTimeMillis() / 1000L >= (session.tokenExpiry - minValiditySecs)
}