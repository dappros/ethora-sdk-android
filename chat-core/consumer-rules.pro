# Consumer ProGuard rules for library consumers

# Keep public API
-keep public class com.ethora.chat.core.** { public *; }

# Keep configuration classes
-keep class com.ethora.chat.core.config.** { *; }

# Keep models
-keep class com.ethora.chat.core.models.** { *; }
