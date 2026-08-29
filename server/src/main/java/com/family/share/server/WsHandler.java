package com.family.share.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 实时推送通道（/ws）。
 * 连接：ws://host:3000/ws?deviceId=xx&familyId=xx&name=xx&token=<口令>&offline=1(可选)
 * 服务器 -> 客户端：hello / report-now / location-update / member-status / track-changed /
 *   ring / member-removed / member-joined / owner-changed / invite / join-request
 */
@Component
public class WsHandler extends TextWebSocketHandler {

    private final Store store;
    private final ObjectMapper mapper = new ObjectMapper();

    /** deviceId -> session */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** sessionId -> deviceId（连接关闭时反查） */
    private final Map<String, String> sessionToDevice = new ConcurrentHashMap<>();

    public WsHandler(Store store) {
        this.store = store;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        Map<String, String> q = parseQuery(uri == null ? null : uri.getRawQuery());
        String deviceId = q.get("deviceId");
        String familyId = q.get("familyId");
        String name = q.get("name");
        Store.Family family = store.getFamily(familyId);
        if (deviceId == null || family == null || !family.members.containsKey(deviceId)
                || family.banned.containsKey(deviceId)) {
            session.close(new CloseStatus(4001, "unauthorized"));
            return;
        }
        Store.Member member = family.members.get(deviceId);
        // 连接时更新昵称
        if (name != null && !name.isEmpty() && !name.equals(member.name)) {
            member.name = name;
        }
        // 下线模式（查看但不更新自己，对他人显示离线）。
        // 连接携带 offline=1 则标记离线；恢复上线重连（不带 offline=1）则清除离线标记，
        // 避免“App 明明在后台（已重连）却仍显示离线”。
        member.offline = "1".equals(q.get("offline"));
        sessions.put(deviceId, session);
        sessionToDevice.put(session.getId(), deviceId);
        send(session, map("type", "hello"));
        // 通知本机当前的轨迹开关状态，以便调整上报频率
        send(session, map("type", "track-changed", "deviceId", deviceId, "track", member.track));
        broadcastToFamily(familyId, map("type", "member-status", "deviceId", deviceId,
                "online", !member.offline, "offlineMode", member.offline));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String deviceId = sessionToDevice.remove(session.getId());
        if (deviceId != null) {
            // 竞态保护：设备刚重连成功(已建立新连接)时，旧连接的关闭事件可能晚到。
            // 若此时直接 sessions.remove(deviceId)，会把「新连接」的 session 误删，
            // 导致 isOnline() 返回 false -> 「成员在线却显示离线、状态来回跳」。
            // 只有当当前映射仍是这个旧连接时才移除，并只在这种情况下广播离线。
            WebSocketSession current = sessions.get(deviceId);
            if (current == session) {
                sessions.remove(deviceId);
                Store.Family family = store.findFamilyByMember(deviceId);
                if (family != null) {
                    Store.Member m = family.members.get(deviceId);
                    boolean offline = m != null && m.offline;
                    broadcastToFamily(family.id, map("type", "member-status", "deviceId", deviceId,
                            "online", false, "offlineMode", offline));
                }
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 客户端当前无需上行消息
    }

    public boolean isOnline(String deviceId) {
        WebSocketSession s = sessions.get(deviceId);
        return s != null && s.isOpen();
    }

    /** 通知目标设备立即上报一次位置，返回是否已送达 */
    public boolean requestReportNow(String targetDeviceId, String requesterId) {
        WebSocketSession s = sessions.get(targetDeviceId);
        if (s != null && s.isOpen()) {
            send(s, map("type", "report-now", "from", requesterId));
            return true;
        }
        return false;
    }

    /** 通知目标设备响铃（家人查找手机用），返回是否已送达 */
    public boolean requestRing(String targetDeviceId, String requesterId, String fromName) {
        WebSocketSession s = sessions.get(targetDeviceId);
        if (s != null && s.isOpen()) {
            send(s, map("type", "ring", "from", requesterId, "name", fromName));
            return true;
        }
        return false;
    }

    /** 主动关闭某设备的连接（如被移出家庭），客户端据此清理本地家庭状态 */
    public void closeSession(String deviceId, int code, String reason) {
        WebSocketSession s = sessions.get(deviceId);
        if (s != null && s.isOpen()) {
            try {
                s.close(new CloseStatus(code, reason));
            } catch (Exception ignored) {
            }
        }
    }

    /** 向指定设备推送家庭邀请（目标不一定在当前家庭），返回是否已送达 */
    public boolean sendInvite(String deviceId, String code, String fromName, String fromDeviceId) {
        WebSocketSession s = sessions.get(deviceId);
        if (s != null && s.isOpen()) {
            send(s, map("type", "invite", "code", code, "from", fromDeviceId, "name", fromName));
            return true;
        }
        return false;
    }

    public void broadcastToFamily(String familyId, Map<String, Object> msg) {
        Store.Family family = store.getFamily(familyId);
        if (family == null) {
            return;
        }
        String text;
        try {
            text = mapper.writeValueAsString(msg);
        } catch (Exception e) {
            return;
        }
        for (String deviceId : family.members.keySet()) {
            WebSocketSession s = sessions.get(deviceId);
            if (s != null && s.isOpen()) {
                send(s, text);
            }
        }
    }

    /** 向某指定设备（无论其是否仍是家庭成员）推送消息；用于“被移出者”立即退出原家庭 */
    public void sendToDevice(String deviceId, Map<String, Object> msg) {
        if (deviceId == null) {
            return;
        }
        WebSocketSession s = sessions.get(deviceId);
        if (s != null && s.isOpen()) {
            send(s, msg);
        }
    }

    private void send(WebSocketSession session, Map<String, Object> msg) {
        try {
            send(session, mapper.writeValueAsString(msg));
        } catch (Exception ignored) {
        }
    }

    private void send(WebSocketSession session, String text) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(text));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return out;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) {
                continue;
            }
            try {
                String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                out.put(k, v);
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
