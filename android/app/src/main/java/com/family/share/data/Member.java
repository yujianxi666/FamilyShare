package com.family.share.data;

import com.amap.api.maps.model.LatLng;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 家庭成员模型：设备标识、昵称、在线状态、最新位置（高德坐标系 GCJ-02）与详情（电量/网络/地址/轨迹）。
 */
public class Member {

    public String deviceId = "";
    public String name = "";
    public boolean online;
    public boolean offlineMode;
    /** 是否家庭创建者（群主），服务端返回，用于显示「群主」标识 */
    public boolean isOwner;
    public boolean track;
    public boolean hasLocation;
    public double lat;
    public double lng;
    public float accuracy;
    public long ts;

    // 详情
    public int battery = -1;
    public String network = "";
    public String address = "";
    /** 头像 URL（相对路径，如 icons/xxx.jpg?v=...；为空表示未上传） */
    public String avatar = "";
    /** 地图标点用之头像位图缓存（本机，非 JSON 字段） */
    public android.graphics.Bitmap avatarBitmap;
    /** avatarBitmap 对应的头像 URL（判断是否需要重新加载） */
    public String avatarBitmapFor;
    public List<LatLng> trajectory = new ArrayList<>();

    public static Member fromJson(JSONObject o) {
        Member m = new Member();
        m.deviceId = o.optString("deviceId", "");
        m.name = o.optString("name", "");
        m.online = o.optBoolean("online", false);
        m.offlineMode = o.optBoolean("offlineMode", false);
        m.isOwner = o.optBoolean("isOwner", false);
        m.track = o.optBoolean("track", false);
        JSONObject loc = o.optJSONObject("location");
        if (loc != null) {
            m.hasLocation = true;
            m.lat = loc.optDouble("lat", 0);
            m.lng = loc.optDouble("lng", 0);
            m.accuracy = (float) loc.optDouble("accuracy", 0);
            m.ts = loc.optLong("ts", 0);
            m.battery = loc.optInt("battery", -1);
            m.network = loc.optString("network", "");
            m.address = loc.optString("address", "");
        }
        m.avatar = o.optString("avatar", "");
        JSONArray traj = o.optJSONArray("trajectory");
        if (traj != null) {
            for (int i = 0; i < traj.length(); i++) {
                JSONObject p = traj.optJSONObject(i);
                if (p != null) {
                    m.trajectory.add(new LatLng(p.optDouble("lat", 0), p.optDouble("lng", 0)));
                }
            }
        }
        return m;
    }

    public static List<Member> listFromJson(String body) throws JSONException {
        List<Member> list = new ArrayList<>();
        JSONArray arr = new JSONArray(body);
        for (int i = 0; i < arr.length(); i++) {
            list.add(fromJson(arr.getJSONObject(i)));
        }
        return list;
    }
}
