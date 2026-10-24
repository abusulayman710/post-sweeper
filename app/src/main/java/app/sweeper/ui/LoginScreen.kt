package app.sweeper.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.sweeper.AppLog
import app.sweeper.BuildConfig
import app.sweeper.net.XTransport
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val origin = settings.general.apiOrigin
    var web by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var showCookieDialog by remember { mutableStateOf(false) }

    // Poll the WebView cookie jar only while the cookie dialog is closed.
    LaunchedEffect(origin, showCookieDialog) {
        if (showCookieDialog) return@LaunchedEffect
        while (true) {
            delay(1500)
            val cm = CookieManager.getInstance()
            val c = cm.getCookie(origin).orEmpty()
            if (c.contains("auth_token=") && c.contains("ct0=")) {
                vm.loginDetected()
            }
        }
    }
    BackHandler(enabled = web?.canGoBack() == true) { web?.goBack() }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Log in to X", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        web?.stopLoading()
                        showCookieDialog = true
                    }) { Text("Paste cookies") }
                    TextButton(onClick = {
                        pageError = null
                        web?.loadUrl("$origin/i/flow/login")
                    }) { Text("Reload") }
                }
            }
            Text(
                "Sign in below or tap Paste cookies to connect using auth_token and ct0 from your browser.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pageError != null) {
                Text(
                    "Load warning: $pageError",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (BuildConfig.DEBUG && !origin.startsWith("https://x.com")) {
                Row { GhostButton("Use dev session (mock server)", Modifier.fillMaxWidth().padding(top = 8.dp)) { vm.devSession() } }
            }
        }

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setBackgroundColor(android.graphics.Color.BLACK)

                    this.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        userAgentString = XTransport.cleanUserAgent(userAgentString)
                    }

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        runCatching {
                            WebViewCompat.addDocumentStartJavaScript(this, XTransport.POLYFILL_JS, setOf("*"))
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                            if (cm != null && cm.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                AppLog.w("LoginWeb", "${cm.message()} (${cm.sourceId()}:${cm.lineNumber()})")
                            }
                            return super.onConsoleMessage(cm)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url ?: return false
                            val scheme = url.scheme?.lowercase()
                            if (scheme != "https" && scheme != "http") {
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            pageError = null
                            view?.evaluateJavascript(XTransport.POLYFILL_JS, null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            CookieManager.getInstance().flush()
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (request?.isForMainFrame == true && error != null) {
                                pageError = "${error.description} (${error.errorCode})"
                            }
                        }
                    }

                    loadUrl("$origin/i/flow/login")
                    web = this
                }
            },
            onRelease = { it.destroy() },
        )
    }

    if (showCookieDialog) {
        var authToken by remember { mutableStateOf("") }
        var ct0 by remember { mutableStateOf("") }
        var handleOrUid by remember { mutableStateOf("") }
        var connecting by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { if (!connecting) showCookieDialog = false },
            title = { Text("Log in with session cookies") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste auth_token and ct0 from a logged-in x.com browser tab (DevTools > Application > Cookies > https://x.com), plus your @username.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = authToken,
                        onValueChange = { authToken = it; err = null },
                        label = { Text("auth_token") },
                        singleLine = true,
                        enabled = !connecting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = ct0,
                        onValueChange = { ct0 = it; err = null },
                        label = { Text("ct0 (CSRF token)") },
                        singleLine = true,
                        enabled = !connecting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = handleOrUid,
                        onValueChange = { handleOrUid = it; err = null },
                        label = { Text("@username or numeric User ID") },
                        placeholder = { Text("e.g. @yourhandle or 12345678") },
                        singleLine = true,
                        enabled = !connecting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (connecting) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        Text(
                            "Verifying session with X…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (err != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(err!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !connecting,
                    onClick = {
                        err = null
                        connecting = true
                        // Stop the login WebView so it cannot overwrite ct0 with a guest cookie
                        web?.stopLoading()
                        web?.loadUrl("about:blank")
                        vm.loginWithCookies(
                            authToken = authToken,
                            ct0 = ct0,
                            handleOrUserId = handleOrUid,
                            onDone = {
                                connecting = false
                                showCookieDialog = false
                            },
                            onError = { msg ->
                                connecting = false
                                err = msg
                            },
                        )
                    },
                ) { Text(if (connecting) "Connecting…" else "Save & Connect") }
            },
            dismissButton = {
                TextButton(
                    enabled = !connecting,
                    onClick = { showCookieDialog = false },
                ) { Text("Cancel") }
            },
        )
    }
}
