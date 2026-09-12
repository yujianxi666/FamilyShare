package com.family.share.network;

import android.net.Uri;

import com.family.share.config.AppConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 后端 REST API 封装（回调在 OkHttp 工作线程执行，UI 调用方需自行切回主线程）。
 */
public final class Api {

    public interface Callback {
        void onSuccess(String body);

        void onError(String msg);
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    /** APK 下载专用客户端：更长的读取超时 */
    private static final OkHttpClient downloadClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private Api() {
    }

    // ---------------- 家庭 ----------------

    /** 创建家庭，返回 {familyId, code} */
    public static void createFamily(String deviceId, String name, Callback cb) {
        post("/api/family/create", json("deviceId", deviceId, "name", name), cb);
    }

    /** 通过家庭码加入家庭（加入需群主同意），返回 {status:"pending",requestId} 或 {status:"ok",familyId,code} */
    public static void joinFamily(String code, String deviceId, String name, Callback cb) {
        post("/api/family/join", json("code", code, "deviceId", deviceId, "name", name), cb);
    }

    /** 查询加入申请状态（pending/approved/rejected），加入家庭需群主同意后轮询此接口 */
    public static void joinStatus(String requestId, String deviceId, Callback cb) {
        get("/api/family/join/status?requestId=" + Uri.encode(requestId)
                + "&deviceId=" + Uri.encode(deviceId), cb);
    }

    /** 群主处理加入申请（approve=true 通过，false 拒绝） */
    public static void handleJoinRequest(String familyId, String ownerDeviceId, String requestId,
                                         boolean approve, Callback cb) {
        post("/api/family/join/handle",
                json("familyId", familyId, "ownerDeviceId", ownerDeviceId,
                        "requestId", requestId, "approve", approve), cb);
    }

    /** 群主拉取待审批的加入申请列表 */
    public static void listJoinRequests(String familyId, String deviceId, Callback cb) {
        get("/api/family/join/list?familyId=" + Uri.encode(familyId)
                + "&deviceId=" + Uri.encode(deviceId), cb);
    }

    /** 上报反馈/Bug（写入服务器 bugs.json） */
    public static void reportBug(String deviceId, String name, String content, Callback cb) {
        post("/api/bug/report", json("deviceId", deviceId, "name", name, "content", content), cb);
    }

    /** 获取已提交的 Bug 列表（用于查询自己提交的 Bug 是否被标记完成，主动告知用户） */
    public static void listBugs(Callback cb) {
        get("/api/bug/list", cb);
    }

    /** 获取家庭成员列表（含最新位置），返回成员数组 */
    public static void listMembers(String familyId, Callback cb) {
        get("/api/family/members?familyId=" + Uri.encode(familyId), cb);
    }

    /** 家庭创建者移出成员；ban=true 表示移出并拉黑（无法再加入） */
    public static void removeMember(String familyId, String ownerDeviceId, String targetDeviceId,
                                    boolean ban, Callback cb) {
        post("/api/family/member/remove",
                json("familyId", familyId, "ownerDeviceId", ownerDeviceId, "targetDeviceId", targetDeviceId,
                        "ban", ban), cb);
    }

    /** 我加入的全部家庭（支持同时属于多个家庭；主页面左右滑动切换用） */
    public static void myFamilies(String deviceId, Callback cb) {
        get("/api/family/my?deviceId=" + Uri.encode(deviceId), cb);
    }

    /** 群主一键解散家庭 */
    public static void disbandFamily(String familyId, String ownerDeviceId, Callback cb) {
        post("/api/family/disband",
                json("familyId", familyId, "ownerDeviceId", ownerDeviceId), cb);
    }

    // ---------------- 成员状态 / 轨迹 ----------------

    /** 开关轨迹功能（所有成员可用；intervalMs>0 时同时设置轨迹更新间隔，如 1/3/5 分钟） */
    public static void setMemberTrack(String familyId, String ownerDeviceId, String targetDeviceId,
                                      boolean track, long intervalMs, Callback cb) {
        post("/api/member/track",
                json("familyId", familyId, "ownerDeviceId", ownerDeviceId, "targetDeviceId", targetDeviceId,
                        "track", track, "intervalMs", intervalMs), cb);
    }

