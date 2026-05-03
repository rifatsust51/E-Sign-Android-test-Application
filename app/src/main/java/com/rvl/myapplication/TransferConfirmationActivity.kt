package com.rvl.myapplication

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class TransferConfirmationActivity : AppCompatActivity() {

    companion object {
        private const val TAG           = "MFA_DEBUG"
        private const val SIGN_ENDPOINT =
            "https://192.168.0.155:8443/tspgatewayservice/gateway/signFile"
        var isActivityVisible = false
    }

    // ── Views ──────────────────────────────────────────────────────────────────
    private lateinit var tvFromName:          TextView
    private lateinit var tvToAccount:         TextView
    private lateinit var tvAmount:            TextView
    private lateinit var tvChannelLabel:      TextView
    private lateinit var tvDetailAccountName: TextView
    private lateinit var tvDetailSourceAcct:  TextView
    private lateinit var tvDetailBeneficiary: TextView
    private lateinit var tvSigningStatus:     TextView
    private lateinit var btnConfirm:          MaterialButton
    private lateinit var btnDeny:             MaterialButton

    // ── Pending intents from TicThik ───────────────────────────────────────────
    // Stored when broadcast arrives so the main buttons can fire them
    private var ticThikConfirmPending: PendingIntent? = null
    private var ticThikDenyPending:    PendingIntent? = null
    private var ticThikReceived        = false

    // ── State ──────────────────────────────────────────────────────────────────
    private val signingComplete    = AtomicBoolean(false)
    private var currentCall: Call? = null
    private var receiverRegistered = false

    // ── Intent data passed from TransferMoneyActivity ─────────────────────────
    private var base64Json         = ""
    private var fileName           = ""
    private var userId             = ""
    private var accessToken        = ""
    private var transferChannel    = ""
    private var timestamp          = ""
    private var sourceName         = ""
    private var sourceAccount      = ""
    private var beneficiaryAccount = ""
    private var amount             = ""

    // ── Broadcast receiver — registered in onCreate to avoid race condition ────
    private val pushReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "=== BROADCAST RECEIVED IN TransferConfirmation ===")
            Log.d(TAG, "Action: ${intent?.action}")

            val senderPkg = intent?.getStringExtra("sender_package")
            if (senderPkg != null && senderPkg != packageName) {
                Log.w(TAG, "Rejected broadcast from foreign package: $senderPkg")
                return
            }

            when (intent?.action) {
                PdfPreviewActivity.ACTION_SHOW_IN_APP_PUSH -> {
                    Log.d(TAG, "Processing TicThik push in TransferConfirmation")
                    runOnUiThread { handleTicThikBroadcast(intent) }
                }
                PdfPreviewActivity.ACTION_NOTIFICATION_DENIED -> {
                    Log.d(TAG, "Notification denied broadcast received")
                    runOnUiThread { triggerDeny(fromTicThik = true) }
                }
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transfer_confirmation)

        bindViews()
        readIntentData()
        populateUI()

        // ── CRITICAL: Register receiver in onCreate BEFORE callSignApi()
        // If registered only in onResume, the TicThik notification can arrive
        // between onCreate and onResume and the broadcast is missed entirely.
        registerPushReceiverNow()

        // Initial button state:
        // btnConfirm → disabled (green) — enabled only after TicThik confirms
        // btnDeny    → enabled  (red)   — always clickable
        btnConfirm.isEnabled = false
        btnDeny.isEnabled    = true
        btnDeny.text         = "Tap to Deny"

        btnConfirm.setOnClickListener {
            // Fires only after TicThik overlay wired the pending intents
            onConfirmTapped()
        }

        btnDeny.setOnClickListener {
            // Works as "Tap to Deny" OR "Go Back" depending on state
            onDenyOrGoBackTapped()
        }

        // Start sign API immediately
        callSignApi()
    }

    override fun onResume() {
        super.onResume()
        isActivityVisible = true
        // Receiver already registered in onCreate — just update flag
    }

    override fun onPause() {
        super.onPause()
        isActivityVisible = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isActivityVisible = false
        unregisterPushReceiver()
        currentCall?.cancel()
    }

    // ── Receiver registration ──────────────────────────────────────────────────

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerPushReceiverNow() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(PdfPreviewActivity.ACTION_SHOW_IN_APP_PUSH)
            addAction(PdfPreviewActivity.ACTION_NOTIFICATION_DENIED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(pushReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(pushReceiver, filter)
        receiverRegistered = true
        Log.d(TAG, "✅ Push receiver registered in onCreate")
    }

    private fun unregisterPushReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(pushReceiver)
            receiverRegistered = false
        } catch (e: Exception) {
            Log.w(TAG, "Receiver unregister error: ${e.message}")
        }
    }

    // ── View binding ───────────────────────────────────────────────────────────

    private fun bindViews() {
        tvFromName          = findViewById(R.id.tvFromName)
        tvToAccount         = findViewById(R.id.tvToAccount)
        tvAmount            = findViewById(R.id.tvAmount)
        tvChannelLabel      = findViewById(R.id.tvChannelLabel)
        tvDetailAccountName = findViewById(R.id.tvDetailAccountName)
        tvDetailSourceAcct  = findViewById(R.id.tvDetailSourceAcct)
        tvDetailBeneficiary = findViewById(R.id.tvDetailBeneficiary)
        tvSigningStatus     = findViewById(R.id.tvSigningStatus)
        btnConfirm          = findViewById(R.id.btnConfirm)
        btnDeny             = findViewById(R.id.btnDeny)
    }

    private fun readIntentData() {
        base64Json         = intent.getStringExtra("base64_json")         ?: ""
        fileName           = intent.getStringExtra("file_name")           ?: "transfer.json"
        userId             = intent.getStringExtra("user_id")             ?: ""
        accessToken        = intent.getStringExtra("access_token")        ?: ""
        transferChannel    = intent.getStringExtra("transfer_channel")    ?: "NPSB"
        timestamp          = intent.getStringExtra("timestamp")           ?: ""
        sourceName         = intent.getStringExtra("source_name")         ?: ""
        sourceAccount      = intent.getStringExtra("source_account")      ?: ""
        beneficiaryAccount = intent.getStringExtra("beneficiary_account") ?: ""
        amount             = intent.getStringExtra("amount")              ?: "0"
    }

    private fun populateUI() {
        tvFromName.text          = sourceName
        tvToAccount.text         = beneficiaryAccount
        tvAmount.text            = "BDT ${fmt(amount)}"
        tvChannelLabel.text      = "$transferChannel Transfer"
        tvDetailAccountName.text = sourceName
        tvDetailSourceAcct.text  = sourceAccount
        tvDetailBeneficiary.text = beneficiaryAccount
        tvSigningStatus.text     = "⏳ Waiting for TicThik push notification..."
        tvSigningStatus.setTextColor(Color.parseColor("#F59E0B"))
    }

    // ── Button handlers ────────────────────────────────────────────────────────

    private fun onConfirmTapped() {
        // btnConfirm is only enabled after TicThik notification arrives
        // and the pending intents are stored
        Log.d(TAG, "Main CONFIRM button tapped")
        btnConfirm.isEnabled = false
        btnDeny.isEnabled    = false
        tvSigningStatus.text = "🔄 Signing in progress..."
        tvSigningStatus.setTextColor(Color.parseColor("#3B82F6"))

        // Fire TicThik confirm pending intent
        try {
            ticThikConfirmPending?.send()
            Log.d(TAG, "✅ TicThik confirm intent fired from main button")
        } catch (e: Exception) {
            Log.e(TAG, "Confirm intent send failed: ${e.message}")
        }
        // Sign API is already running — it will complete and call goToSuccess()
    }

    private fun onDenyOrGoBackTapped() {
        Log.d(TAG, "Main DENY/GOBACK button tapped")
        // Fire TicThik deny intent if we have it
        try {
            ticThikDenyPending?.send()
            Log.d(TAG, "✅ TicThik deny intent fired")
        } catch (e: Exception) {
            Log.e(TAG, "Deny intent send failed: ${e.message}")
        }
        triggerDeny(fromTicThik = false)
    }

    private fun triggerDeny(fromTicThik: Boolean) {
        currentCall?.cancel()
        val msg = if (fromTicThik)
            "Transaction denied by TicThik."
        else
            "Transaction cancelled."
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    // ── TicThik broadcast handler ──────────────────────────────────────────────
    // Called when the broadcast arrives.
    // Does TWO things:
    //   1. Shows an in-app notification message card (no overlay, no blocking dialog)
    //      — it slides in UNDER the transaction details, ABOVE the buttons
    //   2. Wires the TicThik pending intents into the main "Tap to Confirm" /
    //      "Tap to Deny" buttons so they work correctly

    private fun handleTicThikBroadcast(intent: Intent) {
        if (ticThikReceived) {
            Log.d(TAG, "TicThik broadcast already handled, ignoring duplicate")
            return
        }
        ticThikReceived = true

        val notifTitle   = intent.getStringExtra("title")   ?: "Signature Request"
        val notifMessage = intent.getStringExtra("message") ?: "Please confirm your transfer."

        Log.d(TAG, "TicThik broadcast: title='$notifTitle' message='$notifMessage'")

        // ── Step 1: Resolve pending intents ────────────────────────────────────
        fun getPending(key: String): PendingIntent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(key, PendingIntent::class.java)
            else @Suppress("DEPRECATION")
            intent.getParcelableExtra(key) as? PendingIntent

        val a1Title   = intent.getStringExtra("action1_title")?.lowercase() ?: ""
        val a2Title   = intent.getStringExtra("action2_title")?.lowercase() ?: ""
        val a1Pending = getPending("action1_intent")
        val a2Pending = getPending("action2_intent")

        val denyKw    = listOf("deny", "no", "reject", "cancel")
        val confirmKw = listOf("confirm", "yes", "approve", "allow", "sign")

        // Detect which is confirm and which is deny by keyword matching
        when {
            confirmKw.any { a1Title.contains(it) } && denyKw.any { a2Title.contains(it) } -> {
                ticThikConfirmPending = a1Pending
                ticThikDenyPending    = a2Pending
            }
            denyKw.any { a1Title.contains(it) } && confirmKw.any { a2Title.contains(it) } -> {
                ticThikConfirmPending = a2Pending
                ticThikDenyPending    = a1Pending
            }
            denyKw.any { a1Title.contains(it) } -> {
                // action1 = deny, action2 = confirm (or use contentIntent)
                ticThikDenyPending    = a1Pending
                ticThikConfirmPending = a2Pending ?: getPending("contentIntent")
            }
            else -> {
                // Default: action1 = confirm
                ticThikConfirmPending = a1Pending ?: getPending("contentIntent")
                ticThikDenyPending    = a2Pending
            }
        }

        Log.d(TAG, "Resolved: confirmPending=${ticThikConfirmPending != null} " +
                "denyPending=${ticThikDenyPending != null}")

        // ── Step 2: Show the notification message card in-page ─────────────────
        showNotificationMessageCard(notifTitle, notifMessage)

        // ── Step 3: Enable the main Confirm button now that intents are wired ──
        btnConfirm.isEnabled = true
        btnDeny.isEnabled    = true
        btnDeny.text         = "Tap to Deny"

        tvSigningStatus.text = "📲 TicThik request received — tap Confirm or Deny"
        tvSigningStatus.setTextColor(Color.parseColor("#3B82F6"))
    }

    // ── In-page notification message card ─────────────────────────────────────
    // This inserts a styled notification card into the scroll view
    // ABOVE the Confirm/Deny buttons — NOT a blocking overlay.
    // The user can see all their transaction details AND the notification message
    // at the same time, then tap the main buttons.

    private fun showNotificationMessageCard(title: String, message: String) {
        val container = findViewById<ViewGroup>(R.id.notificationContainer)
        container.removeAllViews()
        container.visibility = View.VISIBLE

        val d = resources.displayMetrics.density

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background  = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#EFF6FF"))
                cornerRadius = 14f * d
                setStroke((1.5f * d).toInt(), Color.parseColor("#BFDBFE"))
            }
            setPadding(
                (14 * d).toInt(), (14 * d).toInt(),
                (14 * d).toInt(), (14 * d).toInt()
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Header row: lock icon + app name
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * d).toInt() }
        }

        val iconTv = TextView(this).apply {
            text     = "🔐"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * d).toInt() }
        }

        val appNameTv = TextView(this).apply {
            text     = "TicThik"
            textSize = 11f
            setTextColor(Color.parseColor("#6B7280"))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        headerRow.addView(iconTv)
        headerRow.addView(appNameTv)

        // Notification title
        val titleTv = TextView(this).apply {
            text     = title
            textSize = 14f
            setTextColor(Color.parseColor("#1E3A5F"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * d).toInt() }
        }

        // Notification message
        val messageTv = TextView(this).apply {
            text     = message
            textSize = 13f
            setTextColor(Color.parseColor("#374151"))
            setLineSpacing(4f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        card.addView(headerRow)
        card.addView(titleTv)
        card.addView(messageTv)
        container.addView(card)

        // Fade in animation
        container.alpha = 0f
        container.animate().alpha(1f).setDuration(300).start()
    }

    // ── Sign API ───────────────────────────────────────────────────────────────

    private fun callSignApi() {
        Log.d(TAG, "callSignApi: userId=$userId fileName=$fileName")

        if (base64Json.isBlank() || userId.isBlank() || accessToken.isBlank()) {
            Log.e(TAG, "Missing required sign data — aborting")
            setErrorState("Missing sign data. Please go back and retry.")
            return
        }

        val jsonPayload = JSONObject().apply {
            put("data",             base64Json)
            put("attached",         true)
            put("fileNames",        JSONArray().apply { put(fileName) })
            put("signatureType",    "JADES")
            put("signatureSubType", "JAdES_BASELINE_B")
            put("packaging",        "ENVELOPING")
            put("signAlgorithm",    "SHA256withRSA")
            put("hashAlgorithm",    "SHA256")
            put("userId",           userId)
            put("vSigEnabled",      false)
        }

        val reqBody = jsonPayload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(SIGN_ENDPOINT)
            .post(reqBody)
            .addHeader("Content-Type",  "application/json")
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        val client = NetworkUtils.buildMtlsOkHttpClient(
            context            = this,
            connectTimeoutSecs = 30,
            readTimeoutSecs    = 180,   // 3 min wait for TicThik user action
            writeTimeoutSecs   = 60
        )

        currentCall = client.newCall(request)
        currentCall?.enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) {
                    Log.d(TAG, "Sign call was cancelled")
                    return
                }
                Log.e(TAG, "Sign API failure: ${e.message}")
                runOnUiThread {
                    setErrorState("Network error: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) return
                val body = response.body?.string()
                Log.d(TAG, "Sign API ${response.code}: ${body?.take(400)}")

                if (!response.isSuccessful || body == null) {
                    runOnUiThread {
                        setErrorState("Server error: ${response.code}")
                    }
                    return
                }
                try {
                    val json    = JSONObject(body)
                    val apiCode = json.optInt("code", -1)
                    if (apiCode != 0) {
                        val msg = json.optString("message", "Signing failed")
                        runOnUiThread { setErrorState(msg) }
                        return
                    }
                    // SUCCESS
                    signingComplete.set(true)
                    Log.d(TAG, "✅ JSON signing successful")
                    runOnUiThread { goToSuccess() }
                } catch (e: Exception) {
                    Log.e(TAG, "Response parse error: ${e.message}")
                    runOnUiThread { setErrorState("Parse error: ${e.message}") }
                }
            }
        })
    }

    // ── Error state ────────────────────────────────────────────────────────────
    // When signing fails:
    //   - status shows the error in red
    //   - btnConfirm disabled
    //   - btnDeny text changes to "Go Back" and clicking it calls finish()

    private fun setErrorState(errorMessage: String) {
        tvSigningStatus.text = "❌ $errorMessage"
        tvSigningStatus.setTextColor(Color.parseColor("#EF4444"))
        btnConfirm.isEnabled = false
        // Change Deny → Go Back
        btnDeny.isEnabled = true
        btnDeny.text      = "Go Back"
        // Override deny click to just go back (no toast, no TicThik intent)
        btnDeny.setOnClickListener {
            Log.d(TAG, "Go Back tapped after error")
            finish()
        }
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    private fun goToSuccess() {
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, TransferSuccessActivity::class.java).apply {
            putExtra("amount",           amount)
            putExtra("transfer_channel", transferChannel)
            putExtra("source_name",      sourceName)
            putExtra("source_account",   sourceAccount)
            putExtra("timestamp",        timestamp)
        })
        finish()
    }

    private fun fmt(v: String) = try {
        String.format("%,.2f", v.toDouble())
    } catch (_: Exception) { v }
}