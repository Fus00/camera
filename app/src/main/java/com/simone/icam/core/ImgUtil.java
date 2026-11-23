package com.simone.icam.core;

import java.util.stream.IntStream;

/** Small, allocation-free image helpers used by the pipeline. */
public final class ImgUtil {
    private ImgUtil() {}

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static float[] toFloat(byte[] src) {
        float[] out = new float[src.length];
        IntStream.range(0, src.length / 4096 + 1).parallel().forEach(b -> {
            int s = b * 4096, e = Math.min(src.length, s + 4096);
            for (int i = s; i < e; i++) out[i] = src[i] & 0xFF;
        });
        return out;
    }

    /** 2x box downsample; output size (w/2, h/2). */
    public static float[] down2(float[] src, int w, int h) {
        final int ow = w / 2, oh = h / 2;
        final float[] out = new float[ow * oh];
        IntStream.range(0, oh).parallel().forEach(y -> {
            int r0 = (2 * y) * w, r1 = r0 + w, o = y * ow;
            for (int x = 0; x < ow; x++) {
                int c = 2 * x;
                out[o + x] = 0.25f * (src[r0 + c] + src[r0 + c + 1] + src[r1 + c] + src[r1 + c + 1]);
            }
        });
        return out;
    }

    public static float bilinear(float[] img, int w, int h, float x, float y) {
        if (x < 0) x = 0; else if (x > w - 1) x = w - 1;
        if (y < 0) y = 0; else if (y > h - 1) y = h - 1;
        int x0 = (int) x, y0 = (int) y;
        int x1 = x0 + 1 < w ? x0 + 1 : x0, y1 = y0 + 1 < h ? y0 + 1 : y0;
        float fx = x - x0, fy = y - y0;
        float a = img[y0 * w + x0], b = img[y0 * w + x1];
        float c = img[y1 * w + x0], d = img[y1 * w + x1];
        float top = a + (b - a) * fx, bot = c + (d - c) * fx;
        return top + (bot - top) * fy;
    }

    public static float bilinear(byte[] img, int w, int h, float x, float y) {
        if (x < 0) x = 0; else if (x > w - 1) x = w - 1;
        if (y < 0) y = 0; else if (y > h - 1) y = h - 1;
        int x0 = (int) x, y0 = (int) y;
        int x1 = x0 + 1 < w ? x0 + 1 : x0, y1 = y0 + 1 < h ? y0 + 1 : y0;
        float fx = x - x0, fy = y - y0;
        float a = img[y0 * w + x0] & 0xFF, b = img[y0 * w + x1] & 0xFF;
        float c = img[y1 * w + x0] & 0xFF, d = img[y1 * w + x1] & 0xFF;
        float top = a + (b - a) * fx, bot = c + (d - c) * fx;
        return top + (bot - top) * fy;
    }

    /** Separable 3x3 [1 2 1]/4 blur. */
    public static float[] blur3(float[] src, int w, int h) {
        final float[] tmp = new float[w * h];
        final float[] out = new float[w * h];
        IntStream.range(0, h).parallel().forEach(y -> {
            int r = y * w;
            for (int x = 0; x < w; x++) {
                int xl = x > 0 ? x - 1 : 0, xr = x < w - 1 ? x + 1 : w - 1;
                tmp[r + x] = 0.25f * src[r + xl] + 0.5f * src[r + x] + 0.25f * src[r + xr];
            }
        });
        IntStream.range(0, h).parallel().forEach(y -> {
            int yu = y > 0 ? y - 1 : 0, yd = y < h - 1 ? y + 1 : h - 1;
            int r = y * w, ru = yu * w, rd = yd * w;
            for (int x = 0; x < w; x++) {
                out[r + x] = 0.25f * tmp[ru + x] + 0.5f * tmp[r + x] + 0.25f * tmp[rd + x];
            }
        });
        return out;
    }

    /** Box filter with radius r using summed-area table (edges clamped by shrinking the window). */
    public static float[] box(float[] src, int w, int h, int r) {
        double[] sat = new double[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            double row = 0;
            for (int x = 0; x < w; x++) {
                row += src[y * w + x];
                sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + row;
            }
        }
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - r), y1 = Math.min(h - 1, y + r);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r), x1 = Math.min(w - 1, x + r);
                double s = sat[(y1 + 1) * (w + 1) + x1 + 1] - sat[y0 * (w + 1) + x1 + 1]
                        - sat[(y1 + 1) * (w + 1) + x0] + sat[y0 * (w + 1) + x0];
                out[y * w + x] = (float) (s / ((x1 - x0 + 1) * (y1 - y0 + 1)));
            }
        }
        return out;
    }

    /** Robust noise sigma (0..255 units) from the median absolute Laplacian. */
    public static float estimateNoise(float[] img, int w, int h) {
        int[] hist = new int[2048];
        int n = 0;
        for (int y = 2; y < h - 2; y += 3) {
            for (int x = 2; x < w - 2; x += 3) {
                int i = y * w + x;
                float lap = img[i - 1] + img[i + 1] + img[i - w] + img[i + w] - 4 * img[i];
                int b = (int) (Math.abs(lap) * 4f);
                if (b > 2047) b = 2047;
                hist[b]++;
                n++;
            }
        }
        int half = n / 2, acc = 0, med = 0;
        for (int b = 0; b < 2048; b++) { acc += hist[b]; if (acc >= half) { med = b; break; } }
        float medAbs = (med + 0.5f) / 4f;
        float sigma = medAbs / 0.6745f / (float) Math.sqrt(20.0);
        return Math.max(0.5f, sigma);
    }

    /** Sharpness score: mean squared gradient on a 4x downsampled copy. */
    public static double sharpness(byte[] y, int w, int h) {
        double s = 0;
        int n = 0;
        for (int yy = 4; yy < h - 4; yy += 4) {
            for (int xx = 4; xx < w - 4; xx += 4) {
                int i = yy * w + xx;
                int gx = (y[i + 1] & 0xFF) - (y[i - 1] & 0xFF);
                int gy = (y[i + w] & 0xFF) - (y[i - w] & 0xFF);
                s += gx * gx + gy * gy;
                n++;
            }
        }
        return n > 0 ? s / n : 0;
    }

    /** Guided filter (He et al.): smooths p while following the edges of guide I. */
    public static float[] guided(float[] I, float[] p, int w, int h, int r, float eps) {
        int n = w * h;
        float[] ip = new float[n], ii = new float[n];
        for (int i = 0; i < n; i++) { ip[i] = I[i] * p[i]; ii[i] = I[i] * I[i]; }
        float[] mI = box(I, w, h, r), mP = box(p, w, h, r), mIP = box(ip, w, h, r), mII = box(ii, w, h, r);
        float[] a = ip, b = ii; // reuse
        for (int i = 0; i < n; i++) {
            float var = mII[i] - mI[i] * mI[i];
            float cov = mIP[i] - mI[i] * mP[i];
            a[i] = cov / (var + eps);
            b[i] = mP[i] - a[i] * mI[i];
        }
        float[] ma = box(a, w, h, r), mb = box(b, w, h, r);
        float[] q = new float[n];
        for (int i = 0; i < n; i++) q[i] = ma[i] * I[i] + mb[i];
        return q;
    }

    /** Noise sigma of an 8-bit plane. */
    public static float estimateNoise(byte[] plane, int w, int h) {
        return estimateNoise(toFloat(plane), w, h);
    }
}
