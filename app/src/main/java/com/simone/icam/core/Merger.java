package com.simone.icam.core;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Multi-frame merge.
 * Luma: super-resolution by kernel regression. Every aligned frame contributes its real pixel
 * samples at their (sub-pixel) positions on a finer output grid. Hand shake gives each frame a
 * different sub-pixel offset, so the frames together sample the scene more densely than one frame.
 * Robustness: a frame's contribution is reduced where it disagrees with the reference
 * (moving subject, misalignment) beyond what noise and local detail explain.
 * Chroma: robust temporal average at native chroma resolution.
 */
public final class Merger {
    private Merger() {}

    private static final int TABLE = 1024;
    private static final float N2MAX = 12f;
    public static float K_SHRINK = 0.35f, K_STRETCH = 1.2f, K_FLAT = 0.6f;
    /** Kernel widening in flat areas (0 = off). */
    public static float FLAT_WIDEN = 1.0f;

    /** Isotropic Gaussian kernel regression (kept for comparison/tests). */
    public static float[] mergeLuma(List<YuvFrame> frames, Flow[] flows, int refIdx, float[] refF,
                                    int outW, int outH, float sigma, float noise, float robustness) {
        return mergeLuma(frames, flows, refIdx, refF, outW, outH, sigma, noise, robustness, false);
    }

    /**
     * Kernel-regression super-resolution. With anisotropic=true each output pixel uses a kernel
     * shaped by the local structure tensor of the reference (Wronski et al. 2019, simplified):
     * narrow across edges (sharper edges), long along edges and wide in flat areas (less noise).
     */
    public static float[] mergeLuma(List<YuvFrame> frames, Flow[] flows, int refIdx, float[] refF,
                                    int outW, int outH, float sigma, float noise, float robustness,
                                    boolean anisotropic) {
        final YuvFrame rf = frames.get(refIdx);
        final int w = rf.w, h = rf.h;
        final float sx = (float) w / outW, sy = (float) h / outH;
        final float[] kern = new float[TABLE + 1];
        for (int i = 0; i <= TABLE; i++) kern[i] = (float) Math.exp(-0.5 * i * N2MAX / TABLE);
        final float kScale = TABLE / N2MAX;
        final float base2 = 9f * noise * noise + 4f;
        final float rob = robustness;
        final int n = frames.size();
        final float[] out = new float[outW * outH];

        // structure tensor at half resolution
        final int hw = w / 2, hh = h / 2;
        final float[] sxx, sxy, syy;
        if (anisotropic) {
            float[] half = ImgUtil.down2(refF, w, h);
            float[] ixx = new float[hw * hh], ixy = new float[hw * hh], iyy = new float[hw * hh];
            IntStream.range(1, hh - 1).parallel().forEach(y -> {
                for (int x = 1; x < hw - 1; x++) {
                    int i = y * hw + x;
                    float gx = 0.5f * (half[i + 1] - half[i - 1]);
                    float gy = 0.5f * (half[i + hw] - half[i - hw]);
                    ixx[i] = gx * gx; ixy[i] = gx * gy; iyy[i] = gy * gy;
                }
            });
            sxx = ImgUtil.blur3(ImgUtil.blur3(ixx, hw, hh), hw, hh);
            sxy = ImgUtil.blur3(ImgUtil.blur3(ixy, hw, hh), hw, hh);
            syy = ImgUtil.blur3(ImgUtil.blur3(iyy, hw, hh), hw, hh);
        } else {
            sxx = sxy = syy = null;
        }
        final float flatT = 1.2f * noise + 1.5f;

        IntStream.range(0, outH).parallel().forEach(oy -> {
            float[] d = new float[2];
            float ry = (oy + 0.5f) * sy - 0.5f;
            for (int ox = 0; ox < outW; ox++) {
                float rx = (ox + 0.5f) * sx - 0.5f;
                float refVal = ImgUtil.bilinear(refF, w, h, rx, ry);
                int px = ImgUtil.clamp(Math.round(rx), 1, w - 2), py = ImgUtil.clamp(Math.round(ry), 1, h - 2);
                int pi = py * w + px;
                float gxv = 0.5f * (refF[pi + 1] - refF[pi - 1]);
                float gyv = 0.5f * (refF[pi + w] - refF[pi - w]);
                float tol2 = base2 + 0.35f * (gxv * gxv + gyv * gyv);
                float inv2 = rob > 0 ? rob / tol2 : 0f;

                // kernel shape: e1 = across-edge direction, inverse variances ia (across), ib (along)
                float e1x = 1f, e1y = 0f, ia, ib;
                if (sxx != null) {
                    int hi = ImgUtil.clamp(Math.round(ry * 0.5f - 0.25f), 0, hh - 1) * hw
                            + ImgUtil.clamp(Math.round(rx * 0.5f - 0.25f), 0, hw - 1);
                    float a = sxx[hi], b = sxy[hi], c = syy[hi];
                    float tr = a + c;
                    float disc = (float) Math.sqrt(0.25f * (a - c) * (a - c) + b * b);
                    float l1 = 0.5f * tr + disc, l2 = 0.5f * tr - disc;
                    float vx = l1 - c, vy = b;
                    float nrm = vx * vx + vy * vy;
                    if (nrm > 1e-12f) { float r = 1f / (float) Math.sqrt(nrm); e1x = vx * r; e1y = vy * r; }
                    float anis = tr > 1e-6f ? (l1 - l2) / tr : 0f;
                    float flat = 1f - (float) Math.sqrt(Math.max(l1, 0f)) / flatT;
                    if (flat < 0) flat = 0;
                    float as = (float) Math.sqrt(anis) * (1f - flat);
                    float sa = sigma * (1f - K_SHRINK * as) * (1f + K_FLAT * flat);
                    float sb = sigma * (1f + K_STRETCH * as) * (1f + K_FLAT * flat);
                    ia = 1f / (sa * sa);
                    ib = 1f / (sb * sb);
                } else {
                    // wider kernel where the reference is flat (walls, skin): averages away residual noise
                    float g = (float) Math.sqrt(gxv * gxv + gyv * gyv);
                    float flat = 1f - g / (3f * noise + 2f);
                    if (flat < 0) flat = 0;
                    float se = sigma * (1f + FLAT_WIDEN * flat);
                    ia = ib = 1f / (se * se);
                }

                float sum = 0, wsum = 0;
                for (int f = 0; f < n; f++) {
                    byte[] fy = frames.get(f).y;
                    float qx, qy, wr;
                    if (f == refIdx) {
                        qx = rx; qy = ry; wr = 1.2f;
                    } else {
                        flows[f].sample(rx, ry, d);
                        qx = rx + d[0];
                        qy = ry + d[1];
                        float fv = ImgUtil.bilinear(fy, w, h, qx, qy);
                        float diff = fv - refVal;
                        float t = diff * diff * inv2;
                        wr = 1f / (1f + t * t);
                        if (wr < 0.02f) continue;
                    }
                    int cx = Math.round(qx), cy = Math.round(qy);
                    for (int ny = cy - 1; ny <= cy + 1; ny++) {
                        if (ny < 0 || ny >= h) continue;
                        float ddy = ny - qy;
                        int row = ny * w;
                        for (int nx = cx - 1; nx <= cx + 1; nx++) {
                            if (nx < 0 || nx >= w) continue;
                            float ddx = nx - qx;
                            float pa = ddx * e1x + ddy * e1y;
                            float pb = -ddx * e1y + ddy * e1x;
                            float n2 = pa * pa * ia + pb * pb * ib;
                            if (n2 >= N2MAX) continue;
                            float wk = kern[(int) (n2 * kScale)] * wr;
                            sum += wk * (fy[row + nx] & 0xFF);
                            wsum += wk;
                        }
                    }
                }
                out[oy * outW + ox] = wsum > 1e-6f ? sum / wsum : refVal;
            }
        });
        return out;
    }

