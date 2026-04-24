package com.ethora.chat.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlin.math.roundToInt

@Composable
fun PDFViewer(
    pdfUrl: String,
    onLoadingChange: ((Boolean) -> Unit)? = null,
    onError: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pages by remember(pdfUrl) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var errorMessage by remember(pdfUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(pdfUrl) {
        onLoadingChange?.invoke(true)
        errorMessage = null
        pages = emptyList()
        try {
            pages = withContext(Dispatchers.IO) {
                val pdfFile = resolvePdfFile(context, pdfUrl)
                renderPdfPages(pdfFile)
            }
        } catch (e: Exception) {
            android.util.Log.e("PDFViewer", "Failed to render PDF", e)
            errorMessage = "Failed to load PDF"
            onError?.invoke()
        } finally {
            onLoadingChange?.invoke(false)
        }
    }

    when {
        errorMessage != null -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = errorMessage ?: "Failed to load PDF",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        pages.isNotEmpty() -> {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(pages) { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "PDF page",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
        }
        else -> {
            Box(modifier = modifier.fillMaxSize())
        }
    }
}

private fun resolvePdfFile(context: android.content.Context, pdfUrl: String): File {
    if (pdfUrl.startsWith("file://")) {
        return File(pdfUrl.removePrefix("file://"))
    }
    if (pdfUrl.startsWith("/")) {
        return File(pdfUrl)
    }

    val cacheDir = File(context.cacheDir, "pdf_preview").apply { mkdirs() }
    val cacheFile = File(cacheDir, "${pdfUrl.hashCode().toUInt()}.pdf")
    if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile

    URL(pdfUrl).openConnection().apply {
        connectTimeout = 15_000
        readTimeout = 30_000
    }.getInputStream().use { input ->
        FileOutputStream(cacheFile).use { output ->
            input.copyTo(output)
        }
    }
    return cacheFile
}

private fun renderPdfPages(file: File): List<Bitmap> {
    require(file.exists() && file.length() > 0) { "PDF file is empty or missing" }
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    descriptor.use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            return (0 until renderer.pageCount).map { pageIndex ->
                renderer.openPage(pageIndex).use { page ->
                    val targetWidth = 1400
                    val scale = targetWidth.toFloat() / page.width.toFloat()
                    val targetHeight = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }
}
