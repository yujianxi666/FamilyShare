package com.family.share.location;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;

/**
 * 高德定位封装：
 * - 高精度模式（Hight_Accuracy：GPS + 网络 + Wi-Fi），室外约 5~15m
 * - 单次定位 requestOnce（后台服务每 30 分钟上报与家人实时查看时调用）
 * - **最佳定位收集**：连续回调一小段时间，记录精度最好的一次定位；达到理想精度(<=25m)即提前结束，
 *   避免单次定位偶尔拿到很差的结果（改善“定位偶尔精度太差”）。
 * - 每次单次定位都创建全新客户端（复用同一客户端重复 startLocation 可能静默失败，
 *   这是“定位我”正常（每次都新建）而 report-now 触发不了上报（复用客户端）的根因）
 * - 输出 GCJ-02 坐标，与高德地图坐标系一致，无需转换
 */
public class LocationHelper {

    public interface Callback {
        void onResult(double lat, double lng, float accuracy, long time, String address);

        void onError(String msg);
    }

    /** 达到该精度（米）即视为“够好”，提前结束（室外 GPS 通常 5~15m，放宽到 25m 更稳） */
    private static final float GOOD_ACCURACY = 25f;
    /** 最佳定位收集窗口（毫秒）：窗口内持续回调，取精度最好一次，超时后结束 */
    private static final long COLLECT_MS = 12_000L;
    /** 快速模式窗口（毫秒）：用户点「定位我」时用，通常会因首个有效定位提前返回 */
    private static final long QUICK_MS = 5_000L;
    /** 位置回调间隔（毫秒），收集期内用 1s 更快拿到更好 fix */
    private static final int LOCATION_INTERVAL_MS = 1000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;

    private AMapLocationClient client;
    private Callback current;
    private boolean released;
    /** 快速模式（用户点「定位我」）：拿到首个有效定位立即返回，不等待最佳定位 */
    private boolean quick;
    /** 本次收集窗口时长 */
    private long settleMs = COLLECT_MS;

    // 本次收集到的最佳定位
    private boolean haveFix;
    private double bestLat;
    private double bestLng;
    private float bestAcc;
    private String bestAddress;

    /** 收集窗口结束：取窗口内精度最好的一次；无有效定位则报超时 */
    private final Runnable settleRunnable = () -> {
        Callback cb;
        double rLat, rLng;
        float rAcc;
        String rAddr;
        synchronized (this) {
            if (current == null || released) {
                return;
            }
            cb = current;
            current = null;
            rLat = bestLat;
            rLng = bestLng;
            rAcc = bestAcc;
            rAddr = bestAddress;
        }
        if (haveFix) {
            cb.onResult(rLat, rLng, rAcc, System.currentTimeMillis(), rAddr);
        } else {
            cb.onError("定位超时");
        }
        main.post(this::destroyClient);
    };

    public LocationHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    public void requestOnce(Callback cb) {
        requestOnce(cb, false);
    }

    /**
     * @param quickMode true=快速模式（拿到首个有效定位即返回，用于点「定位我」）；false=最佳定位收集（默认，用于后台上报）
     */
    public void requestOnce(Callback cb, boolean quickMode) {
        if (released) {
            cb.onError("定位组件已释放");
            return;
        }
        quick = quickMode;
        settleMs = quickMode ? QUICK_MS : COLLECT_MS;
        // 高德定位客户端要求在主线程创建/操作（本方法可能被 OkHttp/WebSocket 线程调用）
        main.post(() -> {
            synchronized (this) {
                if (released) {
                    cb.onError("定位组件已释放");
                    return;
                }
                current = cb;
                haveFix = false;
                bestAcc = Float.MAX_VALUE;
                bestAddress = "";
                destroyClient(); // 每次单次定位都用全新客户端，避免复用问题
                try {
                    client = new AMapLocationClient(context);
                } catch (Exception e) {
                    // 构造函数声明了受检异常（多为初始化失败）
                    current = null;
                    cb.onError("定位客户端创建失败：" + e.getMessage());
                    return;
                }
                AMapLocationClientOption opt = new AMapLocationClientOption();
                opt.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
                // 注意：不要 setGpsFirst(true)——GPS 优先会跳过逆地理编码，
                // 返回的定位不含省市区/地址信息（历史上地址栏为空的根因）。
                opt.setOnceLocation(false);  // 连续回调，用于收集最佳 fix 而非仅取一次
                opt.setInterval(LOCATION_INTERVAL_MS);
                opt.setNeedAddress(true);    // 返回省市区街道，供详情页展示
                opt.setLocationCacheEnable(false);
                client.setLocationOption(opt);
                client.setLocationListener(locationListener);
                client.startLocation();
                // 收集窗口结束后结束（快速模式 5s，最佳定位 12s）
                main.removeCallbacks(settleRunnable);
                main.postDelayed(settleRunnable, settleMs);
            }
        });
    }

