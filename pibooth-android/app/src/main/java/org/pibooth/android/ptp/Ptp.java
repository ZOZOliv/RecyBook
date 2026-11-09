package org.pibooth.android.ptp;

/**
 * Codes du protocole PTP (ISO 15740) et des extensions Canon EOS / Nikon
 * utilisés par l'application. Les valeurs sont celles de libgphoto2.
 */
public final class Ptp {

    private Ptp() {
    }

    // Types de conteneurs USB
    public static final int CONTAINER_COMMAND = 1;
    public static final int CONTAINER_DATA = 2;
    public static final int CONTAINER_RESPONSE = 3;
    public static final int CONTAINER_EVENT = 4;

    // Opérations standard
    public static final int OC_GET_DEVICE_INFO = 0x1001;
    public static final int OC_OPEN_SESSION = 0x1002;
    public static final int OC_CLOSE_SESSION = 0x1003;
    public static final int OC_GET_STORAGE_IDS = 0x1004;
    public static final int OC_GET_OBJECT_HANDLES = 0x1007;
    public static final int OC_GET_OBJECT_INFO = 0x1008;
    public static final int OC_GET_OBJECT = 0x1009;
    public static final int OC_DELETE_OBJECT = 0x100B;
    public static final int OC_INITIATE_CAPTURE = 0x100E;

    // Codes de réponse
    public static final int RC_OK = 0x2001;
    public static final int RC_GENERAL_ERROR = 0x2002;
    public static final int RC_SESSION_NOT_OPEN = 0x2003;
    public static final int RC_OPERATION_NOT_SUPPORTED = 0x2005;
    public static final int RC_DEVICE_BUSY = 0x2019;
    public static final int RC_SESSION_ALREADY_OPEN = 0x201E;

    // Événements standard
    public static final int EC_OBJECT_ADDED = 0x4002;
    public static final int EC_CAPTURE_COMPLETE = 0x400D;

    // Formats d'objets
    public static final int OFC_ASSOCIATION = 0x3001;
    public static final int OFC_EXIF_JPEG = 0x3801;
    public static final int OFC_JFIF = 0x3808;

    // Identifiants d'extension constructeur (DeviceInfo.VendorExtensionID)
    public static final int VENDOR_EASTMAN_KODAK = 0x00000001;
    public static final int VENDOR_MICROSOFT = 0x00000006;
    public static final int VENDOR_NIKON = 0x0000000A;
    public static final int VENDOR_CANON = 0x0000000B;

    // Identifiants USB constructeur
    public static final int USB_VENDOR_CANON = 0x04A9;
    public static final int USB_VENDOR_NIKON = 0x04B0;
    public static final int USB_VENDOR_SONY = 0x054C;
    public static final int USB_VENDOR_FUJIFILM = 0x04CB;

    // ----- Canon EOS -----
    public static final int OC_CANON_EOS_GET_PARTIAL_OBJECT = 0x9107;
    public static final int OC_CANON_EOS_SET_DEVICE_PROP_VALUE_EX = 0x9110;
    public static final int OC_CANON_EOS_REMOTE_RELEASE = 0x910F;
    public static final int OC_CANON_EOS_SET_REMOTE_MODE = 0x9114;
    public static final int OC_CANON_EOS_SET_EVENT_MODE = 0x9115;
    public static final int OC_CANON_EOS_GET_EVENT = 0x9116;
    public static final int OC_CANON_EOS_TRANSFER_COMPLETE = 0x9117;
    public static final int OC_CANON_EOS_PC_HDD_CAPACITY = 0x911A;
    public static final int OC_CANON_EOS_REMOTE_RELEASE_ON = 0x9128;
    public static final int OC_CANON_EOS_REMOTE_RELEASE_OFF = 0x9129;
    public static final int OC_CANON_EOS_GET_VIEWFINDER_DATA = 0x9153;

    public static final int RC_CANON_A102_NOT_READY = 0xA102;

    public static final int EC_CANON_EOS_OBJECT_ADDED_EX = 0xC181;
    public static final int EC_CANON_EOS_REQUEST_OBJECT_TRANSFER = 0xC186;
    public static final int EC_CANON_EOS_OBJECT_ADDED_EX64 = 0xC1A7;

    public static final int DPC_CANON_EOS_CAPTURE_DESTINATION = 0xD11C;
    public static final int DPC_CANON_EOS_EVF_OUTPUT_DEVICE = 0xD1B0;
    public static final int DPC_CANON_EOS_EVF_MODE = 0xD1B3;

    public static final int CANON_EOS_CAPTURE_DEST_HOST = 4;

    // ----- Nikon -----
    public static final int OC_NIKON_CAPTURE = 0x90C0;
    public static final int OC_NIKON_CHECK_EVENT = 0x90C7;
    public static final int OC_NIKON_DEVICE_READY = 0x90C8;
    public static final int OC_NIKON_START_LIVE_VIEW = 0x9201;
    public static final int OC_NIKON_END_LIVE_VIEW = 0x9202;
    public static final int OC_NIKON_GET_LIVE_VIEW_IMG = 0x9203;
    public static final int OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA = 0x9207;

    public static final int EC_NIKON_OBJECT_ADDED_IN_SDRAM = 0xC101;
    public static final int NIKON_SDRAM_HANDLE = 0xFFFF0001;

    public static String hex(int code) {
        return String.format("0x%04X", code);
    }
}
