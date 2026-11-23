package com.simone.icam.core;

import java.util.stream.IntStream;

/**
 * Final rendering: detail sharpening, local tone mapping (recovers the burst's deliberate
 * under-exposure, lifts shadows), face-priority exposure, highlight shoulder or high-key knee,
 * colour look, adaptive skin colour, ARGB output.
 */
public final class Finisher {
    private Finisher() {}

    /** Everything the per-pixel renderer needs. */
    private static final class Ctx {
        float[] Ys, base, U, V;
        int W, H, lwid, lhei, cw, ch;
        float sxl, syl, sxc, syc;
        float expGain, globalGain = 1f, anchor, c;
        boolean highKey;
        float invWhite = 1f;
        float fg = 1f, fcx, fcy, frx = 1, fry = 1;
        ColorLut lut;

        /** Renders pixel (x, y) into rgb[0..2], display-referred 0..1 (before skin adjustment). */
        void render(int x, int y, float[] rgb) {
            int i = y * W + x;
            float yv = Ys[i];
            float b = ImgUtil.bilinear(base, lwid, lhei, (x + 0.5f) * sxl - 0.5f, (y + 0.5f) * syl - 0.5f);
            float yl = lin(yv) * expGain * globalGain;
            float gain = b < anchor ? (float) Math.exp((anchor - b) * (1f - c)) : 1f;
            if (fg > 1f) {
                float ex = (x - fcx) / frx, ey = (y - fcy) / fry;
                float e2 = ex * ex + ey * ey;
                if (e2 < 9f) gain *= 1f + (fg - 1f) * (float) Math.exp(-e2);
            }
            float y2 = yl * gain * invWhite;
            float y3;
            if (highKey) {
                y3 = y2 <= 0.8f ? y2 : 0.8f + 0.2f * (float) Math.tanh((y2 - 0.8f) / 0.2f);
            } else {
                y3 = y2 < 0.6f ? y2 : 0.6f + 0.4f * (1f - (float) Math.exp(-(y2 - 0.6f) / 0.4f));
            }
            float ynew = 255f * (float) Math.pow(Math.max(y3, 0f), 1.0 / 2.2);
            float ratio = ynew / Math.max(yv, 0.5f);
            if (ratio > 6f) ratio = 6f;
            float cy = (y + 0.5f) * syc - 0.5f, cx = (x + 0.5f) * sxc - 0.5f;
            float u = ImgUtil.bilinear(U, cw, ch, cx, cy) - 128f;
            float v = ImgUtil.bilinear(V, cw, ch, cx, cy) - 128f;
            rgb[0] = (yv + 1.402f * v) * ratio / 255f;
            rgb[1] = (yv - 0.344136f * u - 0.714136f * v) * ratio / 255f;
            rgb[2] = (yv + 1.772f * u) * ratio / 255f;
            if (lut != null) lut.apply(rgb);
        }
    }

