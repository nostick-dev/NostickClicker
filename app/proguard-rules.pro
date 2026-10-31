# NostickClicker ProGuard / R8 rules
# The accessibility service is bound by name from the manifest.
-keep class com.nostick.clicker.ClickerService { *; }
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.accessibilityservice.AccessibilityService

# Optional Shizuku integration uses reflection on hidden system classes.
-keep class rikka.shizuku.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-keep class com.nostick.clicker.ShizukuInjector { *; }
-dontwarn rikka.shizuku.**
-dontwarn org.lsposed.hiddenapibypass.**
