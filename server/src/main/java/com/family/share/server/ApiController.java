package com.family.share.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 家庭共享 REST 接口（家庭/成员/位置/轨迹/响铃/头像/Bug 反馈/更新）。
 */
@RestController
public class ApiController {

    @Autowired
    private Store store;

    @Autowired
    private WsHandler ws;

    /** 访问口令（application.properties 的 app.api-token），用于注入 Bug 管理页的 fetch 头 */
    @Value("${app.api-token:YOUR_API_TOKEN}")
    private String apiToken;

    /** Bug 管理页的路径口令（application.properties 的 app.bug-admin-token），须访问 /bugadmin/<token> */
    @Value("${app.bug-admin-token:bugadmin-default}")
    private String bugAdminToken;

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------------- 家庭 ----------------

    /** POST /api/family/create  {deviceId, name} -> {familyId, code} */
    @PostMapping("/api/family/create")
    public Map<String, Object> createFamily(@RequestBody Map<String, Object> body) {
        String deviceId = str(body.get("deviceId"));
        String name = str(body.get("name"));
        if (deviceId == null || name == null) {
            throw new BadRequest("deviceId and name required");
        }
        Store.Family f = store.createFamily(deviceId, name);
        return map("familyId", f.id, "code", f.code);
    }

    /** POST /api/family/join  {code, deviceId, name} -> {status:"pending", requestId}（加入需群主同意） */
    @PostMapping("/api/family/join")
    public ResponseEntity<Map<String, Object>> joinFamily(@RequestBody Map<String, Object> body) {
        String code = str(body.get("code"));
        String deviceId = str(body.get("deviceId"));
        String name = str(body.get("name"));
        if (code == null || deviceId == null || name == null) {
            throw new BadRequest("code, deviceId and name required");
        }
        // 已是成员：幂等返回现有家庭
        Store.Family ef = store.findFamilyByCode(code.trim());
        if (ef != null && ef.members.containsKey(deviceId)) {
            return ResponseEntity.ok(map("status", "ok", "familyId", ef.id, "code", ef.code));
        }
        Store.JoinRequest req = store.createJoinRequest(code.trim(), deviceId, name);
        if (req == null) {
            return notFound("家庭码不存在或已被拉黑");
        }
        // 通知全家（主要是群主）有新的加入申请
        ws.broadcastToFamily(req.familyId, map("type", "join-request", "requestId", req.id,
                "deviceId", deviceId, "name", name));
        return ResponseEntity.ok(map("status", "pending", "requestId", req.id));
    }

    /** GET /api/family/join/status?requestId=&deviceId= -> {status:"pending"|"approved"|"rejected"} */
    @GetMapping("/api/family/join/status")
    public ResponseEntity<?> joinStatus(@RequestParam(value = "requestId", required = false) String requestId,
                                        @RequestParam(value = "deviceId", required = false) String deviceId) {
        Store.JoinRequest r = store.getJoinRequest(requestId);
        if (r == null) {
            return notFound("request not found");
        }
        if ("pending".equals(r.status)) {
            return ResponseEntity.ok(map("status", "pending"));
        }
        if ("rejected".equals(r.status)) {
            return ResponseEntity.ok(map("status", "rejected"));
        }
        Store.Family f = store.getFamily(r.familyId);
        return ResponseEntity.ok(map("status", "approved",
                "familyId", f == null ? "" : f.id,
                "code", f == null ? "" : f.code));
    }

    /** POST /api/family/join/handle  {familyId, ownerDeviceId, requestId, approve} -> {status:"ok"}（仅群主） */
    @PostMapping("/api/family/join/handle")
    public ResponseEntity<Map<String, Object>> handleJoin(@RequestBody Map<String, Object> body) {
        String familyId = str(body.get("familyId"));
        String owner = str(body.get("ownerDeviceId"));
        String requestId = str(body.get("requestId"));
        boolean approve = Boolean.TRUE.equals(body.get("approve"));
        Store.Family f = store.getFamily(familyId);
        if (f == null) {
            return notFound("family not found");
        }
        if (!f.owner.equals(owner)) {
            return ResponseEntity.status(403).body(map("error", "not owner"));
        }
        Store.JoinRequest r = store.handleJoinRequest(requestId, approve);
        if (r == null) {
            return notFound("request not found or already handled");
        }
        if (approve && "approved".equals(r.status)) {
            ws.broadcastToFamily(familyId, map("type", "member-joined", "deviceId", r.deviceId, "name", r.name));
        }
        return ResponseEntity.ok(map("status", "ok", "approved", approve));
    }

