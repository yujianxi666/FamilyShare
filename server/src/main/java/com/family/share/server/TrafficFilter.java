package com.family.share.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

/**
 * 带宽统计（应用层）：统计本服务的 HTTP 请求/响应字节数（含 APK 下载、图片等），
 * 供只读看板展示「带宽占用」。只累加字节数，不记录、不解析任何内容。
 *
 * WebSocket 的收发字节由 {@link WsHandler} 单独累加（不经 Servlet 过滤器）。
 */
@Component
public class TrafficFilter extends OncePerRequestFilter {

    private final TrafficCounter counter;

    public TrafficFilter(TrafficCounter counter) {
        this.counter = counter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long reqLen = req.getContentLengthLong();
        if (reqLen > 0) {
            counter.addIn(reqLen);
        }
        CountingResponse wrapper = new CountingResponse(res, counter);
        try {
            chain.doFilter(req, wrapper);
        } finally {
            wrapper.flushQuietly();
        }
    }

    /** 包装响应：统计写出的字节数（输出流与 Writer 两条路径都覆盖） */
    private static class CountingResponse extends HttpServletResponseWrapper {

        private final TrafficCounter counter;
        private CountingStream stream;
        private PrintWriter writer;

        CountingResponse(HttpServletResponse resp, TrafficCounter counter) {
            super(resp);
            this.counter = counter;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) {
                stream = new CountingStream(super.getOutputStream(), counter);
            }
            return stream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                String enc = getCharacterEncoding();
                if (enc == null || enc.isEmpty()) {
                    enc = "UTF-8";
                }
                writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), enc));
            }
            return writer;
        }

        void flushQuietly() {
            if (writer != null) {
                try {
                    writer.flush();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 计数输出流：每个写出字节都累加 */
    private static class CountingStream extends ServletOutputStream {

        private final ServletOutputStream delegate;
        private final TrafficCounter counter;

        CountingStream(ServletOutputStream delegate, TrafficCounter counter) {
            this.delegate = delegate;
            this.counter = counter;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            counter.addOut(1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            counter.addOut(len);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            delegate.setWriteListener(writeListener);
        }
    }
}
