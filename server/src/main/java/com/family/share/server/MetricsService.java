package com.family.share.server;

import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 只读运行指标采集：每 5 秒采样一次 CPU / 堆内存 / 上下行带宽 / 在线设备数，
 * 保留最近 120 个点（约 10 分钟）用于「服务器压力波动曲线」展示。
 *
 * 隐私与安全：
 * - 只产生聚合数字，不含昵称、deviceId、位置、家庭码等任何个人信息；
 * - 只有读方法（summary/series），不提供任何修改服务器状态的入口。
 */
@Component
public class MetricsService {

    /** 保留的采样点数（120 × 5s ≈ 最近 10 分钟） */
    public static final int MAX_POINTS = 120;
    /** 采样间隔（毫秒） */
    private static final long SAMPLE_MS = 5000L;

    private final Store store;
    private final WsHandler ws;
    private final TrafficCounter traffic;
    private final long startedAt = System.currentTimeMillis();
    private final Deque<Map<String, Object>> series = new ArrayDeque<>();

    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "metrics-sampler");
        t.setDaemon(true);
        return t;
    });

    // 上一次采样用于计算字节速率
    private long lastIn = -1;
    private long lastOut = -1;
    private long lastSampleAt = 0;

    public MetricsService(Store store, WsHandler ws, TrafficCounter traffic) {
        this.store = store;
        this.ws = ws;
        this.traffic = traffic;
        sampler.scheduleWithFixedDelay(this::sample, SAMPLE_MS, SAMPLE_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void sample() {
        long now = System.currentTimeMillis();
        double[] cpu = cpuLoad();          // [系统CPU, 进程CPU]，-1 表示不可用
        long in = traffic.in();
        long out = traffic.out();
        double inBps = 0;
        double outBps = 0;
        if (lastSampleAt > 0) {
            double dt = (now - lastSampleAt) / 1000.0;
            if (dt > 0 && in >= lastIn && out >= lastOut) {
                inBps = (in - lastIn) / dt;
                outBps = (out - lastOut) / dt;
            }
        }
        lastIn = in;
        lastOut = out;
        lastSampleAt = now;

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ts", now);
        p.put("cpu", pct(cpu[0]));
        p.put("cpuProc", pct(cpu[1]));
        p.put("memMb", usedHeapMb());
        p.put("online", ws.onlineCount());
        p.put("inBps", Math.max(0L, Math.round(inBps)));    // 服务端接收速率
        p.put("outBps", Math.max(0L, Math.round(outBps)));  // 服务端发送速率
        series.addLast(p);
        while (series.size() > MAX_POINTS) {
            series.removeFirst();
        }
    }

    /** 汇总（供看板卡片展示） */
    public synchronized Map<String, Object> summary() {
        Map<String, Object> last = series.peekLast();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalUsers", store.totalMemberCount());
        m.put("onlineUsers", ws.onlineCount());
        m.put("familyCount", store.familyCount());
        m.put("cpu", last == null ? 0 : last.get("cpu"));
        m.put("cpuProc", last == null ? 0 : last.get("cpuProc"));
        m.put("memUsedMb", last == null ? 0 : last.get("memMb"));
        m.put("memMaxMb", Runtime.getRuntime().maxMemory() / 1048576L);
        m.put("inBps", last == null ? 0 : last.get("inBps"));
        m.put("outBps", last == null ? 0 : last.get("outBps"));
        m.put("uptimeSec", (System.currentTimeMillis() - startedAt) / 1000L);
        m.put("ts", System.currentTimeMillis());
        return m;
    }

    /** 曲线数据（最近 MAX_POINTS 个采样点） */
    public synchronized Map<String, Object> series() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intervalMs", SAMPLE_MS);
        m.put("maxPoints", MAX_POINTS);
        m.put("points", new ArrayList<>(series));
        return m;
    }

    /** 0-1 的比例转 0-100 百分比（保留 1 位小数）；负值（不可用）记 0 */
    private static double pct(double v) {
        if (v < 0) {
            return 0;
        }
        return Math.round(v * 1000.0) / 10.0;
    }

    private static long usedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / 1048576L;
    }

    /** [系统 CPU 负载, 本进程 CPU 负载]，取值 0-1，不可用为 -1 */
    private static double[] cpuLoad() {
        double sys = -1;
        double proc = -1;
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sun = (com.sun.management.OperatingSystemMXBean) os;
                try {
                    sys = sun.getCpuLoad();
                } catch (Throwable ignored) {
                }
                try {
                    proc = sun.getProcessCpuLoad();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return new double[]{sys, proc};
    }
}
