package org.pibooth.android.ptp;

import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Pilote Canon EOS (reflex et hybrides EOS / EOS R) : mode distant,
 * visée en direct via GetViewFinderData (0x9153) et déclenchement via
 * RemoteReleaseOn/Off (0x9128/0x9129). Les nouvelles photos sont signalées
 * dans la liste renvoyée par GetEvent (0x9116).
 */
public class CanonEosDriver extends PtpDriver {

    private boolean captureToHost = false;
    private boolean autofocus = true;
    private int keepAliveCounter = 0;

    public CanonEosDriver(PtpConnection ptp, PtpDeviceInfo info) {
        super(ptp, info);
    }

    @Override
    public String getDescription() {
        return info.toString() + " (Canon EOS)";
    }

    @Override
    public void init(Options options) throws PtpException {
        ptp.transaction(Ptp.OC_CANON_EOS_SET_REMOTE_MODE, 1).check("SetRemoteMode");
        ptp.transaction(Ptp.OC_CANON_EOS_SET_EVENT_MODE, 1).check("SetEventMode");
        // Le premier GetEvent renvoie l'état complet de l'appareil : on le vide.
        getEvents();
        autofocus = options.autofocus;
        captureToHost = options.canonCaptureToHost;
        if (captureToHost) {
            setProperty(Ptp.DPC_CANON_EOS_CAPTURE_DESTINATION, Ptp.CANON_EOS_CAPTURE_DEST_HOST);
            // Annonce un « disque » hôte avec de la place, sinon l'appareil refuse de déclencher.
            ptp.transaction(Ptp.OC_CANON_EOS_PC_HDD_CAPACITY, 0x0FFFFFFF, 0x00001000, 0x00000001);
            getEvents();
        }
    }

    @Override
    public boolean supportsLiveView() {
        return info.supportsOperation(Ptp.OC_CANON_EOS_GET_VIEWFINDER_DATA);
    }

    @Override
    public void startLiveView() throws PtpException {
        // EVF mode (certains boîtiers seulement : erreur ignorée)
        if (info.properties.contains(Ptp.DPC_CANON_EOS_EVF_MODE)) {
            trySetProperty(Ptp.DPC_CANON_EOS_EVF_MODE, 1);
        }
        // Sortie de la visée vers l'hôte (PC = 2)
        setProperty(Ptp.DPC_CANON_EOS_EVF_OUTPUT_DEVICE, 2);
        getEvents();
        liveViewActive = true;
    }

    @Override
    public byte[] getLiveViewFrame() throws PtpException {
        if (++keepAliveCounter % 15 == 0) getEvents();
        PtpResponse r = ptp.transaction(Ptp.OC_CANON_EOS_GET_VIEWFINDER_DATA, 0x00200000, 0, 0);
        if (r.code == Ptp.RC_CANON_A102_NOT_READY || r.code == Ptp.RC_DEVICE_BUSY) {
            return null;
        }
        r.check("GetViewFinderData");
        byte[] data = r.data;
        if (data == null || data.length < 8) return null;
        // Blocs [taille u32][type u32][données] ; type 1 = image JPEG.
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 0;
        while (pos + 8 <= data.length) {
            int size = b.getInt(pos);
            int type = b.getInt(pos + 4);
            if (size < 8 || pos + size > data.length) break;
            if (type == 1) {
                return extractJpeg(data, pos + 8);
            }
            pos += size;
        }
        return extractJpeg(data, 0);
    }

    @Override
    public void stopLiveView() throws PtpException {
        liveViewActive = false;
        trySetProperty(Ptp.DPC_CANON_EOS_EVF_OUTPUT_DEVICE, 0);
        getEvents();
    }

    @Override
    public void keepAlive() throws PtpException {
        getEvents();
    }

