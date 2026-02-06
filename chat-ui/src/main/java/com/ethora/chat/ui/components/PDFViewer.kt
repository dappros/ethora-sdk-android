package com.ethora.chat.ui.components

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * PDF viewer using WebView with PDF.js (like web version)
 */
@Composable
fun PDFViewer(
    pdfUrl: String,
    onLoadingChange: ((Boolean) -> Unit)? = null,
    onError: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = true
                settings.setSupportZoom(true)
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        onLoadingChange?.invoke(true)
                    }
                    
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onLoadingChange?.invoke(false)
                    }
                    
                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        onLoadingChange?.invoke(false)
                        onError?.invoke()
                    }
                }
                
                webChromeClient = WebChromeClient()
                
                // Use PDF.js viewer (same as web version)
                val pdfJsViewer = "https://mozilla.github.io/pdf.js/web/viewer.html"
                val viewerUrl = "$pdfJsViewer?file=${android.net.Uri.encode(pdfUrl)}"
                loadUrl(viewerUrl)
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
