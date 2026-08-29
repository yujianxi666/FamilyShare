package com.family.share.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 访问口令过滤：除白名单外，其余所有请求（/api/**、/ws、/icons/**）都必须在
 * 请求头 X-Api-Token 或查询参数 token 中携带正确的口令，否则返回 401。
 * 白名单：/api/health、/downloads/**、/bugadmin/**（后者另有自己的路径口令 app.bug-admin-token）。
 */
@Component
public class AccessTokenFilter extends OncePerRequestFilter {

    @Value("${app.api-token:YOUR_API_TOKEN}")
    private String apiToken;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        // 公开（无需 API 口令）：官网首页 / 健康检查 / APK 下载 / Bug 管理页（后者自身带路径口令）
        // 隐私政策 / 隐私权利页必须公开可访问，供应用市场审核与客户端内展示
        if (path.equals("/") || path.equals("/index.html")
                || path.equals("/privacy.html")
                || path.equals("/rights.html")
                || path.equals("/api/health")
                || path.equals("/downloads") || path.startsWith("/downloads/")
                || path.equals("/bugadmin") || path.startsWith("/bugadmin/")) {
            chain.doFilter(req, res);
            return;
        }
        // 其余全部需要口令
        String token = req.getHeader("X-Api-Token");
        if (token == null || token.isEmpty()) {
            token = req.getParameter("token");
        }
        if (apiToken != null && apiToken.equals(token)) {
            chain.doFilter(req, res);
        } else {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"error\":\"unauthorized\",\"msg\":\"缺少或错误的口令\"}");
        }
    }
}
