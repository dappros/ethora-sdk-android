# Add project specific ProGuard rules here.

# Keep Compose
-keep class androidx.compose.** { *; }
-keep class kotlin.coroutines.** { *; }

# Keep UI components
-keep class com.ethora.chat.ui.** { *; }
