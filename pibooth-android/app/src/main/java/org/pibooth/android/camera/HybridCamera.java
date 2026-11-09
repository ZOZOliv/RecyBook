package org.pibooth.android.camera;

/**
 * Aperçu par la caméra de la tablette, prise de vue par le reflex
 * (équivalent de pibooth/camera/hybrid.py). Utile pour les boîtiers sans
 * visée en direct en USB.
 */
public final class HybridCamera implements BoothCamera {

    private final BoothCamera preview;
    private final BoothCamera capture;

    public HybridCamera(BoothCamera preview, BoothCamera capture) {
        this.preview = preview;
        this.capture = capture;
    }

    @Override
    public String getName() {
        return capture.getName() + " + aperçu tablette";
    }

    @Override
    public boolean usesPreviewSink() {
        return preview.usesPreviewSink();
    }

    @Override
    public void startPreview(PreviewSink sink) {
        preview.startPreview(sink);
    }

    @Override
    public void stopPreview() {
        preview.stopPreview();
    }

    @Override
    public void capture(CaptureCallback callback) {
        capture.capture(callback);
    }

    @Override
    public void close() {
        preview.close();
        capture.close();
    }
}
