package com.family.share.server;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket(/ws) 注册 + /downloads 静态 APK 下载。
 * 注意：update.json 与 downloads/ 均相对于运行目录（server/）。
 */
@Configuration
@EnableWebSocket
public class WebConfig implements WebSocketConfigurer, WebMvcConfigurer {

    private final WsHandler wsHandler;

    public WebConfig(WsHandler wsHandler) {
        this.wsHandler = wsHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(wsHandler, "/ws").setAllowedOrigins("*");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/downloads/**").addResourceLocations("file:./downloads/");
        // 成员头像图片（上传后保存到 server/icons/）
        registry.addResourceHandler("/icons/**").addResourceLocations("file:./icons/");
        // Bug 管理页改由 /bugadmin/{token} 控制器提供（带路径口令鉴权），此处不再直接暴露
    }
}
