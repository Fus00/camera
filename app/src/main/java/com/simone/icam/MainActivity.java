package com.simone.icam;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.simone.icam.core.Look;
import com.simone.icam.core.Pipeline;
import com.simone.icam.core.YuvFrame;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 1;
    /** Output target: 12 MP like iPhone 12/13/14 (front 8 MP -> 4032x3024; rear 12 MP stays 12 MP). */
    private static final long MAX_OUTPUT_PIXELS = 12_200_000L;
    private static final float MAX_SCALE = 1.25f;

    private TextureView preview;
    private TextView status;
    private Button shutter, switchBtn, mirrorBtn, framesBtn, styleBtn;
    private ImageView thumb;

    private HandlerThread camThread;
    private Handler camHandler;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder previewBuilder;
    private CameraCharacteristics chars;
    private Size captureSize, previewSize;

    private boolean useFront = true;
    private boolean mirrorSelfie = false;
    private int styleIdx = 0; // 0 iPhone, 1 Luminoso, 2 Sera calda
    private static final String[] STYLE_NAMES = {"iPhone", "Luminoso", "Sera calda"};
    private int burstCount = 8;
    private volatile boolean busy = false;
    private int deviceOrientation = 0;
    private OrientationEventListener orientationListener;
    private Uri lastUri;

    private final List<YuvFrame> collected = Collections.synchronizedList(new ArrayList<>());
    private volatile int expected = 0;
    private float appliedEv = 0f;
    private volatile int lastIso = 0;
    private int usedIso = 0;
    private int captureRotation = 0;
    private boolean captureFront = true;

    // ------------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                deviceOrientation = ((o + 45) / 90 * 90) % 360;
            }
        };
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new TextureView(this);
        root.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setPadding(dp(14), dp(10), dp(14), dp(10));
        status.setBackgroundColor(0x88000000);
        status.setText("iPhoneCam");
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        slp.topMargin = dp(36);
        root.addView(status, slp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xCC000000);
        bottom.setPadding(dp(8), dp(8), dp(8), dp(20));

        LinearLayout opts = new LinearLayout(this);
        opts.setGravity(Gravity.CENTER);
        mirrorBtn = smallButton("Specchio: no");
        framesBtn = smallButton("8 scatti");
        styleBtn = smallButton("Stile: iPhone");
        opts.addView(mirrorBtn);
        opts.addView(framesBtn);
        opts.addView(styleBtn);
        bottom.addView(opts);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, 0);

        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(0xFF222222);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(56), dp(56));
        tlp.leftMargin = dp(24);
        row.addView(thumb, tlp);

        View sp1 = new View(this);
        row.addView(sp1, new LinearLayout.LayoutParams(0, 1, 1f));

        shutter = new Button(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.WHITE);
        ring.setStroke(dp(5), 0xFF888888);
        shutter.setBackground(ring);
        row.addView(shutter, new LinearLayout.LayoutParams(dp(76), dp(76)));

        View sp2 = new View(this);
        row.addView(sp2, new LinearLayout.LayoutParams(0, 1, 1f));

        switchBtn = smallButton("Gira");
        LinearLayout.LayoutParams swp = new LinearLayout.LayoutParams(dp(76), dp(56));
        swp.rightMargin = dp(16);
        row.addView(switchBtn, swp);
        bottom.addView(row);

        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);

        shutter.setOnClickListener(v -> takeBurst());
        switchBtn.setOnClickListener(v -> {
            if (busy) return;
            useFront = !useFront;
            restartCamera();
        });
        mirrorBtn.setOnClickListener(v -> {
            mirrorSelfie = !mirrorSelfie;
            mirrorBtn.setText(mirrorSelfie ? "Specchio: sì" : "Specchio: no");
        });
        framesBtn.setOnClickListener(v -> {
            burstCount = burstCount == 8 ? 12 : (burstCount == 12 ? 4 : 8);
            framesBtn.setText(burstCount + " scatti");
        });
        styleBtn.setOnClickListener(v -> {
            styleIdx = (styleIdx + 1) % STYLE_NAMES.length;
            styleBtn.setText("Stile: " + STYLE_NAMES[styleIdx]);
        });
        thumb.setOnClickListener(v -> {
            if (lastUri == null) return;
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(lastUri, "image/jpeg");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(i); } catch (Exception ignored) { }
        });
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0xFF333333);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        orientationListener.enable();
        camThread = new HandlerThread("cam");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        startWhenReady();
    }

    @Override
    protected void onPause() {
        orientationListener.disable();
        closeCamera();
        if (camThread != null) {
            camThread.quitSafely();
            camThread = null;
            camHandler = null;
        }
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_CAMERA && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            startWhenReady();
        } else {
            setStatus("Serve il permesso per la fotocamera");
        }
    }

    private void startWhenReady() {
        if (preview.isAvailable()) {
            openCamera();
        } else {
            preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
            });
        }
    }

    private void restartCamera() {
        closeCamera();
        openCamera();
    }

    private void closeCamera() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        session = null;
        if (camera != null) camera.close();
        camera = null;
        if (reader != null) reader.close();
        reader = null;
    }

    // ------------------------------------------------------------------ camera setup

    private void openCamera() {
        if (camHandler == null) return;
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String id = null;
            int want = useFront ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            for (String c : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == want) { id = c; break; }
            }
            if (id == null) { setStatus("Fotocamera non trovata"); return; }
            chars = cm.getCameraCharacteristics(id);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] yuv = map.getOutputSizes(ImageFormat.YUV_420_888);
            captureSize = largest(yuv);
            previewSize = choosePreview(map.getOutputSizes(SurfaceTexture.class), captureSize);
            fitPreviewView();

            reader = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(), ImageFormat.YUV_420_888, 4);
            reader.setOnImageAvailableListener(this::onImage, camHandler);

            Integer hw = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            final String info = (useFront ? "Frontale " : "Posteriore ") + captureSize.getWidth() + "x" + captureSize.getHeight()
                    + " (" + String.format(Locale.US, "%.1f", captureSize.getWidth() * captureSize.getHeight() / 1e6) + " MP)"
                    + (hw != null && hw == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY ? " [legacy]" : "");
            setStatus(info);

            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { camera = d; createSession(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); camera = null; }
                @Override public void onError(CameraDevice d, int e) { d.close(); camera = null; setStatus("Errore fotocamera " + e); }
            }, camHandler);
        } catch (SecurityException | CameraAccessException e) {
            setStatus("Errore: " + e.getMessage());
        }
    }

    private static Size largest(Size[] sizes) {
        Size best = sizes[0];
        for (Size s : sizes) if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        return best;
    }

    private static Size choosePreview(Size[] sizes, Size cap) {
        double aspect = (double) cap.getWidth() / cap.getHeight();
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1440) continue;
            if (Math.abs((double) s.getWidth() / s.getHeight() - aspect) > 0.02) continue;
            if (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) best = s;
        }
        return best != null ? best : sizes[0];
    }

    /** Portrait app: the preview buffer is landscape, shown rotated. Size the view to its aspect. */
    private void fitPreviewView() {
        runOnUiThread(() -> {
            int vw = preview.getWidth() > 0 ? preview.getWidth() : getResources().getDisplayMetrics().widthPixels;
            int vh = Math.round(vw * (float) previewSize.getWidth() / previewSize.getHeight());
            ViewGroup.LayoutParams lp = preview.getLayoutParams();
            lp.height = vh;
            preview.setLayoutParams(lp);
        });
    }

    @SuppressWarnings("deprecation")
    private void createSession() {
        try {
            SurfaceTexture st = preview.getSurfaceTexture();
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface ps = new Surface(st);
            previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(ps);
            previewBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            if (supportsAf(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE))
                previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Arrays.asList(ps, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    repeatPreview(0, false);
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) { setStatus("Sessione non configurata"); }
            }, camHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            setStatus("Errore sessione: " + e.getMessage());
        }
    }

    private boolean supportsAf(int mode) {
        int[] m = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (m == null) return false;
        for (int x : m) if (x == mode) return true;
        return false;
    }

    private void repeatPreview(int evSteps, boolean aeLock) {
        try {
            previewBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps);
            previewBuilder.set(CaptureRequest.CONTROL_AE_LOCK, aeLock);
            session.setRepeatingRequest(previewBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                    Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
                    if (iso != null && !Boolean.TRUE.equals(r.get(CaptureRequest.CONTROL_AE_LOCK))) lastIso = iso;
                }
            }, camHandler);
        } catch (Exception e) {
            setStatus("Errore anteprima: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ capture

    private void takeBurst() {
        if (busy || session == null || camera == null) return;
        busy = true;
        shutter.setEnabled(false);
        collected.clear();
        // Light level from the preview: in dim light more frames and no under-exposure (less noise).
        usedIso = lastIso;
        final int frames = usedIso >= 800 ? Math.max(burstCount, 12) : burstCount;
        expected = frames;
        captureFront = useFront;
        Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int sensor = so == null ? 0 : so;
        int dev = captureFront ? -deviceOrientation : deviceOrientation;
        captureRotation = (sensor + dev + 360) % 360;

        // Under-expose (protects highlights, shortens exposure) only when there is enough light:
        // -1 EV in good light, -0.5 EV indoors, none in dim light.
        float targetEv = usedIso >= 800 ? 0f : (usedIso >= 400 ? -0.5f : -1f);
        Range<Integer> range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        Rational step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        int evSteps = 0;
        appliedEv = 0f;
        if (range != null && step != null && step.floatValue() > 0 && range.getLower() < 0) {
            evSteps = Math.max(range.getLower(), Math.round(targetEv / step.floatValue()));
            appliedEv = evSteps * step.floatValue();
        }
        final int ev = evSteps;
        setStatus("Tieni fermo il telefono…");
        camHandler.post(() -> repeatPreview(ev, false));
        camHandler.postDelayed(() -> repeatPreview(ev, true), 450);
        camHandler.postDelayed(() -> fireBurst(ev, frames), 650);
    }

    private void fireBurst(int ev, int count) {
        try {
            List<CaptureRequest> reqs = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(reader.getSurface());
                b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
                b.set(CaptureRequest.CONTROL_AE_LOCK, true);
                if (supportsAf(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE))
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                // Keep the ISP's fast noise reduction: with it OFF this sensor delivers heavy colour
                // noise (red/green/blue dots). Sharpening stays off: we sharpen after merging.
                setIfSupported(b, CaptureRequest.NOISE_REDUCTION_MODE,
                        chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
                        CameraMetadata.NOISE_REDUCTION_MODE_FAST, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY);
                setIfSupported(b, CaptureRequest.EDGE_MODE,
                        chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                        CameraMetadata.EDGE_MODE_OFF, CameraMetadata.EDGE_MODE_FAST);
                reqs.add(b.build());
            }
            session.captureBurst(reqs, null, camHandler);
            // safety net: if the camera drops frames, continue with what arrived
            camHandler.postDelayed(() -> {
                if (expected == 0) return;
                expected = 0;
                repeatPreview(0, false);
                final List<YuvFrame> frames = new ArrayList<>(collected);
                collected.clear();
                if (frames.size() >= 2) worker.execute(() -> processAndSave(frames));
                else { setStatus("La fotocamera non ha consegnato gli scatti, riprova"); finishBusy(); }
            }, 6000);
        } catch (Exception e) {
            setStatus("Errore scatto: " + e.getMessage());
            finishBusy();
        }
    }

    private static void setIfSupported(CaptureRequest.Builder b, CaptureRequest.Key<Integer> key, int[] avail, int pref, int fallback) {
        if (avail == null) return;
        for (int m : avail) if (m == pref) { b.set(key, pref); return; }
        for (int m : avail) if (m == fallback) { b.set(key, fallback); return; }
    }

    private void onImage(ImageReader r) {
        Image img = r.acquireNextImage();
        if (img == null) return;
        try {
            if (collected.size() < expected) {
                collected.add(YuvUtil.fromImage(img));
                setStatus("Scatto " + collected.size() + "/" + expected);
            }
        } finally {
            img.close();
        }
        if (collected.size() == expected && expected > 0) {
            expected = 0;
            repeatPreview(0, false);
            final List<YuvFrame> frames = new ArrayList<>(collected);
            collected.clear();
            worker.execute(() -> processAndSave(frames));
        }
    }

    // ------------------------------------------------------------------ processing + saving

    private void processAndSave(List<YuvFrame> frames) {
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());

            // 2) the burst result
            YuvFrame f0 = frames.get(0);
            double scale = Math.sqrt((double) MAX_OUTPUT_PIXELS / ((long) f0.w * f0.h));
            Pipeline.Params p = new Pipeline.Params();
            p.scale = (float) Math.max(1.0, Math.min(MAX_SCALE, scale));
            setStatus("Cerco il viso…");
            p.face = FaceFinder.find(f0, captureRotation);
            boolean faceFound = p.face != null;
            // selfie without a detected face: assume it is in the upper-centre of the frame
            if (!faceFound && captureFront) p.face = FaceFinder.centreBox(captureRotation);
            p.evComp = appliedEv;
            p.look = styleIdx == 0 ? Look.iphone() : (styleIdx == 1 ? Look.luminoso() : Look.sera());
            long t0 = System.currentTimeMillis();
            Pipeline.Output out = Pipeline.process(frames, p,
                    (stage, frac) -> setStatus(stage + " " + Math.round(frac * 100) + "%"));
            Bitmap bmp = Bitmap.createBitmap(out.argb, out.w, out.h, Bitmap.Config.ARGB_8888);
            bmp = orient(bmp);
            Uri uri = saveJpeg(bmp, "IPC_" + stamp);
            final Bitmap th = Bitmap.createScaledBitmap(bmp, 160, Math.max(1, 160 * bmp.getHeight() / bmp.getWidth()), true);
            final String msg = String.format(Locale.ITALY,
                    "Salvata %.1f MP in %.0f s%s\nISO %d · %d/%d scatti usati · rumore %.1f / colore %.1f",
                    bmp.getWidth() * (double) bmp.getHeight() / 1e6, (System.currentTimeMillis() - t0) / 1000.0,
                    faceFound ? " · viso trovato" : "", usedIso, out.framesUsed, out.framesIn, out.noise, out.noiseC);
            bmp.recycle();
            lastUri = uri;
            runOnUiThread(() -> thumb.setImageBitmap(th));
            setStatus(msg);
        } catch (OutOfMemoryError e) {
            setStatus("Memoria insufficiente: prova con 4 scatti");
        } catch (Exception e) {
            setStatus("Errore elaborazione: " + e);
        } finally {
            finishBusy();
        }
    }

    private Bitmap orient(Bitmap src) {
        boolean mirror = captureFront && mirrorSelfie;
        if (captureRotation == 0 && !mirror) return src;
        Matrix m = new Matrix();
        m.postRotate(captureRotation);
        if (mirror) m.postScale(-1, 1);
        Bitmap r = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (r != src) src.recycle();
        return r;
    }

    private Uri saveJpeg(Bitmap bmp, String name) throws Exception {
        ContentResolver cr = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, name + ".jpg");
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) {
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/iPhoneCam");
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new Exception("impossibile creare il file");
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new Exception("impossibile scrivere il file");
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, os);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, done, null, null);
        }
        return uri;
    }

    private void finishBusy() {
        busy = false;
        runOnUiThread(() -> shutter.setEnabled(true));
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