    /** 下线模式开关（查看但不更新自己位置，对他人显示离线） */
    public static void setMemberOffline(String familyId, String deviceId, boolean offline, Callback cb) {
        post("/api/member/offline", json("familyId", familyId, "deviceId", deviceId, "offline", offline), cb);
    }

    // ---------------- 位置 ----------------

    /** 上报本机位置（含电量/网络/地址） */
    public static void reportLocation(String deviceId, String familyId,
                                      double lat, double lng, float accuracy, long ts,
                                      int battery, String network, String address, Callback cb) {
        post("/api/location/report",
                json("deviceId", deviceId, "familyId", familyId, "lat", lat, "lng", lng,
                        "accuracy", accuracy, "ts", ts,
                        "battery", battery, "network", network, "address", address), cb);
    }

    /** 请求某成员实时上报一次位置，返回 {status:"ok"} 或 {status:"offline"} */
    public static void requestLocation(String familyId, String requesterId, String targetDeviceId, Callback cb) {
        post("/api/location/request",
                json("familyId", familyId, "requesterId", requesterId, "targetDeviceId", targetDeviceId), cb);
    }

    /** 请求某成员响铃（查找手机），返回 {status:"ok"} 或 {status:"offline"} */
    public static void requestRing(String familyId, String requesterId, String targetDeviceId,
                                   String fromName, Callback cb) {
        post("/api/ring/request",
                json("familyId", familyId, "requesterId", requesterId,
                        "targetDeviceId", targetDeviceId, "name", fromName), cb);
    }

    /** 拉黑名单（创建者查看） */
    public static void getBanned(String familyId, String deviceId, Callback cb) {
        get("/api/family/banned?familyId=" + Uri.encode(familyId)
                + "&deviceId=" + Uri.encode(deviceId), cb);
    }

    /** 解除拉黑（创建者） */
    public static void unbanMember(String familyId, String ownerDeviceId, String targetDeviceId, Callback cb) {
        post("/api/family/member/unban",
                json("familyId", familyId, "ownerDeviceId", ownerDeviceId, "targetDeviceId", targetDeviceId), cb);
    }

    /** 群主转让：切换家庭前把群主转给家庭内其他成员 */
    public static void transferOwner(String familyId, String ownerDeviceId, String newOwnerDeviceId, Callback cb) {
        post("/api/family/owner/transfer",
                json("familyId", familyId, "ownerDeviceId", ownerDeviceId, "newOwnerDeviceId", newOwnerDeviceId), cb);
    }

    /** 邀请原家庭成员加入新家庭（通过服务器 WS 推送给目标） */
    public static void invite(String familyId, String fromDeviceId, String targetDeviceId,
                              String code, String fromName, Callback cb) {
        post("/api/family/invite",
                json("familyId", familyId, "fromDeviceId", fromDeviceId, "targetDeviceId", targetDeviceId,
                        "code", code, "fromName", fromName), cb);
    }

    // ---------------- 头像 ----------------

    /** 上传头像（JPEG 字节流，multipart），成功后服务器保存为 icons/<deviceId>.jpg */
    public static void uploadAvatar(String deviceId, String familyId, byte[] jpeg, Callback cb) {
        okhttp3.MultipartBody body = new okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("deviceId", deviceId)
                .addFormDataPart("familyId", familyId)
                .addFormDataPart("file", "avatar.jpg",
                        okhttp3.RequestBody.create(jpeg, okhttp3.MediaType.parse("image/jpeg")))
                .build();
        Request req = new Request.Builder()
                .url(AppConfig.SERVER_URL + "/api/avatar/upload")
                .header("X-Api-Token", AppConfig.API_TOKEN)
                .post(body)
                .build();
        enqueue(req, cb);
    }

    // ---------------- 健康检查 ----------------

    /** 服务器健康检查（一键刷新时刷新连接状态） */
    public static void health(Callback cb) {
        get("/api/health", cb);
    }

    // ---------------- 更新 ----------------

    /** 查询最新版本，返回 {versionCode, versionName, note, url, hasApk} */
    public static void getUpdateInfo(Callback cb) {
        get("/api/update/latest", cb);
    }

