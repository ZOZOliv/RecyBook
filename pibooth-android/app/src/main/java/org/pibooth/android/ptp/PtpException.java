package org.pibooth.android.ptp;

/** Erreur de communication ou réponse PTP inattendue. */
public class PtpException extends Exception {

    private final int responseCode;

    public PtpException(String message) {
        super(message);
        this.responseCode = 0;
    }

    public PtpException(String message, int responseCode) {
        super(message + " (réponse " + Ptp.hex(responseCode) + ")");
        this.responseCode = responseCode;
    }

    public PtpException(String message, Throwable cause) {
        super(message, cause);
        this.responseCode = 0;
    }

    public int getResponseCode() {
        return responseCode;
    }
}
