package com.ethora.chat.ui.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

data class UrlPreviewData(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
    val host: String? = null
)

object UrlPreviewStore {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val _previews = MutableStateFlow<Map<String, UrlPreviewData>>(emptyMap())
    val previews: StateFlow<Map<String, UrlPreviewData>> = _previews.asStateFlow()

    fun prefetch(url: String) {
        if (_previews.value.containsKey(url)) return
        if (!inFlight.add(url)) return

        scope.launch {
            val result = runCatching { fetchMetadata(url) }.getOrNull()
                ?: UrlPreviewData(url = url, host = safeHost(url))
            _previews.value = _previews.value + (url to result)
            inFlight.remove(url)
        }
    }

    private fun fetchMetadata(url: String): UrlPreviewData {
        val normalized = normalizeUrl(url)
        val html = URL(normalized).openConnection().run {
            connectTimeout = 5000
            readTimeout = 5000
            getInputStream().bufferedReader().use { reader ->
                reader.readText().take(200_000)
            }
        }

        val title = findMeta(html, "og:title") ?: findTag(html, "title")
        val description = findMeta(html, "og:description")
            ?: findMeta(html, "description")
        val image = findMeta(html, "og:image")
        return UrlPreviewData(
            url = normalized,
            title = title,
            description = description,
            image = image,
            host = safeHost(normalized)
        )
    }

    private fun findMeta(html: String, property: String): String? {
        val pattern = Pattern.compile(
            "<meta[^>]+(?:property|name)=[\"']${Pattern.quote(property)}[\"'][^>]+content=[\"']([^\"']+)[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE
        )
        val match = pattern.matcher(html)
        return if (match.find()) match.group(1)?.trim() else null
    }

    private fun findTag(html: String, tag: String): String? {
        val pattern = Pattern.compile("<$tag[^>]*>(.*?)</$tag>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val match = pattern.matcher(html)
        return if (match.find()) match.group(1)?.replace(Regex("\\s+"), " ")?.trim() else null
    }

    private fun normalizeUrl(url: String): String {
        return if (url.startsWith("www.")) "https://$url" else url
    }

    private fun safeHost(url: String): String? {
        return runCatching { URL(normalizeUrl(url)).host }.getOrNull()
    }
}
