# Mayonaka ProGuard/R8 rules.
#
# Keep stack traces readable -- this is a personal build, size matters less than being able to
# debug a crash from a logcat dump.
-dontobfuscate

# Everything under com.termux is reached from the manifest, from reflection (ResultReturner,
# PluginUtils), from RemoteViews (the widget) or from XML preference/layout inflation. R8 cannot
# see all of those edges, so keep the app's own code wholesale and let it shrink the libraries.
-keep class com.termux.** { *; }
-keepclassmembers class com.termux.** { *; }

# androidx.preference inflates fragments and preferences by name from XML.
-keep class * extends androidx.preference.Preference { *; }
-keep class * extends androidx.fragment.app.Fragment { *; }

# RxJava (used by the widget's Controls provider service) does reflective plugin lookups.
-dontwarn io.reactivex.**
-dontwarn org.reactivestreams.**

# Guava drags in a lot of compile-only annotations.
-dontwarn com.google.common.**
-dontwarn javax.annotation.**
-dontwarn sun.misc.**
-dontwarn java.lang.instrument.**
