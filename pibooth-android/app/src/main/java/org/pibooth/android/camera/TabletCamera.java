package org.pibooth.android.camera;

import android.content.Context;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

/**
 * Caméra intégrée de la tablette via CameraX (équivalent de
 * pibooth/camera/opencv.py pour une webcam). Sert aussi d'aperçu pour le
 * mode hybride lorsque le reflex n'a pas de visée en direct.
 */
public final class TabletCamera implements BoothCamera {

    private static final String TAG = "TabletCamera";

    private final PreviewView previewView;
    private final Executor mainExecutor;
    private final boolean front;
    private ProcessCameraProvider provider;
    private ImageCapture imageCapture;

    private TabletCamera(Context context, PreviewView previewView, boolean front) {
        this.previewView = previewView;
        this.mainExecutor = ContextCompat.getMainExecutor(context);
        this.front = front;
    }

    public static void open(Context context, LifecycleOwner owner, PreviewView previewView,
                            boolean front, OpenCallback callback) {
        TabletCamera cam = new TabletCamera(context, previewView, front);
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            try {
                cam.provider = future.get();
                CameraSelector selector = front && cam.provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                        ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                cam.imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(previewView.getDisplay() != null
                                ? previewView.getDisplay().getRotation() : 0)
                        .build();
                cam.provider.unbindAll();
                cam.provider.bindToLifecycle(owner, selector, preview, cam.imageCapture);
                callback.onOpened(cam);
            } catch (Exception e) {
                Log.e(TAG, "Caméra de la tablette indisponible", e);
                callback.onError(e);
            }
        }, cam.mainExecutor);
    }

    @Override
    public String getName() {
        return front ? "Caméra frontale de la tablette" : "Caméra arrière de la tablette";
    }

    @Override
    public boolean usesPreviewSink() {
        return false;
    }

    @Override
    public void startPreview(PreviewSink sink) {
        previewView.setVisibility(View.VISIBLE);
    }

    @Override
    public void stopPreview() {
        previewView.setVisibility(View.GONE);
    }

    @Override
    public void capture(CaptureCallback callback) {
        if (imageCapture == null) {
            callback.onError(new IllegalStateException("Caméra non initialisée"));
            return;
        }
        imageCapture.takePicture(mainExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                try {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] jpeg = new byte[buffer.remaining()];
                    buffer.get(jpeg);
                    int rotation = image.getImageInfo().getRotationDegrees();
                    callback.onCaptured(new Capture(jpeg, rotation));
                } catch (Exception e) {
                    callback.onError(e);
                } finally {
                    image.close();
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                callback.onError(exception);
            }
        });
    }

    @Override
    public void close() {
        if (provider != null) provider.unbindAll();
        previewView.setVisibility(View.GONE);
    }
}
