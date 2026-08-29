package com.family.share.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.family.share.MainActivity;
import com.family.share.R;
import com.family.share.config.AppConfig;
import com.family.share.data.Member;
import com.family.share.data.Prefs;
import com.family.share.location.LocationHelper;
import com.family.share.network.Api;
import com.family.share.network.WsClient;
import com.family.share.receiver.KeepAliveReceiver;
import com.family.share.util.DeviceInfo;

/**
 * 后台位置上报前台服务：
 * - 前台服务常驻，保证持续运行
 * - AlarmManager 按间隔触发一次定位并上报（默认 30 分钟；开启轨迹后按用户选择的间隔，Doze 下自调度省电）
 * - WebSocket 常连接：收到服务器 "report-now" 指令时立即定位并上报（家人实时查看）
 * - 定位由 LocationHelper 使用高德高精度模式（Hight_Accuracy，GPS+网络+WiFi）上报
 */
public class LocationReportService extends Service {

    private static final int NOTIFY_ID = 1001;

    /** 服务是否存活（看门狗据此判断是否需要重启） */
    public static volatile boolean isRunning = false;

    private Prefs prefs;
    private LocationHelper locationHelper;
    private WsClient wsClient;
    private AlarmManager alarmManager;
    private PendingIntent reportPi;
    private boolean started;

