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
                
                webViewClient = WebViewClient()
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