    /** GET /api/family/join/list?familyId=&deviceId= -> [{requestId,deviceId,name,createdAt}]（仅群主） */
    @GetMapping("/api/family/join/list")
    public ResponseEntity<?> joinList(@RequestParam(value = "familyId", required = false) String familyId,
                                      @RequestParam(value = "deviceId", required = false) String deviceId) {
        Store.Family f = store.getFamily(familyId);
        if (f == null) {
            return notFound("family not found");
        }
        if (!f.owner.equals(deviceId)) {
            return ResponseEntity.status(403).body(map("error", "not owner"));
        }
        return ResponseEntity.ok(store.listJoinRequests(familyId));
    }

    /** GET /api/family/members?familyId=xx -> [{deviceId,name,online,location}] */
    @GetMapping("/api/family/members")
    public ResponseEntity<?> members(@RequestParam(value = "familyId", required = false) String familyId) {
        if (store.getFamily(familyId) == null) {
            return notFound("family not found");
        }
        return ResponseEntity.ok(store.listMembers(familyId, ws::isOnline));
    }

    /** POST /api/family/member/remove  {familyId, ownerDeviceId, targetDeviceId, ban} -> {status:"ok"} */
    @PostMapping("/api/family/member/remove")
    public ResponseEntity<Map<String, Object>> removeMember(@RequestBody Map<String, Object> body) {
        String familyId = str(body.get("familyId"));
        String owner = str(body.get("ownerDeviceId"));
        String target = str(body.get("targetDeviceId"));
        boolean ban = Boolean.TRUE.equals(body.get("ban"));
        Store.Family f = store.getFamily(familyId);
        if (f == null) {
            return notFound("family not found");
        }
        if (!f.owner.equals(owner)) {
            return ResponseEntity.status(403).body(map("error", "not owner"));
        }
        boolean removed = ban ? store.banMember(familyId, target) : store.removeMember(familyId, target);
        if (removed) {
            // 先直接通知被移出者本人（此时其 WS 可能仍在线），客户端据此立即退出原家庭
            ws.sendToDevice(target, map("type", "member-removed", "deviceId", target));
            // 关闭被移除者的 WS 连接（4002：被移出），其客户端立即清理家庭状态并提示
            ws.closeSession(target, 4002, "removed");
            // 通知其余家人移除该成员
            ws.broadcastToFamily(familyId, map("type", "member-removed", "deviceId", target));
        }
        return ResponseEntity.ok(map("status", "ok"));
    }

    // ---------------- 成员状态 / 轨迹 ----------------

    /** POST /api/member/track  {familyId, ownerDeviceId, targetDeviceId, track, intervalMs?} -> {status:"ok"}
     *  所有家庭成员都可为他人/自己开关轨迹；intervalMs>0 时设置轨迹更新间隔（1/3/5/自定义分钟）。 */
    @PostMapping("/api/member/track")
    public ResponseEntity<Map<String, Object>> setTrack(@RequestBody Map<String, Object> body) {
        String familyId = str(body.get("familyId"));
        String operator = str(body.get("ownerDeviceId"));
        String target = str(body.get("targetDeviceId"));
        boolean track = Boolean.TRUE.equals(body.get("track"));
        long intervalMs = 0;
        Object iv = body.get("intervalMs");
        if (iv instanceof Number) {
            intervalMs = ((Number) iv).longValue();
        }
        Store.Family f = store.getFamily(familyId);
        if (f == null) {
            return notFound("family not found");
        }
        if (!f.members.containsKey(operator) || !f.members.containsKey(target)) {
            return ResponseEntity.status(403).body(map("error", "not a family member"));
        }
        store.setMemberTrack(familyId, target, track, intervalMs);
        // 通知目标设备调整上报频率 + 全家人刷新轨迹
        ws.broadcastToFamily(familyId, map("type", "track-changed",
                "deviceId", target, "track", track, "intervalMs", intervalMs));
        return ResponseEntity.ok(map("status", "ok"));
    }