    public static int[] finish(float[] Y, int W, int H, float[] U, float[] V, int cw, int ch,
                               Pipeline.Params p, float noiseAfterMerge) {
        final Look look = p.look;
        // 1) sharpen luma (unsharp mask with a soft noise threshold)
        final float[] blur = ImgUtil.blur3(ImgUtil.blur3(Y, W, H), W, H); // ~gaussian sigma 1
        final float amt = p.sharpen;
        final float thr = Math.max(0.5f, noiseAfterMerge * 0.8f);
        final float[] Ys = new float[W * H];
        IntStream.range(0, H).parallel().forEach(y -> {
            for (int x = 0, i = y * W; x < W; x++, i++) {
                float d = Y[i] - blur[i];
                float ad = Math.abs(d);
                float k = ad < thr ? ad / thr : 1f;
                Ys[i] = Y[i] + amt * d * k * k;
            }
        });

        // 2) local tone map base layer: guided filter on log luminance at low resolution
        final Ctx cx = new Ctx();
        cx.Ys = Ys; cx.U = U; cx.V = V; cx.W = W; cx.H = H; cx.cw = cw; cx.ch = ch;
        cx.expGain = (float) Math.pow(2.0, -p.evComp);
        int f = Math.max(1, Math.max(W, H) / 512);
        cx.lwid = W / f;
        cx.lhei = H / f;
        float[] logL = new float[cx.lwid * cx.lhei];
        for (int y = 0; y < cx.lhei; y++) {
            for (int x = 0; x < cx.lwid; x++) {
                double s = 0;
                for (int yy = 0; yy < f; yy++) for (int xx = 0; xx < f; xx++) {
                    s += lin(Ys[(y * f + yy) * W + x * f + xx]) * cx.expGain;
                }
                logL[y * cx.lwid + x] = (float) Math.log(s / (f * f) + 1e-4);
            }
        }
        cx.base = guided(logL, cx.lwid, cx.lhei, 10, 0.12f);
        cx.anchor = (float) Math.log(0.18);
        cx.c = look.localContrast;
        cx.highKey = look.highKey;
        cx.invWhite = 1f / look.whitePoint;
        cx.lut = look.lut;
        cx.sxl = (float) cx.lwid / W;
        cx.syl = (float) cx.lhei / H;
        cx.sxc = (float) cw / W;
        cx.syc = (float) ch / H;

        final float[] fr = p.face;
        // 3a) backlit face lift (natural look)
        if (fr != null && look.backlitLift) {
            double fs = 0, ss = 0;
            int fn = 0, sn = 0;
            float[] all = new float[cx.lwid * cx.lhei];
            for (int y = 0; y < cx.lhei; y++) for (int x = 0; x < cx.lwid; x++) {
                int i = y * cx.lwid + x;
                float bb = cx.base[i];
                float t = (float) Math.exp(logL[i]) * (bb < cx.anchor ? (float) Math.exp((cx.anchor - bb) * (1f - cx.c)) : 1f);
                all[sn++] = t;
                ss += t;
                float nx = (x + 0.5f) / cx.lwid, ny = (y + 0.5f) / cx.lhei;
                if (nx >= fr[0] && nx <= fr[2] && ny >= fr[1] && ny <= fr[3]) { fs += t; fn++; }
            }
            java.util.Arrays.sort(all);
            float p90 = all[(int) (all.length * 0.9)];
            if (fn > 4) {
                float faceL = (float) (fs / fn), sceneL = (float) (ss / sn);
                if (p90 > 0.5f && faceL < 0.5f * sceneL) {
                    cx.fg = ImgUtil.clampf(0.8f * sceneL / Math.max(faceL, 1e-4f), 1f, 2.2f);
                    cx.fcx = 0.5f * (fr[0] + fr[2]) * W;
                    cx.fcy = 0.5f * (fr[1] + fr[3]) * H;
                    cx.frx = Math.max(1f, 0.5f * (fr[2] - fr[0]) * W * 1.4f);
                    cx.fry = Math.max(1f, 0.5f * (fr[3] - fr[1]) * H * 1.6f);
                }
            }
        }

        // 3b) face-priority exposure: bring the face to the look's target brightness
        if (fr != null && look.faceTarget > 0) {
            for (int it = 0; it < 3; it++) {
                float m = faceStats(cx, fr, null);
                if (m <= 1f) break;
                float step = (float) Math.pow(look.faceTarget / m, 2.2);
                cx.globalGain = ImgUtil.clampf(cx.globalGain * step, 0.4f, 4f);
            }
        }

        // 3c) adaptive skin colour: measure the face's skin, pull it to the look's target chromaticity
        float kr = 1f, kb = 1f, sRG = 0f, sBG = 0f;
        if (fr != null && look.skinRG > 0) {
            float[] skin = new float[3];
            faceStats(cx, fr, skin);
            if (skin[1] > 0.02f) {
                sRG = skin[0] / skin[1];
                sBG = skin[2] / skin[1];
                kr = 1f + (ImgUtil.clampf(look.skinRG / sRG, 0.85f, 1.2f) - 1f) * look.skinStrength;
                kb = 1f + (ImgUtil.clampf(look.skinBG / sBG, 0.85f, 1.15f) - 1f) * look.skinStrength;
            }
        }
        final float fkr = kr, fkb = kb, fRG = sRG, fBG = sBG;
        final boolean doSkin = Math.abs(kr - 1f) > 0.005f || Math.abs(kb - 1f) > 0.005f;

        final int[] out = new int[W * H];
        IntStream.range(0, H).parallel().forEach(y -> {
            float[] rgb = new float[3];
            for (int x = 0; x < W; x++) {
                cx.render(x, y, rgb);
                if (doSkin) skinAdjust(rgb, fRG, fBG, fkr, fkb);
                int R = ImgUtil.clamp(Math.round(rgb[0] * 255f), 0, 255);
                int G = ImgUtil.clamp(Math.round(rgb[1] * 255f), 0, 255);
                int B = ImgUtil.clamp(Math.round(rgb[2] * 255f), 0, 255);
                out[y * W + x] = 0xFF000000 | (R << 16) | (G << 8) | B;
            }
        });
        return out;
    }

