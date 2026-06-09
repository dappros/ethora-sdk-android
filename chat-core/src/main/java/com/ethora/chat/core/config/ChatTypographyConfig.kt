package com.ethora.chat.core.config

/**
 * Font configuration for the chat UI, mirroring the web `TypographyConfig`
 * and the React Native `typography` config.
 *
 * Two sources are supported:
 *
 *  1. **Google Fonts (by name)** — set [googleFontsFamily] (e.g. "Inter").
 *     The UI layer resolves it to a downloadable [androidx.compose.ui.text.font.FontFamily]
 *     via the Google Fonts provider at runtime — no font files to bundle.
 *
 *  2. **Bundled font resources** — for fonts not on Google Fonts (e.g. the
 *     Ukrainian government "e-Ukraine" family), the host app drops the
 *     `.ttf`/`.otf` files into `res/font` and passes their resource IDs via
 *     [fontResources], keyed by weight.
 *
 * When this whole config is null (the default), the UI keeps
 * `FontFamily.Default`, so existing integrations are unaffected.
 */
data class ChatTypographyConfig(
    /**
     * Google Fonts family name, e.g. "Inter", "Roboto", "Montserrat".
     * Resolved to a downloadable FontFamily by the UI layer.
     *
     * Requires [googleFontProviderCerts] to be supplied (the standard
     * `font_certs.xml` array every Downloadable-Fonts app declares). If that
     * is null, this path is skipped and the UI falls back to the default font.
     */
    val googleFontsFamily: String? = null,

    /**
     * Resource id of the Google Fonts provider certificate array
     * (`R.array.com_google_android_gms_fonts_certs`). Host-supplied so the SDK
     * doesn't have to bundle/maintain the cert blob. Required for
     * [googleFontsFamily] to take effect.
     */
    val googleFontProviderCerts: Int? = null,

    /**
     * Bundled font resources keyed by weight (e.g. 400 -> R.font.e_ukraine_regular,
     * 500 -> R.font.e_ukraine_medium, 700 -> R.font.e_ukraine_bold).
     * Takes precedence over [googleFontsFamily] when non-empty.
     */
    val fontResources: Map<Int, Int>? = null,

    /**
     * Weight tokens the UI maps onto its type scale. Defaults match the
     * existing `ChatTypography` (normal body, semibold titles, etc.).
     */
    val weights: ChatFontWeights = ChatFontWeights(),
)

/**
 * Numeric font weights used across the chat type scale. Lets a host nudge the
 * overall weight of the UI to match a brand font that runs light or heavy.
 */
data class ChatFontWeights(
    val regular: Int = 400,
    val medium: Int = 500,
    val semibold: Int = 600,
    val bold: Int = 700,
)
