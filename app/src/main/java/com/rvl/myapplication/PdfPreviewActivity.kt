package com.rvl.myapplication

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class UserSession(
    val userId: String,
    val fullName: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenExpiry: Long
)

class PdfPreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MFA_DEBUG"
        private const val KEYCLOAK_BASE = "https://portal.test.reliefvalidation.com.bd/auth"
        private const val REALM = "CloudID"
        private const val CLIENT_ID = "tspclient-public-1258-ovi_auth"
        private const val REDIRECT_URI = "myapp://callback"
        private const val TOKEN_ENDPOINT =
            "$KEYCLOAK_BASE/realms/$REALM/protocol/openid-connect/token"
        private const val SIGN_ENDPOINT =
            "https://192.168.0.155:8443/tspgatewayservice/gateway/signFile"

        private const val PREFS_NAME = "tsp_secure_prefs"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_FULL_NAME = "full_name"
        private const val KEY_EMAIL = "email"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"

        const val ACTION_SHOW_IN_APP_PUSH = "com.rvl.myapplication.SHOW_IN_APP_PUSH"
        const val ACTION_NOTIFICATION_CONFIRMED = "com.rvl.myapplication.NOTIFICATION_CONFIRMED"
        const val ACTION_NOTIFICATION_DENIED = "com.rvl.myapplication.NOTIFICATION_DENIED"

        var isActivityVisible = false

        fun buildTrustAllOkHttpClient(
            connectTimeoutSecs: Long = 30,
            readTimeoutSecs: Long = 120,
            writeTimeoutSecs: Long = 60
        ): OkHttpClient {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("SSL").also {
                it.init(null, trustAllCerts, SecureRandom())
            }
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(connectTimeoutSecs, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSecs, TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSecs, TimeUnit.SECONDS)
                .build()
        }
    }

    private lateinit var ivPdfPreview: ImageView
    private lateinit var btnLoginAndSign: MaterialButton
    private lateinit var profileCard: MaterialCardView
    private lateinit var tvProfileInitial: TextView
    private lateinit var tvProfileName: TextView
    private lateinit var tvProfileEmail: TextView
    private lateinit var tvProfileStatus: TextView
    private lateinit var btnLogout: MaterialButton

    private var base64PdfDocument: String? = null
    private var dynamicFileName: String = "EmployeeProfile.pdf"
    private var currentSession: UserSession? = null
    private var cachedSignedFile: File? = null
    private var cachedMediaStoreUri: android.net.Uri? = null

    private var isWaitingForConfirmation = AtomicBoolean(false)
    private var currentSigningCall: Call? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pushBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "=== BROADCAST RECEIVED IN PDFPREVIEW ===")
            Log.d(TAG, "Action: ${intent?.action}")

            // Security: verify sender
            val senderPkg = intent?.getStringExtra("sender_package")
            if (senderPkg != null && senderPkg != packageName) {
                Log.w(TAG, "Rejected broadcast from: $senderPkg")
                return
            }

            when (intent?.action) {
                ACTION_SHOW_IN_APP_PUSH -> {
                    Log.d(TAG, "Processing SHOW_IN_APP_PUSH")
                    Log.d(TAG, "  action1_title: ${intent.getStringExtra("action1_title")}")
                    Log.d(TAG, "  action2_title: ${intent.getStringExtra("action2_title")}")
                    Log.d(TAG, "  has action1_intent: ${intent.hasExtra("action1_intent")}")
                    Log.d(TAG, "  has action2_intent: ${intent.hasExtra("action2_intent")}")
                    showInAppNotification(intent)
                }

                ACTION_NOTIFICATION_CONFIRMED -> {
                    Log.d(TAG, "Processing NOTIFICATION_CONFIRMED")
                }

                ACTION_NOTIFICATION_DENIED -> {
                    Log.d(TAG, "Processing NOTIFICATION_DENIED")
                    handleSigningDenied()
                }
            }
        }
    }

    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            this, PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val keycloakLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val authCode = result.data?.getStringExtra(KeycloakWebViewActivity.EXTRA_AUTH_CODE)
                val codeVerifier =
                    result.data?.getStringExtra(KeycloakWebViewActivity.EXTRA_CODE_VERIFIER)
                if (authCode != null && codeVerifier != null) {
                    runOnUiThread {
                        btnLoginAndSign.text = "Verifying login..."; btnLoginAndSign.isEnabled =
                        false
                    }
                    exchangeCodeForToken(authCode, codeVerifier, thenSign = true)
                } else {
                    showError("Login failed: missing auth data"); resetToGuestState()
                }
            } else {
                val error = result.data?.getStringExtra(KeycloakWebViewActivity.EXTRA_ERROR)
                runOnUiThread { if (error != null) showError("Login failed: $error"); resetToGuestState() }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_preview)

        bindViews()
        requestServiceRebind()
        checkNotificationListenerPermission()

        intent.getStringExtra("FILE_PATH")?.let { path ->
            showPdfImage(path)
            try {
                val file = File(path)
                dynamicFileName = file.name.ifBlank { "EmployeeProfile.pdf" }
                base64PdfDocument = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e(TAG, "PDF encode failed: ${e.message}")
            }
        }

        currentSession = loadSessionFromPrefs()
        if (currentSession != null) renderLoggedInUI(currentSession!!) else renderGuestUI()

        btnLoginAndSign.setOnClickListener {
            cancelPendingSigning()

            when {
                cachedSignedFile != null -> openPdf(cachedSignedFile!!)
                currentSession != null -> {
                    btnLoginAndSign.text = "Checking session..."
                    btnLoginAndSign.isEnabled = false
                    refreshTokenIfNeeded { ok ->
                        runOnUiThread {
                            if (ok) {
                                btnLoginAndSign.text = "Waiting for confirmation..."
                                proceedToSign()
                            } else {
                                showError("Session expired. Please log in again.")
                                clearStoredSession(); currentSession = null; renderGuestUI()
                            }
                        }
                    }
                }

                else -> {
                    btnLoginAndSign.text = "Opening login..."; btnLoginAndSign.isEnabled = false
                    keycloakLauncher.launch(Intent(this, KeycloakWebViewActivity::class.java))
                }
            }
        }

        btnLogout.setOnClickListener { showLogoutConfirmation() }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        isActivityVisible = true

        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW_IN_APP_PUSH)
            addAction(ACTION_NOTIFICATION_CONFIRMED)
            addAction(ACTION_NOTIFICATION_DENIED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pushBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(pushBroadcastReceiver, filter)
        }

        Log.d(TAG, "PdfPreview receiver registered with RECEIVER_EXPORTED")
    }

    override fun onPause() {
        super.onPause()
        isActivityVisible = false
        try {
            unregisterReceiver(pushBroadcastReceiver)
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelPendingSigning()
    }

    private fun cancelPendingSigning() {
        isWaitingForConfirmation.set(false)
        currentSigningCall?.cancel()
        currentSigningCall = null
    }

    private fun handleSigningDenied() {
        cancelPendingSigning()
        runOnUiThread {
            tvProfileStatus.text = "Signing denied"
            tvProfileStatus.setTextColor(getColor(android.R.color.holo_red_light))
            btnLoginAndSign.text = "Sign Document"
            btnLoginAndSign.isEnabled = true
            showError("Signing was denied. You can try again.")
        }
    }

    private fun checkNotificationListenerPermission() {
        val packages = NotificationManagerCompat.getEnabledListenerPackages(this)
        if (!packages.contains(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("To display signature notifications seamlessly inside this app, please grant Notification Access.")
                .setPositiveButton("Go to Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun requestServiceRebind() {
        try {
            val componentName = ComponentName(this, TicThikNotificationInterceptor::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                NotificationListenerService.requestRebind(componentName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Rebind failed: ${e.message}")
        }
    }

    private fun showInAppNotification(intent: Intent) {
        val rootView = findViewById<ViewGroup>(android.R.id.content)
        rootView.findViewWithTag<View>("in_app_notification_overlay")
            ?.let { rootView.removeView(it) }

        val title = intent.getStringExtra("title") ?: "Signature Request"
        val message = intent.getStringExtra("message") ?: "Please confirm your action."

        val contentIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("contentIntent", PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra("contentIntent") as? PendingIntent
        }

        val action1Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("action1_intent", PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra("action1_intent") as? PendingIntent
        }
        val action1Title = intent.getStringExtra("action1_title")?.lowercase() ?: ""

        val action2Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("action2_intent", PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra("action2_intent") as? PendingIntent
        }
        val action2Title = intent.getStringExtra("action2_title")?.lowercase() ?: ""

        Log.d(
            TAG,
            "showInAppNotification - action1: '$action1Title' intent=${action1Intent != null}"
        )
        Log.d(
            TAG,
            "showInAppNotification - action2: '$action2Title' intent=${action2Intent != null}"
        )
        Log.d(TAG, "showInAppNotification - contentIntent=${contentIntent != null}")

        var confirmIntent: PendingIntent? = contentIntent
        var denyIntent: PendingIntent? = null

        if (action1Title.contains("confirm") || action1Title.contains("yes") || action1Title.contains(
                "approve"
            ) || action1Title.contains("allow")
        ) {
            confirmIntent = action1Intent
        } else if (action1Title.contains("deny") || action1Title.contains("no") || action1Title.contains(
                "reject"
            ) || action1Title.contains("cancel")
        ) {
            denyIntent = action1Intent
        }

        if (action2Title.contains("confirm") || action2Title.contains("yes") || action2Title.contains(
                "approve"
            ) || action2Title.contains("allow")
        ) {
            confirmIntent = action2Intent
        } else if (action2Title.contains("deny") || action2Title.contains("no") || action2Title.contains(
                "reject"
            ) || action2Title.contains("cancel")
        ) {
            denyIntent = action2Intent
        }

        if (confirmIntent == null && action1Intent != null) confirmIntent = action1Intent
        if (denyIntent == null && action2Intent != null) denyIntent = action2Intent

        Log.d(TAG, "Final confirmIntent=${confirmIntent != null}, denyIntent=${denyIntent != null}")

        val density = resources.displayMetrics.density

        val overlay = FrameLayout(this).apply {
            tag = "in_app_notification_overlay"
            setBackgroundColor(Color.parseColor("#80000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = true
        }

        val cardDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 32f * density
        }

        val cardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable
            elevation = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                setMargins(
                    (24 * density).toInt(),
                    (48 * density).toInt(),
                    (24 * density).toInt(),
                    (48 * density).toInt()
                )
            }
        }

        val topSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                (32 * density).toInt(),
                (32 * density).toInt(),
                (32 * density).toInt(),
                (24 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val iconBadgeDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor("#FFF7ED"))
            setStroke((2 * density).toInt(), Color.parseColor("#FDBA74"))
        }

        val iconBadge = FrameLayout(this).apply {
            background = iconBadgeDrawable
            val size = (80 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
                .apply { bottomMargin = (24 * density).toInt() }
        }

        val iconText = TextView(this).apply {
            text = "🔐"
            textSize = 36f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        iconBadge.addView(iconText)

        val titleView = TextView(this).apply {
            text = title
            textSize = 22f
            setTextColor(Color.parseColor("#111827"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val messageView = TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(Color.parseColor("#6B7280"))
            gravity = Gravity.CENTER
            setLineSpacing(8f, 1.5f)
            maxLines = 6
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding((8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        topSection.addView(iconBadge)
        topSection.addView(titleView)
        topSection.addView(messageView)

        val buttonSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((24 * density).toInt(), 0, (24 * density).toInt(), (32 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val confirmBtnDrawable = android.graphics.drawable.GradientDrawable()
            .apply { setColor(Color.parseColor("#10B981")); cornerRadius = 16f * density }
        val confirmBtnPressedDrawable = android.graphics.drawable.GradientDrawable()
            .apply { setColor(Color.parseColor("#059669")); cornerRadius = 16f * density }
        val confirmStateList = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), confirmBtnPressedDrawable)
            addState(intArrayOf(), confirmBtnDrawable)
        }

        val btnConfirm = TextView(this).apply {
            text = "Confirm Signature"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = confirmStateList
            isClickable = true
            isFocusable = true
            setPadding(
                (24 * density).toInt(),
                (18 * density).toInt(),
                (24 * density).toInt(),
                (18 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
        }

        val denyBtnDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius =
            16f * density; setStroke((2 * density).toInt(), Color.parseColor("#FCA5A5"))
        }
        val denyBtnPressedDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#FEF2F2")); cornerRadius = 16f * density; setStroke(
            (2 * density).toInt(),
            Color.parseColor("#DC2626")
        )
        }
        val denyStateList = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), denyBtnPressedDrawable)
            addState(intArrayOf(), denyBtnDrawable)
        }

        val btnDeny = TextView(this).apply {
            text = "Deny"
            textSize = 16f
            setTextColor(Color.parseColor("#DC2626"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = denyStateList
            isClickable = true
            isFocusable = true
            setPadding(
                (24 * density).toInt(),
                (18 * density).toInt(),
                (24 * density).toInt(),
                (18 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        buttonSection.addView(btnConfirm)
        buttonSection.addView(btnDeny)

        cardView.addView(topSection)
        cardView.addView(buttonSection)
        overlay.addView(cardView)
        rootView.addView(overlay)

        overlay.alpha = 0f
        cardView.scaleX = 0.92f
        cardView.scaleY = 0.92f
        cardView.alpha = 0f

        overlay.animate().alpha(1f).setDuration(250).start()
        cardView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(350)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.6f)).start()

        fun dismissWithAnimation(onComplete: () -> Unit) {
            cardView.animate().scaleX(0.98f).scaleY(0.98f).alpha(0f).setDuration(200).start()
            overlay.animate().alpha(0f).setDuration(250).withEndAction {
                rootView.removeView(overlay)
                onComplete()
            }.start()
        }

        btnDeny.setOnClickListener {
            Log.d(TAG, "DENY clicked, denyIntent=${denyIntent != null}")
            dismissWithAnimation {
                try {
                    denyIntent?.send()
                    Log.d(TAG, "Deny intent sent successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Deny Failed: ${e.message}")
                }
                val deniedIntent = Intent(ACTION_NOTIFICATION_DENIED)
                deniedIntent.setPackage(packageName)
                sendBroadcast(deniedIntent)
            }
        }

        btnConfirm.setOnClickListener {
            Log.d(TAG, "CONFIRM clicked, confirmIntent=${confirmIntent != null}")
            dismissWithAnimation {
                try {
                    confirmIntent?.send()
                    Log.d(TAG, "Confirm intent sent successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Confirm Failed: ${e.message}")
                }
                runOnUiThread { btnLoginAndSign.text = "Signing in progress..." }
            }
        }
    }

    private fun bindViews() {
        ivPdfPreview = findViewById(R.id.ivPdfPreview)
        btnLoginAndSign = findViewById(R.id.btnLoginAndSign)
        profileCard = findViewById(R.id.profileCard)
        tvProfileInitial = findViewById(R.id.tvProfileInitial)
        tvProfileName = findViewById(R.id.tvProfileName)
        tvProfileEmail = findViewById(R.id.tvProfileEmail)
        tvProfileStatus = findViewById(R.id.tvProfileStatus)
        btnLogout = findViewById(R.id.btnLogout)
    }

    private fun renderLoggedInUI(session: UserSession) {
        val initial = session.fullName.firstOrNull()?.uppercaseChar() ?: '?'
        tvProfileInitial.text = initial.toString()
        tvProfileName.text = session.fullName
        tvProfileEmail.text = session.email
        tvProfileStatus.text = "Authenticated"
        tvProfileStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        profileCard.visibility = View.VISIBLE
        btnLoginAndSign.text = "Sign Document"
        btnLoginAndSign.isEnabled = true
        btnLogout.visibility = View.VISIBLE
    }

    private fun renderGuestUI() {
        tvProfileInitial.text = "?"
        tvProfileName.text = "Guest"
        tvProfileEmail.text = "Not logged in"
        tvProfileStatus.text = "Guest mode"
        tvProfileStatus.setTextColor(getColor(android.R.color.darker_gray))
        profileCard.visibility = View.VISIBLE
        btnLoginAndSign.text = "Login and Sign Document"
        btnLoginAndSign.isEnabled = true
        btnLogout.visibility = View.GONE
    }

    private fun resetToGuestState() {
        btnLoginAndSign.text = "Login and Sign Document"; btnLoginAndSign.isEnabled = true
    }

    private fun resetToSignState() {
        btnLoginAndSign.text = "Sign Document"; btnLoginAndSign.isEnabled = true
    }

    private fun exchangeCodeForToken(
        authCode: String,
        codeVerifier: String,
        thenSign: Boolean = false
    ) {
        val formBody =
            FormBody.Builder().add("grant_type", "authorization_code").add("client_id", CLIENT_ID)
                .add("code", authCode).add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", codeVerifier).build()
        val request = Request.Builder().url(TOKEN_ENDPOINT).post(formBody)
            .addHeader("Content-Type", "application/x-www-form-urlencoded").build()

        buildTrustAllOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { showError("Network error"); resetToGuestState() }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    runOnUiThread { showError("Token exchange failed"); resetToGuestState() }; return
                }
                try {
                    val json = JSONObject(body)
                    val accessToken = json.optString("access_token").ifBlank { null }
                        ?: run { runOnUiThread { showError("No token"); resetToGuestState() }; return }
                    val refreshToken = json.optString("refresh_token").ifBlank { "" }
                    val claimsToken = json.optString("id_token").ifBlank { accessToken }
                    val userId = extractClaim(claimsToken, "sub") ?: run {
                        runOnUiThread { showError("Could not read ID"); resetToGuestState() }; return
                    }
                    val fullName = extractClaim(claimsToken, "name") ?: "Unknown User"
                    val email = extractClaim(claimsToken, "email") ?: "No email"
                    val expiry = extractExpFromJwt(accessToken)
                        ?: (System.currentTimeMillis() / 1000L + 300L)

                    val session =
                        UserSession(userId, fullName, email, accessToken, refreshToken, expiry)
                    saveSessionToPrefs(session)
                    currentSession = session

                    runOnUiThread {
                        renderLoggedInUI(session)
                        if (thenSign) {
                            btnLoginAndSign.text = "Waiting for confirmation..."
                            btnLoginAndSign.isEnabled = false
                            proceedToSign()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { showError("Token parse error"); resetToGuestState() }
                }
            }
        })
    }

    private fun isTokenExpired(minValiditySecs: Long = 30L): Boolean {
        val expiry = currentSession?.tokenExpiry ?: return true
        return System.currentTimeMillis() / 1000L >= (expiry - minValiditySecs)
    }

    private fun refreshTokenIfNeeded(onResult: (success: Boolean) -> Unit) {
        if (!isTokenExpired()) {
            onResult(true); return
        }
        val refreshToken = currentSession?.refreshToken?.ifBlank { null }
        if (refreshToken == null) {
            onResult(false); return
        }

        val formBody = FormBody.Builder().add("grant_type", "refresh_token")
            .add("client_id", CLIENT_ID).add("refresh_token", refreshToken).build()
        val request = Request.Builder().url(TOKEN_ENDPOINT).post(formBody)
            .addHeader("Content-Type", "application/x-www-form-urlencoded").build()

        buildTrustAllOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    onResult(false); return
                }
                try {
                    val json = JSONObject(body)
                    val newAccess = json.optString("access_token").ifBlank { null }
                        ?: run { onResult(false); return }
                    val newRefresh = json.optString("refresh_token")
                        .ifBlank { currentSession?.refreshToken ?: "" }
                    val newExpiry =
                        extractExpFromJwt(newAccess) ?: (System.currentTimeMillis() / 1000L + 300L)
                    val updated = currentSession!!.copy(
                        accessToken = newAccess,
                        refreshToken = newRefresh,
                        tokenExpiry = newExpiry
                    )
                    saveSessionToPrefs(updated)
                    currentSession = updated
                    onResult(true)
                } catch (e: Exception) {
                    onResult(false)
                }
            }
        })
    }

    private fun proceedToSign() {
        val session =
            currentSession ?: run { showError("No active session"); renderGuestUI(); return }
        val pdf =
            base64PdfDocument ?: run { showError("PDF not loaded"); resetToSignState(); return }
        isWaitingForConfirmation.set(true)
        signFileService(pdf, session.userId, dynamicFileName, session.accessToken)
    }

    private fun signFileService(
        base64Pdf: String,
        userId: String,
        fileName: String,
        token: String
    ) {
        Log.d(TAG, "signFileService: Starting for userId=$userId")
        runOnUiThread {
            btnLoginAndSign.text = "Waiting for confirmation..."
            btnLoginAndSign.isEnabled = false
            tvProfileStatus.text = "Confirm in notification"
            tvProfileStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
        }

        val jsonPayload = JSONObject().apply {
            put("data", base64Pdf); put("attached", true)
            put("fileNames", JSONArray().apply { put(fileName) })
            put("signatureType", "PADES"); put("signatureSubType", "PAdES_BASELINE_B")
            put("packaging", "ENVELOPED"); put(
            "signAlgorithm",
            "SHA256withRSA"
        ); put("hashAlgorithm", "SHA256")
            put("userId", userId); put("vSigEnabled", true); put("vSigPage", 1)
            put("vSigXPosition", 170); put("vSigYPosition", 430)
        }

        val body = jsonPayload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(SIGN_ENDPOINT).post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $token").build()

        val client = buildTrustAllOkHttpClient(
            connectTimeoutSecs = 30,
            readTimeoutSecs = 180,
            writeTimeoutSecs = 60
        )

        currentSigningCall = client.newCall(request)
        currentSigningCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) {
                    Log.d(TAG, "Signing cancelled"); return
                }
                Log.e(TAG, "Sign API failure: ${e.message}")
                isWaitingForConfirmation.set(false)
                runOnUiThread {
                    if (e.message?.contains(
                            "timeout",
                            ignoreCase = true
                        ) == true
                    ) showError("Confirmation timeout.")
                    else showError("Network error: ${e.message}")
                    tvProfileStatus.text = "Signing failed"
                    tvProfileStatus.setTextColor(getColor(android.R.color.holo_red_light))
                    resetToSignState()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) {
                    Log.d(TAG, "Signing cancelled but response arrived"); return
                }
                isWaitingForConfirmation.set(false)
                val responseString = response.body?.string()
                Log.d(TAG, "Sign API ${response.code}: ${responseString?.take(200)}")

                if (!response.isSuccessful || responseString == null) {
                    runOnUiThread {
                        showError("Server error: ${response.code}"); tvProfileStatus.text =
                        "Signing failed"; tvProfileStatus.setTextColor(getColor(android.R.color.holo_red_light)); resetToSignState()
                    }
                    return
                }
                try {
                    val json = JSONObject(responseString)
                    val apiCode = json.optInt("code", -1)
                    if (apiCode != 0) {
                        val msg = json.optString("message", "Unknown error")
                        runOnUiThread {
                            showError("Signing error: $msg"); tvProfileStatus.text =
                            "Signing failed"; tvProfileStatus.setTextColor(getColor(android.R.color.holo_red_light)); resetToSignState()
                        }
                        return
                    }
                    val dataObj = json.optJSONObject("data")
                    val signedBase64: String? = dataObj?.let { d ->
                        d.optJSONArray("signedData")?.optString(0)?.ifBlank { null } ?: d.optString(
                            "signedData"
                        ).ifBlank { null }
                    } ?: json.optString("signedData").ifBlank { null }

                    if (signedBase64 == null) {
                        runOnUiThread { showError("No signed document"); resetToSignState() }; return
                    }
                    val signedPdfBytes = Base64.decode(signedBase64, Base64.DEFAULT)
                    val savedFile = saveToDownloads(signedPdfBytes, "signed_$fileName")

                    runOnUiThread {
                        if (savedFile != null) {
                            cachedSignedFile = savedFile
                            tvProfileStatus.text = "Document signed ✓"
                            tvProfileStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                            btnLoginAndSign.text = "Download Signed Docs"
                            btnLoginAndSign.isEnabled = true
                            Toast.makeText(
                                this@PdfPreviewActivity,
                                "Document signed successfully!",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            showError("Signed but failed to save"); resetToSignState()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { showError("Error: ${e.message}"); resetToSignState() }
                }
            }
        })
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this).setTitle("Log out").setMessage("You will need to log in again.")
            .setPositiveButton("Log out") { _, _ ->
                cancelPendingSigning(); clearStoredSession(); currentSession = null
                cachedSignedFile = null; cachedMediaStoreUri = null; renderGuestUI()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun saveSessionToPrefs(s: UserSession) {
        securePrefs.edit().putString(KEY_USER_ID, s.userId).putString(KEY_FULL_NAME, s.fullName)
            .putString(KEY_EMAIL, s.email).putString(KEY_ACCESS_TOKEN, s.accessToken)
            .putString(KEY_REFRESH_TOKEN, s.refreshToken).putLong(KEY_TOKEN_EXPIRY, s.tokenExpiry)
            .apply()
    }

    private fun loadSessionFromPrefs(): UserSession? {
        val userId = securePrefs.getString(KEY_USER_ID, null) ?: return null
        return UserSession(
            userId,
            securePrefs.getString(KEY_FULL_NAME, "Unknown")!!,
            securePrefs.getString(KEY_EMAIL, "No email")!!,
            securePrefs.getString(KEY_ACCESS_TOKEN, "")!!,
            securePrefs.getString(KEY_REFRESH_TOKEN, "")!!,
            securePrefs.getLong(KEY_TOKEN_EXPIRY, 0L)
        )
    }

    private fun clearStoredSession() {
        securePrefs.edit().clear().apply()
    }

    private fun extractClaim(token: String, claim: String): String? {
        return try {
            JSONObject(
                String(
                    Base64.decode(
                        token.split(".")[1],
                        Base64.URL_SAFE or Base64.NO_PADDING
                    )
                )
            ).optString(claim).ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractExpFromJwt(token: String): Long? {
        return try {
            JSONObject(
                String(
                    Base64.decode(
                        token.split(".")[1],
                        Base64.URL_SAFE or Base64.NO_PADDING
                    )
                )
            ).optLong("exp").takeIf { it > 0 }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToDownloads(pdfBytes: ByteArray, fileName: String): File? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(
                        MediaStore.Downloads.DISPLAY_NAME,
                        fileName
                    ); put(
                    MediaStore.Downloads.MIME_TYPE,
                    "application/pdf"
                ); put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: return null
                contentResolver.openOutputStream(uri)
                    ?.use { it.write(pdfBytes) }; cv.clear(); cv.put(
                    MediaStore.Downloads.IS_PENDING,
                    0
                ); contentResolver.update(uri, cv, null, null)
                cachedMediaStoreUri = uri
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    fileName
                )
            } else {
                val dir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { it.write(pdfBytes) }
                android.media.MediaScannerConnection.scanFile(
                    this,
                    arrayOf(file.absolutePath),
                    arrayOf("application/pdf"),
                    null
                ); file
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveToDownloads failed: ${e.message}"); null
        }
    }

    private fun openPdf(file: File) {
        try {
            val uri =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cachedMediaStoreUri != null) cachedMediaStoreUri!!
                else FileProvider.getUriForFile(this, "${packageName}.provider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    uri,
                    "application/pdf"
                ); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Open signed PDF"))
        } catch (e: Exception) {
            Toast.makeText(this, "PDF saved to Downloads", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPdfImage(filePath: String) {
        try {
            val fd = ParcelFileDescriptor.open(File(filePath), ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val page = renderer.openPage(0)
            val bmp = Bitmap.createBitmap(
                page.width * 2,
                page.height * 2,
                Bitmap.Config.ARGB_8888
            ); bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            ivPdfPreview.setImageBitmap(bmp); page.close(); renderer.close(); fd.close()
        } catch (e: Exception) {
            Log.e(TAG, "showPdfImage failed: ${e.message}")
        }
    }

    private fun showError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); Log.e(TAG, "showError: $msg")
    }
}