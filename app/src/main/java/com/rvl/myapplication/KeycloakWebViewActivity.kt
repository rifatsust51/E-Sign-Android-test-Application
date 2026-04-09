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
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.security.MessageDigest
import java.security.SecureRandom

class KeycloakWebViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KeycloakWebView"
        const val EXTRA_AUTH_CODE = "auth_code"
        const val EXTRA_CODE_VERIFIER = "code_verifier"
        const val EXTRA_ERROR = "error"
        var isActivityVisible = false

        private const val BASE = "https://portal.test.reliefvalidation.com.bd/auth"
        private const val REALM = "CloudID"
        private const val CLIENT_ID = "tspclient-public-1258-ovi_auth"
        private const val REDIRECT_URI = "myapp://callback"

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
            val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(
                hash,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
        }

        fun buildLoginUrl(codeChallenge: String): String {
            val redirectUri = android.net.Uri.encode(REDIRECT_URI)
            return "$BASE/realms/$REALM/protocol/openid-connect/auth" +
                    "?client_id=$CLIENT_ID&redirect_uri=$redirectUri&response_type=code" +
                    "&scope=openid&prompt=login&code_challenge=$codeChallenge&code_challenge_method=S256"
        }
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var callbackHandled = false
    private lateinit var codeVerifier: String

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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isIndeterminate = true; visibility = View.VISIBLE
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        layout.addView(progressBar); layout.addView(webView)
        setContentView(layout)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else {
                    setResult(Activity.RESULT_CANCELED); finish()
                }
            }
        })

        setupWebView()

        android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() }

        codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        webView.loadUrl(buildLoginUrl(codeChallenge))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            loadWithOverviewMode = true; useWideViewPort = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                handler.proceed()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (url.startsWith("myapp://callback")) {
                    handleCallback(url); return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon); progressBar.visibility = View.VISIBLE
                if (url.startsWith("myapp://callback")) handleCallback(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url); progressBar.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                val url = request.url.toString()
                if (url.startsWith("myapp://callback")) {
                    handleCallback(url); return
                }
                super.onReceivedError(view, request, error)
            }
        }
    }

    private fun handleCallback(url: String) {
        if (callbackHandled) return
        callbackHandled = true

        val uri = android.net.Uri.parse(url)
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        val errorDescription = uri.getQueryParameter("error_description")

        val resultIntent = Intent()
        if (code != null) {
            resultIntent.putExtra(EXTRA_AUTH_CODE, code)
            resultIntent.putExtra(EXTRA_CODE_VERIFIER, codeVerifier)
            setResult(Activity.RESULT_OK, resultIntent)
        } else {
            resultIntent.putExtra(EXTRA_ERROR, "$error: $errorDescription")
            setResult(Activity.RESULT_CANCELED, resultIntent)
        }
        finish()
    }

    private fun showInAppNotification(intent: Intent) {
        val rootView = findViewById<ViewGroup>(android.R.id.content)
        rootView.findViewWithTag<View>("in_app_notification_overlay")
            ?.let { rootView.removeView(it) }

        val title = intent.getStringExtra("title") ?: "Signature Request"
        val message = intent.getStringExtra("message") ?: "Please confirm your action."

        val action1Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra("action1_intent", android.app.PendingIntent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra("action1_intent") as? android.app.PendingIntent

        val action2Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra("action2_intent", android.app.PendingIntent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra("action2_intent") as? android.app.PendingIntent

        val action1Title = intent.getStringExtra("action1_title")?.lowercase() ?: ""
        val action2Title = intent.getStringExtra("action2_title")?.lowercase() ?: ""

        Log.d(
            TAG,
            "showInAppNotification - action1: '$action1Title' intent=${action1Intent != null}"
        )
        Log.d(
            TAG,
            "showInAppNotification - action2: '$action2Title' intent=${action2Intent != null}"
        )

        var confirmIntent = action1Intent
        var denyIntent = action2Intent

        if (action1Title.contains("deny") || action1Title.contains("no") || action1Title.contains("reject") || action1Title.contains(
                "cancel"
            )
        ) {
            confirmIntent = action2Intent; denyIntent = action1Intent
        }
        if (action2Title.contains("confirm") || action2Title.contains("yes") || action2Title.contains(
                "approve"
            ) || action2Title.contains("allow")
        ) {
            confirmIntent = action2Intent
        }

        val density = resources.displayMetrics.density

        val overlay = android.widget.FrameLayout(this).apply {
            tag = "in_app_notification_overlay"
            setBackgroundColor(android.graphics.Color.parseColor("#80000000"))
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true; isFocusable = true
        }

        val cardDrawable = android.graphics.drawable.GradientDrawable()
            .apply { setColor(android.graphics.Color.WHITE); cornerRadius = 32f * density }
        val cardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardDrawable
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER; setMargins(
                (24 * density).toInt(),
                (48 * density).toInt(),
                (24 * density).toInt(),
                (48 * density).toInt()
            )
            }
        }

        val topSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity =
            android.view.Gravity.CENTER_HORIZONTAL
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
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(
            android.graphics.Color.parseColor(
                "#FFF7ED"
            )
        ); setStroke((2 * density).toInt(), android.graphics.Color.parseColor("#FDBA74"))
        }
        val iconBadge = android.widget.FrameLayout(this).apply {
            background = iconBadgeDrawable
            val s = (80 * density).toInt(); layoutParams =
            LinearLayout.LayoutParams(s, s)
                .apply { bottomMargin = (24 * density).toInt() }
        }
        val iconText = android.widget.TextView(this).apply {
            text = "🔐"; textSize = 36f; gravity = android.view.Gravity.CENTER; layoutParams =
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        iconBadge.addView(iconText)

        val titleView = android.widget.TextView(this).apply {
            text = title; textSize =
            22f; setTextColor(android.graphics.Color.parseColor("#111827")); setTypeface(
            null,
            android.graphics.Typeface.BOLD
        ); gravity = android.view.Gravity.CENTER; layoutParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val messageView = android.widget.TextView(this).apply {
            text = message; textSize = 16f; setTextColor(
            android.graphics.Color.parseColor("#6B7280")
        ); gravity = android.view.Gravity.CENTER; setLineSpacing(8f, 1.5f); maxLines =
            6; setPadding(
            (8 * density).toInt(),
            (16 * density).toInt(),
            (8 * density).toInt(),
            0
        ); layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        }

        topSection.addView(iconBadge); topSection.addView(titleView); topSection.addView(messageView)

        val buttonSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity =
            android.view.Gravity.CENTER; setPadding(
            (24 * density).toInt(),
            0,
            (24 * density).toInt(),
            (32 * density).toInt()
        ); layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        }

        val confirmBtnDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#10B981")); cornerRadius = 16f * density
        }
        val confirmBtnPressed = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#059669")); cornerRadius = 16f * density
        }
        val confirmSL = android.graphics.drawable.StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                confirmBtnPressed
            ); addState(intArrayOf(), confirmBtnDrawable)
        }

        val btnConfirm = android.widget.TextView(this).apply {
            text = "Confirm Login"; textSize =
            16f; setTextColor(android.graphics.Color.WHITE); setTypeface(
            null,
            android.graphics.Typeface.BOLD
        ); gravity = android.view.Gravity.CENTER; background = confirmSL; isClickable =
            true; isFocusable = true; setPadding(
            (24 * density).toInt(),
            (18 * density).toInt(),
            (24 * density).toInt(),
            (18 * density).toInt()
        ); layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (12 * density).toInt() }
        }

        val denyBtnDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT); cornerRadius = 16f * density; setStroke(
            (2 * density).toInt(),
            android.graphics.Color.parseColor("#FCA5A5")
        )
        }
        val denyBtnPressed = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#FEF2F2")); cornerRadius =
            16f * density; setStroke(
            (2 * density).toInt(),
            android.graphics.Color.parseColor("#DC2626")
        )
        }
        val denySL = android.graphics.drawable.StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                denyBtnPressed
            ); addState(
            intArrayOf(),
            denyBtnDrawable
        )
        }

        val btnDeny = android.widget.TextView(this).apply {
            text = "Deny"; textSize =
            16f; setTextColor(android.graphics.Color.parseColor("#DC2626")); setTypeface(
            null,
            android.graphics.Typeface.BOLD
        ); gravity = android.view.Gravity.CENTER; background = denySL; isClickable =
            true; isFocusable = true; setPadding(
            (24 * density).toInt(),
            (18 * density).toInt(),
            (24 * density).toInt(),
            (18 * density).toInt()
        ); layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        }

        buttonSection.addView(btnConfirm); buttonSection.addView(btnDeny)
        cardView.addView(topSection); cardView.addView(buttonSection)
        overlay.addView(cardView); rootView.addView(overlay)

        overlay.alpha = 0f; cardView.scaleX = 0.92f; cardView.scaleY = 0.92f; cardView.alpha = 0f
        overlay.animate().alpha(1f).setDuration(250).start()
        cardView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(350)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.6f)).start()

        fun dismissWithAnimation(onComplete: () -> Unit) {
            cardView.animate().scaleX(0.98f).scaleY(0.98f).alpha(0f).setDuration(200).start()
            overlay.animate().alpha(0f).setDuration(250)
                .withEndAction { rootView.removeView(overlay); onComplete() }.start()
        }

        btnDeny.setOnClickListener {
            Log.d(TAG, "DENY clicked, denyIntent=${denyIntent != null}")
            dismissWithAnimation {
                try {
                    denyIntent?.send(); Log.d(TAG, "Deny sent")
                } catch (e: Exception) {
                    Log.e(TAG, "Deny failed: ${e.message}")
                }
                setResult(Activity.RESULT_CANCELED); finish()
            }
        }

        btnConfirm.setOnClickListener {
            Log.d(TAG, "CONFIRM clicked, confirmIntent=${confirmIntent != null}")
            dismissWithAnimation {
                try {
                    confirmIntent?.send(); Log.d(TAG, "Confirm sent")
                } catch (e: Exception) {
                    Log.e(TAG, "Confirm failed: ${e.message}")
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        isActivityVisible = true
        val filter = IntentFilter(PdfPreviewActivity.ACTION_SHOW_IN_APP_PUSH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(notificationReceiver, filter)
        }
        Log.d(TAG, "Keycloak receiver registered with RECEIVER_EXPORTED")
    }

    override fun onPause() {
        super.onPause()
        isActivityVisible = false
        try {
            unregisterReceiver(notificationReceiver)
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        webView.stopLoading(); webView.clearHistory(); webView.removeAllViews()
        (webView.parent as? ViewGroup)?.removeView(webView); webView.destroy()
        super.onDestroy()
    }
}