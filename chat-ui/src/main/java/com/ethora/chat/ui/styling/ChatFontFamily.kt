package com.ethora.chat.ui.styling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
// Aliased: the Google Fonts `Font(...)` overload lives in a different package
// from the bundled-resource `Font(resId, ...)` above and shares its name.
import androidx.compose.ui.text.googlefonts.Font as GoogleFontFont
import com.ethora.chat.core.config.ChatTypographyConfig

/**
 * Resolve the host-provided [ChatTypographyConfig] into a Compose [FontFamily].
 *
 * Resolution order:
 *  1. [ChatTypographyConfig.fontResources] — bundled `res/font` files
 *     (e.g. the e-Ukraine `.ttf`s). Fully self-contained, no Play Services.
 *  2. [ChatTypographyConfig.googleFontsFamily] (+ provider certs) — a
 *     downloadable Google Font resolved by name at runtime.
 *  3. `null` → caller falls back to [FontFamily.Default], so existing
 *     integrations are unaffected.
 */
@Composable
fun rememberChatFontFamily(config: ChatTypographyConfig?): FontFamily? {
    config ?: return null
    val w = config.weights

    return remember(config) {
        val resources = config.fontResources
        when {
            !resources.isNullOrEmpty() -> FontFamily(
                resources.map { (weight, resId) ->
                    Font(resId = resId, weight = FontWeight(weight))
                }
            )

            !config.googleFontsFamily.isNullOrBlank() && config.googleFontProviderCerts != null -> {
                val provider = GoogleFont.Provider(
                    providerAuthority = "com.google.android.gms.fonts",
                    providerPackage = "com.google.android.gms",
                    certificates = config.googleFontProviderCerts,
                )
                val name = GoogleFont(config.googleFontsFamily)
                FontFamily(
                    GoogleFontFont(googleFont = name, fontProvider = provider, weight = FontWeight(w.regular)),
                    GoogleFontFont(googleFont = name, fontProvider = provider, weight = FontWeight(w.medium)),
                    GoogleFontFont(googleFont = name, fontProvider = provider, weight = FontWeight(w.semibold)),
                    GoogleFontFont(googleFont = name, fontProvider = provider, weight = FontWeight(w.bold)),
                )
            }

            else -> null
        }
    }
}