    @Override
    public CapturedObject capture() throws PtpException {
        getEvents(); // purge
        if (info.supportsOperation(Ptp.OC_CANON_EOS_REMOTE_RELEASE_ON)) {
            // 3 = mi-course + pleine course (avec AF), 2 = pleine course seule
            int press = autofocus ? 3 : 2;
            PtpResponse r = ptp.transactionTimeout(Ptp.OC_CANON_EOS_REMOTE_RELEASE_ON, 20_000, press, 0);
            if (!r.isOk() && press == 3) {
                Log.w(TAG, "Déclenchement avec AF refusé (" + Ptp.hex(r.code) + "), essai sans AF");
                ptp.transaction(Ptp.OC_CANON_EOS_REMOTE_RELEASE_OFF, 3);
                r = ptp.transactionTimeout(Ptp.OC_CANON_EOS_REMOTE_RELEASE_ON, 20_000, 2, 0);
                press = 2;
            }
            r.check("RemoteReleaseOn");
            ptp.transaction(Ptp.OC_CANON_EOS_REMOTE_RELEASE_OFF, press).check("RemoteReleaseOff");
        } else {
            ptp.transactionTimeout(Ptp.OC_CANON_EOS_REMOTE_RELEASE, 20_000).check("RemoteRelease");
        }

        long deadline = SystemClock.elapsedRealtime() + 20_000;
        CapturedObject other = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            for (CapturedObject o : getEvents()) {
                if (o.format == Ptp.OFC_EXIF_JPEG || o.format == Ptp.OFC_JFIF) {
                    return o;
                }
                if (o.format != Ptp.OFC_ASSOCIATION) other = o;
            }
            if (other != null) {
                // Photo RAW seule : on attend un peu un éventuel JPEG puis on renonce.
                sleep(300);
                for (CapturedObject o : getEvents()) {
                    if (o.format == Ptp.OFC_EXIF_JPEG || o.format == Ptp.OFC_JFIF) return o;
                }
                throw new PtpException("L'appareil est réglé en RAW seul : choisissez JPEG ou RAW+JPEG");
            }
            sleep(100);
        }
        throw new PtpException("Aucune photo reçue (mise au point impossible ou carte absente ?)");
    }

    @Override
    public byte[] download(CapturedObject object) throws PtpException {
        PtpResponse r = ptp.transactionTimeout(Ptp.OC_GET_OBJECT, 60_000, object.handle);
        if (!r.isOk() && object.size > 0) {
            r = ptp.transactionTimeout(Ptp.OC_CANON_EOS_GET_PARTIAL_OBJECT, 60_000, object.handle, 0, object.size);
        }
        r.check("GetObject");
        if (captureToHost) {
            ptp.transaction(Ptp.OC_CANON_EOS_TRANSFER_COMPLETE, object.handle);
        }
        if (r.data == null || r.data.length == 0) throw new PtpException("Image vide");
        return r.data;
    }

    @Override
    public void delete(CapturedObject object) {
        if (!captureToHost) super.delete(object); // en RAM l'image disparaît d'elle-même
    }

    @Override
    public void shutdown() {
        super.shutdown();
        try {
            ptp.transaction(Ptp.OC_CANON_EOS_SET_EVENT_MODE, 0);
            ptp.transaction(Ptp.OC_CANON_EOS_SET_REMOTE_MODE, 0);
        } catch (PtpException e) {
            Log.w(TAG, "Sortie du mode distant : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ internes

    private void setProperty(int prop, int value) throws PtpException {
        ByteBuffer b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(12).putInt(prop).putInt(value);
        ptp.transactionWithData(Ptp.OC_CANON_EOS_SET_DEVICE_PROP_VALUE_EX, b.array())
                .check("SetDevicePropValueEx " + Ptp.hex(prop));
    }

    private void trySetProperty(int prop, int value) {
        try {
            setProperty(prop, value);
        } catch (PtpException e) {
            Log.w(TAG, e.getMessage());
        }
    }

    /** Lit la file d'événements EOS et renvoie les objets ajoutés. */
    private List<CapturedObject> getEvents() throws PtpException {
        List<CapturedObject> added = new ArrayList<>();
        PtpResponse r = ptp.transaction(Ptp.OC_CANON_EOS_GET_EVENT);
        if (!r.isOk() || r.data == null) return added;
        byte[] d = r.data;
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 0;
        while (pos + 8 <= d.length) {
            int size = b.getInt(pos);
            int type = b.getInt(pos + 4);
            if (size < 8 || type == 0 || pos + size > d.length) break;
            if ((type == Ptp.EC_CANON_EOS_OBJECT_ADDED_EX
                    || type == Ptp.EC_CANON_EOS_OBJECT_ADDED_EX64
                    || type == Ptp.EC_CANON_EOS_REQUEST_OBJECT_TRANSFER) && size >= 0x20) {
                int handle = b.getInt(pos + 8);
                int format = b.getShort(pos + 0x10) & 0xFFFF;
                int objSize = b.getInt(pos + 0x1C);
                added.add(new CapturedObject(handle, format, objSize));
            }
            pos += size;
        }
        return added;
    }
}
