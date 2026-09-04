package com.family.share.util;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.HashMap;
import java.util.Map;

/**
 * 二维码生成：把字符串（家庭码）编码成纯黑白二维码位图。
 * 使用 ZXing core，纯位图生成，无网络依赖。
 */
public final class QrCode {

    private QrCode() {
    }

    /**
     * @param text   要编码的内容（如 6 位家庭码）
     * @param sizePx 二维码边长（像素）
     * @return 二维码 Bitmap；失败返回 null
     */
    public static Bitmap generate(String text, int sizePx) {
        if (text == null || text.isEmpty() || sizePx <= 0) {
            return null;
        }
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter()
                    .encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    pixels[y * w + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }
}