    /** POST /api/member/offline  {familyId, deviceId, offline} -> {status:"ok"} */
    @PostMapping("/api/member/offline")
    public ResponseEntity<Map<String, Object>> setOffline(@RequestBody Map<String, Object> body) {
        String familyId = str(body.get("familyId"));
        String deviceId = str(body.get("deviceId"));
        boolean offline = Boolean.TRUE.equals(body.get("offline"));
        Store.Family f = store.getFamily(familyId);
        if (f == null || !f.members.containsKey(deviceId)) {
            return notFound("member not found");
        }
        store.setMemberOffline(familyId, deviceId, offline);
        // 立即广播在线状态，让全家人看到灰色离线
        ws.broadcastToFamily(familyId, map("type", "member-status",
                "deviceId", deviceId, "online", !offline, "offlineMode", offline));
        return ResponseEntity.ok(map("status", "ok"));
    }

    // ---------------- 黑名单管理 ----------------

    /** GET /api/family/banned?familyId=&deviceId= -> [{deviceId,name,bannedAt}]（仅创建者） */
    @GetMapping("/api/family/banned")
    public ResponseEntity<?> bannedList(@RequestParam(value = "familyId", required = false) String familyId,
                                        @RequestParam(value = "deviceId", required = false) String deviceId) {
        Store.Family f = store.getFamily(familyId);
        if (f == null) {
            return notFound("family not found");
        }
        if (!f.owner.equals(deviceId)) {
            return ResponseEntity.status(403).body(map("error", "not owner"));
        }
        return ResponseEntity.ok(store.listBanned(familyId));
    }

    /** POST /api/family/member/unban  {familyId, ownerDeviceId, targetDeviceId} -> {status:"ok"} */
    @PostMapping("/api/family/member/unban")
    public ResponseEntity<Map<String, Object>> unban(@RequestBody Map<String, Object> body) {
        String familyId = str(body.get("familyId"));
        String owner = str(body.get("ownerDeviceId"));
        String target = str(body.get("targetDeviceId"));
        Store.Family f = store.getFamily(familyId);
        if (f == null) {
            return notFound("family not found");
        }
        if (!f.owner.equals(owner)) {
            return ResponseEntity.status(403).body(map("error", "not owner"));
        }
        store.unban(familyId, target);
        return ResponseEntity.ok(map("status", "ok"));
    }

    // ---------------- 响铃 ----------------

    /** POST /api/ring/request  {familyId, requesterId, targetDeviceId, name} -> {status:"ok"|"offline"} */
    @PostMapping("/api/ring/request")
    public ResponseEntity<Map<String, Object>> ringRequest(@RequestBody Map<String, Object> body) {
        String familyId = str(body.get("familyId"));
        String requesterId = str(body.get("requesterId"));
        String targetDeviceId = str(body.get("targetDeviceId"));
        String fromName = str(body.get("name"));
        Store.Family f = store.getFamily(familyId);
        if (f == null) {
            return notFound("family not found");
        }
        if (!f.members.containsKey(requesterId) || !f.members.containsKey(targetDeviceId)) {
            return ResponseEntity.status(403).body(map("error", "not a family member"));
        }
        // 下线模式：不更新自己位置，也不接受响铃
        Store.Member target = f.members.get(targetDeviceId);
        if (target != null && target.offline) {
            return ResponseEntity.ok(map("status", "offline", "message", "target in offline mode"));
        }
        if (ws.requestRing(targetDeviceId, requesterId, fromName == null ? "" : fromName)) {
            System.out.println("[ring] from=" + requesterId + " -> target=" + targetDeviceId + " 已推送响铃");
            return ResponseEntity.ok(map("status", "ok"));
        }
        return ResponseEntity.ok(map("status", "offline", "message", "target offline"));
    }

