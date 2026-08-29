package com.family.share;

import android.app.Application;
import android.os.RemoteException;

import com.amap.api.location.AMapLocationClient;
import com.amap.api.maps.MapsInitializer;

/**
 * 应用入口：初始化高德地图 SDK（3D 地图 SDK 8.0+ 需在 Application 中显式初始化）。
 */
public class App extends Application {

    private static App instance;

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // ===== 高德 SDK 隐私合规（必须在调用任何高德接口之前）=====
        // 高德地图/定位 SDK 要求：使用前必须先调用 updatePrivacyShow + updatePrivacyAgree 且为 true，
        // 否则 SDK 拒绝工作（地图空白/定位失败，errorCode 555570）。
        // 本应用为家庭自用：位置数据仅上传至用户自建的家庭服务器，仅家庭成员可见；
        // 首次启动时主界面会向用户展示隐私说明（见 MainActivity.maybeShowPrivacyDialog）。
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        AMapLocationClient.updatePrivacyShow(this, true, true);
        AMapLocationClient.updatePrivacyAgree(this, true);

        // 高德 3D 地图 SDK 初始化（Key 在 AndroidManifest 的 meta-data 中配置）
        try {
            MapsInitializer.initialize(this);
        } catch (RemoteException e) {
            // 初始化失败（多为 Key 无效/未联网），地图将无法显示，打印日志便于排查
            e.printStackTrace();
        }
    }
}
