package com.family.share.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 1:1 头像裁切视图：
 * - 中间方形取景框（黑色描边，框外压暗）
 * - 图片初始自动覆盖取景框（center-crop），支持单指拖动平移、双指缩放
 * - crop() 返回取景框内图像（最长边 512）
 */
public class CropView extends View {

    private Bitmap src;
    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();

    private int squareLeft;
    private int squareTop;
    private int squareSize;
    private float minScale = 1f;

    private boolean multiTouch;
    private float lastDist;
    private float downX;
    private float downY;

    public CropView(Context context) {
        super(context);
        init();
    }

    public CropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));
        borderPaint.setColor(Color.BLACK);
        dimPaint.setColor(0xB3000000);
    }

    public void setImage(Bitmap bmp) {
        src = bmp;
        requestLayout();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int side = Math.min(w, h);
        int pad = dp(28);
        squareSize = side - pad * 2;
        if (squareSize <= 0) {
            squareSize = side;
        }
        // 四周各留出边框宽度 + 余量，避免右/下边框被画布边缘裁掉
        int m = (int) Math.ceil(borderPaint.getStrokeWidth() / 2f) + 1;
        int maxSide = Math.min(w, h) - m * 2;
        if (squareSize > maxSide) {
            squareSize = maxSide;
        }
        if (squareSize <= 0) {
            squareSize = 1;
        }
        squareLeft = (w - squareSize) / 2;
        squareTop = (h - squareSize) / 2;
        fitImage();
    }

    /** 图片初始覆盖取景框（center-crop），并记录最小缩放 */
    private void fitImage() {
        if (src == null || squareSize <= 0) {
            return;
        }
        float scale = Math.max(squareSize / (float) src.getWidth(),
                squareSize / (float) src.getHeight());
        float dx = squareLeft + (squareSize - src.getWidth() * scale) / 2f;
        float dy = squareTop + (squareSize - src.getHeight() * scale) / 2f;
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate(dx, dy);
        minScale = scale;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (src == null) {
            return;
        }
        float left = squareLeft;
        float top = squareTop;
        float right = squareLeft + squareSize;
        float bottom = squareTop + squareSize;

        int sc = canvas.save();
        canvas.clipRect(left, top, right, bottom);
        canvas.drawBitmap(src, matrix, null);
        canvas.restoreToCount(sc);

        // 方框外压暗（四块紧贴方形边界，左右与上下块之间无缝隙，避免出现透明亮边）
        float sw = borderPaint.getStrokeWidth();
        canvas.drawRect(0, 0, getWidth(), top, dimPaint);
        canvas.drawRect(0, bottom, getWidth(), getHeight(), dimPaint);
        canvas.drawRect(0, top, left, bottom, dimPaint);
        canvas.drawRect(right, top, getWidth(), bottom, dimPaint);

        // 黑色描边：以方形边界为中心线（覆盖边界内外各一半），最后绘制确保在最上层；
        // 这样四条边（含右边）均匀可见，不会因图片内容颜色偏深而“消失”。
        canvas.drawRect(left - sw / 2f, top - sw / 2f, right + sw / 2f, bottom + sw / 2f, borderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (src == null) {
            return true;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                multiTouch = false;
                downX = ev.getX();
                downY = ev.getY();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                lastDist = dist(ev);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (multiTouch && ev.getPointerCount() >= 2) {
                    float d = dist(ev);
                    if (lastDist > 0 && d > 0) {
                        scaleAroundCenter(d / lastDist);
                    }
                    lastDist = d;
                } else if (!multiTouch) {
                    translateBy(ev.getX() - downX, ev.getY() - downY);
                    downX = ev.getX();
                    downY = ev.getY();
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (ev.getPointerCount() <= 2) {
                    multiTouch = false;
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                multiTouch = false;
                return true;
        }
        return true;
    }

    private void translateBy(float dx, float dy) {
        matrix.postTranslate(dx, dy);
        clampTranslation();
        invalidate();
    }

    private void scaleAroundCenter(float factor) {
        matrix.getValues(values);
        float cur = values[Matrix.MSCALE_X];
        float target = Math.max(minScale, Math.min(minScale * 8f, cur * factor));
        float f = target / cur;
        matrix.postScale(f, f, squareLeft + squareSize / 2f, squareTop + squareSize / 2f);
        clampTranslation();
        invalidate();
    }

    /** 限制平移：图片必须始终覆盖整个方形取景框 */
    private void clampTranslation() {
        if (src == null) {
            return;
        }
        matrix.getValues(values);
        float scale = values[Matrix.MSCALE_X];
        float tx = values[Matrix.MTRANS_X];
        float ty = values[Matrix.MTRANS_Y];
        float w = src.getWidth() * scale;
        float h = src.getHeight() * scale;
        if (w >= squareSize) {
            tx = Math.max(squareLeft + squareSize - w, Math.min(tx, squareLeft));
        } else {
            tx = squareLeft + (squareSize - w) / 2f;
        }
        if (h >= squareSize) {
            ty = Math.max(squareTop + squareSize - h, Math.min(ty, squareTop));
        } else {
            ty = squareTop + (squareSize - h) / 2f;
        }
        values[Matrix.MTRANS_X] = tx;
        values[Matrix.MTRANS_Y] = ty;
        matrix.setValues(values);
    }

    /** 截取取景框内图像（等比输出，最长边 512） */
    public Bitmap crop() {
        if (src == null) {
            return null;
        }
        matrix.getValues(values);
        float scale = values[Matrix.MSCALE_X];
        float tx = values[Matrix.MTRANS_X];
        float ty = values[Matrix.MTRANS_Y];
        int srcX = (int) Math.round((squareLeft - tx) / scale);
        int srcY = (int) Math.round((squareTop - ty) / scale);
        int size = (int) Math.round(squareSize / scale);
        if (srcX < 0) {
            srcX = 0;
        }
        if (srcY < 0) {
            srcY = 0;
        }
        if (srcX + size > src.getWidth()) {
            size = src.getWidth() - srcX;
        }
        if (srcY + size > src.getHeight()) {
            size = src.getHeight() - srcY;
        }
        if (size <= 0) {
            return null;
        }
        Bitmap out = Bitmap.createBitmap(src, srcX, srcY, size, size);
        if (out.getWidth() > 512) {
            out = Bitmap.createScaledBitmap(out, 512, 512, true);
        }
        return out;
    }

    private float dist(MotionEvent ev) {
        float dx = ev.getX(0) - ev.getX(1);
        float dy = ev.getY(0) - ev.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
