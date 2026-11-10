package com.adfree.tv

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var backPressedTime: Long = 0

    // YouTube Leanback (TV) endpoint
    private val youtubeTvUrl = "https://www.youtube.com/tv"

    // Smart TV User-Agent that opens the full D-pad TV UI
    private val smartTvUserAgent =
        "Mozilla/5.0 (Linux; GoogleTV 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 SmartTV"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupFullscreen()

        webView = findViewById(R.id.tvWebView)
        configureWebView()

        if (savedInstanceState == null) {
            webView.loadUrl(youtubeTvUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = smartTvUserAgent

        // Enable cookies for Google account login sync
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Make WebView focusable for TV D-Pad navigation
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        webView.webChromeClient = object : WebChromeClient() {}

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (AdBlocker.shouldBlock(request)) {
                    return AdBlocker.createEmptyResponse()
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Inject the ad-blocking and skipping script
                view?.evaluateJavascript(AdBlocker.INJECTION_SCRIPT, null)
            }
        }
    }

    private fun setupFullscreen() {
        // Keep the screen on during video playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle TV Back button navigation
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            } else {
                val currentTime = System.currentTimeMillis()
                if (currentTime - backPressedTime < 2000) {
                    finish()
                } else {
                    backPressedTime = currentTime
                    Toast.makeText(this, "Press BACK again to exit", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }

        // Support Media Play/Pause keys on TV remotes
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                val toggleScript = """
                    (function() {
                        const video = document.querySelector('video');
                        if (video) {
                            if (video.paused) video.play();
                            else video.pause();
                        }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(toggleScript, null)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val ffScript = """
                    (function() {
                        const video = document.querySelector('video');
                        if (video) video.currentTime += 10;
                    })();
                """.trimIndent()
                webView.evaluateJavascript(ffScript, null)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                val rwScript = """
                    (function() {
                        const video = document.querySelector('video');
                        if (video) video.currentTime -= 10;
                    })();
                """.trimIndent()
                webView.evaluateJavascript(rwScript, null)
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