    // ---------------- 位置 ----------------

    /** POST /api/location/report  {deviceId, familyId, lat, lng, accuracy, ts} -> {status:"ok"} */
    @PostMapping("/api/location/report")
    public ResponseEntity<Map<String, Object>> report(@RequestBody Map<String, Object> body) {
        String deviceId = str(body.get("deviceId"));
        String familyId = str(body.get("familyId"));
        Object latO = body.get("lat");
        Object lngO = body.get("lng");
        if (deviceId == null || familyId == null) {
            return badRequest("deviceId and familyId required");
        }
        if (!(latO instanceof Number) || !(lngO instanceof Number)) {
            return badRequest("lat and lng must be numbers");
        }
        Store.Family f = store.getFamily(familyId);
        if (f == null || !f.members.containsKey(deviceId)) {
            return ResponseEntity.status(403).body(map("error", "not a family member"));
        }
        Store.Member member = f.members.get(deviceId);
        Store.Location loc = new Store.Location();
        loc.lat = ((Number) latO).doubleValue();
        loc.lng = ((Number) lngO).doubleValue();
        Object acc = body.get("accuracy");
        loc.accuracy = acc instanceof Number ? ((Number) acc).floatValue() : 0f;
        Object tsO = body.get("ts");
        loc.ts = tsO instanceof Number ? ((Number) tsO).longValue() : System.currentTimeMillis();
        // 设备信息：电量 / 网络 / 粗略地址
        Object batO = body.get("battery");
        loc.battery = batO instanceof Number ? ((Number) batO).intValue() : -1;
        Object netO = body.get("network");
        loc.network = netO == null ? "" : String.valueOf(netO);
        Object addrO = body.get("address");
        loc.address = addrO == null ? "" : String.valueOf(addrO);
        store.setLocation(deviceId, loc);
        // 广播给全家人（客户端自行忽略自己）
        ws.broadcastToFamily(familyId, map(
                "type", "location-update",
                "deviceId", deviceId,
                "name", member.name,
                "lat", loc.lat,
                "lng", loc.lng,
                "accuracy", loc.accuracy,
                "ts", loc.ts,
                "battery", loc.battery,
                "network", loc.network,
                "address", loc.address));
        System.out.println("[loc] report device=" + deviceId + " family=" + familyId
                + " lat=" + loc.lat + " lng=" + loc.lng
                + " battery=" + loc.battery + " net=" + loc.network);
        return ResponseEntity.ok(map("status", "ok"));
    }

    /** POST /api/location/request  {familyId, requesterId, targetDeviceId} -> {status:"ok"|"offline"} */
    @PostMapping("/api/location/request")
    public ResponseEntity<Map<String, Object>> request(@RequestBody Map<String, Object> body) {
        String familyId = str(body.get("familyId"));
        String requesterId = str(body.get("requesterId"));
        String targetDeviceId = str(body.get("targetDeviceId"));
        Store.Family f = store.getFamily(familyId);
        if (f == null) {
            return notFound("family not found");
        }
        if (!f.members.containsKey(requesterId) || !f.members.containsKey(targetDeviceId)) {
            return ResponseEntity.status(403).body(map("error", "not a family member"));
        }
        if (ws.requestReportNow(targetDeviceId, requesterId)) {
            System.out.println("[loc] request from=" + requesterId + " -> target=" + targetDeviceId + " 已推送 report-now");
            return ResponseEntity.ok(map("status", "ok"));
        }
        System.out.println("[loc] request from=" + requesterId + " -> target=" + targetDeviceId + " 目标 WS 不在线");
        return ResponseEntity.ok(map("status", "offline", "message", "target offline"));
    }

    // ---------------- 头像 ----------------

