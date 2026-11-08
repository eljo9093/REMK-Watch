package eu.remk.coaching.wear

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * RE:MK Standalone Wear OS Smartwatch Activity (v25.7)
 * - 100 % eigenständig ohne Smartphone nutzbar
 * - Unterstützt das ⚙️ Zahnrad-Schnellauswahl-System per Touch UND
 *   die physische drehbare Krone / Lünette (Rotary Input via ACTION_SCROLL)
 */
class WearMainActivity : AppCompatActivity() {

    private lateinit var watchWebView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        watchWebView = WebView(this)
        watchWebView.setBackgroundColor(Color.parseColor("#05070b"))
        watchWebView.isFocusable = true
        watchWebView.isFocusableInTouchMode = true
        setContentView(watchWebView)

        val s = watchWebView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.setGeolocationEnabled(true)
        s.allowFileAccess = true
        s.cacheMode = WebSettings.LOAD_DEFAULT

        watchWebView.webViewClient = WebViewClient()
        watchWebView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }

        // Hardware Rotary Crown / Bezel Listener (Galaxy Watch Lünette & Pixel Watch Krone)
        watchWebView.setOnGenericMotionListener { _, event ->
            if (event.action == MotionEvent.ACTION_SCROLL &&
                event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
            ) {
                val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
                val dir = if (delta > 0) 1 else -1
                watchWebView.evaluateJavascript(
                    "if(typeof window.onWatchRotaryCrownStepV257==='function') window.onWatchRotaryCrownStepV257($dir);",
                    null
                )
                true
            } else {
                false
            }
        }

        watchWebView.loadUrl("file:///android_asset/watch.html")
        watchWebView.requestFocus()
    }
}
