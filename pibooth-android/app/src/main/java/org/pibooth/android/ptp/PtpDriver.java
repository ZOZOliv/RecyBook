package org.pibooth.android.ptp;

import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pilote PTP générique : déclenchement par InitiateCapture (0x100E) et
 * récupération de l'image via l'événement ObjectAdded. Fonctionne avec la
 * plupart des appareils conformes PTP mais sans visée en direct.
 *
 * <p>Les sous-classes {@link CanonEosDriver} et {@link NikonDriver} ajoutent la
 * visée en direct (live view) et le déclenchement propre à chaque marque.</p>
 */
public class PtpDriver {

    protected static final String TAG = "PtpDriver";

    /** Objet créé par l'appareil après un déclenchement. */
    public static final class CapturedObject {
        public final int handle;
        public final int format;
        public final int size;

        public CapturedObject(int handle, int format, int size) {
            this.handle = handle;
            this.format = format;
            this.size = size;
        }
    }

    protected final PtpConnection ptp;
    protected final PtpDeviceInfo info;
    protected boolean liveViewActive = false;

    public PtpDriver(PtpConnection ptp, PtpDeviceInfo info) {
        this.ptp = ptp;
        this.info = info;
    }

    /** Choisit le pilote adapté à l'appareil connecté. */
    public static PtpDriver create(PtpConnection ptp, PtpDeviceInfo info) {
        int usbVendor = ptp.getDevice().getVendorId();
        if ((usbVendor == Ptp.USB_VENDOR_CANON || info.vendorExtensionId == Ptp.VENDOR_CANON)
                && info.supportsOperation(Ptp.OC_CANON_EOS_GET_EVENT)) {
            return new CanonEosDriver(ptp, info);
        }
        if (usbVendor == Ptp.USB_VENDOR_NIKON || info.vendorExtensionId == Ptp.VENDOR_NIKON) {
            return new NikonDriver(ptp, info);
        }
        return new PtpDriver(ptp, info);
    }

    public String getDescription() {
        return info.toString() + " (PTP générique)";
    }

    /** Initialisation spécifique (mode distant, etc.). */
    public void init(Options options) throws PtpException {
    }

    public boolean supportsLiveView() {
        return false;
    }

    public void startLiveView() throws PtpException {
        throw new PtpException("Visée en direct non supportée par " + info);
    }

    /** Renvoie une image JPEG de visée, ou null si elle n'est pas encore prête. */
    public byte[] getLiveViewFrame() throws PtpException {
        return null;
    }

    public void stopLiveView() throws PtpException {
        liveViewActive = false;
    }

    public boolean isLiveViewActive() {
        return liveViewActive;
    }

    /** Opération à appeler régulièrement pour garder la session vivante. */
    public void keepAlive() throws PtpException {
    }

    /** Déclenche et renvoie l'objet image (JPEG de préférence) créé par l'appareil. */
    public CapturedObject capture() throws PtpException {
        Set<Integer> before = null;
        if (!ptp.hasEventEndpoint()) {
            before = new LinkedHashSet<>(getObjectHandles());
        }
        ptp.drainEvents();
        ptp.transactionTimeout(Ptp.OC_INITIATE_CAPTURE, 30_000, 0, 0).check("InitiateCapture");

        List<Integer> handles = new ArrayList<>();
        if (ptp.hasEventEndpoint()) {
            long deadline = SystemClock.elapsedRealtime() + 20_000;
            long lastAdded = 0;
            while (SystemClock.elapsedRealtime() < deadline) {
                PtpConnection.Event ev = ptp.readEvent(300);
                long now = SystemClock.elapsedRealtime();
                if (ev == null) {
                    if (!handles.isEmpty() && now - lastAdded > 1500) break;
                    continue;
                }
                if (ev.code == Ptp.EC_OBJECT_ADDED) {
                    handles.add(ev.param(0));
                    lastAdded = now;
                } else if (ev.code == Ptp.EC_CAPTURE_COMPLETE && !handles.isEmpty()) {
                    break;
                }
            }
        } else {
            long deadline = SystemClock.elapsedRealtime() + 20_000;
            while (handles.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
                sleep(500);
                for (int h : getObjectHandles()) {
                    if (!before.contains(h)) handles.add(h);
                }
            }
        }
        if (handles.isEmpty()) {
            throw new PtpException("L'appareil n'a signalé aucune nouvelle photo");
        }
        return pickJpeg(handles);
    }

