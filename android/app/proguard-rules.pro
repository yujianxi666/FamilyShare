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

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
