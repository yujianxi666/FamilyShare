package com.family.share.network;

import android.net.Uri;
import android.os.Handler;

import com.family.share.config.AppConfig;
import com.family.share.data.Member;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * WebSocket 客户端：接收服务器的实时指令（立即上报）与家人位置推送，断线自动重连。
 */
public class WsClient {

    public interface Listener {
        /** 服务器要求本机立即上报一次位置 */
        void onReportNow();

        /** 某成员的新位置（服务器广播） */
        void onMemberLocation(Member m);

        /** 成员上线/离线状态变化 */
        void onMemberStatus(String deviceId, boolean online);

        /** 轨迹开关变化（intervalMs>0 时携带新的更新间隔） */
        void onTrackChanged(String deviceId, boolean track, long intervalMs);

        /** 家人请求本机响铃（查找手机） */
        void onRingRequested(String fromName);

        /** 某成员被移出家庭（其余成员收到，应移除该成员显示） */
        void onMemberRemoved(String deviceId);

        /** 新成员加入家庭（应刷新成员列表） */
        void onMemberJoined(String deviceId);

        /** 本机被移出家庭（服务器关闭了连接，应清理本地家庭状态） */
        void onRemoved();

        /** 群主已转让（deviceId=新群主，本机为 deviceId 时更新本地群主标记） */
        void onOwnerChanged(String deviceId);

        /** 收到加入家庭邀请（code=家庭码，fromName=邀请人） */
        void onInvited(String code, String fromName);

        /** 有人申请加入家庭（群主审批；requestId/deviceId/name） */
        void onJoinRequest(String requestId, String deviceId, String name);

        /** 家庭被群主解散（familyId=被解散的家庭；本机应把它从家庭列表中移除） */
        void onFamilyDisbanded(String familyId);

        /** 连接状态变化 */
        void onStatus(boolean connected);
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build();

    private final Handler handler;

    private WebSocket ws;
    private Listener listener;
    private String deviceId;
    private String familyId;
    private String name;
    private boolean offline;
    private boolean manualClose;
    private long backoffMs = 2000;

    public WsClient(Handler handler) {
        this.handler = handler;
    }

    public synchronized void connect(String deviceId, String familyId, String name, boolean offline, Listener listener) {
        this.deviceId = deviceId;
        this.familyId = familyId;
        this.name = name;
        this.offline = offline;
        this.listener = listener;
        this.manualClose = false;
        this.backoffMs = 2000;
        doConnect();
    }

    private synchronized void doConnect() {
        if (manualClose || familyId == null || familyId.isEmpty()) {
            return;
        }
        String url = AppConfig.WS_URL
                + "?deviceId=" + Uri.encode(deviceId)
                + "&familyId=" + Uri.encode(familyId)
                + "&name=" + Uri.encode(name)
                + "&token=" + Uri.encode(AppConfig.API_TOKEN)
                + (offline ? "&offline=1" : "");
        Request req = new Request.Builder().url(url).build();
        ws = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                backoffMs = 2000;
                if (listener != null) {
                    listener.onStatus(true);
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                notifyDisconnected();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (code == 4001 || code == 4002) {
                    // 4001：连接被拒（已不是家庭成员/被拉黑，含离线期间被移出后重启的情况）
                    // 4002：在线时被移出家庭
                    // 均不重连，通知上层清理本地家庭状态
                    manualClose = true;
                    if (listener != null) {
                        listener.onRemoved();
                    }
                    return;
                }
                notifyDisconnected();
            }

            private void notifyDisconnected() {
                if (listener != null) {
                    listener.onStatus(false);
                }
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (manualClose) {
            return;
        }
        final long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, 60000);
        handler.postDelayed(() -> {
            synchronized (WsClient.this) {
                if (!manualClose) {
                    doConnect();
                }
            }
        }, delay);
    }

    private void handleMessage(String text) {
        try {
            JSONObject o = new JSONObject(text);
            String type = o.optString("type");
            if (listener == null) {
                return;
            }
            if ("report-now".equals(type)) {
                listener.onReportNow();
            } else if ("location-update".equals(type)) {
                Member m = new Member();
                m.deviceId = o.optString("deviceId");
                m.name = o.optString("name");
                m.lat = o.optDouble("lat");
                m.lng = o.optDouble("lng");
                m.accuracy = (float) o.optDouble("accuracy");
                m.ts = o.optLong("ts");
                m.battery = o.optInt("battery", -1);
                m.network = o.optString("network", "");
                m.address = o.optString("address", "");
                m.online = true;
                m.hasLocation = true;
                listener.onMemberLocation(m);
            } else if ("member-status".equals(type)) {
                listener.onMemberStatus(o.optString("deviceId"), o.optBoolean("online"));
            } else if ("track-changed".equals(type)) {
                listener.onTrackChanged(o.optString("deviceId"), o.optBoolean("track"),
                        o.optLong("intervalMs", 0));
            } else if ("ring".equals(type)) {
                listener.onRingRequested(o.optString("name", "家人"));
            } else if ("member-removed".equals(type)) {
                listener.onMemberRemoved(o.optString("deviceId"));
            } else if ("member-joined".equals(type)) {
                listener.onMemberJoined(o.optString("deviceId"));
            } else if ("owner-changed".equals(type)) {
                listener.onOwnerChanged(o.optString("deviceId"));
            } else if ("invite".equals(type)) {
                listener.onInvited(o.optString("code", ""), o.optString("name", "家人"));
            } else if ("join-request".equals(type)) {
                listener.onJoinRequest(o.optString("requestId", ""), o.optString("deviceId", ""),
                        o.optString("name", ""));
            } else if ("family-disbanded".equals(type)) {
                listener.onFamilyDisbanded(o.optString("familyId", ""));
            }
        } catch (JSONException ignored) {
        }
    }

    public synchronized void disconnect() {
        manualClose = true;
        if (ws != null) {
            ws.close(1000, "bye");
            ws = null;
        }
    }
}