    /**
     * POST /api/avatar/upload  multipart: deviceId, familyId, file
     * 上传成员头像，保存为 server/icons/<deviceId>.jpg（覆盖旧图）。限制 1MB。
     */
    @PostMapping("/api/avatar/upload")
    public ResponseEntity<Map<String, Object>> uploadAvatar(
            @RequestParam("deviceId") String deviceId,
            @RequestParam("familyId") String familyId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        Store.Family f = store.getFamily(familyId);
        if (f == null || !f.members.containsKey(deviceId)) {
            return ResponseEntity.status(403).body(map("error", "not a family member"));
        }
        if (file == null || file.isEmpty() || file.getSize() > 1_048_576) {
            return badRequest("file required and <= 1MB");
        }
        try {
            File dir = new File("icons");
            if (!dir.exists() && !dir.mkdirs()) {
                return ResponseEntity.status(500).body(map("error", "icons dir create failed"));
            }
            // 必须用绝对路径：MultipartFile.transferTo 对相对路径会解析到 Tomcat 临时目录
            // （work\Tomcat\...\ROOT\），导致 FileNotFoundException -> 500
            File target = new File(dir.getAbsoluteFile(), deviceId + ".jpg");
            file.transferTo(target);
            return ResponseEntity.ok(map("status", "ok", "url", "icons/" + deviceId + ".jpg"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(map("error", "save failed: " + e.getMessage()));
        }
    }

    // ---------------- 群主转让 ----------------

    /** POST /api/family/owner/transfer  {familyId, ownerDeviceId, newOwnerDeviceId} -> {status:"ok"}
     *  群主切换家庭前把群主转给家庭内其他成员。 */
    @PostMapping("/api/family/owner/transfer")
    public ResponseEntity<Map<String, Object>> transferOwner(@RequestBody Map<String, Object> body) {
        String familyId = str(body.get("familyId"));
        String owner = str(body.get("ownerDeviceId"));
        String newOwner = str(body.get("newOwnerDeviceId"));
        Store.Family f = store.getFamily(familyId);
        if (f == null) {
            return notFound("family not found");
        }
        if (!f.owner.equals(owner)) {
            return ResponseEntity.status(403).body(map("error", "not owner"));
        }
        if (newOwner == null || !f.members.containsKey(newOwner)) {
            return notFound("member not found");
        }
        f.owner = newOwner;
        // 通知全家（新群主据此更新本地群主标记）
        ws.broadcastToFamily(familyId, map("type", "owner-changed", "deviceId", newOwner));
        return ResponseEntity.ok(map("status", "ok"));
    }

    // ---------------- 家庭邀请 ----------------

    /** POST /api/family/invite  {familyId, fromDeviceId, targetDeviceId, code, fromName} -> {status:"ok"|"offline"}
     *  切换家庭后邀请原家庭成员加入新家庭（通过 WS 推送，目标在线才可收到）。 */
    @PostMapping("/api/family/invite")
    public ResponseEntity<Map<String, Object>> invite(@RequestBody Map<String, Object> body) {
        String familyId = str(body.get("familyId"));
        String fromDeviceId = str(body.get("fromDeviceId"));
        String targetDeviceId = str(body.get("targetDeviceId"));
        String code = str(body.get("code"));
        String fromName = str(body.get("fromName"));
        Store.Family f = store.getFamily(familyId);
        if (f == null) {
            return notFound("family not found");
        }
        if (!f.members.containsKey(fromDeviceId)) {
            return ResponseEntity.status(403).body(map("error", "not a family member"));
        }
        if (code == null || code.isEmpty() || targetDeviceId == null) {
            return badRequest("code and targetDeviceId required");
        }
        if (ws.sendInvite(targetDeviceId, code, fromName == null ? "" : fromName, fromDeviceId)) {
            return ResponseEntity.ok(map("status", "ok"));
        }
        return ResponseEntity.ok(map("status", "offline", "message", "target offline"));
    }

    // ---------------- 其它 ----------------

    /** GET /api/health */
    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return map("status", "ok", "time", System.currentTimeMillis());
    }

    /** POST /api/bug/report  {deviceId, name, content} -> {status:"ok"}（追加写 server/bugs.json） */
    @PostMapping("/api/bug/report")
    public ResponseEntity<Map<String, Object>> reportBug(@RequestBody Map<String, Object> body) {
        String deviceId = str(body.get("deviceId"));
        String name = str(body.get("name"));
        String content = str(body.get("content"));
        if (content == null || content.trim().isEmpty()) {
            return badRequest("content required");
        }
        try {
            File f = new File("bugs.json");
            File parent = f.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            java.util.List<Map<String, Object>> list = new ArrayList<>();
            if (f.exists() && f.length() > 0) {
                try {
                    list = mapper.readValue(f, new TypeReference<java.util.List<Map<String, Object>>>() {
                    });
                } catch (Exception readEx) {
                    // 已存在但为空/损坏：重置为空列表，继续追加（避免读旧文件抛错返回 500）
                    System.err.println("[bug] existing bugs.json unreadable, resetting: " + readEx.getMessage());
                    list = new ArrayList<>();
                }
            }
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("id", "bug_" + System.currentTimeMillis());
            rec.put("deviceId", deviceId == null ? "" : deviceId);
            rec.put("name", name == null ? "" : name);
            rec.put("content", content);
            rec.put("time", System.currentTimeMillis());
            list.add(rec);
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, list);
            return ResponseEntity.ok(map("status", "ok"));
        } catch (Exception e) {
            System.err.println("[bug] save failed: " + e.getMessage());
            return ResponseEntity.status(500).body(map("error", "save failed: " + e.getMessage()));
        }
    }

