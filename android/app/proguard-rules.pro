# ==================== 高德地图 SDK 混淆规则 ====================
-keep class com.amap.api.maps.**{*;}
-keep class com.autonavi.amap.mapcore.**{*;}
-keep class com.amap.api.trace.**{*;}
-keep class com.amap.api.location.**{*;}
-keep class com.amap.api.fence.**{*;}
-keep class com.autonavi.aps.amapapi.model.**{*;}
-keep class com.amap.api.maps.model.**{*;}
-keep class com.amap.api.services.**{*;}
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**

# 安全兜底：高德内部有反射/动态调用，整个包保留（Java 代码量很小，不影响体积大头）
-keep class com.amap.api.**{*;}
-keep class com.autonavi.**{*;}

# 二维码：zxing + zxing-android-embedded（扫码界面由 XML/清单按类名引用）
-keep class com.google.zxing.**{*;}
-keep class com.journeyapps.barcodescanner.**{*;}
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.barcodescanner.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
