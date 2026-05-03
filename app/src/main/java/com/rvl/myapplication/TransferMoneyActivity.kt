package com.rvl.myapplication

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
        private const val TAG            = "MFA_DEBUG"
        // Default source account — user can override by typing in the field
        private const val DEFAULT_SOURCE = ""
    }

    // ── Transfer channel tabs ──────────────────────────────────────────────────
    private lateinit var tabBeftn: TextView
    private lateinit var tabNpsb:  TextView
    private lateinit var tabRtgs:  TextView

    // ── Transfer From (editable) ───────────────────────────────────────────────
    private lateinit var tilSourceAccount: TextInputLayout
    private lateinit var etSourceAccount:  TextInputEditText
    private lateinit var tvAvailableBalance: TextView

    // ── Form fields ────────────────────────────────────────────────────────────
    private lateinit var tilBeneficiary: TextInputLayout
    private lateinit var etBeneficiary:  TextInputEditText
    private lateinit var tilBankName:    TextInputLayout
    private lateinit var etBankName:     TextInputEditText
    private lateinit var tilAmount:      TextInputLayout
    private lateinit var etAmount:       TextInputEditText
    private lateinit var tilRemarks:     TextInputLayout
    private lateinit var etRemarks:      TextInputEditText

    // ── OTP channel tabs ───────────────────────────────────────────────────────
    private lateinit var tabSms:   TextView
    private lateinit var tabEmail: TextView
    private lateinit var tabPush:  TextView
    private lateinit var tvOtpInfo: TextView
    private lateinit var btnRequest: MaterialButton

    // ── Profile card ───────────────────────────────────────────────────────────
    private lateinit var profileCard:      MaterialCardView
    private lateinit var tvProfileInitial: TextView
    private lateinit var tvProfileName:    TextView
    private lateinit var tvProfileEmail:   TextView
    private lateinit var tvProfileStatus:  TextView
    private lateinit var btnLogout:        MaterialButton

    // ── State ──────────────────────────────────────────────────────────────────
    private var selectedChannel    = "NPSB"
    private var selectedOtpChannel = "SMS"
    private var currentSession: UserSession? = null
    private val isSigning = AtomicBoolean(false)

    // ── Keycloak launcher ──────────────────────────────────────────────────────
    private val keycloakLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val authCode     = result.data?.getStringExtra(KeycloakWebViewActivity.EXTRA_AUTH_CODE)
                val codeVerifier = result.data?.getStringExtra(KeycloakWebViewActivity.EXTRA_CODE_VERIFIER)
                if (authCode != null && codeVerifier != null) {
                    showLoading("Verifying login...")
                    AuthManager.exchangeCodeForToken(
                        context      = this,
                        authCode     = authCode,
                        codeVerifier = codeVerifier,
                        onSuccess    = { session ->
                            currentSession = session
                            runOnUiThread {
                                renderLoggedInUI(session)
                                startSigningAndGoToConfirmation(session)
                            }
                        },
                        onError = { msg ->
                            runOnUiThread {
                                showError(msg)
                                resetButton()
                            }
                        }
                    )
                } else {
                    showError("Login failed: missing auth data")
                    resetButton()
                }
            } else {
                val error = result.data?.getStringExtra(KeycloakWebViewActivity.EXTRA_ERROR)
                runOnUiThread {
                    if (error != null) showError("Login cancelled: $error")
                    resetButton()
                }
            }
        }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transfer_money)
        bindViews()
        setupChannelTabs()
        setupOtpTabs()

        // Pre-fill source account with default — user can edit it
        etSourceAccount.setText(DEFAULT_SOURCE)

        currentSession = SessionManager.load(this)
        if (currentSession != null) renderLoggedInUI(currentSession!!) else renderGuestUI()

        btnRequest.setOnClickListener { onRequestClicked() }
        btnLogout.setOnClickListener  { showLogoutConfirmation() }
    }

    // ── View binding ───────────────────────────────────────────────────────────

    private fun bindViews() {
        // Channel tabs
        tabBeftn = findViewById(R.id.tabBeftn)
        tabNpsb  = findViewById(R.id.tabNpsb)
        tabRtgs  = findViewById(R.id.tabRtgs)

        // Transfer From — editable
        tilSourceAccount  = findViewById(R.id.tilSourceAccount)
        etSourceAccount   = findViewById(R.id.etSourceAccount)
        tvAvailableBalance = findViewById(R.id.tvAvailableBalance)

        // Form fields
        tilBeneficiary = findViewById(R.id.tilBeneficiary)
        etBeneficiary  = findViewById(R.id.etBeneficiary)
        tilBankName    = findViewById(R.id.tilBankName)
        etBankName     = findViewById(R.id.etBankName)
        tilAmount      = findViewById(R.id.tilAmount)
        etAmount       = findViewById(R.id.etAmount)
        tilRemarks     = findViewById(R.id.tilRemarks)
        etRemarks      = findViewById(R.id.etRemarks)

        // OTP tabs
        tabSms    = findViewById(R.id.tabSms)
        tabEmail  = findViewById(R.id.tabEmail)
        tabPush   = findViewById(R.id.tabPush)
        tvOtpInfo = findViewById(R.id.tvOtpInfo)
        btnRequest = findViewById(R.id.btnRequest)

        // Profile card
        profileCard      = findViewById(R.id.profileCard)
        tvProfileInitial = findViewById(R.id.tvProfileInitial)
        tvProfileName    = findViewById(R.id.tvProfileName)
        tvProfileEmail   = findViewById(R.id.tvProfileEmail)
        tvProfileStatus  = findViewById(R.id.tvProfileStatus)
        btnLogout        = findViewById(R.id.btnLogout)
    }

    // ── Tab setup ──────────────────────────────────────────────────────────────

    private fun setupChannelTabs() {
        selectChannelTab(tabNpsb) // NPSB selected by default
        mapOf(tabBeftn to "BEFTN", tabNpsb to "NPSB", tabRtgs to "RTGS").forEach { (tab, ch) ->
            tab.setOnClickListener {
                selectedChannel = ch
                listOf(tabBeftn, tabNpsb, tabRtgs).forEach { deselectTab(it) }
                selectChannelTab(tab)
            }
        }
    }

    private fun setupOtpTabs() {
        selectOtpTab(tabSms) // SMS selected by default
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
        tab.background = null
        tab.setTextColor(getColor(android.R.color.darker_gray))
    }

    private fun updateOtpInfo() {
        tvOtpInfo.text = when (selectedOtpChannel) {
            "SMS"               -> "OTP will be sent to 880******4776"
            "EMAIL"             -> "OTP will be sent to your registered email"
            "PUSH_NOTIFICATION" -> "An e-Sign confirmation will be sent to your app"
            else                -> ""
        }
    }

    private fun updateButtonText() {
        btnRequest.text = when (selectedOtpChannel) {
            "SMS"               -> "Request OTP"
            "EMAIL"             -> "Request Email OTP"
            "PUSH_NOTIFICATION" -> "Request E-Sign Notification"
            else                -> "Request OTP"
        }
    }

    // ── Request button clicked ─────────────────────────────────────────────────

    private fun onRequestClicked() {
        if (!validateForm()) return
        when (selectedOtpChannel) {
            "PUSH_NOTIFICATION" -> handlePushFlow()
            else -> {
                // SMS / Email OTP — not in scope of this task
                Toast.makeText(
                    this,
                    "OTP sent via $selectedOtpChannel",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ── Push notification flow ─────────────────────────────────────────────────

    private fun handlePushFlow() {
        val session = currentSession
        if (session == null) {
            // Not logged in → open Keycloak WebView login
            showLoading("Opening login...")
            keycloakLauncher.launch(Intent(this, KeycloakWebViewActivity::class.java))
            return
        }
        // Already logged in → refresh token if needed → go to confirmation
        showLoading("Checking session...")
        AuthManager.refreshIfNeeded(
            context   = this,
            session   = session,
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

    // ── Navigate to Confirmation + pass all data ───────────────────────────────

    private fun startSigningAndGoToConfirmation(session: UserSession) {
        // Guard against double-tap
        if (isSigning.getAndSet(true)) return

        val timestamp   = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
        ).format(Date())

        // Read all form values
        val sourceAcct  = etSourceAccount.text.toString().trim()
            .ifBlank { DEFAULT_SOURCE }   // fallback to default if user cleared the field
        val beneficiary = etBeneficiary.text.toString().trim()
        val bankName    = etBankName.text.toString().trim()
        val amount      = etAmount.text.toString().trim()
        val remarks     = etRemarks.text.toString().trim()

        // Build the transfer JSON that will be signed
        val transferJson = JSONObject().apply {
            put("transferChannel",    selectedChannel)
            put("sourceAccount",      sourceAcct)
            put("beneficiaryAccount", beneficiary)
            put("bankName",           bankName)
            put("amount",             amount)
            put("remarks",            remarks)
            put("otpChannel",         "PUSH_NOTIFICATION")
            put("timestamp",          timestamp)
        }

        val base64Json = android.util.Base64.encodeToString(
            transferJson.toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )

        val safeTs   = timestamp.replace(":", "-").replace(" ", "_")
        val fileName = "transfer_$safeTs.json"

        Log.d("MFA_DEBUG", "Navigating to confirmation: " +
                "source=$sourceAcct beneficiary=$beneficiary amount=$amount")

        // Navigate to TransferConfirmationActivity with all data
        // The sign API call starts inside TransferConfirmationActivity.onCreate()
        startActivity(Intent(this, TransferConfirmationActivity::class.java).apply {
            putExtra("source_name",         session.fullName)
            putExtra("source_account",      sourceAcct)
            putExtra("beneficiary_account", beneficiary)
            putExtra("bank_name",           bankName)
            putExtra("amount",              amount)
            putExtra("transfer_channel",    selectedChannel)
            putExtra("timestamp",           timestamp)
            putExtra("base64_json",         base64Json)
            putExtra("file_name",           fileName)
            putExtra("user_id",             session.userId)
            putExtra("access_token",        session.accessToken)
        })

        // Reset so user can make another transfer when they return
        resetButton()
        isSigning.set(false)
    }

    // ── Form validation ────────────────────────────────────────────────────────

    private fun validateForm(): Boolean {
        var valid = true

        // Source account
        val sourceAcct = etSourceAccount.text.toString().trim()
        if (sourceAcct.isBlank()) {
            tilSourceAccount.error = "Source account is required"
            valid = false
        } else {
            tilSourceAccount.error = null
        }

        // Beneficiary account
        if (etBeneficiary.text.isNullOrBlank()) {
            tilBeneficiary.error = "Beneficiary account is required"
            valid = false
        } else {
            tilBeneficiary.error = null
        }

        // Bank name
        if (etBankName.text.isNullOrBlank()) {
            tilBankName.error = "Bank name is required"
            valid = false
        } else {
            tilBankName.error = null
        }

        // Amount
        val amountStr = etAmount.text.toString().trim()
        when {
            amountStr.isBlank() -> {
                tilAmount.error = "Amount is required"
                valid = false
            }
            amountStr.toDoubleOrNull() == null -> {
                tilAmount.error = "Invalid amount"
                valid = false
            }
            amountStr.toDouble() < 50.0 -> {
                tilAmount.error = "Minimum amount is BDT 50"
                valid = false
            }
            amountStr.toDouble() > 300000.0 -> {
                tilAmount.error = "Maximum amount is BDT 3,00,000"
                valid = false
            }
            else -> tilAmount.error = null
        }

        if (!valid) {
            Toast.makeText(this, "Please fix the errors above", Toast.LENGTH_SHORT).show()
        }
        return valid
    }

    // ── UI state helpers ───────────────────────────────────────────────────────

    private fun renderLoggedInUI(session: UserSession) {
        tvProfileInitial.text =
            session.fullName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        tvProfileName.text   = session.fullName
        tvProfileEmail.text  = session.email
        tvProfileStatus.text = "Authenticated ✓"
        tvProfileStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        profileCard.visibility = View.VISIBLE
        btnLogout.visibility   = View.VISIBLE
        tvAvailableBalance.text = "N/A"
    }

    private fun renderGuestUI() {
        tvProfileInitial.text = "?"
        tvProfileName.text    = "Guest"
        tvProfileEmail.text   = "Not logged in"
        tvProfileStatus.text  = "Guest mode"
        tvProfileStatus.setTextColor(getColor(android.R.color.darker_gray))
        profileCard.visibility = View.VISIBLE
        btnLogout.visibility   = View.GONE
    }

    private fun showLoading(msg: String) {
        btnRequest.text      = msg
        btnRequest.isEnabled = false
    }

    private fun resetButton() {
        runOnUiThread {
            updateButtonText()
            btnRequest.isEnabled = true
        }
    }

    private fun showError(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Log out")
            .setMessage("You will need to log in again for the next transfer.")
            .setPositiveButton("Log out") { _, _ ->
                SessionManager.clear(this)
                currentSession = null
                renderGuestUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}