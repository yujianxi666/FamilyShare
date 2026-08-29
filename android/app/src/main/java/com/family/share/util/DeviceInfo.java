package com.family.share.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;

/**
 * 设备信息：电量 / 网络类型（随位置上报，供家人详情页展示）。
 */
public final class DeviceInfo {

    private DeviceInfo() {
    }

    /** 电池电量 0-100，未知返回 -1 */
    public static int battery(Context context) {
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm == null) {
                return -1;
            }
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 网络类型：WiFi（含名称）/ 移动网络 / 无网络 / 未知 */
    public static String network(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return "未知";
            }
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null) {
                return "无网络";
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                String ssid = wifiSsid(context);
                return ssid.isEmpty() ? "WiFi" : "WiFi（" + ssid + "）";
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return "移动网络";
            }
            return "其他";
        } catch (Exception e) {
            return "未知";
        }
    }

    /**
     * WiFi 名称（SSID），获取失败返回空字符串。
     * 统一使用 WifiManager.getConnectionInfo()（API 1 即存在；Android 12+ 已弃用属刻意兼容，故抑制告警）：
     * 本应用已持有 ACCESS_FINE_LOCATION（地图/定位必需），Android 12+ 下也能正常返回 SSID。
     * 不用 getCurrentNetworkWifiInfo()（API 33 才新增）：在本应用支持的 Android 12/12L（API 31/32）上运行会抛 NoSuchMethodError。
     */
    @SuppressWarnings("deprecation")
    private static String wifiSsid(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return "";
            }
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) {
                return "";
            }
            String ssid = info.getSSID();
            if (ssid == null) {
                return "";
            }
            ssid = ssid.replace("\"", "").trim();
            if (ssid.isEmpty()
                    || "unknown ssid".equalsIgnoreCase(ssid)
                    || "<unknown ssid>".equalsIgnoreCase(ssid)) {
                return "";
            }
            return ssid;
        } catch (Exception e) {
            return "";
        }
    }
}
