package com.family.share.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 只读后端状态看板：
 * - GET /api/stats/summary   聚合汇总（总用户数 / 在线数 / 家庭数 / CPU / 内存 / 上下行带宽 / 运行时长）
 * - GET /api/stats/series    最近约 10 分钟的采样曲线（服务器压力波动）
 * - GET /dashboard/{token}   看板页面（路径口令，风格与 /bugadmin 一致）
 *
 * 只读与隐私保证：
 * - 本控制器只暴露 GET 读接口，不提供任何写/改/删入口（无法通过看板修改服务器任何状态）；
 * - 数据全部为聚合数字，绝不含昵称、deviceId、位置、家庭码等个人信息；
 * - 看板页面本身不授予任何管理权限（看不到任何个人数据，也不能做任何操作）。
 */
@RestController
public class StatsController {

    private final MetricsService metrics;

    /** 看板页面路径口令（application.properties 的 app.dashboard-token） */
    @Value("${app.dashboard-token:dashboard-default}")
    private String dashboardToken;

    /** 用于注入看板页面的 fetch 头（页面读取 /api/stats/** 需携带访问口令） */
    @Value("${app.api-token:YOUR_API_TOKEN}")
    private String apiToken;

    public StatsController(MetricsService metrics) {
        this.metrics = metrics;
    }

    /** 聚合汇总（只读） */
    @GetMapping("/api/stats/summary")
    public Map<String, Object> summary() {
        return metrics.summary();
    }

    /** 采样曲线（只读） */
    @GetMapping("/api/stats/series")
    public Map<String, Object> series() {
        return metrics.series();
    }

    /**
     * GET /dashboard/{token} -> 返回 dashboard.html（token 正确才可访问）。
     * 页面打包进 jar（classpath 根），并注入访问口令供其读取 /api/stats/**。
     */
    @GetMapping(value = "/dashboard/{token}", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> dashboard(@PathVariable("token") String token) {
        if (token == null || !dashboardToken.equals(token)) {
            return ResponseEntity.status(404).body("<h1>404 Not Found</h1>");
        }
        try {
            ClassPathResource res = new ClassPathResource("dashboard.html");
            if (!res.exists()) {
                return ResponseEntity.status(404).body("<h1>dashboard.html 不存在</h1>");
            }
            String html = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            html = html.replace("__API_TOKEN__", apiToken);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("<h1>加载失败：" + e.getMessage() + "</h1>");
        }
    }
}
