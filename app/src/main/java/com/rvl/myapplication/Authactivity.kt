package com.rvl.myapplication

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AuthActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AuthActivity"
        var isActivityVisible = false

        const val PREFS_NAME      = "auth_pkce_prefs"
        const val KEY_VERIFIER    = "pending_code_verifier"
        const val KEY_CALLER      = "auth_caller"
        const val CALLER_TRANSFER = "transfer"
        const val CALLER_PDF      = "pdf"

        @Volatile
        private var prefsInstance: android.content.SharedPreferences? = null

        private fun buildPrefs(context: Context): android.content.SharedPreferences {
            return try {
                val mk = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    context.applicationContext, PREFS_NAME, mk,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().clear().commit()
                val mk = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    context.applicationContext, PREFS_NAME, mk,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
        }

        fun generateCodeVerifier(): String {
            val bytes = ByteArray(64)
            java.security.SecureRandom().nextBytes(bytes)
            return android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.URL_SAFE or
                        android.util.Base64.NO_WRAP or
                        android.util.Base64.NO_PADDING
            )
        }

        fun generateCodeChallenge(verifier: String): String {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return android.util.Base64.encodeToString(
                hash,
                android.util.Base64.URL_SAFE or
                        android.util.Base64.NO_WRAP or
                        android.util.Base64.NO_PADDING
            )
        }
    }

    // --- STATE TRACKING TO PREVENT APP FREEZING ---
    private var isFirstResume = true
    private var isProcessingRedirect = false

    private fun prefs(): android.content.SharedPreferences {
        prefsInstance?.let { return it }
        return synchronized(AuthActivity::class.java) {
            prefsInstance ?: buildPrefs(applicationContext).also { prefsInstance = it }
        }
    }

    private fun saveVerifier(v: String, caller: String) {
        prefs().edit()
            .putString(KEY_VERIFIER, v)
            .putString(KEY_CALLER, caller)
            .commit()
    }

    private fun loadVerifier(): String? =
        try { prefs().getString(KEY_VERIFIER, null) } catch (_: Exception) { null }

    private fun loadCaller(): String =
        try { prefs().getString(KEY_CALLER, CALLER_TRANSFER) ?: CALLER_TRANSFER } catch (_: Exception) { CALLER_TRANSFER }

    private fun clearPrefs() {
        try { prefs().edit().remove(KEY_VERIFIER).remove(KEY_CALLER).commit() } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }



    // Safely detect if the user pressed 'X' to close the browser
    override fun onResume() {
        super.onResume()

        // Ignore the very first time onResume fires right after onCreate
        if (isFirstResume) {
            isFirstResume = false
            return
        }

        // If onResume fires again and we aren't currently processing a redirect,
        // it means the user manually closed the Chrome Custom Tab.
        if (!isProcessingRedirect) {
            Log.w(TAG, "Custom Tab closed manually. Cancelling auth.")
            clearPrefs()
            navigateBack(loadCaller(), authError = "Login cancelled")
        }
    }

    private fun handleIntent(intent: Intent) {
        val data = intent.data

        if (data != null && data.scheme == "myapp" && data.host == "callback") {
            isProcessingRedirect = true // Tell onResume not to trigger cancellation

            // ── ✨ NEW LOGOUT FIX: Check our local Android flag ───────────────
            val logoutPrefs = getSharedPreferences("logout_prefs", Context.MODE_PRIVATE)
            if (logoutPrefs.getBoolean("is_pending_logout", false)) {
                Log.d(TAG, "Keycloak browser cookie successfully cleared. Logout complete.")
                logoutPrefs.edit().clear().commit() // clear the flag
                clearPrefs()
                navigateBack(loadCaller(), authError = null)
                return
            }

            // ── Redirect back from Keycloak (Login) ────────────────────────────
            val code     = data.getQueryParameter("code")
            val error    = data.getQueryParameter("error")
            val verifier = loadVerifier()
            val caller   = loadCaller()

            when {
                error != null -> {
                    clearPrefs()
                    navigateBack(caller, authError = error)
                }
                code == null -> {
                    clearPrefs()
                    navigateBack(caller, authError = "no_code")
                }
                verifier == null -> {
                    navigateBack(caller, authError = "verifier_lost")
                }
                else -> {
                    clearPrefs()
                    AuthManager.exchangeCodeForToken(
                        context      = applicationContext,
                        authCode     = code,
                        codeVerifier = verifier,
                        onSuccess    = { _ ->
                            navigateBack(caller, authError = null)
                        },
                        onError = { msg ->
                            navigateBack(caller, authError = msg)
                        }
                    )
                }
            }
        } else {
            // ── Initial launch ─────────────────────────────────────────────────
            val verifier = intent.getStringExtra(KEY_VERIFIER) ?: generateCodeVerifier()
            val caller   = intent.getStringExtra(KEY_CALLER)   ?: CALLER_TRANSFER
            saveVerifier(verifier, caller)

            val authUri = buildAuthUri(generateCodeChallenge(verifier))
            isActivityVisible = true
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(this, authUri)
        }
    }

//    private fun navigateBack(caller: String, authError: String?) {
//        runOnUiThread {
//            val target = when (caller) {
//                CALLER_PDF -> PdfPreviewActivity::class.java
//                else       -> TransferMoneyActivity::class.java
//            }
//            startActivity(Intent(this, target).apply {
//                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
//                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
//                        Intent.FLAG_ACTIVITY_SINGLE_TOP
//                if (authError != null) putExtra("auth_error", authError)
//            })
//            finish()
//        }
//    }

    private fun navigateBack(caller: String, authError: String?) {
        isActivityVisible = false
        runOnUiThread {
            val target = when (caller) {
                CALLER_PDF -> PdfPreviewActivity::class.java
                else       -> TransferMoneyActivity::class.java
            }

            // ✨ FIX 2: Create a 0-millisecond animation bundle for the OS Task Switcher
            val options = androidx.core.app.ActivityOptionsCompat.makeCustomAnimation(this, 0, 0)

            startActivity(Intent(this, target).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                if (authError != null) putExtra("auth_error", authError)
            }, options.toBundle()) // <-- Apply the zero-animation bundle here

            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun buildAuthUri(codeChallenge: String): Uri =
        Uri.parse("https://portal.test.reliefvalidation.com.bd/auth/realms/CloudID/protocol/openid-connect/auth")
            .buildUpon()
            .appendQueryParameter("client_id",             AuthManager.CLIENT_ID)
            .appendQueryParameter("redirect_uri",          AuthManager.REDIRECT_URI)
            .appendQueryParameter("response_type",         "code")
            .appendQueryParameter("scope",                 "openid")
            // Removed prompt/max_age to prevent Keycloak Push Notification MFA crashes
            .appendQueryParameter("code_challenge",        codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
}