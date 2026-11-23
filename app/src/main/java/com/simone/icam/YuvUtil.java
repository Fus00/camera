package com.simone.icam;

import android.graphics.Bitmap;
import android.media.Image;

import com.simone.icam.core.ImgUtil;
import com.simone.icam.core.YuvFrame;

import java.nio.ByteBuffer;

/** Android-specific conversions between camera Images, the core YuvFrame and Bitmaps. */
final class YuvUtil {
    private YuvUtil() {}

    /** Copies a YUV_420_888 Image into planar arrays (handles any row/pixel stride). */
    static YuvFrame fromImage(Image img) {
        int w = img.getWidth() & ~1, h = img.getHeight() & ~1;
        Image.Plane[] p = img.getPlanes();
        byte[] y = new byte[w * h];
        copyPlane(p[0], w, h, y);
        int cw = w / 2, ch = h / 2;
        byte[] u = new byte[cw * ch], v = new byte[cw * ch];
        copyPlane(p[1], cw, ch, u);
        copyPlane(p[2], cw, ch, v);
        return new YuvFrame(w, h, y, u, v);
    }

    private static void copyPlane(Image.Plane plane, int w, int h, byte[] out) {
        ByteBuffer buf = plane.getBuffer();
        int rs = plane.getRowStride(), ps = plane.getPixelStride();
        int base = buf.position();
        if (ps == 1) {
            for (int row = 0; row < h; row++) {
                buf.position(base + row * rs);
                buf.get(out, row * w, w);
            }
        } else {
            byte[] line = new byte[rs];
            for (int row = 0; row < h; row++) {
                int start = base + row * rs;
                int len = Math.min(rs, buf.limit() - start);
                buf.position(start);
                buf.get(line, 0, len);
                int o = row * w;
                for (int x = 0; x < w; x++) out[o + x] = line[x * ps];
            }
        }
        buf.position(base);
    }

    /** Plain conversion with no processing (for A/B comparison with the burst result). */
    static Bitmap toBitmap(YuvFrame f) {
        int w = f.w, h = f.h, cw = f.cw();
        int[] px = new int[w * h];
        for (int yy = 0; yy < h; yy++) {
            for (int x = 0; x < w; x++) {
                float Y = f.y[yy * w + x] & 0xFF;
                int ci = (yy / 2) * cw + x / 2;
                float U = (f.u[ci] & 0xFF) - 128f, V = (f.v[ci] & 0xFF) - 128f;
                int r = ImgUtil.clamp(Math.round(Y + 1.402f * V), 0, 255);
                int g = ImgUtil.clamp(Math.round(Y - 0.344136f * U - 0.714136f * V), 0, 255);
                int b = ImgUtil.clamp(Math.round(Y + 1.772f * U), 0, 255);
                px[yy * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }
}