    /** GET /api/bug/list -> [ {id,deviceId,name,content,time,resolved?} ]（web 管理页展示用） */
    @GetMapping("/api/bug/list")
    public ResponseEntity<?> bugList() {
        try {
            File f = new File("bugs.json");
            java.util.List<Map<String, Object>> list = new ArrayList<>();
            if (f.exists() && f.length() > 0) {
                list = mapper.readValue(f, new TypeReference<java.util.List<Map<String, Object>>>() {
                });
            }
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(map("error", e.getMessage()));
        }
    }

    /**
     * POST /api/bug/update  {id, content?, resolved?} -> {status:"ok"}
     * 更新某条 Bug：可改文本(content) 或标记已完成/关闭(resolved=true)。
     */
    @PostMapping("/api/bug/update")
    public ResponseEntity<Map<String, Object>> bugUpdate(@RequestBody Map<String, Object> body) {
        String id = str(body.get("id"));
        if (id == null || id.isEmpty()) {
            return badRequest("id required");
        }
        String content = body.get("content") == null ? null : str(body.get("content"));
        Object resolvedO = body.get("resolved");
        try {
            File f = new File("bugs.json");
            java.util.List<Map<String, Object>> list = new ArrayList<>();
            if (f.exists() && f.length() > 0) {
                try {
                    list = mapper.readValue(f, new TypeReference<java.util.List<Map<String, Object>>>() {
                    });
                } catch (Exception readEx) {
                    list = new ArrayList<>();
                }
            }
            boolean found = false;
            for (Map<String, Object> rec : list) {
                if (id.equals(rec.get("id"))) {
                    if (content != null) {
                        rec.put("content", content);
                    }
                    if (resolvedO instanceof Boolean) {
                        rec.put("resolved", resolvedO);
                    } else if (resolvedO != null) {
                        rec.put("resolved", "true".equals(String.valueOf(resolvedO)));
                    }
                    found = true;
                    break;
                }
            }
            if (!found) {
                return notFound("bug not found");
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, list);
            return ResponseEntity.ok(map("status", "ok"));
        } catch (Exception e) {
            System.err.println("[bug] update failed: " + e.getMessage());
            return ResponseEntity.status(500).body(map("error", "save failed: " + e.getMessage()));
        }
    }

