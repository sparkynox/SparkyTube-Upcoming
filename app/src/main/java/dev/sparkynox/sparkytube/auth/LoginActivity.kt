package dev.sparkynox.sparkytube.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import dev.sparkynox.sparkytube.R
import dev.sparkynox.sparkytube.logs.LogRecorder
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Google sign-in in a WebView, same approach as LunoMusic's LoginScreen: log in, wait until the
 * SAPISID cookie exists, then read visitorData / datasync id / client version out of the page's
 * ytcfg and store everything in [Session]. Returns RESULT_OK when done.
 */
class LoginActivity : AppCompatActivity() {

    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bg = getColor(R.color.bg_root)
        val fg = getColor(R.color.text_primary)
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        }
        bar.addView(TextView(this).apply {
            text = "Sign in to YouTube"
            setTextColor(fg)
            textSize = 18f
        }, LinearLayout.LayoutParams(0, (52 * dp).toInt(), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        bar.addView(TextView(this).apply {
            text = "Close"
            setTextColor(getColor(R.color.accent))
            textSize = 15f
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            setOnClickListener { finish() }
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Same WebView identity as the main browser (default user agent + our token). Pretending to be
            // Chrome made Google see a mismatch against WebView's client hints and bounce "Next" back to
            // the identifier page.
            userAgentString = userAgentString + " SparkyTube/1.0"
        }

        // Google's sign-in hops between accounts.google.com and youtube.com and needs cross-site cookies
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                LogRecorder.d("Login", "page started: ${url?.substringBefore('?')}")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                LogRecorder.d("Login", "page finished: ${url?.substringBefore('?')}")
                if (done || url == null) return
                if (url.startsWith("https://m.youtube.com") || url.startsWith("https://www.youtube.com")) poll(view, url, 0)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    LogRecorder.w("Login", "main frame error ${error.errorCode}: ${error.description} (${request.url.host})")
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) {
                    LogRecorder.w("Login", "HTTP ${errorResponse.statusCode} on ${request.url.host}")
                }
            }
        }

        // Start from a clean jar so the account picker always shows. The wipe is asynchronous, so the
        // sign-in page is only loaded once it has finished; loading straight away let the wipe delete
        // the cookies Google had just set, which is what left "Next" hanging.
        cookies.removeAllCookies {
            cookies.flush()
            web.loadUrl(SIGN_IN_URL)
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    // ytcfg is not always ready right when the page finishes, so retry a few times
    private fun poll(view: WebView, url: String, tries: Int) {
        val cookie = CookieManager.getInstance().getCookie(url)
        if (cookie == null || !cookie.contains("SAPISID=")) return

        view.evaluateJavascript(GET_CONFIG) { raw ->
            val cfg = runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull()
            if (cfg == null || cfg.optString("v").isEmpty()) {
                if (tries < 8) view.postDelayed({ if (!done) poll(view, url, tries + 1) }, 700)
                else LogRecorder.w("Login", "signed in but ytcfg never had visitor data")
                return@evaluateJavascript
            }
            if (done) return@evaluateJavascript
            done = true
            Session.save(
                cookie = cookie,
                visitor = cfg.optString("v"),
                sync = cfg.optString("d").substringBefore("||"),
                clientVersion = cfg.optString("c"),
            )
            CookieManager.getInstance().flush()
            LogRecorder.i("Login", "login finished, session saved (client ${cfg.optString("c")})")
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private companion object {
        // YouTube-initiated sign-in: after the Google login, youtube.com's own /signin?action_handle_signin
        // step runs and sets the YouTube cookies (SAPISID), which a plain "continue" to m.youtube.com does not
        const val SIGN_IN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
                "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue" +
                "%26app%3Dm%26hl%3Den%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"

        const val GET_CONFIG =
            "(function(){try{var g=function(k){try{return window.ytcfg.get(k)}catch(e){return null}};" +
                "return JSON.stringify({v:g('VISITOR_DATA')||'',d:g('DATASYNC_ID')||'',c:g('INNERTUBE_CLIENT_VERSION')||''});}" +
                "catch(e){return '';}})()"
    }
}