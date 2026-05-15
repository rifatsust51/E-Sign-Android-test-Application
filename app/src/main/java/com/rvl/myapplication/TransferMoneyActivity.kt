package com.rvl.myapplication
import java.net.URL
import java.net.HttpURLConnection
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class TransferMoneyActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MFA_DEBUG"
        private const val DEFAULT_SOURCE = ""

        private const val PENDING_PREFS = "pending_transfer_prefs"
        private const val KEY_PENDING = "has_pending_transfer"
        private const val KEY_SOURCE = "pt_source"
        private const val KEY_BENEFICIARY = "pt_beneficiary"
        private const val KEY_BANK = "pt_bank"
        private const val KEY_AMOUNT = "pt_amount"
        private const val KEY_REMARKS = "pt_remarks"
        private const val KEY_CHANNEL = "pt_channel"
        private const val KEY_OTP_CHANNEL = "pt_otp_channel"
    }

    // ── Views ──────────────────────────────────────────────────────────────────
    private lateinit var tabBeftn: TextView
    private lateinit var tabNpsb: TextView
    private lateinit var tabRtgs: TextView

    private lateinit var tilSourceAccount: TextInputLayout
    private lateinit var etSourceAccount: TextInputEditText
    private lateinit var tvAvailableBalance: TextView

    private lateinit var tilBeneficiary: TextInputLayout
    private lateinit var etBeneficiary: TextInputEditText
    private lateinit var tilBankName: TextInputLayout
    private lateinit var etBankName: TextInputEditText
    private lateinit var tilAmount: TextInputLayout
    private lateinit var etAmount: TextInputEditText
    private lateinit var tilRemarks: TextInputLayout
    private lateinit var etRemarks: TextInputEditText

    private lateinit var tabSms: TextView
    private lateinit var tabEmail: TextView
    private lateinit var tabPush: TextView
    private lateinit var tvOtpInfo: TextView
    private lateinit var btnRequest: MaterialButton

    private lateinit var profileCard: MaterialCardView
    private lateinit var tvProfileInitial: TextView
    private lateinit var tvProfileName: TextView
    private lateinit var tvProfileEmail: TextView
    private lateinit var tvProfileStatus: TextView
    private lateinit var btnLogout: MaterialButton

    // ── State ──────────────────────────────────────────────────────────────────
    private var selectedChannel = "NPSB"
    private var selectedOtpChannel = "SMS"
    private var currentSession: UserSession? = null
    private val isSigning = AtomicBoolean(false)

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transfer_money)
        bindViews()
        setupChannelTabs()
        setupOtpTabs()
        etSourceAccount.setText(DEFAULT_SOURCE)

        currentSession = SessionManager.load(this)
        if (currentSession != null) renderLoggedInUI(currentSession!!) else renderGuestUI()

        handleAuthResult(intent)

        btnRequest.setOnClickListener { onRequestClicked() }
        btnLogout.setOnClickListener { showLogoutConfirmation() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        setIntent(intent)
        // Reload session — AuthActivity just saved it before calling navigateBack()
        currentSession = SessionManager.load(this)
        if (currentSession != null) renderLoggedInUI(currentSession!!) else renderGuestUI()
        handleAuthResult(intent)
    }

    override fun onResume() {
        super.onResume()

        // Reset the signing lock so the button works again after returning
        // from TransferConfirmationActivity via back-press.
        // NOTE: Do NOT run pending-transfer recovery here — that belongs only
        // in onCreate / onNewIntent which receive the auth result intent.
        isSigning.set(false)
    }

    // ── Auth result handling (shared by onCreate and onNewIntent) ──────────────
    //
    // This is the SINGLE place that handles the post-auth return.
    // Called from onCreate (activity was destroyed while auth was in progress
    // → Android creates a brand-new instance) and from onNewIntent (activity
    // was still alive → Android brings it to front via FLAG_ACTIVITY_SINGLE_TOP).
    //
    // Decision tree:
    //   pending prefs exist + auth_error  → clear pending, show error
    //   pending prefs exist + session     → restore form, go to confirmation ✅
    //   pending prefs exist + no session  → restore form, show "did not complete"
    //   no pending prefs + auth_error     → show error (edge case)
    //   no pending prefs + no auth_error  → normal launch / back from confirmation

    private fun handleAuthResult(intent: Intent) {
        val pendingPrefs = getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
        val hasPending = pendingPrefs.getBoolean(KEY_PENDING, false)

        if (!hasPending) {
            // Normal launch or returning from TransferConfirmationActivity.
            // Still show any auth error that arrived without pending prefs (rare).
            intent.getStringExtra("auth_error")?.let { showError("Login failed: $it") }
            return
        }

        val authError = intent.getStringExtra("auth_error")
        val session = currentSession

        Log.d(
            TAG,
            "handleAuthResult: hasPending=true authError=$authError sessionNull=${session == null}"
        )

        when {
            authError != null -> {
                Log.e(TAG, "Auth failed with pending transfer: $authError")
                clearPendingTransferPrefs()
                showError("Login failed: $authError")
            }

            session != null -> {
                Log.d(TAG, "Auth succeeded — restoring form and proceeding to confirmation")
                restoreFormFromPrefs(pendingPrefs)
                clearPendingTransferPrefs()
                startSigningAndGoToConfirmation(session)
            }

            else -> {
                // Pending data but no session — user cancelled auth or it failed
                // silently. Restore the form so they don't have to retype.
                Log.w(TAG, "Pending transfer but no session (auth cancelled?)")
                restoreFormFromPrefs(pendingPrefs)
                clearPendingTransferPrefs()
                showError("Login did not complete. Please try again.")
            }
        }
    }

    // ── Pending-transfer prefs ─────────────────────────────────────────────────

    private fun saveFormToPrefs() {
        getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PENDING, true)
            .putString(KEY_SOURCE, etSourceAccount.text.toString().trim())
            .putString(KEY_BENEFICIARY, etBeneficiary.text.toString().trim())
            .putString(KEY_BANK, etBankName.text.toString().trim())
            .putString(KEY_AMOUNT, etAmount.text.toString().trim())
            .putString(KEY_REMARKS, etRemarks.text.toString().trim())
            .putString(KEY_CHANNEL, selectedChannel)
            .putString(KEY_OTP_CHANNEL, selectedOtpChannel)
            .commit() // synchronous — must flush before AuthActivity opens
        Log.d(TAG, "Form data saved to pending prefs")
    }

    private fun restoreFormFromPrefs(prefs: android.content.SharedPreferences) {
        etSourceAccount.setText(prefs.getString(KEY_SOURCE, DEFAULT_SOURCE))
        etBeneficiary.setText(prefs.getString(KEY_BENEFICIARY, ""))
        etBankName.setText(prefs.getString(KEY_BANK, ""))
        etAmount.setText(prefs.getString(KEY_AMOUNT, ""))
        etRemarks.setText(prefs.getString(KEY_REMARKS, ""))

        val channel = prefs.getString(KEY_CHANNEL, "NPSB") ?: "NPSB"
        val otpChannel = prefs.getString(KEY_OTP_CHANNEL, "SMS") ?: "SMS"
        selectedChannel = channel
        selectedOtpChannel = otpChannel

        listOf(tabBeftn, tabNpsb, tabRtgs).forEach { deselectTab(it) }
        when (channel) {
            "BEFTN" -> selectChannelTab(tabBeftn)
            "RTGS" -> selectChannelTab(tabRtgs)
            else -> selectChannelTab(tabNpsb)
        }
        listOf(tabSms, tabEmail, tabPush).forEach { deselectTab(it) }
        when (otpChannel) {
            "EMAIL" -> selectOtpTab(tabEmail)
            "PUSH_NOTIFICATION" -> selectOtpTab(tabPush)
            else -> selectOtpTab(tabSms)
        }
        updateOtpInfo()
        updateButtonText()
        Log.d(TAG, "Form data restored from pending prefs")
    }

    private fun clearPendingTransferPrefs() {
        getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        Log.d(TAG, "Pending transfer prefs cleared")
    }

    // ── View binding ───────────────────────────────────────────────────────────

    private fun bindViews() {
        tabBeftn = findViewById(R.id.tabBeftn)
        tabNpsb = findViewById(R.id.tabNpsb)
        tabRtgs = findViewById(R.id.tabRtgs)

        tilSourceAccount = findViewById(R.id.tilSourceAccount)
        etSourceAccount = findViewById(R.id.etSourceAccount)
        tvAvailableBalance = findViewById(R.id.tvAvailableBalance)

        tilBeneficiary = findViewById(R.id.tilBeneficiary)
        etBeneficiary = findViewById(R.id.etBeneficiary)
        tilBankName = findViewById(R.id.tilBankName)
        etBankName = findViewById(R.id.etBankName)
        tilAmount = findViewById(R.id.tilAmount)
        etAmount = findViewById(R.id.etAmount)
        tilRemarks = findViewById(R.id.tilRemarks)
        etRemarks = findViewById(R.id.etRemarks)

        tabSms = findViewById(R.id.tabSms)
        tabEmail = findViewById(R.id.tabEmail)
        tabPush = findViewById(R.id.tabPush)
        tvOtpInfo = findViewById(R.id.tvOtpInfo)
        btnRequest = findViewById(R.id.btnRequest)

        profileCard = findViewById(R.id.profileCard)
        tvProfileInitial = findViewById(R.id.tvProfileInitial)
        tvProfileName = findViewById(R.id.tvProfileName)
        tvProfileEmail = findViewById(R.id.tvProfileEmail)
        tvProfileStatus = findViewById(R.id.tvProfileStatus)
        btnLogout = findViewById(R.id.btnLogout)
    }

    // ── Tab setup ──────────────────────────────────────────────────────────────

    private fun setupChannelTabs() {
        selectChannelTab(tabNpsb)
        mapOf(tabBeftn to "BEFTN", tabNpsb to "NPSB", tabRtgs to "RTGS").forEach { (tab, ch) ->
            tab.setOnClickListener {
                selectedChannel = ch
                listOf(tabBeftn, tabNpsb, tabRtgs).forEach { deselectTab(it) }
                selectChannelTab(tab)
            }
        }
    }

    private fun setupOtpTabs() {
        selectOtpTab(tabSms)
        updateOtpInfo()
        mapOf(tabSms to "SMS", tabEmail to "EMAIL", tabPush to "PUSH_NOTIFICATION")
            .forEach { (tab, ch) ->
                tab.setOnClickListener {
                    selectedOtpChannel = ch
                    listOf(tabSms, tabEmail, tabPush).forEach { deselectTab(it) }
                    selectOtpTab(tab)
                    updateOtpInfo()
                    updateButtonText()
                }
            }
    }

    private fun selectChannelTab(tab: TextView) {
        tab.setBackgroundResource(R.drawable.bg_tab_active)
        tab.setTextColor(getColor(android.R.color.white))
    }

    private fun selectOtpTab(tab: TextView) {
        tab.setBackgroundResource(R.drawable.bg_tab_active)
        tab.setTextColor(getColor(android.R.color.white))
    }

    private fun deselectTab(tab: TextView) {
        tab.setBackgroundResource(R.drawable.bg_tab_inactive)
        tab.setTextColor(getColor(android.R.color.darker_gray))
    }

    private fun updateOtpInfo() {
        tvOtpInfo.text = when (selectedOtpChannel) {
            "SMS" -> "OTP will be sent to your registered mobile number."
            "EMAIL" -> "OTP will be sent to your registered email address."
            "PUSH_NOTIFICATION" -> "A push notification will be sent to your TicThik app."
            else -> ""
        }
    }

    private fun updateButtonText() {
        btnRequest.text = when (selectedOtpChannel) {
            "PUSH_NOTIFICATION" -> "Request & Confirm via TicThik"
            else -> "Request Transfer"
        }
    }

    // ── Main button handler ────────────────────────────────────────────────────

    private fun onRequestClicked() {
        if (!validateForm()) return

        val session = currentSession
        if (session == null) {
            showLoading("Opening login...")
            saveFormToPrefs()
            startActivity(
                Intent(this, AuthActivity::class.java).apply {
                    putExtra(AuthActivity.KEY_VERIFIER, AuthActivity.generateCodeVerifier())
                    putExtra(AuthActivity.KEY_CALLER, AuthActivity.CALLER_TRANSFER)
                }
            )
            return
        }

        showLoading("Checking session...")
        AuthManager.refreshIfNeeded(
            context = this,
            session = session,
            onSuccess = { updatedSession ->
                currentSession = updatedSession
                runOnUiThread { startSigningAndGoToConfirmation(updatedSession) }
            },
            onError = {
                runOnUiThread {
                    showError("Session expired. Please log in again.")
                    SessionManager.clear(this)
                    currentSession = null
                    renderGuestUI()
                    resetButton()
                }
            }
        )
    }

    // ── Navigate to Confirmation ───────────────────────────────────────────────

    private fun startSigningAndGoToConfirmation(session: UserSession) {
        if (isSigning.getAndSet(true)) {
            Log.w(TAG, "startSigningAndGoToConfirmation called while already signing — ignored")
            return
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val sourceAcct = etSourceAccount.text.toString().trim().ifBlank { DEFAULT_SOURCE }
        val beneficiary = etBeneficiary.text.toString().trim()
        val bankName = etBankName.text.toString().trim()
        val amount = etAmount.text.toString().trim()
        val remarks = etRemarks.text.toString().trim()

        val transferJson = JSONObject().apply {
            put("transferChannel", selectedChannel)
            put("sourceAccount", sourceAcct)
            put("beneficiaryAccount", beneficiary)
            put("bankName", bankName)
            put("amount", amount)
            put("remarks", remarks)
            put("otpChannel", "PUSH_NOTIFICATION")
            put("timestamp", timestamp)
        }

        val base64Json = android.util.Base64.encodeToString(
            transferJson.toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val safeTs = timestamp.replace(":", "-").replace(" ", "_")
        val fileName = "transfer_$safeTs.json"

        Log.d(
            TAG,
            "→ TransferConfirmation: source=$sourceAcct beneficiary=$beneficiary amount=$amount"
        )

        startActivity(Intent(this, TransferConfirmationActivity::class.java).apply {
            putExtra("source_name", session.fullName)
            putExtra("source_account", sourceAcct)
            putExtra("beneficiary_account", beneficiary)
            putExtra("bank_name", bankName)
            putExtra("amount", amount)
            putExtra("transfer_channel", selectedChannel)
            putExtra("timestamp", timestamp)
            putExtra("base64_json", base64Json)
            putExtra("file_name", fileName)
            putExtra("user_id", session.userId)
            putExtra("access_token", session.accessToken)
        })

        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        resetButton()
        isSigning.set(false)
    }

    // ── Form validation ────────────────────────────────────────────────────────

    private fun validateForm(): Boolean {
        var valid = true

        if (etSourceAccount.text.toString().trim().isBlank()) {
            tilSourceAccount.error = "Source account is required"; valid = false
        } else tilSourceAccount.error = null

        if (etBeneficiary.text.isNullOrBlank()) {
            tilBeneficiary.error = "Beneficiary account is required"; valid = false
        } else tilBeneficiary.error = null

        if (etBankName.text.isNullOrBlank()) {
            tilBankName.error = "Bank name is required"; valid = false
        } else tilBankName.error = null

        val amountStr = etAmount.text.toString().trim()
        when {
            amountStr.isBlank() -> {
                tilAmount.error = "Amount is required"; valid = false
            }

            amountStr.toDoubleOrNull() == null -> {
                tilAmount.error = "Invalid amount"; valid = false
            }

            amountStr.toDouble() < 50.0 -> {
                tilAmount.error = "Minimum amount is BDT 50"; valid = false
            }

            amountStr.toDouble() > 300000.0 -> {
                tilAmount.error = "Maximum amount is BDT 3,00,000"; valid = false
            }

            else -> tilAmount.error = null
        }

        if (!valid) Toast.makeText(this, "Please fix the errors above", Toast.LENGTH_SHORT).show()
        return valid
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────

    private fun renderLoggedInUI(session: UserSession) {
        tvProfileInitial.text = session.fullName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        tvProfileName.text = session.fullName
        tvProfileEmail.text = session.email
        tvProfileStatus.text = "Authenticated ✓"
        tvProfileStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        profileCard.visibility = View.VISIBLE
        btnLogout.visibility = View.VISIBLE
        tvAvailableBalance.text = "N/A"
    }

    private fun renderGuestUI() {
        tvProfileInitial.text = "?"
        tvProfileName.text = "Guest"
        tvProfileEmail.text = "Not logged in"
        tvProfileStatus.text = "Guest mode"
        tvProfileStatus.setTextColor(getColor(android.R.color.darker_gray))
        profileCard.visibility = View.VISIBLE
        btnLogout.visibility = View.GONE
    }

    private fun showLoading(msg: String) {
        btnRequest.text = msg
        btnRequest.isEnabled = false
    }

    private fun resetButton() {
        runOnUiThread {
            updateButtonText()
            btnRequest.isEnabled = true
        }
    }

    private fun showError(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

//    private fun showLogoutConfirmation() {
//        AlertDialog.Builder(this)
//            .setTitle("Log out")
//            .setMessage("You will need to log in again for the next transfer.")
//            .setPositiveButton("Log out") { _, _ ->
//                // 1. Clear local app session
//                SessionManager.clear(this)
//                clearPendingTransferPrefs()
//                currentSession = null
//                renderGuestUI()
//
//                // 2. Set a local flag so AuthActivity knows we are logging out
//                getSharedPreferences("logout_prefs", Context.MODE_PRIVATE)
//                    .edit().putBoolean("is_pending_logout", true).commit()
//
//                // 3. Clear Keycloak session in the browser using the EXACT whitelisted URI!
//                val logoutUri = Uri.parse("https://portal.test.reliefvalidation.com.bd/auth/realms/CloudID/protocol/openid-connect/logout")
//                    .buildUpon()
//                    .appendQueryParameter("client_id", AuthManager.CLIENT_ID)
//                    .appendQueryParameter("post_logout_redirect_uri", AuthManager.REDIRECT_URI) // Exactly "myapp://callback"
//                    .build()
//
//                androidx.browser.customtabs.CustomTabsIntent.Builder()
//                    .setShowTitle(true)
//                    .build()
//                    .launchUrl(this@TransferMoneyActivity, logoutUri)
//            }
//            .setNegativeButton("Cancel", null)
//            .show()
//    }



    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Log out")
            .setMessage("You will need to log in again for the next transfer.")
            .setPositiveButton("Log out") { _, _ ->

                // 1. Kill the Keycloak session seamlessly in the background! (No browser flash)
                currentSession?.let { session ->
                    Thread {
                        try {
                            val url = URL("https://portal.test.reliefvalidation.com.bd/auth/realms/CloudID/protocol/openid-connect/logout")
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.doOutput = true
                            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                            // Send the refresh token to Keycloak so it knows which session to destroy
                            val postData = "client_id=${AuthManager.CLIENT_ID}&refresh_token=${session.refreshToken}"
                            conn.outputStream.write(postData.toByteArray(Charsets.UTF_8))

                            Log.d("MFA_DEBUG", "Background Keycloak Logout Response: ${conn.responseCode}")
                            conn.disconnect()
                        } catch (e: Exception) {
                            Log.e("MFA_DEBUG", "Background Keycloak Logout Failed", e)
                        }
                    }.start()
                }

                // 2. Clear local Android app data instantly
                SessionManager.clear(this)
                clearPendingTransferPrefs()
                currentSession = null
                renderGuestUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}