    /** 下载进度回调（OkHttp 工作线程执行，bytesRead 累计值，totalBytes 未知时为 -1） */
    public interface ProgressCallback {
        void onProgress(long bytesRead, long totalBytes);
    }

    /**
     * 下载 APK 到本地文件（回调在 OkHttp 工作线程执行）。
     * 返回 Call 供调用方取消下载。
     */
    public static Call downloadFile(String url, File dest, ProgressCallback progress, Callback cb) {
        Request req = new Request.Builder().url(url).build();
        Call call = downloadClient.newCall(req);
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                cb.onError(e.getMessage() == null ? "下载失败" : e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response resp) {
                try (Response r = resp) {
                    if (!r.isSuccessful()) {
                        cb.onError("HTTP " + r.code());
                        return;
                    }
                    okhttp3.ResponseBody body = r.body();
                    long total = body == null ? -1 : body.contentLength();
                    try (InputStream is = body == null ? null : body.byteStream();
                         OutputStream os = new FileOutputStream(dest)) {
                        byte[] buf = new byte[8192];
                        int n;
                        long read = 0;
                        while ((n = is.read(buf)) != -1) {
                            os.write(buf, 0, n);
                            read += n;
                            if (progress != null) {
                                progress.onProgress(read, total);
                            }
                        }
                    }
                    cb.onSuccess(dest.getAbsolutePath());
                } catch (IOException e) {
                    cb.onError(e.getMessage() == null ? "下载失败" : e.getMessage());
                }
            }
        });
        return call;
    }

    // ---------------- 基础 ----------------

    private static void post(String path, String json, Callback cb) {
        Request req = new Request.Builder()
                .url(AppConfig.SERVER_URL + path)
                .header("X-Api-Token", AppConfig.API_TOKEN)
                .post(RequestBody.create(json, JSON))
                .build();
        enqueue(req, cb);
    }

    private static void get(String path, Callback cb) {
        Request req = new Request.Builder()
                .url(AppConfig.SERVER_URL + path)
                .header("X-Api-Token", AppConfig.API_TOKEN)
                .get()
                .build();
        enqueue(req, cb);
    }

    /** 瞬时网络/DNS 错误的最大重试次数 */
    private static final int MAX_RETRY = 2;
    /** 每次重试的递增延迟基础（毫秒） */
    private static final long RETRY_DELAY_MS = 1000;

    private static void enqueue(Request req, Callback cb) {
        enqueueWithRetry(req, cb, 0);
    }

    private static void enqueueWithRetry(final Request req, final Callback cb, final int attempt) {
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (isTransientNetError(e) && attempt < MAX_RETRY) {
                    // 瞬时网络/DNS 抖动（如偶发 Unable to resolve host）：退避后重试
                    try {
                        Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    enqueueWithRetry(req, cb, attempt + 1);
                    return;
                }
                cb.onError(netMessage(e));
            }

            @Override
            public void onResponse(Call call, Response resp) {
                String body = "";
                try {
                    if (resp.body() != null) {
                        body = resp.body().string();
                    }
                } catch (IOException ignored) {
                }
                if (resp.isSuccessful()) {
                    cb.onSuccess(body);
                } else {
                    cb.onError("HTTP " + resp.code() + " " + body);
                }
            }
        });
    }

    /** 是否属于可重试的瞬时网络/DNS 错误 */
    private static boolean isTransientNetError(IOException e) {
        return e instanceof java.net.UnknownHostException
                || e instanceof java.net.ConnectException
                || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.SocketException;
    }

    /** 把网络/DNS 错误转成更易懂的提示 */
    private static String netMessage(IOException e) {
        if (e instanceof java.net.UnknownHostException) {
            return "域名解析失败，请检查网络或 DNS";
        }
        if (e instanceof java.net.ConnectException) {
            return "无法连接服务器";
        }
        return e.getMessage() == null ? "网络错误" : e.getMessage();
    }

    private static String json(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < kv.length; i += 2) {
                o.put((String) kv[i], kv[i + 1]);
            }
        } catch (JSONException ignored) {
        }
        return o.toString();
    }
}
