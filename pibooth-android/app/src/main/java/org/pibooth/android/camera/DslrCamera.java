package org.pibooth.android.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.pibooth.android.ptp.PtpConnection;
import org.pibooth.android.ptp.PtpDeviceInfo;
import org.pibooth.android.ptp.PtpDriver;
import org.pibooth.android.ptp.PtpException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reflex / hybride branché en USB (OTG) et piloté en PTP. Équivalent de
 * pibooth/camera/gphoto.py, sans libgphoto2.
 *
 * <p>Toutes les opérations USB passent par un unique thread : la boucle de
 * visée se re-planifie image par image, ce qui permet à une prise de vue de
 * s'intercaler entre deux images.</p>
 */
public final class DslrCamera implements BoothCamera {

    private static final String TAG = "DslrCamera";
    private static final long FRAME_INTERVAL_MS = 40; // ~25 i/s max

    private final ExecutorService usbThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ptp-usb");
        t.setPriority(Thread.NORM_PRIORITY + 1);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    private final PtpDriver.Options options;
    private final boolean deleteAfterDownload;
    private final int previewRotation;
    private final boolean previewFlip;

    private PtpConnection connection;
    private PtpDriver driver;
    private String name = "Reflex USB";

    private volatile boolean previewRunning = false;
    private volatile PreviewSink sink;
    private volatile boolean closed = false;
    private int consecutiveErrors = 0;

    private DslrCamera(PtpDriver.Options options, boolean deleteAfterDownload,
                       int previewRotation, boolean previewFlip) {
        this.options = options;
        this.deleteAfterDownload = deleteAfterDownload;
        this.previewRotation = previewRotation;
        this.previewFlip = previewFlip;
    }

    /** Ouvre l'appareil (la permission USB doit avoir été obtenue). */
    public static void open(UsbManager manager, UsbDevice device, PtpDriver.Options options,
                            boolean deleteAfterDownload, int previewRotation, boolean previewFlip,
                            OpenCallback callback) {
        DslrCamera cam = new DslrCamera(options, deleteAfterDownload, previewRotation, previewFlip);
        cam.usbThread.execute(() -> {
            try {
                cam.connection = PtpConnection.open(manager, device);
                cam.connection.openSession();
                PtpDeviceInfo info = cam.connection.getDeviceInfo();
                cam.driver = PtpDriver.create(cam.connection, info);
                cam.driver.init(options);
                cam.name = cam.driver.getDescription();
                Log.i(TAG, "Appareil connecté : " + cam.name + ", live view : " + cam.driver.supportsLiveView());
                cam.main.post(() -> callback.onOpened(cam));
            } catch (Exception e) {
                Log.e(TAG, "Ouverture impossible", e);
                cam.closeNow();
                cam.usbThread.shutdown();
                cam.main.post(() -> callback.onError(e));
            }
        });
    }

    public boolean supportsLiveView() {
        return driver != null && driver.supportsLiveView();
    }

    public UsbDevice getDevice() {
        return connection != null ? connection.getDevice() : null;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean usesPreviewSink() {
        return true;
    }

    @Override
    public void startPreview(PreviewSink previewSink) {
        if (!supportsLiveView() || previewRunning) return;
        sink = previewSink;
        previewRunning = true;
        usbThread.execute(() -> {
            try {
                if (!driver.isLiveViewActive()) driver.startLiveView();
            } catch (PtpException e) {
                Log.w(TAG, "Démarrage live view impossible", e);
                previewRunning = false;
                return;
            }
            usbThread.execute(this::previewStep);
        });
    }

    private void previewStep() {
        if (!previewRunning || closed) return;
        long start = SystemClock.elapsedRealtime();
        try {
            byte[] jpeg = driver.getLiveViewFrame();
            consecutiveErrors = 0;
            if (jpeg != null) {
                Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (frame != null) {
                    Bitmap shown = transform(frame);
                    PreviewSink s = sink;
                    main.post(() -> {
                        if (previewRunning && s != null) s.onPreviewFrame(shown);
                    });
                }
            }
        } catch (PtpException e) {
            if (++consecutiveErrors > 20) {
                Log.e(TAG, "Live view arrêtée après erreurs répétées", e);
                previewRunning = false;
                return;
            }
        }
        long wait = FRAME_INTERVAL_MS - (SystemClock.elapsedRealtime() - start);
        if (wait > 0) SystemClock.sleep(wait);
        if (previewRunning && !closed) usbThread.execute(this::previewStep);
    }

    private Bitmap transform(Bitmap src) {
        if (previewRotation == 0 && !previewFlip) return src;
        Matrix m = new Matrix();
        m.postRotate(previewRotation);
        if (previewFlip) m.postScale(-1, 1);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    @Override
    public void stopPreview() {
        if (!previewRunning) return;
        previewRunning = false;
        sink = null;
        usbThread.execute(() -> {
            try {
                if (driver.isLiveViewActive()) driver.stopLiveView();
            } catch (PtpException e) {
                Log.w(TAG, "Arrêt live view : " + e.getMessage());
            }
        });
    }

    @Override
    public void capture(CaptureCallback callback) {
        if (closed) {
            callback.onError(new PtpException("Appareil déconnecté"));
            return;
        }
        usbThread.execute(() -> {
            try {
                PtpDriver.CapturedObject obj = driver.capture();
                byte[] jpeg = driver.download(obj);
                if (deleteAfterDownload) driver.delete(obj);
                Capture c = new Capture(jpeg, 0);
                main.post(() -> callback.onCaptured(c));
            } catch (Exception e) {
                Log.e(TAG, "Prise de vue impossible", e);
                main.post(() -> callback.onError(e));
            }
        });
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        previewRunning = false;
        usbThread.execute(this::closeNow);
        usbThread.shutdown();
    }

    private void closeNow() {
        try {
            if (driver != null) driver.shutdown();
        } catch (Exception ignored) {
        }
        if (connection != null) connection.close();
    }
}
