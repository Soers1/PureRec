# PureRec — 默认不开启混淆（isMinifyEnabled = false）。
# 如果开启混淆，以下规则可保持必要类不被裁掉。

-keep class com.purerec.core.** { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
