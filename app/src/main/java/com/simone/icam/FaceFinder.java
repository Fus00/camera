package com.simone.icam;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.media.FaceDetector;

import com.simone.icam.core.YuvFrame;

/**
 * Finds the largest face with the built-in android.media.FaceDetector (no extra libraries).
 * The detector needs upright faces, so the small preview image is rotated like the final photo;
 * the result is mapped back to the sensor frame, normalised {left, top, right, bottom}.
 */
final class FaceFinder {
    private FaceFinder() {}

    static float[] find(YuvFrame f, int rotation) {
        try {
            int step = Math.max(1, f.w / 480);
            int sw = (f.w / step) & ~1, sh = (f.h / step) & ~1;
            int[] px = new int[sw * sh];
            for (int y = 0; y < sh; y++) {
                for (int x = 0; x < sw; x++) {
                    int v = f.y[(y * step) * f.w + x * step] & 0xFF;
                    px[y * sw + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
                }
            }
            Bitmap small = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888);
            if (rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                small = Bitmap.createBitmap(small, 0, 0, sw, sh, m, true);
            }
            int bw = small.getWidth() & ~1, bh = small.getHeight();
            Bitmap rgb = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565);
            new android.graphics.Canvas(rgb).drawBitmap(small, 0, 0, null);
            FaceDetector.Face[] faces = new FaceDetector.Face[4];
            int n = new FaceDetector(bw, bh, faces.length).findFaces(rgb, faces);
            FaceDetector.Face best = null;
            for (int i = 0; i < n; i++) {
                if (faces[i] == null || faces[i].confidence() < 0.3f) continue;
                if (best == null || faces[i].eyesDistance() > best.eyesDistance()) best = faces[i];
            }
            if (best == null) return null;
            PointF mid = new PointF();
            best.getMidPoint(mid);
            float ed = best.eyesDistance();
            // face box around the eyes, in the rotated (upright) image, normalised
            float cx = mid.x / bw, cy = (mid.y + 0.45f * ed) / bh;
            float hwN = 1.15f * ed / bw, hhN = 1.5f * ed / bh;
            // map centre and half-sizes back to the sensor orientation
            float u, v, hu, hv;
            switch (rotation) {
                case 90:  u = cy;     v = 1 - cx; hu = hhN; hv = hwN; break;
                case 180: u = 1 - cx; v = 1 - cy; hu = hwN; hv = hhN; break;
                case 270: u = 1 - cy; v = cx;     hu = hhN; hv = hwN; break;
                default:  u = cx;     v = cy;     hu = hwN; hv = hhN; break;
            }
            return new float[]{clamp01(u - hu), clamp01(v - hv), clamp01(u + hu), clamp01(v + hv)};
        } catch (Throwable t) {
            return null;
        }
    }

    private static float clamp01(float x) {
        return x < 0 ? 0 : (x > 1 ? 1 : x);
    }
}
