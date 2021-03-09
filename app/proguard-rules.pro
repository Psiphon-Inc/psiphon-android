# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /opt/android-sdk-linux/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# keep all of the ca.psiphon package
-keep class ca.psiphon.** {*;}

# From https://github.com/googleads/googleads-consent-sdk-android/blob/master/consent-library/proguard-rules.pro
-keep class com.google.ads.consent.** { <fields>; }
-keepattributes *Annotation*
-keepattributes Signature

# MoPub Proguard Config
# Keep public classes and methods.
-keepclassmembers class com.mopub.** { public *; }
-keep public class com.mopub.**
-keep public class android.webkit.JavascriptInterface {}

# Explicitly keep any custom event classes in any package.
-keep class * extends com.mopub.mobileads.CustomEventBanner {}
-keep class * extends com.mopub.mobileads.CustomEventInterstitial {}
-keep class * extends com.mopub.nativeads.CustomEventNative {}
-keep class * extends com.mopub.nativeads.CustomEventRewardedAd {}

# Keep methods that are accessed via reflection
-keepclassmembers class ** { @com.mopub.common.util.ReflectionTarget *; }

# Viewability support
-keepclassmembers class com.integralads.avid.library.mopub.** { public *; }
-keep public class com.integralads.avid.library.mopub.**
-keepclassmembers class com.moat.analytics.mobile.mpub.** { public *; }
-keep public class com.moat.analytics.mobile.mpub.**

# Support for Android Advertiser ID.
-keep class com.google.android.gms.common.GooglePlayServicesUtil {*;}
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient {*;}
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient$Info {*;}

# Support for Google Play Services
# http://developer.android.com/google/play-services/setup.html
-keep class * extends java.util.ListResourceBundle {
    protected Object[][] getContents();
}

-keep public class com.google.android.gms.common.internal.safeparcel.SafeParcelable {
    public static final *** NULL;
}

-keepnames @com.google.android.gms.common.annotation.KeepName class *
-keepclassmembernames class * {
    @com.google.android.gms.common.annotation.KeepName *;
}

-keepnames class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
# Support for Google Play Billing Library
# https://developer.android.com/google/play/billing/billing_library_overview
-keep class com.android.vending.billing.**

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jacoco.**
-dontwarn com.mopub.nativeads.**
-dontwarn com.mopub.mobileads.**
-dontwarn com.android.billingclient.**

# ProGuard rules for FreeStar Ads Mediation SDK

-dontwarn android.app.Activity

# For communication with AdColony's WebView
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep filenames and line numbers for stack traces
-keepattributes SourceFile,LineNumberTable

# Keep JavascriptInterface for WebView bridge
-keepattributes JavascriptInterface

# Sometimes keepattributes is not enough to keep annotations
-keep class android.webkit.JavascriptInterface {
   *;
}

-keep class androidx.** {*;}
-keep interface androidx.** {*;}
-dontwarn androidx.**

-keep class android.** {*;}
-keep interface android.** {*;}
-dontwarn android.**

-keep class com.adcolony.** { *; }
-keep interface com.iab.omid.** { *; }
-dontwarn com.iab.omid.**
-dontwarn com.adcolony.**

-keep class com.amazon.** {*;}
-keep interface com.amazon.** {*;}

-keep class com.applovin.** {*;}
-keep interface com.applovin.** {*;}

-keep class com.criteo.** {*;}
-keep interface com.criteo.** {*;}

-keep class com.danikula.** {*;}
-keep interface com.danikula.** {*;}

-keep class com.facebook.** {*;}
-keep interface com.facebook.** {*;}

-keep interface com.freestar.** {*;}
-keep class com.freestar.** { *; }

-keep interface com.iab.** {*;}
-keep class com.iab.** { *; }

-keep class com.google.** { *; }
-keep interface com.google.** { *; }
-dontwarn com.google.**

-keep class com.mopub.** {*;}
-keep interface com.mopub.** {*;}

-keep class com.tapjoy.** { *; }
-keep interface com.tapjoy.** { *; }
-keep class com.moat.** { *; }
-keep interface com.moat.** { *; }

-keep class com.squareup.picasso.** {*;}
-dontwarn com.squareup.picasso.**
-dontwarn com.squareup.okhttp.**

-keep class com.unity3d.** {*;}
-keep interface com.unity3d.** {*;}

-keep class com.vungle.** {*;}
-keep interface com.vungle.** {*;}

-keep class okio.** {*;}
-keep interface okio.** {*;}
-dontwarn okio.**

-keep class retrofit2.** {*;}
-keep interface retrofit2.** {*;}
-dontwarn retrofit2.**

# End of ProGuard rules for FreeStar Ads Mediation SDK