    /** Télécharge le fichier image. */
    public byte[] download(CapturedObject object) throws PtpException {
        PtpResponse r = ptp.transactionTimeout(Ptp.OC_GET_OBJECT, 60_000, object.handle).check("GetObject");
        if (r.data == null || r.data.length == 0) throw new PtpException("Image vide");
        return r.data;
    }

    public void delete(CapturedObject object) {
        try {
            ptp.transaction(Ptp.OC_DELETE_OBJECT, object.handle, 0);
        } catch (PtpException e) {
            Log.w(TAG, "Suppression impossible : " + e.getMessage());
        }
    }

    /** Remet l'appareil dans son état normal avant déconnexion. */
    public void shutdown() {
        try {
            if (liveViewActive) stopLiveView();
        } catch (PtpException e) {
            Log.w(TAG, "Arrêt live view : " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------- utilitaires

    protected List<Integer> getObjectHandles() throws PtpException {
        PtpResponse r = ptp.transaction(Ptp.OC_GET_OBJECT_HANDLES, 0xFFFFFFFF, 0, 0).check("GetObjectHandles");
        ByteBuffer b = r.dataBuffer();
        List<Integer> out = new ArrayList<>();
        if (b.remaining() < 4) return out;
        int n = b.getInt();
        for (int i = 0; i < n && b.remaining() >= 4; i++) out.add(b.getInt());
        return out;
    }

    /** Lit le format et la taille d'un objet (GetObjectInfo). */
    protected CapturedObject getObjectInfo(int handle) throws PtpException {
        PtpResponse r = ptp.transaction(Ptp.OC_GET_OBJECT_INFO, handle).check("GetObjectInfo");
        ByteBuffer b = r.dataBuffer();
        b.getInt(); // storage id
        int format = b.getShort() & 0xFFFF;
        b.getShort(); // protection
        int size = b.getInt();
        return new CapturedObject(handle, format, size);
    }

    /** Parmi les objets créés (RAW + JPEG, dossiers…), garde le JPEG. */
    protected CapturedObject pickJpeg(List<Integer> handles) throws PtpException {
        CapturedObject fallback = null;
        for (int h : handles) {
            CapturedObject o;
            try {
                o = getObjectInfo(h);
            } catch (PtpException e) {
                o = new CapturedObject(h, 0, 0);
            }
            if (o.format == Ptp.OFC_EXIF_JPEG || o.format == Ptp.OFC_JFIF) return o;
            if (o.format != Ptp.OFC_ASSOCIATION) fallback = o;
        }
        if (fallback == null) throw new PtpException("Aucune image JPEG produite");
        return fallback;
    }

    /** Extrait le flux JPEG (SOI…EOI) d'un tampon de visée. */
    public static byte[] extractJpeg(byte[] data, int from) {
        if (data == null) return null;
        int start = -1;
        for (int i = Math.max(0, from); i < data.length - 2; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD8 && (data[i + 2] & 0xFF) == 0xFF) {
                start = i;
                break;
            }
        }
        if (start < 0) return null;
        int end = data.length;
        for (int i = data.length - 2; i > start; i--) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD9) {
                end = i + 2;
                break;
            }
        }
        byte[] out = new byte[end - start];
        System.arraycopy(data, start, out, 0, out.length);
        return out;
    }

    protected static void sleep(long ms) {
        SystemClock.sleep(ms);
    }

    /** Options issues de la configuration. */
    public static final class Options {
        /** Canon : enregistrer dans la RAM de l'appareil (pas de carte SD nécessaire). */
        public boolean canonCaptureToHost;
        /** Utiliser l'autofocus au déclenchement. */
        public boolean autofocus = true;
    }
}
