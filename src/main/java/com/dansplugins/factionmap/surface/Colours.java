package com.dansplugins.factionmap.surface;

import java.util.Locale;

/** Small colour helpers on {@code #rrggbb} strings, without AWT. */
public final class Colours {
    private Colours() {
    }

    public static int[] rgb(String hex) {
        return new int[]{
                Integer.parseInt(hex.substring(1, 3), 16),
                Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16)};
    }

    public static String hex(int r, int g, int b) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", clamp(r), clamp(g), clamp(b));
    }

    public static double distance(String a, String b) {
        int[] x = rgb(a);
        int[] y = rgb(b);
        return Math.sqrt(Math.pow(x[0] - y[0], 2) + Math.pow(x[1] - y[1], 2) + Math.pow(x[2] - y[2], 2));
    }

    /** HSB to {@code #rrggbb}; hue in [0, 1). Same maths as {@code java.awt.Color.HSBtoRGB}. */
    public static String hsb(float hue, float saturation, float brightness) {
        int r = 0;
        int g = 0;
        int b = 0;
        if (saturation == 0) {
            r = g = b = (int) (brightness * 255.0f + 0.5f);
        } else {
            float h = (hue - (float) Math.floor(hue)) * 6.0f;
            float f = h - (float) Math.floor(h);
            float p = brightness * (1.0f - saturation);
            float q = brightness * (1.0f - saturation * f);
            float t = brightness * (1.0f - (saturation * (1.0f - f)));
            switch ((int) h) {
                case 0 -> { r = (int) (brightness * 255.0f + 0.5f); g = (int) (t * 255.0f + 0.5f); b = (int) (p * 255.0f + 0.5f); }
                case 1 -> { r = (int) (q * 255.0f + 0.5f); g = (int) (brightness * 255.0f + 0.5f); b = (int) (p * 255.0f + 0.5f); }
                case 2 -> { r = (int) (p * 255.0f + 0.5f); g = (int) (brightness * 255.0f + 0.5f); b = (int) (t * 255.0f + 0.5f); }
                case 3 -> { r = (int) (p * 255.0f + 0.5f); g = (int) (q * 255.0f + 0.5f); b = (int) (brightness * 255.0f + 0.5f); }
                case 4 -> { r = (int) (t * 255.0f + 0.5f); g = (int) (p * 255.0f + 0.5f); b = (int) (brightness * 255.0f + 0.5f); }
                default -> { r = (int) (brightness * 255.0f + 0.5f); g = (int) (p * 255.0f + 0.5f); b = (int) (q * 255.0f + 0.5f); }
            }
        }
        return hex(r, g, b);
    }

    /** Rotates the hue of {@code hex} by {@code degrees}. */
    public static String hueShift(String hex, float degrees) {
        int[] v = rgb(hex);
        float[] hsb = toHsb(v[0], v[1], v[2]);
        return hsb((hsb[0] + degrees / 360f) % 1f, hsb[1], hsb[2]);
    }

    static float[] toHsb(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        float brightness = max / 255.0f;
        float saturation = max != 0 ? (float) (max - min) / max : 0;
        float hue;
        if (saturation == 0) {
            hue = 0;
        } else {
            float rc = (float) (max - r) / (max - min);
            float gc = (float) (max - g) / (max - min);
            float bc = (float) (max - b) / (max - min);
            if (r == max) {
                hue = bc - gc;
            } else if (g == max) {
                hue = 2.0f + rc - bc;
            } else {
                hue = 4.0f + gc - rc;
            }
            hue = hue / 6.0f;
            if (hue < 0) {
                hue = hue + 1.0f;
            }
        }
        return new float[]{hue, saturation, brightness};
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
