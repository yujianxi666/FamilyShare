package com.family.share.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * 本地偏好存储：设备标识、家庭信息、最后位置等。
 */
public class Prefs {

    private static final String NAME = "family_share_prefs";

    private static Prefs instance;

    private final SharedPreferences sp;

    private Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static synchronized Prefs get(Context context) {
        if (instance == null) {
            instance = new Prefs(context);
        }
        return instance;
    }

    // ---------- 设备标识 ----------

    public String deviceId() {
        String id = sp.getString("device_id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            sp.edit().putString("device_id", id).apply();
        }
        return id;
    }

    public String deviceName() {
        return sp.getString("device_name", "");
    }

    public void deviceName(String name) {
        sp.edit().putString("device_name", name).apply();
    }

    // ---------- 家庭信息 ----------

    public String familyId() {
        return sp.getString("family_id", "");
    }

    public void familyId(String id) {
        sp.edit().putString("family_id", id).apply();
    }

    public String familyCode() {
        return sp.getString("family_code", "");
    }

    public void familyCode(String code) {
        sp.edit().putString("family_code", code).apply();
    }

    /** 待群主同意的入群申请 requestId（空=无待审批申请）；关掉等待弹窗/重开 App 后，上线时继续检查审批结果 */
    public String pendingJoinRequestId() {
        return sp.getString("pending_join_request_id", "");
    }

    public void pendingJoinRequestId(String id) {
        sp.edit().putString("pending_join_request_id", id == null ? "" : id).apply();
    }

    /** 是否是家庭创建者（拥有移出成员的权限） */
    public boolean isOwner() {
        return sp.getBoolean("is_owner", false);
    }

    public void isOwner(boolean owner) {
        sp.edit().putBoolean("is_owner", owner).apply();
    }

    /** 是否已开启位置共享（后台服务/开机自启的依据） */
    public boolean shareEnabled() {
        return sp.getBoolean("share_enabled", false);
    }

    public void shareEnabled(boolean enabled) {
        sp.edit().putBoolean("share_enabled", enabled).apply();
    }

    // ---------- 本机最后位置（double 以位存储） ----------

    public void saveLastLocation(double lat, double lng, long ts) {
        sp.edit()
                .putLong("last_lat", Double.doubleToLongBits(lat))
                .putLong("last_lng", Double.doubleToLongBits(lng))
                .putLong("last_ts", ts)
                .apply();
    }

    public boolean hasLastLocation() {
        return sp.getLong("last_ts", 0) > 0;
    }

    public double lastLat() {
        return Double.longBitsToDouble(sp.getLong("last_lat", 0));
    }

    public double lastLng() {
        return Double.longBitsToDouble(sp.getLong("last_lng", 0));
    }

    public long lastTs() {
        return sp.getLong("last_ts", 0);
    }

    // ---------- 其它标记 ----------

    public boolean batteryPrompted() {
        return sp.getBoolean("battery_prompted", false);
    }

    public void batteryPrompted(boolean v) {
        sp.edit().putBoolean("battery_prompted", v).apply();
    }

    /** 隐私说明是否已展示过（首次启动展示一次） */
    public boolean privacyPrompted() {
        return sp.getBoolean("privacy_prompted", false);
    }

    public void privacyPrompted(boolean v) {
        sp.edit().putBoolean("privacy_prompted", v).apply();
    }

    /** 下线模式（查看但不更新自己的位置，对他人显示灰色离线） */
    public boolean offlineMode() {
        return sp.getBoolean("offline_mode", false);
    }

    public void offlineMode(boolean v) {
        sp.edit().putBoolean("offline_mode", v).apply();
    }

    /** 轨迹功能是否开启（开启后按 trackIntervalMs 记录位置） */
    public boolean trackEnabled() {
        return sp.getBoolean("track_enabled", false);
    }

    public void trackEnabled(boolean v) {
        sp.edit().putBoolean("track_enabled", v).apply();
    }

    /** 轨迹更新间隔（毫秒），默认 5 分钟 */
    public long trackIntervalMs() {
        return sp.getLong("track_interval_ms", 5 * 60 * 1000L);
    }

    public void trackIntervalMs(long v) {
        sp.edit().putLong("track_interval_ms", v).apply();
    }

    /** 是否允许家人让本机响铃（默认开启） */
    public boolean ringEnabled() {
        return sp.getBoolean("ring_enabled", true);
    }

    public void ringEnabled(boolean v) {
        sp.edit().putBoolean("ring_enabled", v).apply();
    }

    /** 允许他人让本机响铃的时长（毫秒），默认 30 秒 */
    public long ringDurationMs() {
        return sp.getLong("ring_duration_ms", 30 * 1000L);
    }

    public void ringDurationMs(long v) {
        sp.edit().putLong("ring_duration_ms", v).apply();
    }

    // ---------- 服务器切换 ----------

    /** 自定义服务器列表，每项为 {scheme, host, port}（port 可为空串）。内部以 '\n' 分隔条目、'|' 分隔字段。 */
    public java.util.List<String[]> customServers() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        String raw = sp.getString("custom_servers", "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String[] parts = line.split("\\|", -1);
            if (parts.length >= 3) {
                out.add(new String[]{parts[0], parts[1], parts[2]});
            }
        }
        return out;
    }

    public void customServers(java.util.List<String[]> list) {
        StringBuilder sb = new StringBuilder();
        for (String[] s : list) {
            if (s == null || s.length < 3) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s[0]).append('|').append(s[1]).append('|').append(s[2]);
        }
        sp.edit().putString("custom_servers", sb.toString()).apply();
    }

    /** 当前选中的服务器下标：-1 = 官方默认；>= 0 = customServers() 的下标 */
    public int activeServerIndex() {
        return sp.getInt("active_server_index", -1);
    }

    public void activeServerIndex(int v) {
        sp.edit().putInt("active_server_index", v).apply();
    }

    // ---------- Bug 处理结果告知 ----------

    /** 已被标记完成、且已告知过用户的 Bug id 集合 */
    public java.util.Set<String> bugNotifiedIds() {
        return new java.util.HashSet<>(sp.getStringSet("bug_notified_ids", new java.util.HashSet<String>()));
    }

    /** 记录一条已告知过的 Bug id（防止重复提醒） */
    public void addBugNotified(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        java.util.Set<String> s = new java.util.HashSet<>(sp.getStringSet("bug_notified_ids", new java.util.HashSet<String>()));
        s.add(id);
        sp.edit().putStringSet("bug_notified_ids", s).apply();
    }
}
