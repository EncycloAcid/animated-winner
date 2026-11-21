package io.github.encycloacid.clearwaytower

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedDispatcher
import androidx.webkit.WebViewAssetLoader

/** Clearway Tower: the game page (index.html, bundled in the APK) in a full-screen WebView. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var insetsJs = ""

    // Serve the bundled page from an https origin, so storage (best scores) works like on the web.
    private val assets by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goEdgeToEdge()

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.builtInZoomControls = false
            settings.setSupportZoom(false)
            settings.mediaPlaybackRequiresUserGesture = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor(if (isNight()) "#111513" else "#eef1ec"))
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assets.shouldInterceptRequest(request.url)

                // Keep the app on its bundled page; other links (OpenStreetMap credits) open in the browser.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == APP_HOST) return false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) = pushInsets()
            }
        }

        // The map draws behind the status and navigation bars. The page keeps its controls and
        // flight panel clear of them using these insets (CSS px).
        web.setOnApplyWindowInsetsListener { _, insets ->
            val d = resources.displayMetrics.density
            val (l, t, r, b) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                listOf(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insetsJs = "(()=>{const s=document.documentElement.style;" +
                "s.setProperty('--sal','${l / d}px');s.setProperty('--sat','${t / d}px');" +
                "s.setProperty('--sar','${r / d}px');s.setProperty('--sab','${b / d}px');" +
                "dispatchEvent(new Event('resize'));})()"
            pushInsets()
            insets
        }

        setContentView(web)
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("https://$APP_HOST/assets/index.html")

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
    }

    private fun pushInsets() {
        if (insetsJs.isNotEmpty()) web.evaluateJavascript(insetsJs, null)
    }

    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    @Suppress("DEPRECATION")
    private fun goEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        val light = !isNight()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
        } else {
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (light) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = flags
        }
    }

    // Back first closes a menu, then lets go of the selected flight, then pauses the shift; then leaves.
    private fun handleBack() {
        web.evaluateJavascript("window.cleBack ? window.cleBack() : false") { handled ->
            if (handled != "true") finish()
        }
    }

    @Deprecated("Used below Android 13")
    override fun onBackPressed() = handleBack()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    // the shift pauses while the app is in the background
    override fun onPause() {
        web.evaluateJavascript("window.towerPause && towerPause()", null)
        web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val APP_HOST = WebViewAssetLoader.DEFAULT_DOMAIN
    }
}