    /**
     * Mean display luma (0..255) over the inner 70% of the face box. If skinOut != null, also
     * returns the mean RGB (0..1) of skin-coloured pixels there.
     */
    private static float faceStats(Ctx cx, float[] fr, float[] skinOut) {
        float mx = 0.5f * (fr[0] + fr[2]), my = 0.5f * (fr[1] + fr[3]);
        float hx = 0.35f * (fr[2] - fr[0]), hy = 0.35f * (fr[3] - fr[1]);
        int x0 = ImgUtil.clamp((int) ((mx - hx) * cx.W), 0, cx.W - 1), x1 = ImgUtil.clamp((int) ((mx + hx) * cx.W), 0, cx.W - 1);
        int y0 = ImgUtil.clamp((int) ((my - hy) * cx.H), 0, cx.H - 1), y1 = ImgUtil.clamp((int) ((my + hy) * cx.H), 0, cx.H - 1);
        int step = Math.max(1, Math.max(x1 - x0, y1 - y0) / 120);
        float[] rgb = new float[3];
        double sl = 0, sr = 0, sg = 0, sb = 0;
        int n = 0, ns = 0;
        for (int y = y0; y <= y1; y += step) {
            for (int x = x0; x <= x1; x += step) {
                cx.render(x, y, rgb);
                float r = ImgUtil.clampf(rgb[0], 0, 1), g = ImgUtil.clampf(rgb[1], 0, 1), b = ImgUtil.clampf(rgb[2], 0, 1);
                float l = 255f * (0.299f * r + 0.587f * g + 0.114f * b);
                sl += l;
                n++;
                if (r > g && g > b && l > 40 && l < 235 && (r - b) * 255f > 15) { sr += r; sg += g; sb += b; ns++; }
            }
        }
        if (skinOut != null) {
            if (ns > 20) { skinOut[0] = (float) (sr / ns); skinOut[1] = (float) (sg / ns); skinOut[2] = (float) (sb / ns); }
            else { skinOut[0] = skinOut[1] = skinOut[2] = 0f; }
        }
        return n > 0 ? (float) (sl / n) : 0f;
    }

    /** Applies the skin colour correction to pixels whose colour is close to the measured skin. */
    private static void skinAdjust(float[] rgb, float sRG, float sBG, float kr, float kb) {
        float r = rgb[0], g = rgb[1], b = rgb[2];
        if (g < 0.03f) return;
        float rg = r / g, bg = b / g;
        float d1 = (rg - sRG) / 0.22f, d2 = (bg - sBG) / 0.12f;
        float d = d1 * d1 + d2 * d2;
        if (d > 6f) return;
        float l = 0.299f * r + 0.587f * g + 0.114f * b;
        float gate = l < 0.12f ? l / 0.12f : (l > 0.95f ? Math.max(0f, (1f - l) / 0.05f) : 1f);
        float w = (float) Math.exp(-d) * gate;
        float nr = r * (1f + (kr - 1f) * w), nb = b * (1f + (kb - 1f) * w);
        float l2 = 0.299f * nr + 0.587f * g + 0.114f * nb;
        float s = l2 > 1e-5f ? l / l2 : 1f;
        rgb[0] = nr * s;
        rgb[1] = g * s;
        rgb[2] = nb * s;
    }

    private static float lin(float v) {
        float n = v / 255f;
        if (n <= 0f) return 0f;
        return (float) Math.pow(n, 2.2);
    }

    /** Self-guided filter (He et al.), edge-preserving smoothing. */
    static float[] guided(float[] I, int w, int h, int r, float eps) {
        float[] mean = ImgUtil.box(I, w, h, r);
        float[] sq = new float[I.length];
        for (int i = 0; i < I.length; i++) sq[i] = I[i] * I[i];
        float[] meanSq = ImgUtil.box(sq, w, h, r);
        float[] a = new float[I.length], b = new float[I.length];
        for (int i = 0; i < I.length; i++) {
            float var = meanSq[i] - mean[i] * mean[i];
            a[i] = var / (var + eps);
            b[i] = mean[i] - a[i] * mean[i];
        }
        float[] ma = ImgUtil.box(a, w, h, r), mb = ImgUtil.box(b, w, h, r);
        float[] q = new float[I.length];
        for (int i = 0; i < I.length; i++) q[i] = ma[i] * I[i] + mb[i];
        return q;
    }
}
