package com.simone.icam.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * 3D colour look-up table (RGB in [0,1] -> RGB in [0,1]), trilinear interpolation.
 * Built-in "apple-like" look, or loaded from a standard .cube file.
 */
public final class ColorLut {
    public final int n;
    private final float[] t; // (b*n*n + g*n + r)*3

    private ColorLut(int n, float[] t) {
        this.n = n;
        this.t = t;
    }

    public static ColorLut identity(int n) {
        float[] t = new float[n * n * n * 3];
        for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
            int i = ((b * n + g) * n + r) * 3;
            t[i] = r / (n - 1f);
            t[i + 1] = g / (n - 1f);
            t[i + 2] = b / (n - 1f);
        }
        return new ColorLut(n, t);
    }

    /**
     * Approximation of the iPhone default rendering: slightly warm white balance, gentle
     * contrast with soft highlight roll-off, faint black lift, moderate saturation with skin
     * tones kept natural, and slightly richer skies.
     * warmth / saturation are user-tunable multipliers (1 = default).
     */
    public static ColorLut appleLike(int n, float warmth, float saturation) {
        return look(n, warmth, saturation, 1.05f, 1.0f);
    }

    /** General look: warmth, saturation, contrast (slope of the S-curve), skin saturation. */
    public static ColorLut look(int n, float warmth, float saturation, float contrast, float skinSat) {
        return look(n, warmth, saturation, contrast, skinSat, 0f);
    }

    /** highlightWarm: tints near-white areas warm/pink (0 = off, 1 = like the reference high-key photo). */
    public static ColorLut look(int n, float warmth, float saturation, float contrast, float skinSat, float highlightWarm) {
        float[] t = new float[n * n * n * 3];
        float[] c = new float[3];
        for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
            c[0] = r / (n - 1f);
            c[1] = g / (n - 1f);
            c[2] = b / (n - 1f);
            look(c, warmth, saturation, contrast, skinSat, highlightWarm);
            int i = ((b * n + g) * n + r) * 3;
            t[i] = c[0];
            t[i + 1] = c[1];
            t[i + 2] = c[2];
        }
        return new ColorLut(n, t);
    }

    private static float curveRaw(float x, float contrast) {
        // gentle S around 0.45 + soft shoulder + tiny black lift
        float y = 0.45f + (x - 0.45f) * contrast;
        if (y < 0.06f) y = 0.06f * (float) Math.pow(Math.max(y, 0f) / 0.06f, 1.10);
        if (y > 0.82f) y = 0.82f + 0.18f * (float) Math.tanh((y - 0.82f) / 0.18f);
        return y;
    }

    /** Tone curve normalised so that input white stays pure white. */
    private static float curve(float x, float contrast) {
        float y = curveRaw(x, contrast) / curveRaw(1f, contrast);
        y = 0.008f + y * (1f - 0.008f);
        return ImgUtil.clampf(y, 0f, 1f);
    }

    private static void look(float[] c, float warmth, float sat, float contrast, float skinSat, float hlWarm) {
        float wr = 1f + 0.018f * warmth, wb = 1f - 0.03f * warmth;
        float r = c[0] * wr, g = c[1], b = c[2] * wb;
        // luminance-preserving tone curve
        float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        float lc = curve(ImgUtil.clampf(l, 0f, 1.2f), contrast);
        float k = l > 1e-4f ? lc / l : 1f;
        r *= k; g *= k; b *= k;
        // hue-dependent saturation
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float hue = 0f;
        if (max - min > 1e-5f) {
            if (max == r) hue = 60f * (((g - b) / (max - min)) % 6f);
            else if (max == g) hue = 60f * ((b - r) / (max - min) + 2f);
            else hue = 60f * ((r - g) / (max - min) + 4f);
            if (hue < 0) hue += 360f;
        }
        float boost = 1.07f;
        if (hue >= 5f && hue <= 50f) boost = skinSat;        // skin
        else if (hue >= 190f && hue <= 245f) boost = 1.12f;  // sky/blue
        else if (hue >= 75f && hue <= 160f) boost = 1.05f;   // foliage
        boost = 1f + (boost - 1f) * sat;
        float l2 = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        r = l2 + (r - l2) * boost;
        g = l2 + (g - l2) * boost;
        b = l2 + (b - l2) * boost;
        if (hlWarm > 0f) {
            // near-white -> warm white (reference walls: R/G 1.02, B/G 0.98)
            float l3 = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            float t = ImgUtil.clampf((l3 - 0.75f) / 0.2f, 0f, 1f) * hlWarm;
            r *= 1f + 0.008f * t;
            g *= 1f - 0.004f * t;
            b *= 1f - 0.018f * t;
        }
        c[0] = ImgUtil.clampf(r, 0f, 1f);
        c[1] = ImgUtil.clampf(g, 0f, 1f);
        c[2] = ImgUtil.clampf(b, 0f, 1f);
    }

    /** Parses an Adobe/Resolve .cube 3D LUT (red varies fastest). */
    public static ColorLut parseCube(Reader reader) throws IOException {
        BufferedReader br = new BufferedReader(reader);
        int n = 0;
        float[] t = null;
        int idx = 0;
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("LUT_3D_SIZE")) {
                n = Integer.parseInt(line.split("\\s+")[1]);
                t = new float[n * n * n * 3];
                continue;
            }
            char c0 = line.charAt(0);
            if (!(Character.isDigit(c0) || c0 == '-' || c0 == '.')) continue;
            if (t == null) throw new IOException("LUT_3D_SIZE missing");
            String[] p = line.split("\\s+");
            if (p.length < 3 || idx >= t.length) continue;
            t[idx++] = Float.parseFloat(p[0]);
            t[idx++] = Float.parseFloat(p[1]);
            t[idx++] = Float.parseFloat(p[2]);
        }
        if (t == null || idx != t.length) throw new IOException("Incomplete .cube file");
        return new ColorLut(n, t);
    }

    /** In-place: c = rgb in [0,1]. */
    public void apply(float[] c) {
        float s = n - 1;
        float fr = ImgUtil.clampf(c[0], 0f, 1f) * s, fg = ImgUtil.clampf(c[1], 0f, 1f) * s, fb = ImgUtil.clampf(c[2], 0f, 1f) * s;
        int r0 = Math.min((int) fr, n - 2), g0 = Math.min((int) fg, n - 2), b0 = Math.min((int) fb, n - 2);
        float ar = fr - r0, ag = fg - g0, ab = fb - b0;
        for (int ch = 0; ch < 3; ch++) {
            float c000 = t[((b0 * n + g0) * n + r0) * 3 + ch];
            float c100 = t[((b0 * n + g0) * n + r0 + 1) * 3 + ch];
            float c010 = t[((b0 * n + g0 + 1) * n + r0) * 3 + ch];
            float c110 = t[((b0 * n + g0 + 1) * n + r0 + 1) * 3 + ch];
            float c001 = t[(((b0 + 1) * n + g0) * n + r0) * 3 + ch];
            float c101 = t[(((b0 + 1) * n + g0) * n + r0 + 1) * 3 + ch];
            float c011 = t[(((b0 + 1) * n + g0 + 1) * n + r0) * 3 + ch];
            float c111 = t[(((b0 + 1) * n + g0 + 1) * n + r0 + 1) * 3 + ch];
            float c00 = c000 + (c100 - c000) * ar, c10 = c010 + (c110 - c010) * ar;
            float c01 = c001 + (c101 - c001) * ar, c11 = c011 + (c111 - c011) * ar;
            float c0 = c00 + (c10 - c00) * ag, c1 = c01 + (c11 - c01) * ag;
            c[ch] = c0 + (c1 - c0) * ab;
        }
    }
}