    /** Returns {U, V} merged at native chroma resolution (w/2 x h/2). */
    public static float[][] mergeChroma(List<YuvFrame> frames, Flow[] flows, int refIdx, float chromaNoise) {
        final YuvFrame rf = frames.get(refIdx);
        final int cw = rf.cw(), ch = rf.ch();
        final float[] U = new float[cw * ch], V = new float[cw * ch];
        final float tol = 3f * chromaNoise + 4f;
        final float inv2 = 1f / (tol * tol);
        final int n = frames.size();
        IntStream.range(0, ch).parallel().forEach(cy -> {
            float[] d = new float[2];
            for (int cx = 0; cx < cw; cx++) {
                int ci = cy * cw + cx;
                float ru = rf.u[ci] & 0xFF, rv = rf.v[ci] & 0xFF;
                float su = ru * 1.2f, sv = rv * 1.2f, ws = 1.2f;
                float fx = (cx + 0.5f) * 2f - 0.5f, fyy = (cy + 0.5f) * 2f - 0.5f;
                for (int f = 0; f < n; f++) {
                    if (f == refIdx) continue;
                    flows[f].sample(fx, fyy, d);
                    YuvFrame fr = frames.get(f);
                    float qx = cx + d[0] * 0.5f, qy = cy + d[1] * 0.5f;
                    float u = ImgUtil.bilinear(fr.u, cw, ch, qx, qy);
                    float v = ImgUtil.bilinear(fr.v, cw, ch, qx, qy);
                    float du = u - ru, dv = v - rv;
                    float t = (du * du + dv * dv) * inv2;
                    float wr = 1f / (1f + t * t);
                    su += wr * u;
                    sv += wr * v;
                    ws += wr;
                }
                U[ci] = su / ws;
                V[ci] = sv / ws;
            }
        });
        return new float[][]{U, V};
    }
}
