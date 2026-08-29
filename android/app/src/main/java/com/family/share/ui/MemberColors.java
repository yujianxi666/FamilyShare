package com.family.share.ui;

import android.graphics.Color;

/**
 * 共享调色板：成员头像颜色、地图标点颜色、轨迹线颜色一一对应（同一 deviceId 得到同色）。
 * 标点 hue 由头像 ARGB 颜色换算而来，保证三者视觉上完全一致。
 */
public final class MemberColors {

    private MemberColors() {
    }

    /** 成员颜色（ARGB），头像/轨迹线直接使用；地图标点由 hueOf() 换算 */
    private static final int[] COLORS = {
            0xFF2B7CE9, // 蓝
            0xFF22C55E, // 绿
            0xFFF59E0B, // 橙
            0xFF8B5CF6, // 紫
            0xFFEC4899, // 粉
            0xFF06B6D4  // 青
    };

    /** 自己固定蓝色 */
    public static int selfColor() {
        return 0xFF2B7CE9;
    }

    /** 精度圈：半透明绿色填充 */
    public static final int ACCURACY_FILL = 0x3322C55E;
    /** 精度圈：边缘 75% 不透明深绿色 */
    public static final int ACCURACY_STROKE = 0xBF15803D;

    /** 自己的地图标点 hue（与 selfColor 同色） */
    public static float selfHue() {
        return hueOf(selfColor());
    }

    public static int colorFor(String deviceId) {
        return COLORS[Math.abs(deviceId.hashCode()) % COLORS.length];
    }

    /** 成员的地图标点 hue（与 colorFor 完全同色） */
    public static float hueFor(String deviceId) {
        return hueOf(colorFor(deviceId));
    }

    /** ARGB 颜色 -> 高德标点 hue（0-360） */
    private static float hueOf(int argb) {
        float[] hsv = new float[3];
        Color.colorToHSV(argb, hsv);
        return hsv[0];
    }
}
