package com.heyheyon.armbandbot

import android.graphics.Color as AndroidColor
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

internal data class SnapshotMovieWebPolicy(
    val javaScriptEnabled: Boolean,
    val allowFileAccess: Boolean,
    val allowContentAccess: Boolean,
    val mediaPlaybackRequiresUserGesture: Boolean,
)

internal fun snapshotMovieWebPolicy() = SnapshotMovieWebPolicy(
    javaScriptEnabled = false,
    allowFileAccess = false,
    allowContentAccess = false,
    mediaPlaybackRequiresUserGesture = true,
)

internal enum class SnapshotMovieLifecycleAction { RESUME, PAUSE, NONE }

internal fun snapshotMovieLifecycleAction(event: Lifecycle.Event): SnapshotMovieLifecycleAction = when (event) {
    Lifecycle.Event.ON_RESUME -> SnapshotMovieLifecycleAction.RESUME
    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> SnapshotMovieLifecycleAction.PAUSE
    else -> SnapshotMovieLifecycleAction.NONE
}

@Composable
internal fun SnapshotDcMovieCard(
    movie: BodyElement.DcMovieElement,
    textColor: Color,
    subTextColor: Color,
    onPlay: (BodyElement.DcMovieElement) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF111111))
            .clickable { onPlay(movie) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("▶", color = Color.White, fontSize = 30.sp, modifier = Modifier.padding(start = 4.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("디시 동영상", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("눌러서 온라인 재생", color = Color(0xFFBDBDBD), fontSize = 12.sp)
            }
        }
        HorizontalDivider(color = Color(0xFF333333))
        Text(
            "영상 파일은 스냅샷에 포함되지 않으며 현재 디시 서버에서 불러옵니다.",
            color = subTextColor,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(textColor.copy(alpha = 0.04f))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

@Composable
internal fun SnapshotDcMoviePlayer(
    movie: BodyElement.DcMovieElement,
    topBarColor: Color,
    textColor: Color,
    subTextColor: Color,
    onBack: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val policy = remember { snapshotMovieWebPolicy() }
    var webView by remember(movie) { mutableStateOf<WebView?>(null) }
    var loading by remember(movie) { mutableStateOf(true) }
    var loadFailed by remember(movie) { mutableStateOf(false) }

    DisposableEffect(movie) {
        onDispose {
            webView?.onPause()
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
            webView = null
        }
    }

    DisposableEffect(lifecycleOwner, movie) {
        val observer = LifecycleEventObserver { _, event ->
            when (snapshotMovieLifecycleAction(event)) {
                SnapshotMovieLifecycleAction.RESUME -> webView?.onResume()
                SnapshotMovieLifecycleAction.PAUSE -> webView?.onPause()
                SnapshotMovieLifecycleAction.NONE -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(topBarColor)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("← 돌아가기", color = textColor)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("디시 동영상", color = textColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("현재 디시 서버에서 온라인으로 재생합니다", color = subTextColor, fontSize = 11.sp)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        webView = this
                        setBackgroundColor(AndroidColor.BLACK)
                        settings.apply {
                            javaScriptEnabled = policy.javaScriptEnabled
                            allowFileAccess = policy.allowFileAccess
                            allowContentAccess = policy.allowContentAccess
                            mediaPlaybackRequiresUserGesture = policy.mediaPlaybackRequiresUserGesture
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(false)
                            builtInZoomControls = false
                            displayZoomControls = false
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean = true

                            @Suppress("DEPRECATION")
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    loadFailed = true
                                }
                            }
                        }
                        loadUrl(movie.movieUrl, snapshotMovieRequestHeaders(movie))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (loading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (loadFailed) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("동영상을 불러올 수 없습니다.", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        "영상이 삭제됐거나 네트워크 연결이 끊겼을 수 있습니다.",
                        color = Color(0xFFBDBDBD),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
