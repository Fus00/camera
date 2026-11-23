package com.simone.icam.core;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Coarse-to-fine alignment of burst frames to a reference frame.
 * 1) global translation on a 4-level pyramid;
 * 2) per-tile (32 px) refinement at half and full resolution;
 * 3) sub-pixel refinement by parabola fit on the SSD surface (needed for super-resolution).
 */
public final class Aligner {
    public static final int TILE = 32;
    private static final int LEVELS = 4;

    private final int w, h, gw, gh;
    private final float[][] ref = new float[LEVELS][];
    private final int[] lw = new int[LEVELS], lh = new int[LEVELS];
    private final boolean[] textured;

    public Aligner(float[] refY, int w, int h, float noise) {
        this.w = w;
        this.h = h;
        ref[0] = refY;
        lw[0] = w;
        lh[0] = h;
        for (int l = 1; l < LEVELS; l++) {
            ref[l] = ImgUtil.down2(ref[l - 1], lw[l - 1], lh[l - 1]);
            lw[l] = lw[l - 1] / 2;
            lh[l] = lh[l - 1] / 2;
        }
        gw = (w + TILE - 1) / TILE;
        gh = (h + TILE - 1) / TILE;
        textured = new boolean[gw * gh];
        float thr = Math.max(6f, 6f * noise * noise);
        for (int ty = 0; ty < gh; ty++) {
            for (int tx = 0; tx < gw; tx++) {
                int x0 = tx * TILE, y0 = ty * TILE;
                int x1 = Math.min(w, x0 + TILE), y1 = Math.min(h, y0 + TILE);
                double s = 0, s2 = 0;
                int n = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        float v = refY[y * w + x];
                        s += v;
                        s2 += v * v;
                        n++;
                    }
                }
                double mean = s / n;
                double var = s2 / n - mean * mean;
                textured[ty * gw + tx] = var > thr;
            }
        }
    }

    public int gridW() { return gw; }
    public int gridH() { return gh; }

    public Flow align(byte[] frameY) {
        float[][] f = new float[LEVELS][];
        f[0] = ImgUtil.toFloat(frameY);
        for (int l = 1; l < LEVELS; l++) f[l] = ImgUtil.down2(f[l - 1], lw[l - 1], lh[l - 1]);

        // ---- global translation ----
        int gx = 0, gy = 0;
        for (int l = LEVELS - 1; l >= 0; l--) {
            if (l < LEVELS - 1) { gx *= 2; gy *= 2; }
            int r = (l == LEVELS - 1) ? 8 : 1;
            int stride = l == 0 ? 4 : (l == 1 ? 2 : 1);
            int[] b = globalSearch(ref[l], f[l], lw[l], lh[l], gx, gy, r, stride);
            gx = b[0];
            gy = b[1];
        }
        final int fgx = gx, fgy = gy;

        // ---- per-tile ----
        final Flow flow = new Flow(TILE, gw, gh);
        final float[] f0 = f[0], f1 = f[1];
        IntStream.range(0, gh).parallel().forEach(ty -> {
            for (int tx = 0; tx < gw; tx++) {
                int x0 = tx * TILE, y0 = ty * TILE;
                int sx = Math.min(TILE, w - x0), sy = Math.min(TILE, h - y0);
                // half resolution, +-4 around global
                int hx0 = x0 / 2, hy0 = y0 / 2, hsx = Math.max(1, sx / 2), hsy = Math.max(1, sy / 2);
                int cx = Math.round(fgx / 2f), cy = Math.round(fgy / 2f);
                int bx = cx, by = cy;
                double best = Double.MAX_VALUE;
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dx = -4; dx <= 4; dx++) {
                        double e = ssd(ref[1], f1, lw[1], lh[1], hx0, hy0, hsx, hsy, cx + dx, cy + dy, best);
                        if (e < best) { best = e; bx = cx + dx; by = cy + dy; }
                    }
                }
                // full resolution, +-1 around 2x
                int c0x = bx * 2, c0y = by * 2;
                int ix = c0x, iy = c0y;
                best = Double.MAX_VALUE;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        double e = ssd(ref[0], f0, w, h, x0, y0, sx, sy, c0x + dx, c0y + dy, best);
                        if (e < best) { best = e; ix = c0x + dx; iy = c0y + dy; }
                    }
                }
                double eL = ssd(ref[0], f0, w, h, x0, y0, sx, sy, ix - 1, iy, Double.MAX_VALUE);
                double eR = ssd(ref[0], f0, w, h, x0, y0, sx, sy, ix + 1, iy, Double.MAX_VALUE);
                double eU = ssd(ref[0], f0, w, h, x0, y0, sx, sy, ix, iy - 1, Double.MAX_VALUE);
                double eD = ssd(ref[0], f0, w, h, x0, y0, sx, sy, ix, iy + 1, Double.MAX_VALUE);
                float subx = parabola(eL, best, eR);
                float suby = parabola(eU, best, eD);
                int ti = ty * gw + tx;
                flow.dx[ti] = ix + subx;
                flow.dy[ti] = iy + suby;
            }
        });

        cleanup(flow, fgx, fgy);
        return flow;
    }

    private static float parabola(double em, double e0, double ep) {
        double den = em - 2 * e0 + ep;
        if (den <= 1e-9) return 0f;
        double off = 0.5 * (em - ep) / den;
        if (off > 0.5) off = 0.5;
        if (off < -0.5) off = -0.5;
        return (float) off;
    }

    /** Replace untextured tiles with neighbour estimates; reject outliers with a 3x3 median. */
    private void cleanup(Flow fl, int gx, int gy) {
        float[] ndx = fl.dx.clone(), ndy = fl.dy.clone();
        float[] bx = new float[9], by = new float[9];
        for (int ty = 0; ty < gh; ty++) {
            for (int tx = 0; tx < gw; tx++) {
                int i = ty * gw + tx;
                int n = 0;
                double sx = 0, sy = 0;
                int nt = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int x = tx + dx, y = ty + dy;
                        if (x < 0 || y < 0 || x >= gw || y >= gh) continue;
                        int j = y * gw + x;
                        if (!textured[j]) continue;
                        sx += fl.dx[j];
                        sy += fl.dy[j];
                        nt++;
                        if (Math.abs(dx) <= 1 && Math.abs(dy) <= 1) { bx[n] = fl.dx[j]; by[n] = fl.dy[j]; n++; }
                    }
                }
                if (!textured[i]) {
                    if (nt > 0) { ndx[i] = (float) (sx / nt); ndy[i] = (float) (sy / nt); }
                    else { ndx[i] = gx; ndy[i] = gy; }
                } else if (n >= 3) {
                    float mx = median(bx, n), my = median(by, n);
                    if (Math.abs(fl.dx[i] - mx) > 1.0f || Math.abs(fl.dy[i] - my) > 1.0f) {
                        ndx[i] = mx;
                        ndy[i] = my;
                    }
                }
            }
        }
        System.arraycopy(ndx, 0, fl.dx, 0, ndx.length);
        System.arraycopy(ndy, 0, fl.dy, 0, ndy.length);
        regularize(fl);
    }

    /**
     * Hand shake is mostly a smooth (affine) warp. Per-tile estimates carry ~0.1-0.2 px noise,
     * which blurs super-resolution. Fit an affine model robustly; tiles that agree with it within
     * 0.6 px snap to the model (precise), tiles that disagree (moving subject) keep their own flow.
     */
    private void regularize(Flow fl) {
        boolean[] use = textured.clone();
        double[] px = null, py = null;
        for (int iter = 0; iter < 3; iter++) {
            px = fitAffine(fl.dx, use);
            py = fitAffine(fl.dy, use);
            if (px == null || py == null) return;
            for (int ty = 0; ty < gh; ty++) for (int tx = 0; tx < gw; tx++) {
                int i = ty * gw + tx;
                if (!textured[i]) continue;
                double cx = tx * TILE + TILE * 0.5, cy = ty * TILE + TILE * 0.5;
                double ex = fl.dx[i] - (px[0] + px[1] * cx + px[2] * cy);
                double ey = fl.dy[i] - (py[0] + py[1] * cx + py[2] * cy);
                use[i] = ex * ex + ey * ey < 0.6 * 0.6 * (iter == 0 ? 4 : 1);
            }
        }
        for (int ty = 0; ty < gh; ty++) for (int tx = 0; tx < gw; tx++) {
            int i = ty * gw + tx;
            double cx = tx * TILE + TILE * 0.5, cy = ty * TILE + TILE * 0.5;
            float mx = (float) (px[0] + px[1] * cx + px[2] * cy);
            float my = (float) (py[0] + py[1] * cx + py[2] * cy);
            float ex = fl.dx[i] - mx, ey = fl.dy[i] - my;
            if (!textured[i] || ex * ex + ey * ey < 0.36f) { fl.dx[i] = mx; fl.dy[i] = my; }
        }
    }

    /** Least squares v = a + b*x + c*y over selected tiles. */
    private double[] fitAffine(float[] v, boolean[] use) {
        double s1 = 0, sx = 0, sy = 0, sxx = 0, sxy = 0, syy = 0, sv = 0, sxv = 0, syv = 0;
        int n = 0;
        for (int ty = 0; ty < gh; ty++) for (int tx = 0; tx < gw; tx++) {
            int i = ty * gw + tx;
            if (!use[i]) continue;
            double x = tx * TILE + TILE * 0.5, y = ty * TILE + TILE * 0.5, val = v[i];
            s1 += 1; sx += x; sy += y; sxx += x * x; sxy += x * y; syy += y * y;
            sv += val; sxv += x * val; syv += y * val;
            n++;
        }
        if (n < 6) return null;
        double[][] A = {{s1, sx, sy}, {sx, sxx, sxy}, {sy, sxy, syy}};
        double[] b = {sv, sxv, syv};
        return solve3(A, b);
    }

    private static double[] solve3(double[][] A, double[] b) {
        double det = A[0][0] * (A[1][1] * A[2][2] - A[1][2] * A[2][1])
                - A[0][1] * (A[1][0] * A[2][2] - A[1][2] * A[2][0])
                + A[0][2] * (A[1][0] * A[2][1] - A[1][1] * A[2][0]);
        if (Math.abs(det) < 1e-12) return null;
        double[] r = new double[3];
        for (int c = 0; c < 3; c++) {
            double[][] M = {A[0].clone(), A[1].clone(), A[2].clone()};
            for (int k = 0; k < 3; k++) M[k][c] = b[k];
            r[c] = (M[0][0] * (M[1][1] * M[2][2] - M[1][2] * M[2][1])
                    - M[0][1] * (M[1][0] * M[2][2] - M[1][2] * M[2][0])
                    + M[0][2] * (M[1][0] * M[2][1] - M[1][1] * M[2][0])) / det;
        }
        return r;
    }

    private static float median(float[] a, int n) {
        float[] c = Arrays.copyOf(a, n);
        Arrays.sort(c);
        return c[n / 2];
    }

    private static int[] globalSearch(float[] r, float[] f, int w, int h, int cx, int cy, int rad, int stride) {
        int mx = w / 8, my = h / 8;
        double best = Double.MAX_VALUE;
        int bx = cx, by = cy;
        for (int dy = -rad; dy <= rad; dy++) {
            for (int dx = -rad; dx <= rad; dx++) {
                int ox = cx + dx, oy = cy + dy;
                double s = 0;
                for (int y = my; y < h - my; y += stride) {
                    int yy = ImgUtil.clamp(y + oy, 0, h - 1);
                    for (int x = mx; x < w - mx; x += stride) {
                        int xx = ImgUtil.clamp(x + ox, 0, w - 1);
                        s += Math.abs(r[y * w + x] - f[yy * w + xx]);
                    }
                    if (s >= best) break;
                }
                if (s < best) { best = s; bx = ox; by = oy; }
            }
        }
        return new int[]{bx, by};
    }

    private static double ssd(float[] r, float[] f, int w, int h, int x0, int y0, int sx, int sy,
                              int ox, int oy, double bail) {
        double s = 0;
        boolean inside = x0 + ox >= 0 && y0 + oy >= 0 && x0 + sx + ox <= w && y0 + sy + oy <= h;
        for (int y = y0; y < y0 + sy; y++) {
            int rr = y * w;
            if (inside) {
                int fr = (y + oy) * w + ox;
                for (int x = x0; x < x0 + sx; x++) {
                    float d = r[rr + x] - f[fr + x];
                    s += d * d;
                }
            } else {
                int yy = ImgUtil.clamp(y + oy, 0, h - 1) * w;
                for (int x = x0; x < x0 + sx; x++) {
                    float d = r[rr + x] - f[yy + ImgUtil.clamp(x + ox, 0, w - 1)];
                    s += d * d;
                }
            }
            if (s >= bail) return s;
        }
        return s;
    }
}
