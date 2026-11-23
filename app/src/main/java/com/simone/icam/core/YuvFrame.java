package com.simone.icam.core;

/**
 * One camera frame in planar YUV 4:2:0 (full range, BT.601).
 * y: w*h bytes; u, v: (w/2)*(h/2) bytes each.
 */
public final class YuvFrame {
    public final int w, h;
    public final byte[] y, u, v;

    public YuvFrame(int w, int h, byte[] y, byte[] u, byte[] v) {
        this.w = w;
        this.h = h;
        this.y = y;
        this.u = u;
        this.v = v;
    }

    public int cw() { return w / 2; }
    public int ch() { return h / 2; }
}
