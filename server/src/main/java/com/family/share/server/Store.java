package com.family.share.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 数据存储：内存 Map 作为工作集 + Redis（按实体分 key）持久化。
 * 启动时从 Redis 载入，此后每 2 秒把内存态“全量同步”到 Redis（含删除对账），
 * 服务重启后数据不丢失。各表索引用 Redis Set 维护，便于枚举整表。
 */
@Component
public class Store {

    /** 轨迹最多保留的点数 */
    public static final int MAX_TRACK_POINTS = 300;

    // ---------- Redis key ----------
    private static final String FAMILIES_KEY = "familyshare:families";       // Set<familyId>
    private static final String LOCATIONS_KEY = "familyshare:locations";     // Set<deviceId>
    private static final String TRAJECTORIES_KEY = "familyshare:trajectories"; // Set<deviceId>
    private static final String JOINREQS_KEY = "familyshare:joinrequests";   // Set<requestId>
    private static final String FAMILY_PREFIX = "familyshare:family:";
    private static final String LOC_PREFIX = "familyshare:location:";
    private static final String TRAJ_PREFIX = "familyshare:trajectory:";
    private static final String JOIN_PREFIX = "familyshare:joinreq:";

    // ---------- 模型 ----------

    public static class Member {
        public String deviceId;
        public String name;
        /** 轨迹功能是否开启 */
        public boolean track;
        /** 轨迹更新间隔（毫秒），0=使用默认 5 分钟 */
        public long trackIntervalMs;
        /** 下线模式（查看但不更新自己位置，对他人显示离线） */
        public boolean offline;

        public Member() {
        }

        public Member(String deviceId, String name) {
            this.deviceId = deviceId;
            this.name = name;
        }
    }

    /** 黑名单记录 */
    public static class BanRecord {
        public String deviceId;
        public String name;
        public long bannedAt;

        public BanRecord() {
        }

        public BanRecord(String deviceId, String name) {
            this.deviceId = deviceId;
            this.name = name;
            this.bannedAt = System.currentTimeMillis();
        }
    }

    /** 加入家庭的待审批申请（加入家庭需群主同意） */
    public static class JoinRequest {
        public String id;
        public String familyId;
        public String code;
        public String deviceId;
        public String name;
        public long createdAt;
        /** pending | approved | rejected */
        public String status = "pending";

        public JoinRequest() {
        }

        public JoinRequest(String id, String familyId, String code, String deviceId, String name) {
            this.id = id;
            this.familyId = familyId;
            this.code = code;
            this.deviceId = deviceId;
            this.name = name;
            this.createdAt = System.currentTimeMillis();
        }
    }

    public static class Family {
        public String id;
        public String code;
        public String owner;
        public long createdAt;
        public final Map<String, Member> members = new LinkedHashMap<>();
        public final Map<String, BanRecord> banned = new LinkedHashMap<>();
    }

    public static class Location {
        public double lat;
        public double lng;
        public float accuracy;
        public long ts;
        /** 上报设备电量（0-100，未知 -1） */
        public int battery = -1;
        /** 上报设备网络：WiFi / 移动网络 / 无网络 / 未知 */
        public String network = "";
        /** 粗略地址：XX省XX市XX区XX街道 */
        public String address = "";
    }

