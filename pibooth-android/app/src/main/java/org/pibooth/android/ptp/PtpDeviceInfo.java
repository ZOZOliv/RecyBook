package org.pibooth.android.ptp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/** Contenu du jeu de données DeviceInfo (opérations supportées, modèle…). */
public final class PtpDeviceInfo {

    public int standardVersion;
    public int vendorExtensionId;
    public String vendorExtensionDesc = "";
    public final Set<Integer> operations = new HashSet<>();
    public final Set<Integer> events = new HashSet<>();
    public final Set<Integer> properties = new HashSet<>();
    public String manufacturer = "";
    public String model = "";
    public String deviceVersion = "";
    public String serialNumber = "";

    public boolean supportsOperation(int code) {
        return operations.contains(code);
    }

    static PtpDeviceInfo parse(byte[] data) throws PtpException {
        if (data == null) throw new PtpException("DeviceInfo vide");
        try {
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            PtpDeviceInfo info = new PtpDeviceInfo();
            info.standardVersion = b.getShort() & 0xFFFF;
            info.vendorExtensionId = b.getInt();
            b.getShort(); // vendor extension version
            info.vendorExtensionDesc = readString(b);
            b.getShort(); // functional mode
            readU16Array(b, info.operations);
            readU16Array(b, info.events);
            readU16Array(b, info.properties);
            readU16Array(b, new HashSet<>()); // capture formats
            readU16Array(b, new HashSet<>()); // image formats
            info.manufacturer = readString(b);
            info.model = readString(b);
            info.deviceVersion = readString(b);
            info.serialNumber = readString(b);
            return info;
        } catch (RuntimeException e) {
            throw new PtpException("DeviceInfo illisible", e);
        }
    }

    private static void readU16Array(ByteBuffer b, Set<Integer> out) {
        int count = b.getInt();
        for (int i = 0; i < count; i++) out.add(b.getShort() & 0xFFFF);
    }

    /** Chaîne PTP : nombre de caractères UTF-16 (terminateur compris) puis les caractères. */
    static String readString(ByteBuffer b) {
        int len = b.get() & 0xFF;
        if (len == 0) return "";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = (char) (b.getShort() & 0xFFFF);
            if (c != 0) sb.append(c);
        }
        return sb.toString().trim();
    }

    @Override
    public String toString() {
        return (manufacturer + " " + model).trim();
    }
}
