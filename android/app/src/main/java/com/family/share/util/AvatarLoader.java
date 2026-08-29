package com.family.share.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.family.share.config.AppConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 头像异步加载：内存缓存（LruCache，按字节计）+ 后台下载 + 圆形裁剪，回调统一在主线程。
 * URL 带 ?v= 时间戳，头像更新后 URL 变化，缓存自动失效。
 */
public final class AvatarLoader {

    public interface Callback {
        void onResult(Bitmap bitmap);
    }

    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getByteCount();
        }
    };

    private static final OkHttpClient client = new OkHttpClient();
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private AvatarLoader() {
    }

    public static void load(String url, Callback cb) {
        String full = url.startsWith("http") ? url : AppConfig.SERVER_URL + "/" + url;
        Bitmap cached = cache.get(full);
        if (cached != null && !cached.isRecycled()) {
            cb.onResult(cached);
            return;
        }
        executor.execute(() -> {
            Bitmap bmp = null;
            try {
                Request req = new Request.Builder()
                        .url(full)
                        .header("X-Api-Token", AppConfig.API_TOKEN)
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        bmp = BitmapFactory.decodeStream(resp.body().byteStream());
                    }
                }
            } catch (Exception ignored) {
            }
            final Bitmap result = bmp;
            if (result != null) {
                cache.put(full, result);
            }
            main.post(() -> cb.onResult(result));
        });
    }

    /** 圆形裁剪（输出正方形位图，用于圆形头像显示） */
    public static Bitmap circleCrop(Bitmap src) {
        if (src == null) {
            return null;
        }
        int size = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, new Rect(0, 0, src.getWidth(), src.getHeight()),
                new Rect(0, 0, size, size), paint);
        return out;
    }
}
