package org.pibooth.android.ptp;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Transport PTP sur USB (classe « Still Image », 0x06) via l'API USB Host
 * d'Android. Équivalent minimal de la couche port USB de libgphoto2.
 *
 * <p>Une transaction PTP est : conteneur COMMANDE → (conteneur DONNÉES dans
 * un sens ou dans l'autre) → conteneur RÉPONSE. Cette classe n'est pas
 * ré-entrante : tous les appels doivent venir du même thread.</p>
 */
public final class PtpConnection implements Closeable {

    private static final String TAG = "PtpConnection";
    private static final int HEADER_SIZE = 12;
    private static final int DEFAULT_TIMEOUT_MS = 10_000;

    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private final UsbInterface usbInterface;
    private final UsbEndpoint endpointIn;
    private final UsbEndpoint endpointOut;
    private final UsbEndpoint endpointEvent;
    /** Taille max d'un transfert bulk (16 Ko avant Android 9). */
    private final int chunkSize;
    private final byte[] readBuffer;

    private int transactionId = 0;
    private boolean sessionOpen = false;

    private PtpConnection(UsbDevice device, UsbDeviceConnection connection, UsbInterface intf,
                          UsbEndpoint in, UsbEndpoint out, UsbEndpoint evt) {
        this.device = device;
        this.connection = connection;
        this.usbInterface = intf;
        this.endpointIn = in;
        this.endpointOut = out;
        this.endpointEvent = evt;
        this.chunkSize = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? 512 * 1024 : 16 * 1024;
        this.readBuffer = new byte[chunkSize];
    }

    /** Recherche l'interface PTP d'un périphérique (classe 6, ou à défaut une interface bulk in/out). */
    public static UsbInterface findPtpInterface(UsbDevice device) {
        UsbInterface fallback = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE) {
                return intf;
            }
            if (fallback == null && hasBulkPair(intf)) {
                fallback = intf;
            }
        }
        return fallback;
    }

    private static boolean hasBulkPair(UsbInterface intf) {
        boolean in = false, out = false;
        for (int e = 0; e < intf.getEndpointCount(); e++) {
            UsbEndpoint ep = intf.getEndpoint(e);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = true;
                else out = true;
            }
        }
        return in && out;
    }

    /** Vrai si le périphérique ressemble à un appareil photo PTP. */
    public static boolean isPtpDevice(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE) {
                return true;
            }
        }
        return false;
    }

    /** Ouvre la connexion USB (la permission doit déjà avoir été accordée). */
    public static PtpConnection open(UsbManager manager, UsbDevice device) throws PtpException {
        UsbInterface intf = findPtpInterface(device);
        if (intf == null) {
            throw new PtpException("Aucune interface PTP sur " + device.getDeviceName());
        }
        UsbEndpoint in = null, out = null, evt = null;
        for (int e = 0; e < intf.getEndpointCount(); e++) {
            UsbEndpoint ep = intf.getEndpoint(e);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
                else out = ep;
            } else if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT
                    && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                evt = ep;
            }
        }
        if (in == null || out == null) {
            throw new PtpException("Points de terminaison bulk introuvables");
        }
        UsbDeviceConnection conn = manager.openDevice(device);
        if (conn == null) {
            throw new PtpException("Impossible d'ouvrir le périphérique USB (permission ?)");
        }
        // force = true : détache un éventuel pilote noyau / MTP qui tiendrait l'interface
        if (!conn.claimInterface(intf, true)) {
            conn.close();
            throw new PtpException("Interface USB déjà utilisée par une autre application");
        }
        return new PtpConnection(device, conn, intf, in, out, evt);
    }

    public UsbDevice getDevice() {
        return device;
    }

    public boolean hasEventEndpoint() {
        return endpointEvent != null;
    }

    // ------------------------------------------------------------------ session

    public void openSession() throws PtpException {
        transactionId = 0;
        PtpResponse r = transaction(Ptp.OC_OPEN_SESSION, new int[]{1}, null, DEFAULT_TIMEOUT_MS);
        if (r.code == Ptp.RC_SESSION_ALREADY_OPEN) {
            // Session restée ouverte (application tuée) : on la referme proprement.
            transaction(Ptp.OC_CLOSE_SESSION, new int[0], null, DEFAULT_TIMEOUT_MS);
            r = transaction(Ptp.OC_OPEN_SESSION, new int[]{1}, null, DEFAULT_TIMEOUT_MS);
        }
        r.check("OpenSession");
        sessionOpen = true;
    }

    public PtpDeviceInfo getDeviceInfo() throws PtpException {
        PtpResponse r = transaction(Ptp.OC_GET_DEVICE_INFO, new int[0], null, DEFAULT_TIMEOUT_MS)
                .check("GetDeviceInfo");
        return PtpDeviceInfo.parse(r.data);
    }

    @Override
    public void close() {
        try {
            if (sessionOpen) {
                transaction(Ptp.OC_CLOSE_SESSION, new int[0], null, 2000);
            }
        } catch (Exception e) {
            Log.w(TAG, "CloseSession: " + e.getMessage());
        }
        sessionOpen = false;
        try {
            connection.releaseInterface(usbInterface);
        } catch (Exception ignored) {
        }
        connection.close();
    }

    // -------------------------------------------------------------- transactions

    public PtpResponse transaction(int code, int... params) throws PtpException {
        return transaction(code, params, null, DEFAULT_TIMEOUT_MS);
    }

    public PtpResponse transactionWithData(int code, byte[] dataOut, int... params) throws PtpException {
        return transaction(code, params, dataOut, DEFAULT_TIMEOUT_MS);
    }

    public PtpResponse transactionTimeout(int code, int timeoutMs, int... params) throws PtpException {
        return transaction(code, params, null, timeoutMs);
    }

    /**
     * Exécute une transaction complète.
     *
     * @param dataOut données envoyées à l'appareil (null si pas de phase de données sortante)
     */
    public synchronized PtpResponse transaction(int code, int[] params, byte[] dataOut, int timeoutMs)
            throws PtpException {
        int tid = (code == Ptp.OC_OPEN_SESSION) ? 0 : ++transactionId;

        // 1) Commande
        ByteBuffer cmd = ByteBuffer.allocate(HEADER_SIZE + 4 * params.length).order(ByteOrder.LITTLE_ENDIAN);
        cmd.putInt(HEADER_SIZE + 4 * params.length);
        cmd.putShort((short) Ptp.CONTAINER_COMMAND);
        cmd.putShort((short) code);
        cmd.putInt(tid);
        for (int p : params) cmd.putInt(p);
        writeFully(cmd.array(), timeoutMs);

        // 2) Données sortantes
        if (dataOut != null) {
            ByteBuffer data = ByteBuffer.allocate(HEADER_SIZE + dataOut.length).order(ByteOrder.LITTLE_ENDIAN);
            data.putInt(HEADER_SIZE + dataOut.length);
            data.putShort((short) Ptp.CONTAINER_DATA);
            data.putShort((short) code);
            data.putInt(tid);
            data.put(dataOut);
            writeFully(data.array(), timeoutMs);
        }

        // 3) Données entrantes éventuelles puis réponse
        byte[] dataIn = null;
        while (true) {
            int n = readPacket(timeoutMs);
            if (n < HEADER_SIZE) {
                throw new PtpException("Conteneur PTP tronqué (" + n + " octets) pour " + Ptp.hex(code));
            }
            ByteBuffer hdr = ByteBuffer.wrap(readBuffer, 0, n).order(ByteOrder.LITTLE_ENDIAN);
            int length = hdr.getInt();
            int type = hdr.getShort() & 0xFFFF;
            int rcode = hdr.getShort() & 0xFFFF;
            hdr.getInt(); // transaction id

            if (type == Ptp.CONTAINER_DATA) {
                int payload = length - HEADER_SIZE;
                if (payload < 0) throw new PtpException("Longueur de données invalide");
                dataIn = new byte[payload];
                int got = Math.min(payload, n - HEADER_SIZE);
                System.arraycopy(readBuffer, HEADER_SIZE, dataIn, 0, got);
                while (got < payload) {
                    int len = Math.min(chunkSize, payload - got);
                    int r = connection.bulkTransfer(endpointIn, dataIn, got, len, timeoutMs);
                    if (r < 0) throw new PtpException("Lecture USB interrompue (" + got + "/" + payload + ")");
                    got += r;
                }
            } else if (type == Ptp.CONTAINER_RESPONSE) {
                int nParams = Math.max(0, Math.min(5, (length - HEADER_SIZE) / 4));
                int[] rparams = new int[nParams];
                for (int i = 0; i < nParams && hdr.remaining() >= 4; i++) rparams[i] = hdr.getInt();
                return new PtpResponse(rcode, rparams, dataIn);
            } else {
                Log.w(TAG, "Conteneur inattendu de type " + type + " ignoré");
            }
        }
    }

    private void writeFully(byte[] buffer, int timeoutMs) throws PtpException {
        int sent = 0;
        while (sent < buffer.length) {
            int len = Math.min(chunkSize, buffer.length - sent);
            int r = connection.bulkTransfer(endpointOut, buffer, sent, len, timeoutMs);
            if (r < 0) throw new PtpException("Écriture USB impossible (appareil débranché ?)");
            sent += r;
        }
        // Paquet de longueur nulle si le transfert finit pile sur une frontière de paquet
        int mps = endpointOut.getMaxPacketSize();
        if (buffer.length > 0 && mps > 0 && buffer.length % mps == 0) {
            connection.bulkTransfer(endpointOut, new byte[0], 0, timeoutMs);
        }
    }

    /** Lit un paquet non vide (les paquets de longueur nulle sont ignorés). */
    private int readPacket(int timeoutMs) throws PtpException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (true) {
            int n = connection.bulkTransfer(endpointIn, readBuffer, readBuffer.length, timeoutMs);
            if (n > 0) return n;
            if (n < 0 || SystemClock.elapsedRealtime() > deadline) {
                throw new PtpException("Pas de réponse de l'appareil photo (délai dépassé)");
            }
        }
    }

    // ------------------------------------------------------------------ événements

    /** Événement reçu sur le point de terminaison d'interruption. */
    public static final class Event {
        public final int code;
        public final int[] params;

        Event(int code, int[] params) {
            this.code = code;
            this.params = params;
        }

        public int param(int i) {
            return i < params.length ? params[i] : 0;
        }
    }

    /** Lit un événement PTP ; renvoie null si aucun dans le délai imparti. */
    public Event readEvent(int timeoutMs) {
        if (endpointEvent == null) return null;
        byte[] buf = new byte[64];
        int n = connection.bulkTransfer(endpointEvent, buf, buf.length, timeoutMs);
        if (n < HEADER_SIZE) return null;
        ByteBuffer b = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN);
        int length = b.getInt();
        int type = b.getShort() & 0xFFFF;
        int code = b.getShort() & 0xFFFF;
        b.getInt();
        if (type != Ptp.CONTAINER_EVENT) return null;
        int nParams = Math.max(0, Math.min(3, (Math.min(length, n) - HEADER_SIZE) / 4));
        int[] params = new int[nParams];
        for (int i = 0; i < nParams; i++) params[i] = b.getInt();
        return new Event(code, params);
    }

    /** Vide la file d'événements en attente. */
    public void drainEvents() {
        if (endpointEvent == null) return;
        for (int i = 0; i < 32 && readEvent(20) != null; i++) {
            // ignore
        }
    }
}