    /** POST /api/bug/delete  {id} -> {status:"ok"}（删除一条反馈；404=不存在） */
    @PostMapping("/api/bug/delete")
    public ResponseEntity<Map<String, Object>> bugDelete(@RequestBody Map<String, Object> body) {
        String id = str(body.get("id"));
        if (id == null || id.isEmpty()) {
            return badRequest("id required");
        }
        try {
            File f = new File("bugs.json");
            java.util.List<Map<String, Object>> list = new ArrayList<>();
            if (f.exists() && f.length() > 0) {
                try {
                    list = mapper.readValue(f, new TypeReference<java.util.List<Map<String, Object>>>() {
                    });
                } catch (Exception readEx) {
                    list = new ArrayList<>();
                }
            }
            boolean removed = false;
            for (java.util.Iterator<Map<String, Object>> it = list.iterator(); it.hasNext(); ) {
                if (id.equals(it.next().get("id"))) {
                    it.remove();
                    removed = true;
                    break;
                }
            }
            if (!removed) {
                return notFound("bug not found");
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, list);
            return ResponseEntity.ok(map("status", "ok"));
        } catch (Exception e) {
            System.err.println("[bug] delete failed: " + e.getMessage());
            return ResponseEntity.status(500).body(map("error", "save failed: " + e.getMessage()));
        }
    }

    /**
     * GET /bugadmin/{token} -> 返回 bugadmin.html（token 正确才可访问）。
     * 口令来自配置 app.bug-admin-token（默认 bugadmin-default），请在 application.properties 设成你自己的值。
     * 页面已打包进 jar（classpath 根目录），无需与 jar 同目录的独立文件。
     * 以路径段作为口令，避免直接把 html 暴露给未知访问者。
     */
    @GetMapping(value = "/bugadmin/{token}", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> bugAdmin(@PathVariable("token") String token) {
        if (token == null || !bugAdminToken.equals(token)) {
            return ResponseEntity.status(404).body("<h1>404 Not Found</h1>");
        }
        try {
            ClassPathResource res = new ClassPathResource("bugadmin.html");
            if (!res.exists()) {
                return ResponseEntity.status(404).body("<h1>bugadmin.html 不存在</h1>");
            }
            String html = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // 注入访问口令，页面 fetch /api/bug/* 时携带，避免被 AccessTokenFilter 拦截(401)
            html = html.replace("__API_TOKEN__", apiToken);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("<h1>加载失败：" + e.getMessage() + "</h1>");
        }
    }

    /**
     * GET /api/update/latest -> {versionCode, versionName, note, url, hasApk}
     * 每次请求都重新读取 update.json（不缓存）；响应头显式禁止缓存。
     */
    @GetMapping("/api/update/latest")
    public Map<String, Object> updateLatest(HttpServletRequest req, HttpServletResponse resp) {
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        resp.setHeader("Pragma", "no-cache");
        Map<String, Object> cfg = new HashMap<>();
        File uf = new File("update.json");
        if (uf.exists()) {
            try {
                cfg = mapper.readValue(uf, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception ignored) {
            }
        }
        boolean hasApk = new File("downloads/app-release.apk").exists();
        String md5 = "";
        long size = 0;
        if (hasApk) {
            File apk = new File("downloads/app-release.apk");
            size = apk.length();
            md5 = md5(apk);
        }
        String base = req.getScheme() + "://" + req.getHeader("Host");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versionCode", cfg.getOrDefault("versionCode", 1));
        out.put("versionName", cfg.getOrDefault("versionName", "1.0.0"));
        out.put("note", cfg.getOrDefault("note", ""));
        out.put("url", hasApk ? base + "/downloads/app-release.apk" : "");
        out.put("hasApk", hasApk);
        out.put("md5", md5);   // APK 的 MD5，客户端与本机安装包比对判断是否需要更新
        out.put("size", size);
        return out;
    }

    /** 计算文件 MD5（小写十六进制） */
    private static String md5(File file) {
        try (java.io.InputStream is = new java.io.FileInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------- 工具 ----------------

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(map("error", msg));
    }

    private static ResponseEntity<Map<String, Object>> notFound(String msg) {
        return ResponseEntity.status(404).body(map("error", msg));
    }

    static class BadRequest extends RuntimeException {
        BadRequest(String msg) {
            super(msg);
        }
    }

    @ExceptionHandler(BadRequest.class)
    public ResponseEntity<Map<String, Object>> onBadRequest(BadRequest e) {
        return badRequest(e.getMessage());
    }
}
