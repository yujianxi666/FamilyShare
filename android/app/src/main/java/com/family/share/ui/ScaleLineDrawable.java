package com.family.share.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/**
 * 刻度尺横线：|___| 样式（两端竖线 + 中间横线）。
 * 高德地图底图固定为浅色，故使用深色绘制保证在浅色底图上清晰可见（深浅色模式下均适用）。
 */
public class ScaleLineDrawable extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float tickLength;

    public ScaleLineDrawable(int color, float strokeWidth, float tickLength) {
        this.tickLength = tickLength;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
    }

    @Override
    public void draw(Canvas canvas) {
        float w = getBounds().width();
        float cy = getBounds().height() / 2f;
        // 中间横线
        canvas.drawLine(0, cy, w, cy, paint);
        // 两端竖线（|___|）
        canvas.drawLine(0, cy - tickLength, 0, cy + tickLength, paint);
        canvas.drawLine(w, cy - tickLength, w, cy + tickLength, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    @SuppressWarnings("deprecation") // Drawable.getOpacity() 为抽象方法必须实现，API 33+ 标记过时
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
