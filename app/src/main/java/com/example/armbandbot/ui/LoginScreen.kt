package com.example.armbandbot.ui

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun LoginScreen(onLoginSuccess: (String) -> Unit) {
    val targetUrl = "https://m.dcinside.com/auth/login"
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()

            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(
                            "(function() { return document.body.innerText.includes('로그아웃'); })();"
                        ) { result ->
                            if (result == "true") {
                                val cookies = CookieManager.getInstance().getCookie("https://dcinside.com")
                                if (cookies != null) onLoginSuccess(cookies)
                            }
                        }
                    }
                }
                loadUrl(targetUrl)
            }
        }
    )
}
