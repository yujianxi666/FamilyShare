package com.family.share.server;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 带宽字节计数器（应用层）：只累计收发字节数，供只读看板计算「带宽占用」。
 * 不记录任何内容，也不涉及任何用户信息。
 */
@Component
public class TrafficCounter {

    /** 累计收到字节 */
    private final AtomicLong in = new AtomicLong();
    /** 累计发出字节 */
    private final AtomicLong out = new AtomicLong();

    public void addIn(long bytes) {
        if (bytes > 0) {
            in.addAndGet(bytes);
        }
    }

    public void addOut(long bytes) {
        if (bytes > 0) {
            out.addAndGet(bytes);
        }
    }

    public long in() {
        return in.get();
    }

    public long out() {
        return out.get();
    }
}
