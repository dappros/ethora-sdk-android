package com.ethora.chat.core.networking

import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.search.MessageSearchPage
import com.ethora.chat.core.search.MessageSearchParams
import com.ethora.chat.core.search.MessageSearchResponseDto
import com.ethora.chat.core.search.parseMessageSearchPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.QueryMap
import retrofit2.http.Url
import java.net.URLEncoder

/**
 * GET /v2/apps/{appId}/messages/search. The endpoint lives on v2 while the
 * configured baseUrl usually ends in /v1, so the full URL is built per call.
 * The user token is added by the shared auth interceptor.
 */
interface MessageSearchAPI {
    @GET
    suspend fun search(
        @Url url: String,
        @QueryMap params: Map<String, String>
    ): Response<MessageSearchResponseDto>
}

object MessageSearchService {

    /** Origin of a configured base URL: `https://host/v1` -> `https://host` (web apiClient.ts rule). */
    internal fun apiOriginOf(baseUrl: String): String =
        baseUrl.trim().trimEnd('/').replace(Regex("/+v[12]$", RegexOption.IGNORE_CASE), "")

    /** Full search URL for an app. Pure, so the path can be tested. */
    internal fun searchUrl(baseUrl: String, appId: String): String =
        "${apiOriginOf(baseUrl)}/v2/apps/${URLEncoder.encode(appId, "UTF-8")}/messages/search"

    /**
     * Searches the archive. Throws when search is not configured (no appId) or
     * the request fails; callers show the error with a retry.
     */
    suspend fun search(params: MessageSearchParams): MessageSearchPage = withContext(Dispatchers.IO) {
        val appId = ChatStore.config.value?.appId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("message_search_no_app_id")
        val url = searchUrl(ChatStore.getEffectiveBaseUrl(), appId)
        val query = buildMap {
            put("q", params.q)
            put("limit", params.limit.toString())
            put("offset", params.offset.toString())
            params.chatId?.takeIf { it.isNotEmpty() }?.let { put("chatId", it) }
            params.fromUserId?.takeIf { it.isNotEmpty() }?.let { put("fromUserId", it) }
            params.since?.takeIf { it.isNotEmpty() }?.let { put("since", it) }
            params.until?.takeIf { it.isNotEmpty() }?.let { put("until", it) }
        }
        val response = ApiClient.createService<MessageSearchAPI>().search(url, query)
        if (!response.isSuccessful) {
            throw IllegalStateException("message_search_http_${response.code()}")
        }
        parseMessageSearchPage(response.body(), params.offset, params.limit)
    }
}
