# Release R8 / ProGuard configuration for AGT Code
# Keep line numbers & sanitized source file for redacted crash diagnostics.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve Kotlin coroutines & Room database generated implementations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Preserve Retrofit / OkHttp / Moshi reflection and codegen classes
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep parcelables and serializable models
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
