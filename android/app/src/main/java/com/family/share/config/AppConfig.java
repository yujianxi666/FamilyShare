package com.family.share.config;

import com.family.share.BuildConfig;

/**
 * 全局配置。
 * AMAP_KEY / SERVER_URL 在 android/gradle.properties 中配置（构建时注入 BuildConfig）。
 */
public final class AppConfig {

    /** 高德开放平台 Key */
    public static final String AMAP_KEY = BuildConfig.AMAP_KEY;

    /** 后端服务器地址，如 http://192.168.1.100:3000 */
    public static final String SERVER_URL = BuildConfig.SERVER_URL;

    /** 访问口令：/api/**、/ws、/icons/** 请求需携带（与服务器 app.api-token 一致） */
    public static final String API_TOKEN = BuildConfig.API_TOKEN;

    /** WebSocket 地址（由 HTTP 地址推导） */
    public static final String WS_URL = SERVER_URL.replaceFirst("^http", "ws") + "/ws";

    /** 默认上报间隔：30 分钟 */
    public static final long REPORT_INTERVAL_MS = 30 * 60 * 1000L;

    /** 前台 UI 刷新成员列表间隔（在线/头像/群主等状态需要及时同步，避免“手动刷新才更新”） */
    public static final long UI_REFRESH_INTERVAL_MS = 30 * 1000L;

    /** 轨迹上报间隔：5 分钟（轨迹功能开启时） */
    public static final long TRACK_INTERVAL_MS = 5 * 60 * 1000L;

    /** 看门狗检查间隔：服务被杀后最快 5 分钟内自动拉起 */
    public static final long WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L;

    /** 通知渠道 */
    public static final String CHANNEL_ID = "family_location";

    /** 服务动作 */
    public static final String ACTION_START = "com.family.share.action.START";
    public static final String ACTION_STOP = "com.family.share.action.STOP";
    public static final String ACTION_REPORT_NOW = "com.family.share.action.REPORT_NOW";
    public static final String ACTION_RECONNECT = "com.family.share.action.RECONNECT";
    /** 停止本机正在播放的响铃（详情页「关闭响铃」按钮 / 通知按钮） */
    public static final String ACTION_STOP_RING = "com.family.share.action.STOP_RING";
    /** 查询当前响铃状态（服务回广播 RING_STARTED/RING_STOPPED，用于一键刷新） */
    public static final String ACTION_QUERY_RING = "com.family.share.action.QUERY_RING";

    /** UI 广播 */
    public static final String BROADCAST_LOCATION_UPDATE = "com.family.share.broadcast.LOCATION_UPDATE";
    public static final String BROADCAST_MEMBER_STATUS = "com.family.share.broadcast.MEMBER_STATUS";
    public static final String BROADCAST_SERVICE_STATUS = "com.family.share.broadcast.SERVICE_STATUS";
    /** 响铃开始/结束（主页面据此显示/隐藏「关闭响铃」按钮） */
    public static final String BROADCAST_RING_STARTED = "com.family.share.broadcast.RING_STARTED";
    public static final String BROADCAST_RING_STOPPED = "com.family.share.broadcast.RING_STOPPED";
    /** 成员被移出家庭（deviceId=被移出者；本机被移出时同发该广播） */
    public static final String BROADCAST_MEMBER_REMOVED = "com.family.share.broadcast.MEMBER_REMOVED";
    /** 新成员加入家庭 */
    public static final String BROADCAST_MEMBER_JOINED = "com.family.share.broadcast.MEMBER_JOINED";
    /** 群主已转让（deviceId=新群主） */
    public static final String BROADCAST_OWNER_CHANGED = "com.family.share.broadcast.OWNER_CHANGED";
    /** 收到加入家庭邀请（code=家庭码，name=邀请人） */
    public static final String BROADCAST_INVITE = "com.family.share.broadcast.INVITE";
    /** 有人申请加入家庭（群主审批用；requestId/deviceId/name） */
    public static final String BROADCAST_JOIN_REQUEST = "com.family.share.broadcast.JOIN_REQUEST";

    /** 服务连接状态 */
    public static final int STATUS_CONNECTING = 0;
    public static final int STATUS_ONLINE = 1;
    public static final int STATUS_OFFLINE = 2;

    private AppConfig() {
    }
}
