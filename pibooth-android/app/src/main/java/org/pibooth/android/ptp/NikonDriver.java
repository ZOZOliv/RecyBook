package org.pibooth.android.ptp;

import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Pilote Nikon (reflex D et hybrides Z) : visée en direct via
 * StartLiveView / GetLiveViewImg (0x9201 / 0x9203), déclenchement via
 * InitiateCaptureRecInMedia (0x9207) ou Capture (0x90C0), et lecture des
 * événements par CheckEvent (0x90C7).
 */
public class NikonDriver extends PtpDriver {

    private boolean autofocus = true;

    public NikonDriver(PtpConnection ptp, PtpDeviceInfo info) {
        super(ptp, info);
    }

    @Override
    public String getDescription() {
        return info.toString() + " (Nikon)";
    }

    @Override
    public void init(Options options) {
        autofocus = options.autofocus;
    }

    @Override
    public boolean supportsLiveView() {
        return info.supportsOperation(Ptp.OC_NIKON_START_LIVE_VIEW)
                && info.supportsOperation(Ptp.OC_NIKON_GET_LIVE_VIEW_IMG);
    }

    @Override
    public void startLiveView() throws PtpException {
        PtpResponse r = ptp.transaction(Ptp.OC_NIKON_START_LIVE_VIEW);
        if (!r.isOk() && r.code != Ptp.RC_DEVICE_BUSY) r.check("StartLiveView");
        waitReady(5000);
        liveViewActive = true;
    }

    @Override
    public byte[] getLiveViewFrame() throws PtpException {
        PtpResponse r = ptp.transaction(Ptp.OC_NIKON_GET_LIVE_VIEW_IMG);
        if (!r.isOk()) {
            // 0xA00B = pas en live view, 0x2019 = occupé : image pas encore prête
            if (r.code == Ptp.RC_DEVICE_BUSY || (r.code & 0xF000) == 0xA000) return null;
            r.check("GetLiveViewImg");
        }
        // En-tête de taille variable selon le modèle, puis JPEG
        return extractJpeg(r.data, 0);
    }

    @Override
    public void stopLiveView() throws PtpException {
        liveViewActive = false;
        ptp.transaction(Ptp.OC_NIKON_END_LIVE_VIEW);
        waitReady(3000);
    }

    @Override
    public CapturedObject capture() throws PtpException {
        drainNikonEvents();
        boolean restartLiveView = false;

        if (liveViewActive && info.supportsOperation(Ptp.OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA)) {
            // param1 : 0xFFFFFFFE = avec AF, 0xFFFFFFFF = sans AF ; param2 : 0 = carte, 1 = SDRAM
            PtpResponse r = ptp.transactionTimeout(Ptp.OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA, 20_000,
                    autofocus ? 0xFFFFFFFE : 0xFFFFFFFF, 0);
            if (!r.isOk() && autofocus) {
                Log.w(TAG, "Déclenchement avec AF refusé (" + Ptp.hex(r.code) + "), essai sans AF");
                r = ptp.transactionTimeout(Ptp.OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA, 20_000, 0xFFFFFFFF, 0);
            }
            r.check("InitiateCaptureRecInMedia");
        } else {
            if (liveViewActive) {
                stopLiveView();
                restartLiveView = true;
            }
            PtpResponse r;
            if (info.supportsOperation(Ptp.OC_NIKON_CAPTURE)) {
                r = ptp.transactionTimeout(Ptp.OC_NIKON_CAPTURE, 20_000, 0xFFFFFFFF);
            } else {
                r = ptp.transactionTimeout(Ptp.OC_INITIATE_CAPTURE, 20_000, 0, 0);
            }
            r.check("Capture");
        }

        waitReady(30_000);
        List<Integer> handles = new ArrayList<>();
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        long lastAdded = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            List<Integer> found = info.supportsOperation(Ptp.OC_NIKON_CHECK_EVENT)
                    ? checkEvents() : interruptEvents();
            long now = SystemClock.elapsedRealtime();
            if (!found.isEmpty()) {
                handles.addAll(found);
                lastAdded = now;
            } else if (!handles.isEmpty() && now - lastAdded > 800) {
                break;
            }
            sleep(100);
        }
        if (restartLiveView) startLiveView();
        if (handles.isEmpty()) {
            throw new PtpException("Aucune photo reçue (mise au point impossible ou carte absente ?)");
        }
        return pickJpeg(handles);
    }

    @Override
    public void delete(CapturedObject object) {
        if (object.handle != Ptp.NIKON_SDRAM_HANDLE) super.delete(object);
    }

    // ------------------------------------------------------------------ internes

    private void waitReady(long timeoutMs) throws PtpException {
        if (!info.supportsOperation(Ptp.OC_NIKON_DEVICE_READY)) {
            sleep(200);
            return;
        }
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            PtpResponse r = ptp.transaction(Ptp.OC_NIKON_DEVICE_READY);
            if (r.code != Ptp.RC_DEVICE_BUSY) return;
            sleep(50);
        }
        throw new PtpException("L'appareil reste occupé");
    }

    /** CheckEvent : u16 nombre, puis (u16 code, u32 paramètre) × nombre. */
    private List<Integer> checkEvents() throws PtpException {
        List<Integer> out = new ArrayList<>();
        PtpResponse r = ptp.transaction(Ptp.OC_NIKON_CHECK_EVENT);
        if (!r.isOk()) return out;
        ByteBuffer b = r.dataBuffer();
        if (b.remaining() < 2) return out;
        int count = b.getShort() & 0xFFFF;
        for (int i = 0; i < count && b.remaining() >= 6; i++) {
            int code = b.getShort() & 0xFFFF;
            int param = b.getInt();
            if (code == Ptp.EC_OBJECT_ADDED) out.add(param);
            else if (code == Ptp.EC_NIKON_OBJECT_ADDED_IN_SDRAM) {
                out.add(param != 0 ? param : Ptp.NIKON_SDRAM_HANDLE);
            }
        }
        return out;
    }

    private List<Integer> interruptEvents() {
        List<Integer> out = new ArrayList<>();
        PtpConnection.Event ev;
        while ((ev = ptp.readEvent(100)) != null) {
            if (ev.code == Ptp.EC_OBJECT_ADDED) out.add(ev.param(0));
            else if (ev.code == Ptp.EC_NIKON_OBJECT_ADDED_IN_SDRAM) out.add(Ptp.NIKON_SDRAM_HANDLE);
        }
        return out;
    }

    private void drainNikonEvents() {
        try {
            if (info.supportsOperation(Ptp.OC_NIKON_CHECK_EVENT)) checkEvents();
            else ptp.drainEvents();
        } catch (PtpException ignored) {
        }
    }
}
