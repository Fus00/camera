package com.simone.icam.core;

import java.util.List;

/** Burst -> one finished photo. Pure Java: runs on Android and on a desktop JVM for testing. */
public final class Pipeline {
    private Pipeline() {}

    public static final class Params {
        /** Linear upscale of the output vs the sensor frames (1.0 = no super-resolution). */
        public float scale = 1.25f;
        /** Kernel width (input pixels) of the super-resolution merge. */
        public float kernelSigma = 0.4f;
        public float sharpen = 0.75f;
        /** Ghost rejection strength (0 = plain average). */
        public float robustness = 1f;
        /** Structure-adaptive kernels. Tested in simulation: no gain over isotropic, so off. */
        public boolean anisotropic = false;
        /** Face box in the sensor frame, normalised {left, top, right, bottom}, or null. */
        public float[] face = null;
        /** Exposure compensation used during capture (e.g. -1 EV); undone here with highlight roll-off. */
        public float evComp = 0f;
        /** Rendering style (tone, face exposure, skin colour, colour LUT). */
        public Look look = Look.iphone();
    }

    public interface Progress {
        void update(String stage, float fraction);
    }

    public static final class Output {
        public final int[] argb;
        public final int w, h;
        /** Diagnostics shown in the app after each photo. */
        public int framesIn, framesUsed;
        public float noise, noiseC;
        Output(int[] argb, int w, int h) { this.argb = argb; this.w = w; this.h = h; }
    }

    /** Note: clears the (mutable) frames list once merged, to free memory on the phone. */
    public static Output process(List<YuvFrame> frames, Params p, Progress cb) {
        if (cb == null) cb = (s, f) -> {};
        int n = frames.size();
        final int framesIn = n;
        YuvFrame first = frames.get(0);
        int w = first.w, h = first.h;

        // reference = sharpest frame
        cb.update("Scelgo il fotogramma più nitido", 0.02f);
        double[] sharp = new double[n];
        double bestS = -1;
        for (int i = 0; i < n; i++) {
            sharp[i] = ImgUtil.sharpness(frames.get(i).y, w, h);
            if (sharp[i] > bestS) bestS = sharp[i];
        }
        // drop frames blurred by hand shake (keep at least 3)
        List<YuvFrame> kept = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) if (sharp[i] >= 0.6 * bestS) kept.add(frames.get(i));
        if (kept.size() < Math.min(3, n)) {
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Double.compare(sharp[b], sharp[a]));
            kept.clear();
            for (int i = 0; i < Math.min(3, n); i++) kept.add(frames.get(order[i]));
        }
        int refIdx = 0;
        double rs = -1;
        for (int i = 0; i < kept.size(); i++) {
            double s = ImgUtil.sharpness(kept.get(i).y, w, h);
            if (s > rs) { rs = s; refIdx = i; }
        }
        try { frames.clear(); } catch (UnsupportedOperationException ignored) { }
        frames = kept;
        n = frames.size();
        float[] refF = ImgUtil.toFloat(frames.get(refIdx).y);
        float noise = ImgUtil.estimateNoise(refF, w, h);

        // alignment
        Aligner al = new Aligner(refF, w, h, noise);
        Flow[] flows = new Flow[n];
        for (int i = 0; i < n; i++) {
            cb.update("Allineo " + (i + 1) + "/" + n, 0.05f + 0.45f * i / n);
            flows[i] = (i == refIdx) ? Flow.zero(Aligner.TILE, al.gridW(), al.gridH()) : al.align(frames.get(i).y);
        }

        // merge
        int W = Math.round(w * p.scale), H = Math.round(h * p.scale);
        W -= W % 2;
        H -= H % 2;
        cb.update("Super-risoluzione", 0.5f);
        float[] Y = Merger.mergeLuma(frames, flows, refIdx, refF, W, H, p.kernelSigma, noise, p.robustness, p.anisotropic);
        cb.update("Colore", 0.8f);
        YuvFrame rf = frames.get(refIdx);
        float noiseC = Math.max(ImgUtil.estimateNoise(rf.u, w / 2, h / 2), ImgUtil.estimateNoise(rf.v, w / 2, h / 2));
        float[][] uv = Merger.mergeChroma(frames, flows, refIdx, noiseC);
        // colour noise ("red/green/blue dots"): edge-aware smoothing of chroma guided by the merged luma
        cb.update("Pulizia colore", 0.84f);
        int cw = w / 2, chh = h / 2;
        float[] guide = new float[cw * chh];
        for (int y = 0; y < chh; y++) for (int x = 0; x < cw; x++)
            guide[y * cw + x] = ImgUtil.bilinear(Y, W, H, (x + 0.5f) * 2f * W / w - 0.5f, (y + 0.5f) * 2f * H / h - 0.5f);
        float residualC = noiseC / (float) Math.sqrt(Math.max(1, n * 0.6));
        float epsC = 9f * noise * noise / Math.max(1, n * 0.6f) + 16f;
        int rC = residualC > 2.5f ? 6 : (residualC > 1.2f ? 4 : 3);
        uv[0] = ImgUtil.guided(guide, uv[0], cw, chh, rC, epsC);
        uv[1] = ImgUtil.guided(guide, uv[1], cw, chh, rC, epsC);
        refF = null;
        // frames are no longer needed: free memory before the final (largest) stage
        try { frames.clear(); } catch (UnsupportedOperationException ignored) { }

        cb.update("Tono e colori", 0.88f);
        float noiseAfter = noise / (float) Math.sqrt(Math.max(1, n * 0.6));
        int[] argb = Finisher.finish(Y, W, H, uv[0], uv[1], w / 2, h / 2, p, noiseAfter);
        cb.update("Fatto", 1f);
        Output o = new Output(argb, W, H);
        o.framesIn = framesIn;
        o.framesUsed = n;
        o.noise = noise;
        o.noiseC = noiseC;
        return o;
    }

    /** Merge only (no tone/colour), returned as luma. Used for objective tests. */
    public static float[] mergeLumaOnly(List<YuvFrame> frames, Params p, int[] outSize) {
        YuvFrame first = frames.get(0);
        int w = first.w, h = first.h, n = frames.size();
        int refIdx = 0;
        float[] refF = ImgUtil.toFloat(frames.get(refIdx).y);
        float noise = ImgUtil.estimateNoise(refF, w, h);
        Aligner al = new Aligner(refF, w, h, noise);
        Flow[] flows = new Flow[n];
        for (int i = 0; i < n; i++)
            flows[i] = (i == refIdx) ? Flow.zero(Aligner.TILE, al.gridW(), al.gridH()) : al.align(frames.get(i).y);
        int W = outSize[0], H = outSize[1];
        return Merger.mergeLuma(frames, flows, refIdx, refF, W, H, p.kernelSigma, noise, p.robustness, p.anisotropic);
    }
}
