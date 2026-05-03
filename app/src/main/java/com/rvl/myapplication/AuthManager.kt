package com.rvl.myapplication

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

object AuthManager {

    private const val TAG            = "MFA_DEBUG"
    private const val KEYCLOAK_BASE  = "https://portal.test.reliefvalidation.com.bd/auth"
    private const val REALM          = "CloudID"
    const val CLIENT_ID              = "tspclient-public-1258-ovi_auth"
    const val REDIRECT_URI           = "myapp://callback"
    val TOKEN_ENDPOINT               = "$KEYCLOAK_BASE/realms/$REALM/protocol/openid-connect/token"

    fun exchangeCodeForToken(
        context: Context,
        authCode: String,
        codeVerifier: String,
        onSuccess: (UserSession) -> Unit,
        onError: (String) -> Unit
    ) {
        val formBody = FormBody.Builder()
            .add("grant_type",    "authorization_code")
            .add("client_id",     CLIENT_ID)
            .add("code",          authCode)
            .add("redirect_uri",  REDIRECT_URI)
            .add("code_verifier", codeVerifier)
            .build()

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(formBody)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()

        NetworkUtils.buildTrustedOkHttpClient(context).newCall(request)
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    onError("Network error: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string()
                    if (!response.isSuccessful || body == null) {
                        onError("Token exchange failed: ${response.code}")
                        return
                    }
                    try {
                        val json         = JSONObject(body)
                        val accessToken  = json.optString("access_token").ifBlank { null }
                            ?: run { onError("No access token"); return }
                        val refreshToken = json.optString("refresh_token").ifBlank { "" }
                        val claimsToken  = json.optString("id_token").ifBlank { accessToken }
                        val userId   = extractClaim(claimsToken, "sub")
                            ?: run { onError("Could not read user ID"); return }
                        val fullName = extractClaim(claimsToken, "name")  ?: "Unknown User"
                        val email    = extractClaim(claimsToken, "email") ?: "No email"
                        val expiry   = extractExp(accessToken)
                            ?: (System.currentTimeMillis() / 1000L + 300L)
                        val session  = UserSession(userId, fullName, email, accessToken, refreshToken, expiry)
                        SessionManager.save(context, session)
                        onSuccess(session)
                    } catch (e: Exception) {
                        onError("Token parse error: ${e.message}")
                    }
                }
            })
    }

    fun refreshIfNeeded(
        context: Context,
        session: UserSession,
        onSuccess: (UserSession) -> Unit,
        onError: () -> Unit
    ) {
        if (!SessionManager.isExpired(session)) { onSuccess(session); return }
        val refreshToken = session.refreshToken.ifBlank { null }
            ?: run { onError(); return }

        val formBody = FormBody.Builder()
            .add("grant_type",    "refresh_token")
            .add("client_id",     CLIENT_ID)
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(formBody)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()

        NetworkUtils.buildTrustedOkHttpClient(context).newCall(request)
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { onError() }
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string()
                    if (!response.isSuccessful || body == null) { onError(); return }
                    try {
                        val json       = JSONObject(body)
                        val newAccess  = json.optString("access_token").ifBlank { null }
                            ?: run { onError(); return }
                        val newRefresh = json.optString("refresh_token").ifBlank { session.refreshToken }
                        val newExpiry  = extractExp(newAccess)
                            ?: (System.currentTimeMillis() / 1000L + 300L)
                        val updated    = session.copy(
                            accessToken  = newAccess,
                            refreshToken = newRefresh,
                            tokenExpiry  = newExpiry
                        )
                        SessionManager.save(context, updated)
                        onSuccess(updated)
                    } catch (e: Exception) { onError() }
                }
            })
    }

    fun extractClaim(token: String, claim: String): String? = try {
        JSONObject(String(Base64.decode(
            token.split(".")[1], Base64.URL_SAFE or Base64.NO_PADDING
        ))).optString(claim).ifBlank { null }
    } catch (_: Exception) { null }

    fun extractExp(token: String): Long? = try {
        JSONObject(String(Base64.decode(
            token.split(".")[1], Base64.URL_SAFE or Base64.NO_PADDING
        ))).optLong("exp").takeIf { it > 0 }
    } catch (_: Exception) { null }
}