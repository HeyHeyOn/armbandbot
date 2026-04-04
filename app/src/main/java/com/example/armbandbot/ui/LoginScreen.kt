package com.heyheyon.armbandbot.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun BotLoginScreen(
    botId: String,
    onLoginSuccess: (String) -> Unit,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val botPref = context.getSharedPreferences("bot_prefs_$botId", Context.MODE_PRIVATE)
    val botName = botPref.getString("bot_name", "이름 없는 봇") ?: "이름 없는 봇"

    val isDarkMode = LocalIsDarkMode.current
    val colors = botColors(isDarkMode)

    var loginId by remember { mutableStateOf(botPref.getString("auto_login_id", "") ?: "") }
    var loginPw by remember { mutableStateOf(botPref.getString("auto_login_pw", "") ?: "") }
    var autoLogin by remember { mutableStateOf(botPref.getBoolean("auto_login_enabled", false)) }
    var pwVisible by remember { mutableStateOf(false) }

    val isLoadingState = remember { mutableStateOf(false) }
    val errorMsgState = remember { mutableStateOf<String?>(null) }
    val triggerLoginState = remember { mutableStateOf(false) }

    var isLoading by isLoadingState
    var errorMsg by errorMsgState
    var triggerLogin by triggerLoginState

    var showFallbackWebView by remember { mutableStateOf(false) }

    if (showFallbackWebView) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)

                            val leftMsign = url?.contains("dcinside.com") == true &&
                                    url.contains("msign.dcinside.com") == false
                            val cookies = CookieManager.getInstance().getCookie("https://dcinside.com")
                            val hasCiC = cookies?.contains("ci_c=") == true

                            if (leftMsign || hasCiC) {
                                if (cookies != null) onLoginSuccess(cookies)
                                return
                            }

                            view?.evaluateJavascript(
                                "(function() { var t = document.body.innerText; return t.includes('로그아웃') || t.includes('비밀번호 변경') || t.includes('30일 후') || t.includes('나중에 변경') || t.includes('비밀번호를 변경'); })();"
                            ) { result ->
                                if (result == "true") {
                                    val c = CookieManager.getInstance().getCookie("https://dcinside.com")
                                    if (c != null) onLoginSuccess(c)
                                }
                            }
                        }
                    }
                    loadUrl("https://msign.dcinside.com/login")
                }
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(24.dp)
    ) {
        // 1. 상단 Row: 봇 이름 + 서브텍스트
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(botName, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text)
                Text("디시인사이드 로그인", fontSize = 13.sp, color = colors.subText)
            }
        }

        Spacer(Modifier.height(24.dp))

        // 3. 식별코드 입력
        OutlinedTextField(
            value = loginId,
            onValueChange = { loginId = it },
            label = { Text("식별코드") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        // 5. 비밀번호 입력
        OutlinedTextField(
            value = loginPw,
            onValueChange = { loginPw = it },
            label = { Text("비밀번호") },
            singleLine = true,
            visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { pwVisible = !pwVisible }) {
                    Icon(
                        imageVector = if (pwVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = if (pwVisible) "비밀번호 숨기기" else "비밀번호 표시"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        // 7. 자동 재로그인 체크박스
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(checked = autoLogin, onCheckedChange = { autoLogin = it })
            Text("세션 만료 시 자동으로 재로그인합니다", fontSize = 14.sp, color = colors.text)
        }

        Spacer(Modifier.height(8.dp))

        // 9. 저장 안내
        Text(
            "비밀번호는 기기 내에만 저장됩니다.",
            fontSize = 12.sp,
            color = colors.subText
        )

        Spacer(Modifier.height(24.dp))

        // 11. 로그인 버튼
        Button(
            onClick = {
                botPref.edit()
                    .putString("auto_login_id", loginId)
                    .putString("auto_login_pw", loginPw)
                    .putBoolean("auto_login_enabled", autoLogin)
                    .apply()
                errorMsgState.value = null
                isLoadingState.value = true
                triggerLoginState.value = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = PastelNavy),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text("로그인", color = Color.White)
            }
        }

        Spacer(Modifier.height(8.dp))

        // 13. 에러 메시지
        if (errorMsg != null) {
            Text(errorMsg!!, color = colors.warningRed, fontSize = 14.sp)
        }

        Spacer(Modifier.height(16.dp))

        // 15. 브라우저 직접 로그인
        TextButton(onClick = { showFallbackWebView = true }) {
            Text("직접 로그인 (브라우저)", color = colors.subText)
        }
    }

    // 백그라운드 WebView 로그인 처리
    if (triggerLogin) {
        val capturedId = loginId
        val capturedPw = loginPw

        Box(modifier = Modifier.size(0.dp)) {
            AndroidView(
                factory = { ctx ->
                    val handler = Handler(Looper.getMainLooper())
                    val timeoutRunnable = Runnable {
                        isLoadingState.value = false
                        triggerLoginState.value = false
                        errorMsgState.value = "로그인에 실패했습니다. 아이디와 비밀번호를 확인해주세요."
                    }
                    handler.postDelayed(timeoutRunnable, 30_000L)

                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()

                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)

                                if (url?.contains("msign.dcinside.com/login") == true) {
                                    val escapedId = capturedId
                                        .replace("\\", "\\\\")
                                        .replace("'", "\\'")
                                    val escapedPw = capturedPw
                                        .replace("\\", "\\\\")
                                        .replace("'", "\\'")
                                    val js = """
                                        (function() {
                                          try {
                                            var codeField = document.querySelector('input[name="code"], input#code');
                                            var pwField = document.querySelector('input[name="password"], input#password');
                                            if (codeField && pwField) {
                                              var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                                              setter.call(codeField, '$escapedId');
                                              codeField.dispatchEvent(new Event('input', { bubbles: true }));
                                              setter.call(pwField, '$escapedPw');
                                              pwField.dispatchEvent(new Event('input', { bubbles: true }));
                                              var form = codeField.closest('form');
                                              if (form) {
                                                var submitBtn = form.querySelector('button[type="submit"], input[type="submit"]');
                                                if (submitBtn) submitBtn.click();
                                                form.submit();
                                              }
                                            }
                                          } catch(e) {}
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(js, null)
                                }

                                val leftMsign = url?.contains("dcinside.com") == true &&
                                        url.contains("msign.dcinside.com") == false
                                val cookies = CookieManager.getInstance().getCookie("https://dcinside.com")
                                val hasCiC = cookies?.contains("ci_c=") == true

                                if (leftMsign || hasCiC) {
                                    handler.removeCallbacks(timeoutRunnable)
                                    if (cookies != null) {
                                        isLoadingState.value = false
                                        triggerLoginState.value = false
                                        onLoginSuccess(cookies)
                                    }
                                    return
                                }

                                view?.evaluateJavascript(
                                    "(function() { var t = document.body.innerText; return t.includes('로그아웃') || t.includes('비밀번호 변경') || t.includes('30일 후') || t.includes('나중에 변경') || t.includes('비밀번호를 변경'); })();"
                                ) { result ->
                                    if (result == "true") {
                                        handler.removeCallbacks(timeoutRunnable)
                                        val c = CookieManager.getInstance().getCookie("https://dcinside.com")
                                        if (c != null) {
                                            isLoadingState.value = false
                                            triggerLoginState.value = false
                                            onLoginSuccess(c)
                                        }
                                    }
                                }
                            }
                        }
                        loadUrl("https://msign.dcinside.com/login")
                    }
                }
            )
        }
    }
}