    private final WsClient.Listener wsListener = new WsClient.Listener() {
        @Override
        public void onReportNow() {
            // 家人正在查看 -> 立即上报一次真实位置（下线模式不上报）
            doReport();
        }

        @Override
        public void onMemberLocation(Member m) {
            broadcastLocation(m);
        }

        @Override
        public void onMemberStatus(String deviceId, boolean online) {
            Intent i = new Intent(AppConfig.BROADCAST_MEMBER_STATUS)
                    .setPackage(getPackageName())
                    .putExtra("deviceId", deviceId)
                    .putExtra("online", online);
            sendBroadcast(i);
        }

        @Override
        public void onTrackChanged(String deviceId, boolean track, long intervalMs) {
            // 只处理本机的轨迹开关
            if (deviceId.equals(prefs.deviceId())) {
                prefs.trackEnabled(track);
                if (intervalMs > 0) {
                    prefs.trackIntervalMs(intervalMs);
                }
                scheduleNextReport();
            }
            // 转发给 UI，刷新轨迹线
            Intent i = new Intent(AppConfig.BROADCAST_MEMBER_STATUS)
                    .setPackage(getPackageName())
                    .putExtra("deviceId", deviceId)
                    .putExtra("trackChanged", true)
                    .putExtra("track", track);
            sendBroadcast(i);
        }

        @Override
        public void onRingRequested(String fromName) {
            // WS 回调在 OkHttp 工作线程，MediaPlayer 操作统一切回主线程
            ringHandler.post(() -> playRing(fromName));
        }

        @Override
        public void onMemberRemoved(String deviceId) {
            // 其余家人收到：通知 UI 移除该成员
            sendBroadcast(new Intent(AppConfig.BROADCAST_MEMBER_REMOVED)
                    .setPackage(getPackageName())
                    .putExtra("deviceId", deviceId));
        }

        @Override
        public void onMemberJoined(String deviceId) {
            // 有新成员加入：通知 UI 刷新成员列表
            sendBroadcast(new Intent(AppConfig.BROADCAST_MEMBER_JOINED)
                    .setPackage(getPackageName())
                    .putExtra("deviceId", deviceId));
        }

        @Override
        public void onRemoved() {
            // 本机被移出家庭：清理本地家庭状态，停止服务（前台通知随之消失），通知 UI
            Prefs p = Prefs.get(LocationReportService.this);
            p.familyId("");
            p.familyCode("");
            p.isOwner(false);
            p.shareEnabled(false);
            p.offlineMode(false);
            sendBroadcast(new Intent(AppConfig.BROADCAST_MEMBER_REMOVED)
                    .setPackage(getPackageName())
                    .putExtra("deviceId", prefs.deviceId()));
            stopSelf();
        }

        @Override
        public void onOwnerChanged(String deviceId) {
            // 群主已转让：转发给 UI（本机是新群主时更新本地标记）
            sendBroadcast(new Intent(AppConfig.BROADCAST_OWNER_CHANGED)
                    .setPackage(getPackageName())
                    .putExtra("deviceId", deviceId));
        }

        @Override
        public void onInvited(String code, String fromName) {
            // 收到加入家庭邀请：转发给 UI 弹窗
            sendBroadcast(new Intent(AppConfig.BROADCAST_INVITE)
                    .setPackage(getPackageName())
                    .putExtra("code", code)
                    .putExtra("name", fromName));
        }

        @Override
        public void onJoinRequest(String requestId, String deviceId, String name) {
            // 有人申请加入本家庭：转发给 UI（群主审批）
            sendBroadcast(new Intent(AppConfig.BROADCAST_JOIN_REQUEST)
                    .setPackage(getPackageName())
                    .putExtra("requestId", requestId)
                    .putExtra("deviceId", deviceId)
                    .putExtra("name", name));
        }

        @Override
        public void onStatus(boolean connected) {
            Intent i = new Intent(AppConfig.BROADCAST_SERVICE_STATUS)
                    .setPackage(getPackageName())
                    .putExtra("status", connected ? AppConfig.STATUS_ONLINE : AppConfig.STATUS_OFFLINE);
            sendBroadcast(i);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        prefs = Prefs.get(this);
        locationHelper = new LocationHelper(this);
        alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        createChannel();

        Intent i = new Intent(this, LocationReportService.class).setAction(AppConfig.ACTION_REPORT_NOW);
        reportPi = PendingIntent.getService(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 启动看门狗：服务被杀后自动拉起
        KeepAliveReceiver.scheduleNext(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (AppConfig.ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundCompat();

        if (!started) {
            started = true;
            connectWs();
            scheduleNextReport();
            // 服务启动/设备上线后立即上报一次，让家人尽快看到
            doReport();
        }

        if (AppConfig.ACTION_REPORT_NOW.equals(action)) {
            doReport();
            scheduleNextReport();
        } else if (AppConfig.ACTION_RECONNECT.equals(action)) {
            // 家庭切换后重连
            if (wsClient != null) {
                wsClient.disconnect();
            }
            connectWs();
            scheduleNextReport();
        } else if (AppConfig.ACTION_STOP_RING.equals(action)) {
            // 主页面「关闭响铃」按钮 / 响铃通知按钮：立即停止
            ringHandler.post(this::stopRing);
        } else if (AppConfig.ACTION_QUERY_RING.equals(action)) {
            // 一键刷新：回广播当前响铃状态，供主页面同步「关闭响铃」按钮
            sendBroadcast(new Intent(ringActive
                    ? AppConfig.BROADCAST_RING_STARTED : AppConfig.BROADCAST_RING_STOPPED)
                    .setPackage(getPackageName()));
        }
        return START_STICKY;
    }

    // ---------------- 前台通知 ----------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    AppConfig.CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void startForegroundCompat() {
        Notification notification = new NotificationCompat.Builder(this, AppConfig.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_location)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // dataSync 类型：允许开机自启时启动前台服务（Android 14+ 限制 location 类型自启）
            ServiceCompat.startForeground(this, NOTIFY_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFY_ID, notification);
        }
    }

    // ---------------- 上报 ----------------

    /** 定位一次并上报服务器（成功与否静默，等下次调度；下线模式跳过） */
    private void doReport() {
        final String familyId = prefs.familyId();
        if (familyId.isEmpty() || prefs.offlineMode()) {
            return;
        }
        locationHelper.requestOnce(new LocationHelper.Callback() {
            @Override
            public void onResult(double lat, double lng, float accuracy, long time, String address) {
                prefs.saveLastLocation(lat, lng, time);
                Api.reportLocation(prefs.deviceId(), familyId, lat, lng, accuracy, time,
                        DeviceInfo.battery(LocationReportService.this),
                        DeviceInfo.network(LocationReportService.this),
                        address,
                        new Api.Callback() {
                            @Override
                            public void onSuccess(String body) {
                                // 服务器会向全家人广播 location-update
                            }

                            @Override
                            public void onError(String msg) {
                                // 静默，等待下次上报
                            }
                        });
                // 本地广播，让前台 UI 及时刷新自己的标记
                broadcastLocation(selfMember(lat, lng, accuracy, time, address));
            }

            @Override
            public void onError(String msg) {
                // 静默，等待下次调度
            }
        });
    }

    private Member selfMember(double lat, double lng, float accuracy, long time, String address) {
        Member m = new Member();
        m.deviceId = prefs.deviceId();
        m.name = prefs.deviceName();
        m.lat = lat;
        m.lng = lng;
        m.accuracy = accuracy;
        m.ts = time;
        m.address = address;
        m.battery = DeviceInfo.battery(this);
        m.network = DeviceInfo.network(this);
        m.online = !prefs.offlineMode();
        m.hasLocation = true;
        return m;
    }

    private void broadcastLocation(Member m) {
        Intent i = new Intent(AppConfig.BROADCAST_LOCATION_UPDATE)
                .setPackage(getPackageName())
                .putExtra("deviceId", m.deviceId)
                .putExtra("name", m.name)
                .putExtra("lat", m.lat)
                .putExtra("lng", m.lng)
                .putExtra("accuracy", m.accuracy)
                .putExtra("ts", m.ts)
                .putExtra("battery", m.battery)
                .putExtra("network", m.network)
                .putExtra("address", m.address);
        sendBroadcast(i);
    }

    // ---------------- 定时调度（30 分钟，轨迹开启时 5 分钟） ----------------

    private void scheduleNextReport() {
        // 轨迹开启时按用户选择的间隔（默认 5 分钟），否则 30 分钟
        long interval = prefs.trackEnabled() ? prefs.trackIntervalMs() : AppConfig.REPORT_INTERVAL_MS;
        long next = SystemClock.elapsedRealtime() + interval;
        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, reportPi);
    }

    // ---------------- WebSocket ----------------

    private void connectWs() {
        final String familyId = prefs.familyId();
        if (familyId.isEmpty()) {
            return;
        }
        wsClient = new WsClient(new Handler(Looper.getMainLooper()));
        wsClient.connect(prefs.deviceId(), familyId, prefs.deviceName(), prefs.offlineMode(), wsListener);
        sendStatus(AppConfig.STATUS_CONNECTING);
    }

    private void sendStatus(int status) {
        Intent i = new Intent(AppConfig.BROADCAST_SERVICE_STATUS)
                .setPackage(getPackageName())
                .putExtra("status", status);
        sendBroadcast(i);
    }

    // ---------------- 响铃（家人查找手机） ----------------

    private static final int RING_NOTIFY_ID = 1002;
    private static final long RING_DURATION_MS = 30 * 1000L;

    private MediaPlayer ringPlayer;
    /** 响铃前记录的闹钟音量，-1 表示无需恢复 */
    private int ringOriginalVolume = -1;
    /** 当前是否正在响铃（用于响铃结束广播与停止按钮显隐） */
    private boolean ringActive;
    private final Handler ringHandler = new Handler(Looper.getMainLooper());
    private final Runnable ringStopRunnable = this::stopRing;

    /**
     * 播放响铃 30 秒 + 常驻通知（点击打开 App）。
     * 使用闹钟音频流：手机静音/振动模式下也能出声（查找手机场景），结束后恢复原音量。
     * 若用户在权限设置中关闭了「允许他人响铃」，则忽略本次请求。
     */
    private void playRing(String fromName) {
        stopRing();
        if (!prefs.ringEnabled() || prefs.offlineMode()) {
            // 关闭了「允许他人响铃」或处于下线模式：忽略响铃请求
            return;
        }
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri == null) {
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            }
            if (uri == null) {
                return;
            }
            ringPlayer = new MediaPlayer();
            ringPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            ringPlayer.setDataSource(this, uri);
            ringPlayer.setLooping(true);
            ringPlayer.prepare();
            ringPlayer.start();
            boostRingVolume();
        } catch (Exception ignored) {
            // 播放失败静默
        }
        // 常驻通知（含「停止响铃」按钮）
        Notification notification = new NotificationCompat.Builder(this, AppConfig.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_location)
                .setContentTitle(getString(R.string.ring_notification_title))
                .setContentText(getString(R.string.ring_notification_text, fromName))
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .addAction(new NotificationCompat.Action(R.drawable.ic_stat_location,
                        getString(R.string.btn_stop_ring),
                        PendingIntent.getService(this, 1,
                                new Intent(this, LocationReportService.class)
                                        .setAction(AppConfig.ACTION_STOP_RING),
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)))
                .build();
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(RING_NOTIFY_ID, notification);
            }
        } catch (Exception ignored) {
        }
        // 通知主页面显示「关闭响铃」按钮
        sendBroadcast(new Intent(AppConfig.BROADCAST_RING_STARTED)
                .setPackage(getPackageName())
                .putExtra("name", fromName));
        ringActive = true;
        // 30 秒后自动停止
        ringHandler.removeCallbacks(ringStopRunnable);
        ringHandler.postDelayed(ringStopRunnable, RING_DURATION_MS);
    }

    /** 静音/振动模式下临时把闹钟音量抬到 60%，确保响铃能听到；结束后在 stopRing 恢复 */
    private void boostRingVolume() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            ringOriginalVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            if (ringOriginalVolume <= 0) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                am.setStreamVolume(AudioManager.STREAM_ALARM, Math.max(1, max * 3 / 5), 0);
            } else {
                ringOriginalVolume = -1; // 原本有声，无需恢复
            }
        } catch (Exception ignored) {
        }
    }

    private void restoreRingVolume() {
        if (ringOriginalVolume < 0) {
            return;
        }
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, ringOriginalVolume, 0);
            }
        } catch (Exception ignored) {
        }
        ringOriginalVolume = -1;
    }

    private void stopRing() {
        ringHandler.removeCallbacks(ringStopRunnable);
        boolean wasActive = ringActive;
        ringActive = false;
        if (ringPlayer != null) {
            try {
                ringPlayer.stop();
                ringPlayer.release();
            } catch (Exception ignored) {
            }
            ringPlayer = null;
        }
        restoreRingVolume();
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(RING_NOTIFY_ID);
            }
        } catch (Exception ignored) {
        }
        if (wasActive) {
            // 响铃结束：通知主页面隐藏「关闭响铃」按钮
            sendBroadcast(new Intent(AppConfig.BROADCAST_RING_STOPPED)
                    .setPackage(getPackageName()));
        }
    }

    // ---------------- 生命周期 ----------------

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 部分厂商在划掉任务时杀后台，这里尝试重启（服务本身是前台服务，一般不会被停止）
        try {
            Intent restart = new Intent(this, LocationReportService.class).setAction(AppConfig.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restart);
            } else {
                startService(restart);
            }
        } catch (Exception ignored) {
            // 个别系统限制后台启动，忽略即可
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        KeepAliveReceiver.cancel(this);
        stopRing();
        if (alarmManager != null && reportPi != null) {
            alarmManager.cancel(reportPi);
        }
        if (wsClient != null) {
            wsClient.disconnect();
            wsClient = null;
        }
        if (locationHelper != null) {
            locationHelper.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
