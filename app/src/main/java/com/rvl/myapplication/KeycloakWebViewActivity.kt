
package com.rvl.myapplication

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ClientCertRequest
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class KeycloakWebViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG            = "KeycloakWebView"
        const val EXTRA_AUTH_CODE        = "auth_code"
        const val EXTRA_CODE_VERIFIER    = "code_verifier"
        const val EXTRA_ERROR            = "error"
        var isActivityVisible            = false

        private const val BASE           = "https://portal.test.reliefvalidation.com.bd/auth"
        private const val REALM          = "CloudID"
        private const val CLIENT_ID      = "tspclient-public-1258-ovi_auth"
        private const val REDIRECT_URI   = "myapp://callback"
        private const val PFX_PASSWORD   = "password"

        fun generateCodeVerifier(): String {
            val bytes = ByteArray(64)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
        }

        fun generateCodeChallenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash   = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(
                hash,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
        }

        fun buildLoginUrl(codeChallenge: String): String {
            val redirectUri = android.net.Uri.encode(REDIRECT_URI)
            return "$BASE/realms/$REALM/protocol/openid-connect/auth" +
                    "?client_id=$CLIENT_ID" +
                    "&redirect_uri=$redirectUri" +
                    "&response_type=code" +
                    "&scope=openid" +
                    "&prompt=login" +
                    "&code_challenge=$codeChallenge" +
                    "&code_challenge_method=S256"
        }
    }

    private lateinit var webView:      WebView
    private lateinit var progressBar:  ProgressBar
    private var callbackHandled        = false
    private lateinit var codeVerifier: String

    // ── Broadcast receiver for MFA push notifications ──────────────────────────
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "=== BROADCAST RECEIVED IN KEYCLOAK ===")

            val senderPkg = intent?.getStringExtra("sender_package")
            if (senderPkg != null && senderPkg != packageName) {
                Log.w(TAG, "Rejected broadcast from: $senderPkg")
                return
            }

            if (intent?.action == PdfPreviewActivity.ACTION_SHOW_IN_APP_PUSH) {
                Log.d(TAG, "Processing notification in KeycloakWebViewActivity")
                Log.d(TAG, "  action1_title: ${intent.getStringExtra("action1_title")}")
                Log.d(TAG, "  action2_title: ${intent.getStringExtra("action2_title")}")
                Log.d(TAG, "  has action1_intent: ${intent.hasExtra("action1_intent")}")
                Log.d(TAG, "  has action2_intent: ${intent.hasExtra("action2_intent")}")
                showInAppNotification(intent)
            }
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        progressBar = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isIndeterminate = true
            visibility      = View.VISIBLE
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        layout.addView(progressBar)
        layout.addView(webView)
        setContentView(layout)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
            }
        })

        // ── CRITICAL: Inject private CA into default SSLContext
        // BEFORE WebView loads anything.
        // This fixes: "Trust anchor for certification path not found"
        // for BOTH main frame and sub-resource requests.
        // We do NOT include client cert here because Keycloak
        // does not need mTLS — only TSP Gateway does.
        try {
            injectCaIntoWebViewTrustStore()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject CA into trust store: ${e.message}")
        }

        setupWebView()

        // Clear stale Keycloak session cookies so login prompt always appears
        android.webkit.CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }

        codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        webView.loadUrl(buildLoginUrl(codeChallenge))
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        isActivityVisible = true
        val filter = IntentFilter(PdfPreviewActivity.ACTION_SHOW_IN_APP_PUSH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                notificationReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(notificationReceiver, filter)
        }
        Log.d(TAG, "Keycloak receiver registered")
    }

    override fun onPause() {
        super.onPause()
        isActivityVisible = false
        try {
            unregisterReceiver(notificationReceiver)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.clearHistory()
        webView.removeAllViews()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // ── CA Injection ────────────────────────────────────────────────────────────

    /**
     * Injects the private CA into the default SSLContext so that
     * WebView's internal Chromium X509 validator trusts it for
     * ALL connections (main frame + sub-resources).
     *
     * IMPORTANT: We do NOT inject the client cert (keyManagers) here
     * because Keycloak does not require mTLS. Sending the TSP Gateway
     * client cert to Keycloak causes ERR_BAD_SSL_CLIENT_AUTH_CERT.
     * The client cert is only provided in onReceivedClientCertRequest
     * when the host is the TSP Gateway.
     */
    private fun injectCaIntoWebViewTrustStore() {
        // ── Step 1: Load all system trusted CAs ─────────────────────────────
        val systemTmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(null as KeyStore?) }

        val systemTm = systemTmf.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: throw IllegalStateException("No system X509TrustManager found")

        // ── Step 2: Create a new combined KeyStore ───────────────────────────
        val combinedStore = KeyStore.getInstance(
            KeyStore.getDefaultType()
        ).apply { load(null, null) }

        // Seed with all system CAs
        systemTm.acceptedIssuers.forEachIndexed { i, cert ->
            combinedStore.setCertificateEntry("system_ca_$i", cert)
        }
        Log.d(TAG, "Loaded ${systemTm.acceptedIssuers.size} system CAs into trust store")

        // ── Step 3: Add private CA(s) from res/raw/server_ca.crt ────────────
        val caResId = resources.getIdentifier(
            "server_ca", "raw", packageName
        )
        if (caResId != 0) {
            resources.openRawResource(caResId).use { stream ->
                val cf    = CertificateFactory.getInstance("X.509")
                // generateCertificates handles both single cert and full chains
                val certs = cf.generateCertificates(stream)
                certs.forEachIndexed { i, cert ->
                    combinedStore.setCertificateEntry("private_ca_$i", cert)
                    Log.d(
                        TAG,
                        "✅ Added private CA[$i]: " +
                                (cert as X509Certificate).subjectX500Principal.name
                    )
                }
            }
        } else {
            Log.e(TAG, "❌ server_ca.crt NOT FOUND in res/raw! SSL will likely fail.")
        }

        // ── Step 4: Build TrustManagerFactory from combined store ────────────
        val combinedTmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(combinedStore) }

        val combinedTm = combinedTmf.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: throw IllegalStateException("No combined X509TrustManager found")

        // ── Step 5: Set as default SSLContext — NO client cert included ───────
        // Keycloak = no client cert needed
        // TSP Gateway client cert is handled in onReceivedClientCertRequest
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(
                null,              // ← NO keyManagers (no client cert)
                arrayOf(combinedTm),
                SecureRandom()
            )
        }
        SSLContext.setDefault(sslContext)

        Log.d(TAG, "✅ Default SSLContext injected with private CA (no client cert)")
    }

    // ── WebView setup ───────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            loadWithOverviewMode = true
            useWideViewPort      = true
            // Allow sub-resources from Keycloak page to load correctly
            mixedContentMode     = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {

            // ── SSL errors ─────────────────────────────────────────────────────
            // Handles main frame SSL issues.
            // Sub-resource SSL is fixed by injectCaIntoWebViewTrustStore().
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                val url = error.url ?: view.url ?: ""
                Log.w(TAG, "onReceivedSslError: primaryError=${error.primaryError} url=$url")

                if (isTrustedHost(url)) {
                    Log.w(TAG, "✅ SSL proceed for trusted host")
                    handler.proceed()
                } else {
                    Log.e(TAG, "❌ SSL rejected for unknown host: $url")
                    handler.cancel()
                    runOnUiThread {
                        setResult(Activity.RESULT_CANCELED, Intent().apply {
                            putExtra(EXTRA_ERROR, "SSL error (${error.primaryError})")
                        })
                        finish()
                    }
                }
            }

            // ── mTLS client certificate ────────────────────────────────────────
            // ONLY provide client cert to TSP Gateway.
            // Keycloak does NOT need a client cert — sending one causes
            // ERR_BAD_SSL_CLIENT_AUTH_CERT and blocks the login page.
            override fun onReceivedClientCertRequest(
                view: WebView,
                request: ClientCertRequest
            ) {
                Log.d(TAG, "onReceivedClientCertRequest: host=${request.host}")

                // Check if this is TSP Gateway
                val isTspGateway =
                    request.host.contains("192.168.0.155") ||
                            request.host.contains("192.168.")      ||
                            request.host.contains("10.0.")         ||
                            request.host.contains("172.16.")

                if (!isTspGateway) {
                    // Keycloak or any other host — do not send client cert
                    Log.d(TAG, "✅ Not TSP Gateway — ignoring client cert for: ${request.host}")
                    request.ignore()
                    return
                }

                // TSP Gateway — provide client cert on background thread
                Thread {
                    try {
                        val pfxResId = resources.getIdentifier(
                            "tspgw_client", "raw", packageName
                        )
                        if (pfxResId == 0) {
                            Log.w(TAG, "tspgw_client.pfx not found — ignoring")
                            request.ignore()
                            return@Thread
                        }

                        val keyStore = resources.openRawResource(pfxResId).use { stream ->
                            KeyStore.getInstance("PKCS12").apply {
                                load(stream, PFX_PASSWORD.toCharArray())
                            }
                        }

                        val alias = keyStore.aliases().asSequence()
                            .firstOrNull { keyStore.isKeyEntry(it) }

                        if (alias == null) {
                            Log.e(TAG, "No private key entry in PFX — ignoring")
                            request.ignore()
                            return@Thread
                        }

                        val privateKey = keyStore.getKey(
                            alias, PFX_PASSWORD.toCharArray()
                        ) as PrivateKey

                        val certChain = keyStore.getCertificateChain(alias)
                            ?.filterIsInstance<X509Certificate>()
                            ?.toTypedArray()

                        if (certChain.isNullOrEmpty()) {
                            Log.e(TAG, "Empty cert chain for alias '$alias' — ignoring")
                            request.ignore()
                            return@Thread
                        }

                        Log.d(
                            TAG,
                            "✅ Client cert provided for TSP Gateway: " +
                                    "alias='$alias' chain=${certChain.size}"
                        )
                        request.proceed(privateKey, certChain)

                    } catch (e: Exception) {
                        Log.e(TAG, "onReceivedClientCertRequest exception: ${e.message}")
                        request.ignore()
                    }
                }.start()
            }

            // ── Intercept PKCE callback redirect ───────────────────────────────
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                Log.d(TAG, "shouldOverrideUrlLoading: $url")
                if (url.startsWith("myapp://callback")) {
                    handleCallback(url)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "onPageStarted: $url")
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.d(TAG, "onPageFinished: $url")
                progressBar.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                val url  = request.url.toString()
                val code = error.errorCode
                Log.e(TAG, "onReceivedError: code=$code desc=${error.description} url=$url")

                // myapp:// scheme always triggers an error — treat as callback
                if (request.isForMainFrame && url.startsWith("myapp://callback")) {
                    handleCallback(url)
                    return
                }

                // Show retry dialog only for main frame errors
                if (request.isForMainFrame) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        AlertDialog.Builder(this@KeycloakWebViewActivity)
                            .setTitle("Connection Error")
                            .setMessage(
                                "Cannot connect to login server.\n\n" +
                                        "Error $code: ${error.description}\n\n" +
                                        "Please check your connection and try again."
                            )
                            .setPositiveButton("Retry") { _, _ ->
                                callbackHandled = false
                                val challenge = generateCodeChallenge(codeVerifier)
                                webView.loadUrl(buildLoginUrl(challenge))
                            }
                            .setNegativeButton("Cancel") { _, _ ->
                                setResult(Activity.RESULT_CANCELED)
                                finish()
                            }
                            .setCancelable(false)
                            .show()
                    }
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Returns true for hosts that use a private / self-signed certificate.
     * Extend this list if you add more internal servers.
     */
    private fun isTrustedHost(url: String): Boolean {
        val trustedHosts = listOf(
            "portal.test.reliefvalidation.com.bd",
            "192.168.0.155",
            "192.168.",
            "10.0.",
            "172.16.",
            "localhost"
        )
        return trustedHosts.any { url.contains(it) }
    }

    private fun handleCallback(url: String) {
        if (callbackHandled) return
        callbackHandled = true
        Log.d(TAG, "handleCallback: $url")

        val uri   = android.net.Uri.parse(url)
        val code  = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        val desc  = uri.getQueryParameter("error_description")

        val resultIntent = Intent()
        if (code != null) {
            Log.d(TAG, "✅ Auth code received successfully")
            resultIntent.putExtra(EXTRA_AUTH_CODE,     code)
            resultIntent.putExtra(EXTRA_CODE_VERIFIER, codeVerifier)
            setResult(Activity.RESULT_OK, resultIntent)
        } else {
            Log.e(TAG, "❌ Auth error: $error — $desc")
            resultIntent.putExtra(EXTRA_ERROR, "$error: $desc")
            setResult(Activity.RESULT_CANCELED, resultIntent)
        }
        finish()
    }

    // ── In-app MFA notification overlay ────────────────────────────────────────

    private fun showInAppNotification(intent: Intent) {
        val rootView = findViewById<ViewGroup>(android.R.id.content)
        rootView.findViewWithTag<View>("in_app_notification_overlay")
            ?.let { rootView.removeView(it) }

        val title   = intent.getStringExtra("title")   ?: "Signature Request"
        val message = intent.getStringExtra("message") ?: "Please confirm your action."

        fun getPending(key: String): android.app.PendingIntent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(key, android.app.PendingIntent::class.java)
            else
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(key) as? android.app.PendingIntent

        val action1Intent = getPending("action1_intent")
        val action2Intent = getPending("action2_intent")
        val action1Title  = intent.getStringExtra("action1_title")?.lowercase() ?: ""
        val action2Title  = intent.getStringExtra("action2_title")?.lowercase() ?: ""

        Log.d(TAG, "showInAppNotification - action1: '$action1Title' intent=${action1Intent != null}")
        Log.d(TAG, "showInAppNotification - action2: '$action2Title' intent=${action2Intent != null}")

        // Resolve which PendingIntent is confirm and which is deny
        var confirmIntent = action1Intent
        var denyIntent    = action2Intent
        val denyKw    = listOf("deny", "no", "reject", "cancel")
        val confirmKw = listOf("confirm", "yes", "approve", "allow")
        if (denyKw.any    { action1Title.contains(it) }) {
            confirmIntent = action2Intent
            denyIntent    = action1Intent
        }
        if (confirmKw.any { action2Title.contains(it) }) {
            confirmIntent = action2Intent
        }

        val d = resources.displayMetrics.density

        // ── Dim overlay ────────────────────────────────────────────────────────
        val overlay = android.widget.FrameLayout(this).apply {
            tag = "in_app_notification_overlay"
            setBackgroundColor(android.graphics.Color.parseColor("#80000000"))
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = true
        }

        // ── White card ─────────────────────────────────────────────────────────
        val cardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background  = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 32f * d
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
                setMargins(
                    (24 * d).toInt(), (48 * d).toInt(),
                    (24 * d).toInt(), (48 * d).toInt()
                )
            }
        }

        // ── Top section ────────────────────────────────────────────────────────
        val topSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(
                (32 * d).toInt(), (32 * d).toInt(),
                (32 * d).toInt(), (24 * d).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val iconBadge = android.widget.FrameLayout(this).apply {
            val sz = (80 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                bottomMargin = (24 * d).toInt()
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#FFF7ED"))
                setStroke((2 * d).toInt(), android.graphics.Color.parseColor("#FDBA74"))
            }
            addView(android.widget.TextView(this@KeycloakWebViewActivity).apply {
                text      = "🔐"
                textSize  = 36f
                gravity   = android.view.Gravity.CENTER
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        }

        val titleTv = android.widget.TextView(this).apply {
            text     = title
            textSize = 22f
            gravity  = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#111827"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val messageTv = android.widget.TextView(this).apply {
            text     = message
            textSize = 16f
            gravity  = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#6B7280"))
            setLineSpacing(8f, 1.5f)
            maxLines = 6
            setPadding(
                (8 * d).toInt(), (16 * d).toInt(),
                (8 * d).toInt(), 0
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        topSection.addView(iconBadge)
        topSection.addView(titleTv)
        topSection.addView(messageTv)

        // ── Buttons ────────────────────────────────────────────────────────────
        val buttonSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = android.view.Gravity.CENTER
            setPadding(
                (24 * d).toInt(), 0,
                (24 * d).toInt(), (32 * d).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun makeBtn(
            label:       String,
            bgNormal:    Int,
            bgPressed:   Int,
            textColor:   Int,
            strokeColor: Int? = null
        ): android.widget.TextView =
            android.widget.TextView(this).apply {
                text     = label
                textSize = 16f
                gravity  = android.view.Gravity.CENTER
                setTextColor(textColor)
                setTypeface(null, android.graphics.Typeface.BOLD)
                isClickable = true
                isFocusable = true
                setPadding(
                    (24 * d).toInt(), (18 * d).toInt(),
                    (24 * d).toInt(), (18 * d).toInt()
                )
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(bgPressed)
                            cornerRadius = 16f * d
                            strokeColor?.let { setStroke((2 * d).toInt(), it) }
                        }
                    )
                    addState(
                        intArrayOf(),
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(bgNormal)
                            cornerRadius = 16f * d
                            strokeColor?.let { setStroke((2 * d).toInt(), it) }
                        }
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (12 * d).toInt() }
            }

        val btnConfirm = makeBtn(
            label     = "Confirm Login",
            bgNormal  = android.graphics.Color.parseColor("#10B981"),
            bgPressed = android.graphics.Color.parseColor("#059669"),
            textColor = android.graphics.Color.WHITE
        )

        val btnDeny = makeBtn(
            label       = "Deny",
            bgNormal    = android.graphics.Color.TRANSPARENT,
            bgPressed   = android.graphics.Color.parseColor("#FEF2F2"),
            textColor   = android.graphics.Color.parseColor("#DC2626"),
            strokeColor = android.graphics.Color.parseColor("#FCA5A5")
        ).also {
            it.layoutParams = (it.layoutParams as LinearLayout.LayoutParams)
                .apply { bottomMargin = 0 }
        }

        buttonSection.addView(btnConfirm)
        buttonSection.addView(btnDeny)

        cardView.addView(topSection)
        cardView.addView(buttonSection)
        overlay.addView(cardView)
        rootView.addView(overlay)

        // ── Entry animation ────────────────────────────────────────────────────
        overlay.alpha   = 0f
        cardView.scaleX = 0.92f
        cardView.scaleY = 0.92f
        cardView.alpha  = 0f
        overlay.animate().alpha(1f).setDuration(250).start()
        cardView.animate()
            .scaleX(1f).scaleY(1f).alpha(1f).setDuration(350)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.6f))
            .start()

        fun dismissWithAnimation(onComplete: () -> Unit) {
            cardView.animate()
                .scaleX(0.98f).scaleY(0.98f).alpha(0f).setDuration(200).start()
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
                    Log.d(TAG, "✅ Deny intent sent")
                } catch (e: Exception) {
                    Log.e(TAG, "Deny failed: ${e.message}")
                }
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }

        btnConfirm.setOnClickListener {
            Log.d(TAG, "CONFIRM clicked, confirmIntent=${confirmIntent != null}")
            dismissWithAnimation {
                try {
                    confirmIntent?.send()
                    Log.d(TAG, "✅ Confirm intent sent")
                } catch (e: Exception) {
                    Log.e(TAG, "Confirm failed: ${e.message}")
                }
                // Stay open — Keycloak will redirect after MFA confirmation
            }
        }
    }
}