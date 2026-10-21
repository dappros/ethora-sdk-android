package com.ethora.chat.core.networking

import com.ethora.chat.core.i18n.canonicalLocale
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.ReaderLanguageStore
import com.ethora.chat.core.store.UserStore
import com.google.gson.JsonParser
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Url

/**
 * Account endpoints of the settings screen. Port of web `networking/api-requests/user.api.ts`
 * and the React Native `changePassword`.
 *
 * Every call takes an absolute URL: the shared Retrofit instance is bound to the chat base URL,
 * and `v1` and `v2` paths live side by side, so [AccountService] builds the full URL itself.
 */
interface AccountAPI {
    /** `PUT /v1/users` with one profile field, e.g. `{"appLanguage": "fr-CA"}`. */
    @PUT
    suspend fun updateProfile(
        @Url url: String,
        @Body body: Map<String, String>
    ): Response<ResponseBody>

    /** `PUT /v2/users/me/password`. */
    @PUT
    suspend fun changePassword(
        @Url url: String,
        @Body body: ChangePasswordRequest
    ): Response<ResponseBody>

    /** `DELETE /v1/users`: permanently deletes the account. */
    @DELETE
    suspend fun deleteAccount(@Url url: String): Response<ResponseBody>

    /** `GET /v1/users/exportData`: the account's data as a file. */
    @GET
    suspend fun exportData(@Url url: String): Response<ResponseBody>
}

/** Body of `PUT /v2/users/me/password`. */
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

/** Failure from an account call. [serverMessage] is the server's reason when it sent one. */
class AccountException(message: String, val serverMessage: String? = null) : Exception(message)

/**
 * Account operations for the settings screen. Each returns a [Result]; the failure carries the
 * server's own reason when it gave one.
 */
object AccountService {
    private const val LANGUAGE_FIELD_APP = "appLanguage"
    private const val LANGUAGE_FIELD_CHAT = "chatLanguage"

    /** Chat base URL without its trailing `/v1` or `/v2`, so each call can name its own version. */
    internal fun apiRoot(baseUrl: String = ChatStore.getEffectiveBaseUrl()): String =
        baseUrl.trim().trimEnd('/').replace(Regex("/v[12]$"), "")

    private fun api(): AccountAPI = ApiClient.createService(AccountAPI::class.java)

    private fun languagesEnabled(): Boolean =
        ChatStore.getConfig()?.settings?.languages?.enabled == true

    /**
     * Set the interface language. Applied at once on the device; written to the profile only when
     * the Language card is enabled. A failed write keeps the local choice.
     */
    suspend fun setAppLanguage(locale: String): Result<Unit> {
        val canonical = canonicalLocale(locale)
            ?: return Result.failure(IllegalArgumentException("Not a language tag: $locale"))
        ReaderLanguageStore.setUiLanguage(canonical)
        return if (languagesEnabled()) updateProfileField(LANGUAGE_FIELD_APP, canonical) else Result.success(Unit)
    }

    /** Set the language messages are translated into. Same rules as [setAppLanguage]. */
    suspend fun setChatLanguage(locale: String): Result<Unit> {
        val canonical = canonicalLocale(locale)
            ?: return Result.failure(IllegalArgumentException("Not a language tag: $locale"))
        ReaderLanguageStore.setChatLanguage(canonical)
        return if (languagesEnabled()) updateProfileField(LANGUAGE_FIELD_CHAT, canonical) else Result.success(Unit)
    }

    /** One field per request, so writing one language never rewrites the other. */
    suspend fun updateProfileField(field: String, value: String): Result<Unit> = runCatching {
        val response = api().updateProfile(apiRoot() + "/v1/users", mapOf(field to value))
        ensureSuccess(response.code(), response.errorBody(), "update-profile")
        UserStore.currentUser.value?.let { user ->
            val updated = if (field == LANGUAGE_FIELD_APP) {
                user.copy(appLanguage = value)
            } else {
                user.copy(chatLanguage = value)
            }
            UserStore.setUser(updated)
        }
        Unit
    }

    /** `PUT /v2/users/me/password`. */
    suspend fun changePassword(currentPassword: String, newPassword: String): Result<Unit> = runCatching {
        val response = api().changePassword(
            apiRoot() + "/v2/users/me/password",
            ChangePasswordRequest(currentPassword, newPassword)
        )
        ensureSuccess(response.code(), response.errorBody(), "change-password")
        Unit
    }

    /** `DELETE /v1/users`. The caller tears the session down afterwards. */
    suspend fun deleteAccount(): Result<Unit> = runCatching {
        val response = api().deleteAccount(apiRoot() + "/v1/users")
        ensureSuccess(response.code(), response.errorBody(), "delete-account")
        Unit
    }

    /** `GET /v1/users/exportData`. The bytes of the exported file. */
    suspend fun exportData(): Result<ByteArray> = runCatching {
        val response = api().exportData(apiRoot() + "/v1/users/exportData")
        if (!response.isSuccessful) {
            throw AccountException(
                "export-data failed: HTTP ${response.code()}",
                serverMessageOf(response.errorBody()?.string())
            )
        }
        response.body()?.bytes() ?: ByteArray(0)
    }

    private fun ensureSuccess(code: Int, errorBody: ResponseBody?, op: String) {
        if (code in 200..299) return
        val raw = errorBody?.string()
        throw AccountException("$op failed: HTTP $code", serverMessageOf(raw))
    }

    /** The reason the server gave: its `message` or `error` field, when the body is JSON. */
    internal fun serverMessageOf(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val obj = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return null
            listOf("message", "error").firstNotNullOfOrNull { key ->
                obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            }
        } catch (e: Exception) {
            null
        }
    }
}
