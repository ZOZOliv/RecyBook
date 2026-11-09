package org.pibooth.android.camera;

import android.graphics.Bitmap;

/**
 * Abstraction d'une caméra pour le photomaton (équivalent de
 * pibooth/camera/base.py). Tous les rappels sont faits sur le thread UI.
 */
public interface BoothCamera {

    /** Reçoit les images de visée d'un reflex (inutile pour la caméra de la tablette). */
    interface PreviewSink {
        void onPreviewFrame(Bitmap frame);
    }

    /** Résultat d'une prise de vue : fichier JPEG d'origine + rotation à appliquer. */
    final class Capture {
        public final byte[] jpeg;
        /** Rotation supplémentaire (degrés) non portée par l'EXIF. */
        public final int extraRotation;

        public Capture(byte[] jpeg, int extraRotation) {
            this.jpeg = jpeg;
            this.extraRotation = extraRotation;
        }
    }

    interface CaptureCallback {
        void onCaptured(Capture capture);

        void onError(Exception error);
    }

    interface OpenCallback {
        void onOpened(BoothCamera camera);

        void onError(Exception error);
    }

    /** Nom lisible (affiché dans la barre d'état). */
    String getName();

    /** Vrai si l'aperçu passe par {@link PreviewSink} (reflex), faux s'il utilise la PreviewView. */
    boolean usesPreviewSink();

    void startPreview(PreviewSink sink);

    void stopPreview();

    void capture(CaptureCallback callback);

    void close();
}
