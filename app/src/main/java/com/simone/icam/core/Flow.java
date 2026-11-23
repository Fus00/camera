package com.simone.icam.core;

/**
 * Per-tile motion of one frame relative to the reference frame.
 * Convention: frame(p + d) ~= ref(p), with p in reference full-res pixel coordinates.
 */
public final class Flow {
    public final int tile, gw, gh;
    public final float[] dx, dy;

    public Flow(int tile, int gw, int gh) {
        this.tile = tile;
        this.gw = gw;
        this.gh = gh;
        this.dx = new float[gw * gh];
        this.dy = new float[gw * gh];
    }

    /** Identity flow (used for the reference frame). */
    public static Flow zero(int tile, int gw, int gh) {
        return new Flow(tile, gw, gh);
    }

    /** Bilinear interpolation between tile centres. Writes (dx, dy) into out[0..1]. */
    public void sample(float x, float y, float[] out) {
        float fx = (x - tile * 0.5f) / tile;
        float fy = (y - tile * 0.5f) / tile;
        if (fx < 0) fx = 0; else if (fx > gw - 1) fx = gw - 1;
        if (fy < 0) fy = 0; else if (fy > gh - 1) fy = gh - 1;
        int x0 = (int) fx, y0 = (int) fy;
        int x1 = x0 + 1 < gw ? x0 + 1 : x0, y1 = y0 + 1 < gh ? y0 + 1 : y0;
        float ax = fx - x0, ay = fy - y0;
        int i00 = y0 * gw + x0, i01 = y0 * gw + x1, i10 = y1 * gw + x0, i11 = y1 * gw + x1;
        float w00 = (1 - ax) * (1 - ay), w01 = ax * (1 - ay), w10 = (1 - ax) * ay, w11 = ax * ay;
        out[0] = dx[i00] * w00 + dx[i01] * w01 + dx[i10] * w10 + dx[i11] * w11;
        out[1] = dy[i00] * w00 + dy[i01] * w01 + dy[i10] * w10 + dy[i11] * w11;
    }
}