    /** 持久化 DTO（Family 的 members/banned 为 final，序列化用该 DTO 承载） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FamilyRecord {
        public String id;
        public String code;
        public String owner;
        public long createdAt;
        public List<Member> members = new ArrayList<>();
        public List<BanRecord> banned = new ArrayList<>();
    }

    // ---------- 状态 ----------

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Family> families = new HashMap<>();
    private final Map<String, Location> locations = new HashMap<>();
    private final Map<String, List<Location>> trajectories = new HashMap<>();
    /** 加入家庭的待审批申请（key = requestId） */
    private final Map<String, JoinRequest> joinRequests = new LinkedHashMap<>();
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "store-saver");
        t.setDaemon(true);
        return t;
    });

    public Store(StringRedisTemplate redis) {
        this.redis = redis;
        load();
        saver.scheduleWithFixedDelay(this::saveNow, 2, 2, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveNow));
    }

    // ---------- 载入（从 Redis） ----------

    private synchronized void load() {
        try {
            Set<String> famIds = redis.opsForSet().members(FAMILIES_KEY);
            if (famIds != null) {
                for (String id : famIds) {
                    String json = redis.opsForValue().get(FAMILY_PREFIX + id);
                    if (json == null) {
                        continue;
                    }
                    Family f = toFamily(mapper.readValue(json, FamilyRecord.class));
                    families.put(f.id, f);
                }
            }
            Set<String> locDevs = redis.opsForSet().members(LOCATIONS_KEY);
            if (locDevs != null) {
                for (String d : locDevs) {
                    String json = redis.opsForValue().get(LOC_PREFIX + d);
                    if (json == null) {
                        continue;
                    }
                    locations.put(d, mapper.readValue(json, Location.class));
                }
            }
            Set<String> trajDevs = redis.opsForSet().members(TRAJECTORIES_KEY);
            if (trajDevs != null) {
                for (String d : trajDevs) {
                    String json = redis.opsForValue().get(TRAJ_PREFIX + d);
                    if (json == null) {
                        continue;
                    }
                    List<Location> list = mapper.readValue(json, new TypeReference<List<Location>>() {
                    });
                    trajectories.put(d, new ArrayList<>(list));
                }
            }
            Set<String> jrIds = redis.opsForSet().members(JOINREQS_KEY);
            if (jrIds != null) {
                for (String id : jrIds) {
                    String json = redis.opsForValue().get(JOIN_PREFIX + id);
                    if (json == null) {
                        continue;
                    }
                    joinRequests.put(id, mapper.readValue(json, JoinRequest.class));
                }
            }
            System.out.println("[store] loaded from redis: " + families.size() + " families, "
                    + locations.size() + " locations, " + trajectories.size() + " trajectories, "
                    + joinRequests.size() + " joinRequests");
        } catch (Exception e) {
            System.err.println("[store] load failed: " + e.getMessage());
        }
    }

    // ---------- 全量同步到 Redis（含删除对账，每 2 秒） ----------

    private synchronized void saveNow() {
        try {
            // 家庭
            reconcileSet(FAMILIES_KEY, FAMILY_PREFIX, families.keySet());
            for (Family f : families.values()) {
                redis.opsForValue().set(FAMILY_PREFIX + f.id, mapper.writeValueAsString(toRecord(f)));
                redis.opsForSet().add(FAMILIES_KEY, f.id);
            }
            // 位置
            reconcileSet(LOCATIONS_KEY, LOC_PREFIX, locations.keySet());
            for (Map.Entry<String, Location> e : locations.entrySet()) {
                redis.opsForValue().set(LOC_PREFIX + e.getKey(), mapper.writeValueAsString(e.getValue()));
                redis.opsForSet().add(LOCATIONS_KEY, e.getKey());
            }
            // 轨迹
            reconcileSet(TRAJECTORIES_KEY, TRAJ_PREFIX, trajectories.keySet());
            for (Map.Entry<String, List<Location>> e : trajectories.entrySet()) {
                redis.opsForValue().set(TRAJ_PREFIX + e.getKey(), mapper.writeValueAsString(e.getValue()));
                redis.opsForSet().add(TRAJECTORIES_KEY, e.getKey());
            }
            // 入群申请（保留全部状态，便于重启后仍能查到 pending/approved/rejected）
            reconcileSet(JOINREQS_KEY, JOIN_PREFIX, joinRequests.keySet());
            for (Map.Entry<String, JoinRequest> e : joinRequests.entrySet()) {
                redis.opsForValue().set(JOIN_PREFIX + e.getKey(), mapper.writeValueAsString(e.getValue()));
                redis.opsForSet().add(JOINREQS_KEY, e.getKey());
            }
        } catch (Exception e) {
            System.err.println("[store] save failed: " + e.getMessage());
        }
    }

    /** 把 Redis 索引 Set 里已不在内存表的 key 删除（对账删除） */
    private void reconcileSet(String indexKey, String prefix, Set<String> currentIds) {
        Set<String> stale = redis.opsForSet().members(indexKey);
        if (stale == null) {
            return;
        }
        for (String id : stale) {
            if (!currentIds.contains(id)) {
                redis.delete(prefix + id);
                redis.opsForSet().remove(indexKey, id);
            }
        }
    }

    private Family toFamily(FamilyRecord r) {
        Family f = new Family();
        f.id = r.id;
        f.code = r.code;
        f.owner = r.owner;
        f.createdAt = r.createdAt;
        if (r.members != null) {
            for (Member m : r.members) {
                if (m.deviceId != null) {
                    f.members.put(m.deviceId, m);
                }
            }
        }
        if (r.banned != null) {
            for (BanRecord b : r.banned) {
                if (b.deviceId != null) {
                    f.banned.put(b.deviceId, b);
                }
            }
        }
        return f;
    }

    private FamilyRecord toRecord(Family f) {
        FamilyRecord r = new FamilyRecord();
        r.id = f.id;
        r.code = f.code;
        r.owner = f.owner;
        r.createdAt = f.createdAt;
        r.members = new ArrayList<>(f.members.values());
        r.banned = new ArrayList<>(f.banned.values());
        return r;
    }

    // ---------- 家庭 ----------

    public synchronized Family createFamily(String deviceId, String name) {
        removeDeviceFromAllFamilies(deviceId);
        String code = randomCode();
        while (codeExists(code)) {
            code = randomCode();
        }
        // id 防重复：已存在则重新生成
        String id = randomFamilyId();
        while (families.containsKey(id)) {
            id = randomFamilyId();
        }
        Family f = new Family();
        f.id = id;
        f.code = code;
        f.owner = deviceId;
        f.createdAt = System.currentTimeMillis();
        f.members.put(deviceId, new Member(deviceId, name));
        families.put(f.id, f);
        return f;
    }

    /** 通过家庭码加入；家庭不存在或已拉黑返回 null */
    public synchronized Family joinFamily(String code, String deviceId, String name) {
        Family f = findFamilyByCode(code);
        if (f == null || f.banned.containsKey(deviceId)) {
            return null;
        }
        removeDeviceFromAllFamilies(deviceId);
        f.members.put(deviceId, new Member(deviceId, name));
        return f;
    }

    public synchronized Family getFamily(String familyId) {
        return familyId == null ? null : families.get(familyId);
    }

    public synchronized Family findFamilyByCode(String code) {
        for (Family f : families.values()) {
            if (f.code.equals(code)) {
                return f;
            }
        }
        return null;
    }

    public synchronized Family findFamilyByMember(String deviceId) {
        for (Family f : families.values()) {
            if (f.members.containsKey(deviceId)) {
                return f;
            }
        }
        return null;
    }

    // ---------- 加入家庭审批（群主同意） ----------

    /**
     * 创建一条加入家庭申请；家庭不存在 / 已拉黑 / 已是成员时返回 null。
     * 若同一设备对同一家庭已有 pending 申请，直接返回该申请（避免重复）。
     */
    public synchronized JoinRequest createJoinRequest(String code, String deviceId, String name) {
        Family f = findFamilyByCode(code);
        if (f == null || f.banned.containsKey(deviceId) || f.members.containsKey(deviceId)) {
            return null;
        }
        for (JoinRequest r : joinRequests.values()) {
            if ("pending".equals(r.status) && r.deviceId.equals(deviceId) && r.familyId.equals(f.id)) {
                return r;
            }
        }
        String id = "jr_" + Long.toHexString(System.nanoTime()) + "_" + Math.abs(new Random().nextInt(1000));
        JoinRequest r = new JoinRequest(id, f.id, f.code, deviceId, name == null ? deviceId : name);
        joinRequests.put(id, r);
        return r;
    }

    public synchronized JoinRequest getJoinRequest(String requestId) {
        return requestId == null ? null : joinRequests.get(requestId);
    }

    /**
     * 处理加入申请：approve=true 通过（把设备加入家庭），否则拒绝。
     * 返回处理后的申请；申请不存在或已处理过返回 null。
     */
    public synchronized JoinRequest handleJoinRequest(String requestId, boolean approve) {
        JoinRequest r = joinRequests.get(requestId);
        if (r == null || !"pending".equals(r.status)) {
            return null;
        }
        r.status = approve ? "approved" : "rejected";
        if (approve) {
            Family f = families.get(r.familyId);
            if (f == null || f.banned.containsKey(r.deviceId)) {
                r.status = "rejected";
                return r;
            }
            removeDeviceFromAllFamilies(r.deviceId);
            f.members.put(r.deviceId, new Member(r.deviceId, r.name));
        }
        return r;
    }

    /** 某家庭待审批的加入申请列表（群主查看） */
    public synchronized List<Map<String, Object>> listJoinRequests(String familyId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JoinRequest r : joinRequests.values()) {
            if (r.familyId.equals(familyId) && "pending".equals(r.status)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("requestId", r.id);
                m.put("deviceId", r.deviceId);
                m.put("name", r.name);
                m.put("createdAt", r.createdAt);
                out.add(m);
            }
        }
        return out;
    }

    public synchronized boolean isBanned(String familyId, String deviceId) {
        Family f = families.get(familyId);
        return f != null && f.banned.containsKey(deviceId);
    }

    /** 拉黑名单列表（创建者查看用） */
    public synchronized List<Map<String, Object>> listBanned(String familyId) {
        Family f = families.get(familyId);
        List<Map<String, Object>> out = new ArrayList<>();
        if (f == null) {
            return out;
        }
        for (BanRecord b : f.banned.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceId", b.deviceId);
            item.put("name", b.name);
            item.put("bannedAt", b.bannedAt);
            out.add(item);
        }
        return out;
    }

    /** 解除拉黑 */
    public synchronized boolean unban(String familyId, String deviceId) {
        Family f = families.get(familyId);
        return f != null && f.banned.remove(deviceId) != null;
    }

    /** 移出成员（保留黑名单状态），返回是否成功 */
    public synchronized boolean removeMember(String familyId, String targetDeviceId) {
        Family f = families.get(familyId);
        if (f == null) {
            return false;
        }
        if (f.members.remove(targetDeviceId) != null) {
            locations.remove(targetDeviceId);
            trajectories.remove(targetDeviceId);
            return true;
        }
        return false;
    }

    /** 移出并加入黑名单：之后无法再加入该家庭 */
    public synchronized boolean banMember(String familyId, String targetDeviceId) {
        Family f = families.get(familyId);
        if (f == null) {
            return false;
        }
        Member m = f.members.get(targetDeviceId);
        String name = m == null ? targetDeviceId : m.name;
        f.banned.put(targetDeviceId, new BanRecord(targetDeviceId, name));
        f.members.remove(targetDeviceId);
        locations.remove(targetDeviceId);
        trajectories.remove(targetDeviceId);
        return true;
    }

    /** 设置成员轨迹开关（intervalMs>0 时同时更新轨迹更新间隔） */
    public synchronized void setMemberTrack(String familyId, String deviceId, boolean track, long intervalMs) {
        Family f = families.get(familyId);
        if (f == null) {
            return;
        }
        Member m = f.members.get(deviceId);
        if (m != null) {
            m.track = track;
            if (intervalMs > 0) {
                m.trackIntervalMs = intervalMs;
            }
            if (!track) {
                trajectories.remove(deviceId);
            }
        }
    }

    /** 设置成员下线模式（对他人显示离线且不上报位置） */
    public synchronized void setMemberOffline(String familyId, String deviceId, boolean offline) {
        Family f = families.get(familyId);
        if (f == null) {
            return;
        }
        Member m = f.members.get(deviceId);
        if (m != null) {
            m.offline = offline;
        }
    }

    /** 成员列表（含最新位置/轨迹/状态），在线状态由 WebSocket 层提供 */
    public synchronized List<Map<String, Object>> listMembers(String familyId, Function<String, Boolean> isOnline) {
        Family f = families.get(familyId);
        List<Map<String, Object>> out = new ArrayList<>();
        if (f == null) {
            return out;
        }
        for (Member m : f.members.values()) {
            out.add(memberMap(f, m, isOnline));
        }
        return out;
    }

    /**
     * 成员（设备）总数：仅返回一个数字，供只读统计看板使用。
     * 一个设备同时只属于一个家庭，故累加各家庭成员数即为设备总数；不返回任何设备信息。
     */
    public synchronized int totalMemberCount() {
        int n = 0;
        for (Family f : families.values()) {
            n += f.members.size();
        }
        return n;
    }

    /** 家庭总数：仅返回一个数字，供只读统计看板使用 */
    public synchronized int familyCount() {
        return families.size();
    }

    private Map<String, Object> memberMap(Family f, Member m, Function<String, Boolean> isOnline) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("deviceId", m.deviceId);
        item.put("name", m.name);
        item.put("online", !m.offline && isOnline.apply(m.deviceId));
        item.put("offlineMode", m.offline);
        // 是否家庭创建者（群主），客户端据此显示「群主」标识
        item.put("isOwner", m.deviceId.equals(f.owner));
        item.put("track", m.track);
        item.put("trackInterval", m.trackIntervalMs);
        item.put("location", locations.get(m.deviceId));
        // 头像：server/icons/<deviceId>.jpg（?v=最后修改时间 用于客户端缓存失效）
        File avatarFile = new File("icons", m.deviceId + ".jpg");
        item.put("avatar", avatarFile.exists()
                ? "icons/" + m.deviceId + ".jpg?v=" + avatarFile.lastModified() : "");
        // 轨迹功能开启时返回轨迹点（限最近 N 点）
        if (m.track) {
            List<Location> t = trajectories.get(m.deviceId);
            if (t != null && !t.isEmpty()) {
                int from = Math.max(0, t.size() - MAX_TRACK_POINTS);
                item.put("trajectory", new ArrayList<>(t.subList(from, t.size())));
            } else {
                item.put("trajectory", new ArrayList<Location>());
            }
        } else {
            item.put("trajectory", null);
        }
        return item;
    }

    // ---------- 位置 ----------

    public synchronized void setLocation(String deviceId, Location loc) {
        locations.put(deviceId, loc);
        // 轨迹：设备所属家庭开启了轨迹则记录
        for (Family f : families.values()) {
            Member m = f.members.get(deviceId);
            if (m != null && m.track) {
                List<Location> t = trajectories.computeIfAbsent(deviceId, k -> new ArrayList<>());
                t.add(loc);
                if (t.size() > MAX_TRACK_POINTS * 2) {
                    trajectories.put(deviceId,
                            new ArrayList<>(t.subList(t.size() - MAX_TRACK_POINTS, t.size())));
                }
                break;
            }
        }
    }

    // ---------- 内部 ----------

    /** 把设备从所有家庭移除（创建/加入/被审批加入新家庭时调用，保证设备不被其它任何家庭列出） */
    public synchronized void removeDeviceFromAllFamilies(String deviceId) {
        for (Family f : families.values()) {
            if (f.members.remove(deviceId) != null) {
                locations.remove(deviceId);
                trajectories.remove(deviceId);
            }
        }
    }

    private String randomCode() {
        return String.valueOf(100000 + new Random().nextInt(900000));
    }

    private String randomFamilyId() {
        return "fam_" + Long.toHexString(System.nanoTime()) + new Random().nextInt(1000);
    }

    private boolean codeExists(String code) {
        for (Family f : families.values()) {
            if (f.code.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