    private final AMapLocationListener locationListener = new AMapLocationListener() {
        @Override
        public void onLocationChanged(AMapLocation loc) {
            Callback cb = null;
            double rLat = 0, rLng = 0;
            float rAcc = 0;
            String rAddr = "";
            boolean good = false;
            synchronized (LocationHelper.this) {
                if (current == null || released) {
                    return;
                }
                if (loc != null && loc.getErrorCode() == 0) {
                    float acc = loc.getAccuracy();
                    if (!haveFix || acc < bestAcc) {
                        haveFix = true;
                        bestLat = loc.getLatitude();
                        bestLng = loc.getLongitude();
                        bestAcc = acc;
                        bestAddress = buildAddress(loc);
                    }
                    if (quick) {
                        // 快速模式：拿到首个有效定位立即返回，避免「定位我」等待过久
                        good = true;
                        cb = current;
                        current = null;
                        rLat = bestLat;
                        rLng = bestLng;
                        rAcc = bestAcc;
                        rAddr = bestAddress;
                    } else if (bestAcc <= GOOD_ACCURACY) {
                        // 已足够好：提前结束，不再等收集窗口满
                        good = true;
                        cb = current;
                        current = null;
                        rLat = bestLat;
                        rLng = bestLng;
                        rAcc = bestAcc;
                        rAddr = bestAddress;
                    }
                }
            }
            if (good && cb != null) {
                main.removeCallbacks(settleRunnable);
                cb.onResult(rLat, rLng, rAcc, System.currentTimeMillis(), rAddr);
                main.post(LocationHelper.this::destroyClient);
            }
        }
    };

    /** 拼接粗略地址：优先高德完整格式化地址（如「广东省深圳市南山区粤海街道…」），
     *  缺失时回退省市区街道拼接（直辖市去重）。
     *  GPS 优先定位时省市区街道组件可能为空，但完整地址字段通常有值，故优先取它。 */
    private static String buildAddress(AMapLocation loc) {
        String full = loc.getAddress();
        if (full != null && !full.isEmpty() && !"未知".equals(full)) {
            return full;
        }
        String p = loc.getProvince();
        String c = loc.getCity();
        String d = loc.getDistrict();
        String s = loc.getStreet();
        StringBuilder sb = new StringBuilder();
        if (p != null && !p.isEmpty()) {
            sb.append(p);
        }
        if (c != null && !c.isEmpty() && !c.equals(p)) {
            sb.append(c);
        }
        if (d != null && !d.isEmpty()) {
            sb.append(d);
        }
        if (s != null && !s.isEmpty()) {
            sb.append(s);
        }
        return sb.toString();
    }

    /** 释放并销毁当前定位客户端（在主线程调用） */
    private void destroyClient() {
        synchronized (this) {
            if (client != null) {
                try {
                    client.stopLocation();
                } catch (Exception ignored) {
                }
                try {
                    client.onDestroy();
                } catch (Exception ignored) {
                }
                client = null;
            }
        }
    }

    public synchronized void release() {
        released = true;
        current = null;
        main.removeCallbacks(settleRunnable);
        destroyClient();
    }
}
