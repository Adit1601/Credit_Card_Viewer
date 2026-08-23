# Keep Room-generated implementations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep BiometricPrompt callback surface
-keep class androidx.biometric.** { *; }

# Kotlin metadata (usually preserved by default rules; explicit for safety)
-keep class kotlin.Metadata { *; }

# androidx.security.crypto pulls in Google Tink, which references javax.annotation.Nullable
# and javax.annotation.concurrent.GuardedBy from JSR-305. Those are compile-only annotations
# not shipped at runtime. R8 hard-errors on missing classes in release mode; silence it.
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
